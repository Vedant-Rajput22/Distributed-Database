// ─── Data Types ────────────────────────────────────────

export interface ChatMessage {
  key: string;
  user: string;
  text: string;
  timestamp: number;
  edited: boolean;
  editedAt?: number;
}

export interface ClusterStatus {
  nodeId: string;
  role: string;
  term: number;
  leaderId: string;
  commitIndex: number;
  lastApplied: number;
  killed: boolean;
  uptime: number;
  peers: string[];
  peerHealth: Record<string, boolean>;
}

export interface MvccVersion {
  version: number;
  timestamp: number;
  value: string;
  tombstone: boolean;
}

export type Theme = 'light' | 'dark';

// ─── Constants ─────────────────────────────────────────

export interface NodeInfo {
  id: number;
  label: string;
  baseUrl: string;
  httpPort: number;
}

export const NODES: NodeInfo[] = [
  { id: 1, label: 'node-1', baseUrl: '',                            httpPort: 8080 },
  { id: 2, label: 'node-2', baseUrl: 'http://localhost:8082/api',   httpPort: 8082 },
  { id: 3, label: 'node-3', baseUrl: 'http://localhost:8084/api',   httpPort: 8084 },
];

export const CHANNELS = [
  { id: 'general',   name: 'General',   emoji: '💬', description: 'General discussion' },
  { id: 'random',    name: 'Random',    emoji: '🎲', description: 'Off-topic & fun' },
  { id: 'tech-talk', name: 'Tech Talk', emoji: '⚙️', description: 'Technical discussions' },
];

export const PRESET_USERS = [
  { name: 'Vedant',  color: '#0084ff', avatar: 'V' },
  { name: 'Alice',   color: '#00c853', avatar: 'A' },
  { name: 'Bob',     color: '#ff6d00', avatar: 'B' },
  { name: 'Charlie', color: '#aa00ff', avatar: 'C' },
];

// ─── Helpers ───────────────────────────────────────────

const USER_COLORS = [
  '#0084ff', '#00c853', '#ff6d00', '#aa00ff', '#ff1744',
  '#00bcd4', '#7c4dff', '#64dd17', '#ff9100', '#e91e63',
];

export function getUserColor(name: string): string {
  const preset = PRESET_USERS.find(u => u.name === name);
  if (preset) return preset.color;
  let hash = 0;
  for (let i = 0; i < name.length; i++) {
    hash = name.charCodeAt(i) + ((hash << 5) - hash);
  }
  return USER_COLORS[Math.abs(hash) % USER_COLORS.length];
}

export function formatTime(ts: number): string {
  const d = new Date(ts);
  return d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
}

export function formatTimeFull(ts: number): string {
  return new Date(ts).toLocaleString();
}

export function formatDateSeparator(ts: number): string {
  const d = new Date(ts);
  const now = new Date();
  const isToday = d.toDateString() === now.toDateString();
  if (isToday) return 'Today';
  const yesterday = new Date(now);
  yesterday.setDate(yesterday.getDate() - 1);
  if (d.toDateString() === yesterday.toDateString()) return 'Yesterday';
  return d.toLocaleDateString(undefined, { weekday: 'long', month: 'long', day: 'numeric' });
}
