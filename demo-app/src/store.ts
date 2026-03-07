import { create } from 'zustand';
import type { ChatMessage, ClusterStatus, MvccVersion, Theme } from './types';

interface ChatStore {
  // Theme
  theme: Theme;
  toggleTheme: () => void;

  // User
  currentUser: string | null;
  setCurrentUser: (name: string) => void;

  // Channel
  activeChannel: string;
  setActiveChannel: (ch: string) => void;

  // Messages
  messages: ChatMessage[];
  setMessages: (msgs: ChatMessage[]) => void;

  // Node
  activeNodeIndex: number;
  setActiveNodeIndex: (idx: number) => void;
  nodeStatus: ClusterStatus | null;
  setNodeStatus: (s: ClusterStatus | null) => void;

  // Edit history modal
  editModalKey: string | null;
  editVersions: MvccVersion[];
  openEditHistory: (key: string, versions: MvccVersion[]) => void;
  closeEditHistory: () => void;

  // Inline editing
  editingMessageKey: string | null;
  setEditingMessageKey: (key: string | null) => void;

  // Connection error
  connectionError: boolean;
  setConnectionError: (err: boolean) => void;

  // Toast
  toast: string | null;
  showToast: (msg: string) => void;

  // Cluster panel
  showClusterPanel: boolean;
  setShowClusterPanel: (v: boolean) => void;
}

function getInitialTheme(): Theme {
  const stored = localStorage.getItem('raftchat-theme');
  if (stored === 'dark' || stored === 'light') return stored;
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

export const useStore = create<ChatStore>((set, get) => ({
  theme: getInitialTheme(),
  toggleTheme: () => {
    const next = get().theme === 'light' ? 'dark' : 'light';
    localStorage.setItem('raftchat-theme', next);
    set({ theme: next });
  },

  currentUser: localStorage.getItem('raftchat-user'),
  setCurrentUser: (name) => {
    localStorage.setItem('raftchat-user', name);
    set({ currentUser: name });
  },

  activeChannel: 'general',
  setActiveChannel: (ch) => set({ activeChannel: ch }),

  messages: [],
  setMessages: (msgs) => set({ messages: msgs }),

  activeNodeIndex: 0,
  setActiveNodeIndex: (idx) => set({ activeNodeIndex: idx }),
  nodeStatus: null,
  setNodeStatus: (s) => set({ nodeStatus: s }),

  editModalKey: null,
  editVersions: [],
  openEditHistory: (key, versions) => set({ editModalKey: key, editVersions: versions }),
  closeEditHistory: () => set({ editModalKey: null, editVersions: [] }),

  editingMessageKey: null,
  setEditingMessageKey: (key) => set({ editingMessageKey: key }),

  connectionError: false,
  setConnectionError: (err) => set({ connectionError: err }),

  toast: null,
  showToast: (msg) => {
    set({ toast: msg });
    setTimeout(() => set({ toast: null }), 3000);
  },

  showClusterPanel: false,
  setShowClusterPanel: (v) => set({ showClusterPanel: v }),
}));
