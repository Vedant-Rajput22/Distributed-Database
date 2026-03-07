import { useEffect, useCallback, useState } from 'react';
import { useStore } from './store';
import * as api from './api';
import { PRESET_USERS, getUserColor, NODES } from './types';
import Sidebar from './components/Sidebar';
import ChatArea from './components/ChatArea';
import MessageInput from './components/MessageInput';
import EditHistoryModal from './components/EditHistoryModal';
import ClusterPanel from './components/ClusterPanel';
import {
  MessageCircle, ArrowRight, Database, Sun, Moon,
  Zap, CheckCircle2, AlertCircle, X, RefreshCw,
} from 'lucide-react';

// ─── Failover Hook ─────────────────────────────────────
// Registers the callback so when api.ts auto-fails-over to another node
// after a 503 / timeout, the store + UI update accordingly.

function useFailoverCallback() {
  const { setActiveNodeIndex, showToast } = useStore();

  useEffect(() => {
    api.setFailoverCallback((fromIdx, toIdx) => {
      setActiveNodeIndex(toIdx);
      showToast(`Auto-failover: ${NODES[fromIdx].label} → ${NODES[toIdx].label}`);
    });
  }, [setActiveNodeIndex, showToast]);
}

// ─── Polling ───────────────────────────────────────────

function usePolling() {
  const { activeChannel, setMessages, setNodeStatus, setConnectionError } = useStore();

  const fetchMessages = useCallback(async () => {
    try {
      const msgs = await api.getMessages(activeChannel);
      setMessages(msgs);
      setConnectionError(false);
    } catch {
      setConnectionError(true);
    }
  }, [activeChannel, setMessages, setConnectionError]);

  const fetchStatus = useCallback(async () => {
    try {
      const status = await api.getClusterStatus();
      setNodeStatus(status);
    } catch {
      setNodeStatus(null);
    }
  }, [setNodeStatus]);

  useEffect(() => {
    fetchMessages();
    fetchStatus();
    const a = setInterval(fetchMessages, 2000);
    const b = setInterval(fetchStatus, 3000);
    return () => { clearInterval(a); clearInterval(b); };
  }, [fetchMessages, fetchStatus]);
}

// ─── Apply Theme ───────────────────────────────────────

function useThemeClass() {
  const theme = useStore((s) => s.theme);
  useEffect(() => {
    document.documentElement.classList.toggle('dark', theme === 'dark');
  }, [theme]);
}

// ─── Welcome / User Setup ──────────────────────────────

function UserSetupModal() {
  const setCurrentUser = useStore((s) => s.setCurrentUser);
  const theme = useStore((s) => s.theme);
  const toggleTheme = useStore((s) => s.toggleTheme);
  const [name, setName] = useState('');

  const join = (n: string) => {
    if (n.trim()) setCurrentUser(n.trim());
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 dark:bg-black/60 backdrop-blur-md">
      <div className="animate-scale-in w-[480px] bg-white dark:bg-surface-800 rounded-3xl shadow-modal dark:shadow-modal-dark overflow-hidden border border-surface-200 dark:border-white/[0.06]">
        {/* Top bar with theme toggle */}
        <div className="flex justify-end px-4 pt-4">
          <button
            onClick={toggleTheme}
            className="p-2 rounded-xl bg-surface-100 dark:bg-surface-700 text-surface-600 dark:text-surface-400 hover:bg-surface-200 dark:hover:bg-surface-600 transition-colors"
          >
            {theme === 'light' ? <Moon className="w-4 h-4" /> : <Sun className="w-4 h-4" />}
          </button>
        </div>

        {/* Header */}
        <div className="px-10 pb-6 text-center">
          <div className="w-20 h-20 mx-auto mb-5 rounded-full bg-gradient-to-br from-primary-500 to-blue-600 flex items-center justify-center shadow-lg shadow-primary-500/30">
            <MessageCircle className="w-10 h-10 text-white" strokeWidth={1.8} />
          </div>
          <h1 className="text-[28px] font-bold text-surface-900 dark:text-white mb-2 tracking-tight">
            RaftChat
          </h1>
          <p className="text-[15px] text-surface-500 dark:text-surface-400 leading-relaxed">
            Distributed chat powered by Raft consensus
          </p>
        </div>

        {/* Body */}
        <div className="px-10 pb-10">
          {/* Quick-pick users */}
          <p className="text-xs font-semibold text-surface-500 dark:text-surface-400 uppercase tracking-wider mb-3">
            Choose a user
          </p>
          <div className="grid grid-cols-4 gap-3 mb-6">
            {PRESET_USERS.map((u) => (
              <button
                key={u.name}
                onClick={() => join(u.name)}
                className="flex flex-col items-center gap-2.5 py-4 rounded-2xl bg-surface-100 dark:bg-surface-700/60 border-2 border-transparent hover:border-primary-500/40 hover:bg-surface-50 dark:hover:bg-surface-700 transition-all group"
              >
                <div
                  className="w-12 h-12 rounded-full flex items-center justify-center text-white font-bold text-lg shadow-md group-hover:scale-110 transition-transform"
                  style={{ backgroundColor: u.color }}
                >
                  {u.avatar}
                </div>
                <span className="text-[13px] font-medium text-surface-700 dark:text-surface-300 group-hover:text-surface-900 dark:group-hover:text-white transition-colors">
                  {u.name}
                </span>
              </button>
            ))}
          </div>

          {/* Divider */}
          <div className="flex items-center gap-4 mb-5">
            <div className="flex-1 h-px bg-surface-200 dark:bg-surface-700" />
            <span className="text-[11px] text-surface-400 dark:text-surface-500 uppercase tracking-wider font-medium">
              or enter a name
            </span>
            <div className="flex-1 h-px bg-surface-200 dark:bg-surface-700" />
          </div>

          {/* Custom name */}
          <div className="flex gap-3">
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && join(name)}
              placeholder="Your display name..."
              className="flex-1 px-4 py-3 rounded-xl bg-surface-100 dark:bg-surface-700 border border-surface-200 dark:border-surface-600 text-surface-900 dark:text-white text-[15px] placeholder:text-surface-400 dark:placeholder:text-surface-500 focus:outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-500/20 transition-all"
              autoFocus
            />
            <button
              onClick={() => join(name)}
              disabled={!name.trim()}
              className="px-6 py-3 rounded-xl bg-primary-500 text-white text-[15px] font-semibold hover:bg-primary-600 disabled:opacity-30 disabled:cursor-not-allowed transition-all flex items-center gap-2 shadow-lg shadow-primary-500/25"
            >
              Join <ArrowRight className="w-4 h-4" />
            </button>
          </div>

          {/* Footer */}
          <div className="mt-6 flex items-center gap-2 justify-center text-[12px] text-surface-400 dark:text-surface-500">
            <Database className="w-3.5 h-3.5" />
            <span>Replicated via Raft consensus across 3 nodes</span>
          </div>
        </div>
      </div>
    </div>
  );
}

// ─── Toast ─────────────────────────────────────────────

function Toast() {
  const toast = useStore((s) => s.toast);
  if (!toast) return null;

  const isError = toast.toLowerCase().includes('fail') || toast.toLowerCase().includes('error');
  const isFailover = toast.toLowerCase().includes('failover');

  return (
    <div className="fixed bottom-6 left-1/2 -translate-x-1/2 z-[60] animate-slide-up">
      <div className={`
        flex items-center gap-2.5 px-5 py-3 rounded-2xl shadow-lg backdrop-blur-md
        text-[14px] font-medium
        ${isError
          ? 'bg-red-500/90 text-white shadow-red-500/20'
          : isFailover
          ? 'bg-amber-500/90 text-white shadow-amber-500/20'
          : 'bg-surface-900/90 dark:bg-white/90 text-white dark:text-surface-900 shadow-black/20 dark:shadow-white/20'
        }
      `}>
        {isError
          ? <AlertCircle className="w-4 h-4 shrink-0" />
          : isFailover
          ? <RefreshCw className="w-4 h-4 shrink-0" />
          : <CheckCircle2 className="w-4 h-4 shrink-0" />
        }
        {toast}
      </div>
    </div>
  );
}

// ─── Main App ──────────────────────────────────────────

export default function App() {
  const currentUser = useStore((s) => s.currentUser);

  usePolling();
  useThemeClass();
  useFailoverCallback();

  return (
    <div className="h-screen flex bg-white dark:bg-surface-950 text-surface-900 dark:text-white overflow-hidden">
      {!currentUser && <UserSetupModal />}
      <Sidebar />
      <div className="flex-1 flex flex-col min-w-0 border-l border-surface-200 dark:border-surface-800">
        <ChatArea />
        <MessageInput />
      </div>
      <ClusterPanel />
      <EditHistoryModal />
      <Toast />
    </div>
  );
}

