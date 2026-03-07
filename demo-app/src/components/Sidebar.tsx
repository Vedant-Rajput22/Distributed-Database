import { useState } from 'react';
import { useStore } from '../store';
import * as api from '../api';
import { CHANNELS, PRESET_USERS, getUserColor } from '../types';
import {
  Search, Sun, Moon, ChevronDown, Settings,
  LogOut, MessageCircle,
} from 'lucide-react';

export default function Sidebar() {
  const {
    activeChannel, setActiveChannel,
    currentUser, setCurrentUser,
    messages, theme, toggleTheme,
    showClusterPanel, setShowClusterPanel,
    showToast,
  } = useStore();

  const [showUserMenu, setShowUserMenu] = useState(false);
  const [search, setSearch] = useState('');

  // Get last message per channel for previews
  function getChannelPreview(channelId: string) {
    const channelMsgs = messages.filter(
      (m) => m.key.startsWith(`chat:${channelId}:`)
    );
    if (channelMsgs.length === 0) return null;
    return channelMsgs[channelMsgs.length - 1];
  }

  const filteredChannels = CHANNELS.filter((ch) =>
    ch.name.toLowerCase().includes(search.toLowerCase())
  );

  return (
    <aside className="w-[320px] bg-surface-50 dark:bg-surface-900 flex flex-col shrink-0">
      {/* Header */}
      <div className="px-5 pt-5 pb-3">
        <div className="flex items-center justify-between mb-4">
          <h1 className="text-[22px] font-bold text-surface-900 dark:text-white tracking-tight">
            Chats
          </h1>
          <div className="flex items-center gap-1">
            <button
              onClick={toggleTheme}
              className="p-2 rounded-xl hover:bg-surface-200 dark:hover:bg-surface-800 text-surface-500 dark:text-surface-400 transition-colors"
              title={theme === 'light' ? 'Switch to dark mode' : 'Switch to light mode'}
            >
              {theme === 'light' ? <Moon className="w-[18px] h-[18px]" /> : <Sun className="w-[18px] h-[18px]" />}
            </button>
            <button
              onClick={() => setShowClusterPanel(!showClusterPanel)}
              className={`p-2 rounded-xl transition-colors ${
                showClusterPanel
                  ? 'bg-primary-500/10 text-primary-500'
                  : 'hover:bg-surface-200 dark:hover:bg-surface-800 text-surface-500 dark:text-surface-400'
              }`}
              title="Toggle cluster panel"
            >
              <Settings className="w-[18px] h-[18px]" />
            </button>
          </div>
        </div>

        {/* Search */}
        <div className="relative">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-surface-400 dark:text-surface-500" />
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search channels..."
            className="w-full pl-10 pr-4 py-2.5 rounded-xl bg-surface-200/70 dark:bg-surface-800 text-[14px] text-surface-900 dark:text-white placeholder:text-surface-400 dark:placeholder:text-surface-500 focus:outline-none focus:ring-2 focus:ring-primary-500/30 transition-all border border-transparent focus:border-primary-500/30"
          />
        </div>
      </div>

      {/* Channel list */}
      <div className="flex-1 overflow-y-auto px-2 pb-2">
        {filteredChannels.map((ch) => {
          const isActive = activeChannel === ch.id;
          const preview = getChannelPreview(ch.id);

          return (
            <button
              key={ch.id}
              onClick={() => setActiveChannel(ch.id)}
              className={`w-full flex items-center gap-3 px-3 py-3 rounded-2xl transition-all duration-150 mb-0.5 ${
                isActive
                  ? 'bg-primary-500/10 dark:bg-primary-500/15'
                  : 'hover:bg-surface-200/60 dark:hover:bg-surface-800/60'
              }`}
            >
              {/* Channel emoji avatar */}
              <div
                className={`w-12 h-12 rounded-full flex items-center justify-center text-xl shrink-0 ${
                  isActive
                    ? 'bg-primary-500 shadow-lg shadow-primary-500/25'
                    : 'bg-surface-200 dark:bg-surface-700'
                }`}
              >
                <span className={isActive ? 'grayscale-0' : ''}>{ch.emoji}</span>
              </div>

              {/* Channel info */}
              <div className="flex-1 min-w-0 text-left">
                <div className="flex items-center justify-between mb-0.5">
                  <span className={`text-[15px] font-semibold truncate ${
                    isActive
                      ? 'text-primary-600 dark:text-primary-400'
                      : 'text-surface-900 dark:text-white'
                  }`}>
                    {ch.name}
                  </span>
                  {preview && (
                    <span className="text-[11px] text-surface-400 dark:text-surface-500 shrink-0 ml-2">
                      {new Date(preview.timestamp).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}
                    </span>
                  )}
                </div>
                <p className="text-[13px] text-surface-500 dark:text-surface-400 truncate">
                  {preview
                    ? `${preview.user}: ${preview.text}`
                    : ch.description
                  }
                </p>
              </div>
            </button>
          );
        })}

        {filteredChannels.length === 0 && (
          <div className="flex flex-col items-center justify-center py-12 text-surface-400 dark:text-surface-500">
            <MessageCircle className="w-8 h-8 mb-2 opacity-40" />
            <p className="text-sm">No channels found</p>
          </div>
        )}
      </div>

      {/* User section */}
      <div className="border-t border-surface-200 dark:border-surface-800 p-3">
        <div className="relative">
          <button
            onClick={() => setShowUserMenu(!showUserMenu)}
            className="w-full flex items-center gap-3 px-3 py-2.5 rounded-xl hover:bg-surface-200/70 dark:hover:bg-surface-800 transition-colors"
          >
            <div className="relative">
              <div
                className="w-10 h-10 rounded-full flex items-center justify-center text-white font-bold text-sm shadow-md"
                style={{ backgroundColor: getUserColor(currentUser || 'U') }}
              >
                {(currentUser || 'U')[0].toUpperCase()}
              </div>
              <div className="absolute -bottom-0.5 -right-0.5 w-3 h-3 rounded-full bg-emerald-500 border-2 border-surface-50 dark:border-surface-900" />
            </div>
            <div className="flex-1 text-left min-w-0">
              <p className="text-[14px] font-semibold text-surface-900 dark:text-white truncate">{currentUser}</p>
              <p className="text-[11px] text-surface-500 dark:text-surface-400">Active now</p>
            </div>
            <ChevronDown className={`w-4 h-4 text-surface-400 dark:text-surface-500 transition-transform ${showUserMenu ? 'rotate-180' : ''}`} />
          </button>

          {/* User switcher dropdown */}
          {showUserMenu && (
            <div className="absolute bottom-full left-0 right-0 mb-2 bg-white dark:bg-surface-800 border border-surface-200 dark:border-surface-700 rounded-2xl shadow-card dark:shadow-card-dark overflow-hidden animate-scale-in z-50">
              <p className="px-4 py-2.5 text-[11px] text-surface-400 dark:text-surface-500 uppercase tracking-wider font-bold border-b border-surface-100 dark:border-surface-700">
                Switch User
              </p>
              {PRESET_USERS.map((u) => (
                <button
                  key={u.name}
                  onClick={() => {
                    setCurrentUser(u.name);
                    setShowUserMenu(false);
                    showToast(`Switched to ${u.name}`);
                  }}
                  className={`w-full flex items-center gap-3 px-4 py-2.5 text-[14px] transition-colors ${
                    currentUser === u.name
                      ? 'bg-primary-500/10 text-primary-600 dark:text-primary-400 font-medium'
                      : 'text-surface-700 dark:text-surface-300 hover:bg-surface-100 dark:hover:bg-surface-700'
                  }`}
                >
                  <div
                    className="w-8 h-8 rounded-full flex items-center justify-center text-white text-xs font-bold"
                    style={{ backgroundColor: u.color }}
                  >
                    {u.avatar}
                  </div>
                  {u.name}
                </button>
              ))}
              <div className="border-t border-surface-100 dark:border-surface-700">
                <button
                  onClick={() => {
                    localStorage.removeItem('raftchat-user');
                    setCurrentUser('' as any);
                    setShowUserMenu(false);
                    window.location.reload();
                  }}
                  className="w-full flex items-center gap-3 px-4 py-2.5 text-[14px] text-red-500 hover:bg-red-50 dark:hover:bg-red-500/10 transition-colors"
                >
                  <LogOut className="w-4 h-4" />
                  Sign Out
                </button>
              </div>
            </div>
          )}
        </div>
      </div>
    </aside>
  );
}
