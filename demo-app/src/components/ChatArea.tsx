import { useEffect, useRef, useCallback, useState } from 'react';
import { useStore } from '../store';
import * as api from '../api';
import { getUserColor, formatTime, formatDateSeparator, CHANNELS, NODES } from '../types';
import type { ChatMessage } from '../types';
import { motion, AnimatePresence } from 'framer-motion';
import {
  Pencil, Trash2, Clock, Check, X, Wifi, WifiOff,
  Crown, MessageCircle, MoreHorizontal,
  Sparkles,
} from 'lucide-react';

// ─── Channel Header ────────────────────────────────────

function ChannelHeader() {
  const activeChannel = useStore((s) => s.activeChannel);
  const nodeStatus = useStore((s) => s.nodeStatus);
  const activeNodeIndex = useStore((s) => s.activeNodeIndex);
  const connectionError = useStore((s) => s.connectionError);

  const channel = CHANNELS.find((c) => c.id === activeChannel);
  const node = NODES[activeNodeIndex];
  const isKilled = nodeStatus?.killed && nodeStatus.nodeId === node.label;

  return (
    <div className="h-[64px] flex items-center justify-between px-5 border-b border-surface-200 dark:border-surface-800 bg-white/80 dark:bg-surface-900/80 backdrop-blur-md shrink-0">
      <div className="flex items-center gap-3">
        <div className="w-10 h-10 rounded-full bg-primary-500/10 dark:bg-primary-500/15 flex items-center justify-center text-lg">
          {channel?.emoji}
        </div>
        <div>
          <h2 className="text-[16px] font-semibold text-surface-900 dark:text-white">{channel?.name}</h2>
          <p className="text-[12px] text-surface-500 dark:text-surface-400">{channel?.description}</p>
        </div>
      </div>

      <div className="flex items-center gap-1">
        {/* Connection badge */}
        <div
          className={`flex items-center gap-1.5 px-3 py-1.5 rounded-full text-[11px] font-medium ${
            connectionError
              ? 'bg-red-100 dark:bg-red-500/15 text-red-600 dark:text-red-400'
              : isKilled
              ? 'bg-amber-100 dark:bg-amber-500/15 text-amber-600 dark:text-amber-400'
              : 'bg-emerald-100 dark:bg-emerald-500/15 text-emerald-600 dark:text-emerald-400'
          }`}
        >
          {connectionError ? <WifiOff className="w-3 h-3" /> : <Wifi className="w-3 h-3" />}
          <span>{node.label}</span>
          {isKilled && <span className="font-bold">KILLED</span>}
        </div>

        {nodeStatus && (
          <div className="flex items-center gap-1 ml-1 px-2.5 py-1.5 rounded-full bg-surface-100 dark:bg-surface-800 text-[11px] text-surface-500 dark:text-surface-400">
            <Crown className="w-3 h-3 text-amber-500" />
            <span>{nodeStatus.leaderId || '—'}</span>
            <span className="opacity-60">T{nodeStatus.term}</span>
          </div>
        )}

      </div>
    </div>
  );
}

// ─── Date Separator ────────────────────────────────────

function DateSeparator({ timestamp }: { timestamp: number }) {
  return (
    <div className="flex items-center justify-center py-4">
      <div className="px-4 py-1.5 rounded-full bg-surface-100 dark:bg-surface-800 text-[12px] font-medium text-surface-500 dark:text-surface-400 shadow-sm">
        {formatDateSeparator(timestamp)}
      </div>
    </div>
  );
}

// ─── Single Message (Bubble) ───────────────────────────

interface MessageProps {
  msg: ChatMessage;
  isOwn: boolean;
  isFirst: boolean;
  isLast: boolean;
  showAvatar: boolean;
}

function Message({ msg, isOwn, isFirst, isLast, showAvatar }: MessageProps) {
  const { editingMessageKey, setEditingMessageKey, openEditHistory, showToast } = useStore();
  const [editText, setEditText] = useState(msg.text);
  const [hovered, setHovered] = useState(false);
  const isEditing = editingMessageKey === msg.key;
  const editRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    if (isEditing && editRef.current) {
      editRef.current.focus();
      editRef.current.selectionStart = editRef.current.value.length;
    }
  }, [isEditing]);

  const startEdit = () => {
    setEditText(msg.text);
    setEditingMessageKey(msg.key);
  };

  const cancelEdit = () => setEditingMessageKey(null);

  const saveEdit = async () => {
    if (!editText.trim() || editText === msg.text) { cancelEdit(); return; }
    await api.editMessage(msg.key, msg.user, editText.trim(), msg.timestamp);
    setEditingMessageKey(null);
    showToast('Message edited — MVCC version created');
  };

  const handleDelete = async () => {
    await api.deleteMessage(msg.key);
    showToast('Message deleted');
  };

  const handleShowHistory = async () => {
    try {
      const data = await api.getVersionHistory(msg.key);
      openEditHistory(msg.key, data.versions || []);
    } catch { showToast('Failed to load version history'); }
  };

  const color = getUserColor(msg.user);

  // Determine bubble border-radius
  const bubbleRadius = isOwn
    ? `${isFirst ? '20px' : '6px'} 20px 20px ${isLast ? '20px' : '6px'}`
    : `20px ${isFirst ? '20px' : '6px'} ${isLast ? '20px' : '6px'} 20px`;

  return (
    <motion.div
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.2, ease: [0.25, 0.46, 0.45, 0.94] }}
      className={`group relative flex items-end gap-2 px-5 ${isFirst ? 'mt-3' : 'mt-0.5'} ${isOwn ? 'flex-row-reverse' : ''}`}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
    >
      {/* Avatar (received msgs only) */}
      {!isOwn ? (
        <div className="shrink-0 mb-0.5">
          {showAvatar ? (
            <div
              className="w-8 h-8 rounded-full flex items-center justify-center text-white font-bold text-xs shadow-md"
              style={{ backgroundColor: color }}
            >
              {msg.user[0].toUpperCase()}
            </div>
          ) : (
            <div className="w-8" />
          )}
        </div>
      ) : null}

      {/* Bubble */}
      <div className={`max-w-[70%] min-w-[80px] ${isOwn ? 'items-end' : 'items-start'} flex flex-col`}>
        {/* Sender name (first msg in group, received only) */}
        {isFirst && !isOwn && (
          <span className="text-[12px] font-semibold mb-1 ml-1" style={{ color }}>
            {msg.user}
          </span>
        )}

        {isEditing ? (
          <div className="w-full max-w-md">
            <textarea
              ref={editRef as React.RefObject<HTMLTextAreaElement>}
              value={editText}
              onChange={(e) => setEditText(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); saveEdit(); }
                if (e.key === 'Escape') cancelEdit();
              }}
              className="w-full px-4 py-2.5 rounded-2xl bg-surface-100 dark:bg-surface-800 border border-primary-500/40 text-[14px] text-surface-900 dark:text-white resize-none focus:outline-none focus:ring-2 focus:ring-primary-500/30"
              rows={2}
            />
            <div className="flex items-center gap-2 mt-1.5 text-[11px] text-surface-500 dark:text-surface-400 ml-1">
              <span>Esc to</span>
              <button onClick={cancelEdit} className="text-primary-500 hover:underline">cancel</button>
              <span>· Enter to</span>
              <button onClick={saveEdit} className="text-primary-500 hover:underline">save</button>
            </div>
          </div>
        ) : (
          <div
            className={`relative px-4 py-2.5 ${
              isOwn
                ? 'bg-primary-500 text-white shadow-bubble'
                : 'bg-surface-100 dark:bg-surface-800 text-surface-900 dark:text-surface-100 shadow-bubble'
            }`}
            style={{ borderRadius: bubbleRadius }}
          >
            <p className="text-[14px] leading-relaxed break-words whitespace-pre-wrap">
              {msg.text}
              {msg.edited && (
                <button
                  onClick={handleShowHistory}
                  className={`ml-1.5 text-[10px] italic cursor-pointer transition-colors ${
                    isOwn
                      ? 'text-white/60 hover:text-white/80'
                      : 'text-surface-400 dark:text-surface-500 hover:text-primary-500'
                  }`}
                >
                  (edited)
                </button>
              )}
            </p>

            {/* Timestamp */}
            <div className={`flex items-center gap-1 mt-1 ${isOwn ? 'justify-end' : ''}`}>
              <span className={`text-[10px] ${
                isOwn ? 'text-white/50' : 'text-surface-400 dark:text-surface-500'
              }`}>
                {formatTime(msg.timestamp)}
              </span>
              {isOwn && <Check className="w-3 h-3 text-white/50" />}
            </div>
          </div>
        )}
      </div>

      {/* Action buttons on hover */}
      {hovered && !isEditing && isOwn && (
        <div className={`flex items-center gap-0.5 p-1 bg-white dark:bg-surface-800 border border-surface-200 dark:border-surface-700 rounded-xl shadow-card dark:shadow-card-dark animate-fade-in absolute ${isOwn ? 'right-[calc(70%+3rem)]' : 'left-[calc(70%+3rem)]'} top-1/2 -translate-y-1/2`}>
          <button
            onClick={startEdit}
            className="p-1.5 rounded-lg hover:bg-surface-100 dark:hover:bg-surface-700 text-surface-400 dark:text-surface-500 hover:text-surface-700 dark:hover:text-white transition-colors"
            title="Edit"
          >
            <Pencil className="w-3.5 h-3.5" />
          </button>
          <button
            onClick={handleDelete}
            className="p-1.5 rounded-lg hover:bg-red-50 dark:hover:bg-red-500/10 text-surface-400 dark:text-surface-500 hover:text-red-500 transition-colors"
            title="Delete"
          >
            <Trash2 className="w-3.5 h-3.5" />
          </button>
          {msg.edited && (
            <button
              onClick={handleShowHistory}
              className="p-1.5 rounded-lg hover:bg-primary-50 dark:hover:bg-primary-500/10 text-surface-400 dark:text-surface-500 hover:text-primary-500 transition-colors"
              title="Version history (MVCC)"
            >
              <Clock className="w-3.5 h-3.5" />
            </button>
          )}
        </div>
      )}
    </motion.div>
  );
}

// ─── Empty State ───────────────────────────────────────

function EmptyState({ channelName }: { channelName: string }) {
  return (
    <div className="flex flex-col items-center justify-center h-full text-center px-6">
      <div className="w-20 h-20 rounded-full bg-primary-500/10 dark:bg-primary-500/15 flex items-center justify-center mb-5">
        <MessageCircle className="w-10 h-10 text-primary-500/60" strokeWidth={1.5} />
      </div>
      <h3 className="text-xl font-semibold text-surface-900 dark:text-white mb-2">
        Welcome to {channelName}
      </h3>
      <p className="text-[14px] text-surface-500 dark:text-surface-400 max-w-sm leading-relaxed">
        This is the beginning of the channel. Send a message and it will be
        replicated across all nodes via Raft consensus!
      </p>
      <div className="mt-5 flex items-center gap-2 text-[12px] text-surface-500 dark:text-surface-400 bg-surface-100 dark:bg-surface-800 px-4 py-2.5 rounded-full">
        <Sparkles className="w-3.5 h-3.5 text-primary-500" />
        Open the cluster panel to seed demo data
      </div>
    </div>
  );
}

// ─── Chat Area (main export) ───────────────────────────

export default function ChatArea() {
  const messages = useStore((s) => s.messages);
  const currentUser = useStore((s) => s.currentUser);
  const activeChannel = useStore((s) => s.activeChannel);
  const scrollRef = useRef<HTMLDivElement>(null);
  const isNearBottom = useRef(true);
  const prevMessageCount = useRef(0);

  const channel = CHANNELS.find((c) => c.id === activeChannel);

  // Track scroll position
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    const handleScroll = () => {
      isNearBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 120;
    };
    el.addEventListener('scroll', handleScroll);
    return () => el.removeEventListener('scroll', handleScroll);
  }, []);

  // Auto-scroll when new messages arrive
  useEffect(() => {
    if (messages.length > prevMessageCount.current && isNearBottom.current && scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
    prevMessageCount.current = messages.length;
  }, [messages]);

  // Initial scroll to bottom
  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [activeChannel]);

  // ─── Group messages ──────────────────────────────

  type GroupedMsg = ChatMessage & {
    isOwn: boolean;
    isFirst: boolean;
    isLast: boolean;
    showAvatar: boolean;
    showDateSeparator: boolean;
  };

  const groupedMessages: GroupedMsg[] = messages.map((msg, i) => {
    const prev = i > 0 ? messages[i - 1] : null;
    const next = i < messages.length - 1 ? messages[i + 1] : null;
    const isOwn = msg.user === currentUser;

    const sameUserAsPrev = prev && prev.user === msg.user && msg.timestamp - prev.timestamp < 5 * 60 * 1000;
    const sameUserAsNext = next && next.user === msg.user && next.timestamp - msg.timestamp < 5 * 60 * 1000;

    const isFirst = !sameUserAsPrev;
    const isLast = !sameUserAsNext;
    const showAvatar = isLast && !isOwn;

    // Show date separator if first msg or different day from previous
    const showDateSeparator = i === 0 || (prev && new Date(prev.timestamp).toDateString() !== new Date(msg.timestamp).toDateString());

    return { ...msg, isOwn, isFirst, isLast, showAvatar, showDateSeparator: !!showDateSeparator };
  });

  return (
    <div className="flex-1 flex flex-col min-h-0 bg-white dark:bg-surface-950">
      <ChannelHeader />
      <div ref={scrollRef} className="flex-1 overflow-y-auto pb-4">
        {messages.length === 0 ? (
          <EmptyState channelName={channel?.name || activeChannel} />
        ) : (
          <AnimatePresence initial={false}>
            {groupedMessages.map((msg) => (
              <div key={msg.key}>
                {msg.showDateSeparator && <DateSeparator timestamp={msg.timestamp} />}
                <Message
                  msg={msg}
                  isOwn={msg.isOwn}
                  isFirst={msg.isFirst}
                  isLast={msg.isLast}
                  showAvatar={msg.showAvatar}
                />
              </div>
            ))}
          </AnimatePresence>
        )}
      </div>
    </div>
  );
}
