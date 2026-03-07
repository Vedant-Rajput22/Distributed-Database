import { useStore } from '../store';
import { formatTimeFull } from '../types';
import type { MvccVersion } from '../types';
import { X, Clock, FileText, Trash2, GitBranch } from 'lucide-react';

export default function EditHistoryModal() {
  const { editModalKey, editVersions, closeEditHistory } = useStore();

  if (!editModalKey) return null;

  const versions = editVersions.map((v: MvccVersion) => {
    let parsed: { text?: string; user?: string; timestamp?: number; edited?: boolean } = {};
    try { parsed = JSON.parse(v.value); } catch { parsed = { text: v.value }; }
    return { ...v, parsed };
  });

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 dark:bg-black/60 backdrop-blur-md"
      onClick={closeEditHistory}
    >
      <div
        className="animate-scale-in w-[560px] max-h-[80vh] bg-white dark:bg-surface-800 rounded-3xl shadow-modal dark:shadow-modal-dark overflow-hidden flex flex-col border border-surface-200 dark:border-white/[0.06]"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-5 border-b border-surface-100 dark:border-surface-700">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-primary-500/10 dark:bg-primary-500/15 flex items-center justify-center">
              <GitBranch className="w-5 h-5 text-primary-500" />
            </div>
            <div>
              <h2 className="text-[16px] font-semibold text-surface-900 dark:text-white">Version History</h2>
              <p className="text-[11px] text-surface-400 dark:text-surface-500 font-mono truncate max-w-[350px]">
                MVCC — {versions.length} version{versions.length !== 1 ? 's' : ''}
              </p>
            </div>
          </div>
          <button
            onClick={closeEditHistory}
            className="p-2 rounded-xl hover:bg-surface-100 dark:hover:bg-surface-700 text-surface-400 dark:text-surface-500 hover:text-surface-700 dark:hover:text-white transition-colors"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Versions list */}
        <div className="flex-1 overflow-y-auto p-5 space-y-3">
          {versions.length === 0 ? (
            <div className="text-center py-12 text-surface-400 dark:text-surface-500 text-sm">
              No version history available
            </div>
          ) : (
            versions.map((v, i) => {
              const isCurrent = i === 0;
              const isTombstone = v.tombstone;

              return (
                <div
                  key={`${v.version}-${v.timestamp}`}
                  className={`rounded-2xl border p-4 transition-all ${
                    isCurrent
                      ? 'bg-primary-50 dark:bg-primary-500/[0.08] border-primary-200 dark:border-primary-500/20'
                      : isTombstone
                      ? 'bg-red-50 dark:bg-red-500/[0.06] border-red-200 dark:border-red-500/15'
                      : 'bg-surface-50 dark:bg-surface-800/50 border-surface-200 dark:border-surface-700'
                  }`}
                >
                  {/* Version header */}
                  <div className="flex items-center justify-between mb-3">
                    <div className="flex items-center gap-2">
                      <span
                        className={`text-[11px] font-bold px-2.5 py-1 rounded-full ${
                          isCurrent
                            ? 'bg-primary-500/15 text-primary-600 dark:text-primary-400'
                            : isTombstone
                            ? 'bg-red-500/15 text-red-600 dark:text-red-400'
                            : 'bg-surface-200 dark:bg-surface-700 text-surface-500 dark:text-surface-400'
                        }`}
                      >
                        {isTombstone ? 'DELETED' : `v${v.version}`}
                      </span>
                      {isCurrent && (
                        <span className="text-[10px] text-primary-500 font-semibold uppercase tracking-wider">
                          Current
                        </span>
                      )}
                    </div>
                    <span className="text-[11px] text-surface-400 dark:text-surface-500 font-mono">
                      {formatTimeFull(v.timestamp)}
                    </span>
                  </div>

                  {/* Content */}
                  {isTombstone ? (
                    <div className="flex items-center gap-2 text-sm text-red-500 dark:text-red-400">
                      <Trash2 className="w-4 h-4" />
                      <span className="italic">Message deleted</span>
                    </div>
                  ) : (
                    <div className="flex items-start gap-2.5">
                      <FileText className="w-4 h-4 text-surface-400 dark:text-surface-500 mt-0.5 shrink-0" />
                      <p className="text-[14px] text-surface-800 dark:text-surface-200 break-words leading-relaxed">
                        {v.parsed.text || v.value}
                      </p>
                    </div>
                  )}

                  {v.parsed.user && (
                    <div className="mt-2.5 text-[11px] text-surface-400 dark:text-surface-500">
                      by <span className="text-surface-600 dark:text-surface-300 font-medium">{v.parsed.user}</span>
                    </div>
                  )}
                </div>
              );
            })
          )}
        </div>

        {/* Footer */}
        <div className="px-6 py-4 border-t border-surface-100 dark:border-surface-700 bg-surface-50 dark:bg-surface-800/50">
          <div className="flex items-center gap-2 text-[12px] text-surface-400 dark:text-surface-500">
            <Clock className="w-3.5 h-3.5" />
            <span>
              MVCC stores every version of a key, enabling time-travel queries and audit trails.
            </span>
          </div>
        </div>
      </div>
    </div>
  );
}
