import { useState, useRef, useEffect } from 'react';
import { useStore } from '../store';
import * as api from '../api';
import { CHANNELS } from '../types';
import { Send, Loader2 } from 'lucide-react';

export default function MessageInput() {
  const { activeChannel, currentUser, showToast } = useStore();
  const [text, setText] = useState('');
  const [sending, setSending] = useState(false);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const channel = CHANNELS.find((c) => c.id === activeChannel);

  // Auto-resize textarea
  useEffect(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = Math.min(el.scrollHeight, 160) + 'px';
  }, [text]);

  const handleSend = async () => {
    if (!text.trim() || !currentUser || sending) return;
    const message = text.trim();
    setText('');
    setSending(true);

    try {
      const result = await api.sendMessage(activeChannel, currentUser, message);
      if (result.success) {
        showToast('Message replicated via Raft');
      } else {
        showToast('Failed to send — ' + (result.error || 'no quorum?'));
      }
    } catch {
      showToast('Send failed — is the cluster running?');
    }

    setSending(false);
    textareaRef.current?.focus();
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  const hasText = text.trim().length > 0;

  return (
    <div className="shrink-0 px-5 pb-4 pt-2 bg-white dark:bg-surface-950">
      <div className={`flex items-end gap-2 p-2 rounded-2xl border transition-all ${
        sending
          ? 'border-primary-500/30 bg-primary-50/50 dark:bg-primary-500/5'
          : 'border-surface-200 dark:border-surface-700 bg-surface-50 dark:bg-surface-900 focus-within:border-primary-500/40 focus-within:ring-2 focus-within:ring-primary-500/10'
      }`}>
        {/* Text input */}
        <textarea
          ref={textareaRef}
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder={`Message ${channel?.name || activeChannel}...`}
          rows={1}
          className="flex-1 bg-transparent text-[15px] text-surface-900 dark:text-white placeholder:text-surface-400 dark:placeholder:text-surface-500 resize-none focus:outline-none max-h-40 leading-relaxed py-1.5 px-1"
          disabled={sending}
        />

        {/* Send button */}
        <div className="flex items-center gap-1 shrink-0">
          <button
            onClick={handleSend}
            disabled={!hasText || sending}
            className={`p-2.5 rounded-xl transition-all ${
              hasText
                ? 'bg-primary-500 text-white hover:bg-primary-600 shadow-lg shadow-primary-500/25 scale-100'
                : 'bg-surface-200 dark:bg-surface-700 text-surface-400 dark:text-surface-500 scale-95'
            } disabled:cursor-not-allowed`}
          >
            {sending ? (
              <Loader2 className="w-4 h-4 animate-spin" />
            ) : (
              <Send className="w-4 h-4" />
            )}
          </button>
        </div>
      </div>

      {/* Footer hint */}
      <div className="flex items-center justify-center mt-2">
        <p className="text-[11px] text-surface-400 dark:text-surface-500">
          <kbd className="px-1.5 py-0.5 rounded-md bg-surface-100 dark:bg-surface-800 border border-surface-200 dark:border-surface-700 text-[10px] font-mono">
            Enter
          </kbd>
          {' '}send{' · '}
          <kbd className="px-1.5 py-0.5 rounded-md bg-surface-100 dark:bg-surface-800 border border-surface-200 dark:border-surface-700 text-[10px] font-mono">
            Shift+Enter
          </kbd>
          {' '}new line
        </p>
      </div>
    </div>
  );
}
