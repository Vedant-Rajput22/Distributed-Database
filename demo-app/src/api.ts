import { NODES, type ChatMessage } from './types';

// ─── Node Selection & Health ───────────────────────────

let currentNodeIndex = 0;
const nodeHealth: boolean[] = NODES.map(() => true);    // track which nodes are reachable
let onFailover: ((fromIdx: number, toIdx: number) => void) | null = null;

/** Called by the store to sync the UI when the API auto-fails-over. */
export function setFailoverCallback(cb: (from: number, to: number) => void) {
  onFailover = cb;
}

export function setNodeIndex(idx: number) { currentNodeIndex = idx; }
export function getNodeIndex() { return currentNodeIndex; }

/** Returns the base URL for a given node index. */
function baseFor(idx: number) {
  const node = NODES[idx];
  // node-1 uses Vite proxy (empty baseUrl), others use direct URLs
  return node.baseUrl || '/api';
}

// ─── Resilient Fetch (auto-failover) ───────────────────
//
// When a request to the current node fails (network error, timeout,
// or 503 Service Unavailable from a killed node), we automatically
// retry on the next healthy node — exactly like a real chat app with
// a load balancer / client-side service discovery.

const REQUEST_TIMEOUT = 3000; // 3s — aggressive, like a real mobile app

async function resilientFetch(
  path: string,
  options?: RequestInit,
  /** Set true for chaos/status endpoints that should go to the selected node only */
  skipFailover = false,
): Promise<Response> {
  const maxAttempts = skipFailover ? 1 : NODES.length;
  let lastError: Error | null = null;

  for (let attempt = 0; attempt < maxAttempts; attempt++) {
    const idx = (currentNodeIndex + attempt) % NODES.length;
    const url = `${baseFor(idx)}${path}`;

    try {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT);

      const response = await fetch(url, {
        ...options,
        signal: controller.signal,
      });

      clearTimeout(timeout);

      // 503 = node is killed — treat as unreachable
      if (response.status === 503) {
        nodeHealth[idx] = false;
        const body = await response.json().catch(() => ({}));
        lastError = new Error(body.error || `Node ${NODES[idx].label} returned 503`);
        continue; // try next node
      }

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }

      // Success — mark this node as healthy and switch to it
      nodeHealth[idx] = true;
      if (attempt > 0 && !skipFailover) {
        const fromIdx = currentNodeIndex;
        currentNodeIndex = idx;
        onFailover?.(fromIdx, idx);
      }

      return response;
    } catch (err: any) {
      // Network error or timeout — node is unreachable
      nodeHealth[idx] = false;
      lastError = err;
      continue;
    }
  }

  throw lastError || new Error('All nodes are unreachable');
}

// ─── Generic Helpers ───────────────────────────────────

async function post(path: string, body?: object, skipFailover = false) {
  const res = await resilientFetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  }, skipFailover);
  return res.json();
}

async function get(path: string, skipFailover = false) {
  const res = await resilientFetch(path, undefined, skipFailover);
  return res.json();
}

// ─── Chat Messages ────────────────────────────────────

export async function sendMessage(channel: string, user: string, text: string) {
  const ts = Date.now();
  const rand = Math.random().toString(36).substring(2, 8);
  const key = `chat:${channel}:${String(ts).padStart(15, '0')}:${rand}`;
  const value = JSON.stringify({ user, text, timestamp: ts, edited: false });
  return post('/kv/put', { key, value });
}

export async function getMessages(channel: string): Promise<ChatMessage[]> {
  const scanKey = `chat:${channel}`;
  const data = await post('/kv/scan', { prefix: scanKey, endKey: scanKey, limit: 200 });
  if (!data.pairs) return [];
  return data.pairs
    .map((p: { key: string; value: string }) => {
      try {
        const val = JSON.parse(p.value);
        return { key: p.key, ...val } as ChatMessage;
      } catch {
        return null;
      }
    })
    .filter(Boolean) as ChatMessage[];
}

export async function editMessage(
  key: string, user: string, newText: string, originalTs: number
) {
  const value = JSON.stringify({
    user, text: newText, timestamp: originalTs, edited: true, editedAt: Date.now(),
  });
  return post('/kv/put', { key, value });
}

export async function deleteMessage(key: string) {
  return post('/kv/delete', { key });
}

// ─── MVCC Version History ──────────────────────────────

export async function getVersionHistory(key: string) {
  return get(`/kv/versions/${encodeURIComponent(key)}`);
}

// ─── Cluster Status & Chaos ────────────────────────────
// These always target the currently-selected node (no failover).

export async function getClusterStatus() {
  return get('/cluster/status', true);
}

export async function killNode(id: string) {
  return post(`/chaos/kill-node/${id}`, undefined, true);
}

export async function recoverNode(id: string) {
  return post(`/chaos/recover/${id}`, undefined, true);
}
