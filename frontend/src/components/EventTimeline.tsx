import { useState, useMemo, useRef, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { useClusterStore } from '../stores/clusterStore';
import type { ClusterEvent, EventCategory } from '../types/events';
import {
  Shield, ArrowUpDown, Database, HeartPulse, Camera,
  GitBranch, Clock, Flame, Search, ChevronDown,
  ChevronRight, Radio, Filter, X
} from 'lucide-react';

const CATEGORY_CONFIG: Record<EventCategory, { icon: typeof Shield; color: string; accent: string }> = {
  RAFT:        { icon: Shield,     color: 'text-purple-400', accent: 'bg-purple-500/10 border-purple-500/10' },
  REPLICATION: { icon: ArrowUpDown,color: 'text-brand-400',  accent: 'bg-brand-500/10 border-brand-500/10' },
  KV:          { icon: Database,   color: 'text-emerald-400',accent: 'bg-emerald-500/10 border-emerald-500/10' },
  HEARTBEAT:   { icon: HeartPulse, color: 'text-pink-400',   accent: 'bg-pink-500/10 border-pink-500/10' },
  SNAPSHOT:    { icon: Camera,     color: 'text-amber-400',  accent: 'bg-amber-500/10 border-amber-500/10' },
  TXN:         { icon: GitBranch,  color: 'text-cyan-400',   accent: 'bg-cyan-500/10 border-cyan-500/10' },
  MVCC:        { icon: Clock,      color: 'text-orange-400', accent: 'bg-orange-500/10 border-orange-500/10' },
  CHAOS:       { icon: Flame,      color: 'text-red-400',    accent: 'bg-red-500/10 border-red-500/10' },
};

export default function EventTimeline() {
  const events = useClusterStore((s) => s.events);
  const eventFilters = useClusterStore((s) => s.eventFilters);
  const toggleEventFilter = useClusterStore((s) => s.toggleEventFilter);
  const [searchQuery, setSearchQuery] = useState('');
  const [autoScroll, setAutoScroll] = useState(true);
  const [expandedEventId, setExpandedEventId] = useState<string | null>(null);
  const listRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (autoScroll && listRef.current) listRef.current.scrollTop = 0;
  }, [events.length, autoScroll]);

  const filteredEvents = useMemo(() => {
    let result = events;
    if (eventFilters.size > 0) result = result.filter((e) => eventFilters.has(e.category));
    if (searchQuery.trim()) {
      const q = searchQuery.toLowerCase();
      result = result.filter((e) =>
        e.message?.toLowerCase().includes(q) || e.type?.toLowerCase().includes(q) || e.nodeId?.toLowerCase().includes(q)
      );
    }
    return result;
  }, [events, eventFilters, searchQuery]);

  const clearFilters = () => {
    (Object.keys(CATEGORY_CONFIG) as EventCategory[]).forEach((cat) => {
      if (!eventFilters.has(cat)) toggleEventFilter(cat);
    });
  };

  const formatTime = (ts: string | number) => {
    const d = new Date(ts);
    return d.toLocaleTimeString('en-US', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' })
      + `.${d.getMilliseconds().toString().padStart(3, '0')}`;
  };

  return (
    <div className="h-full flex flex-col gap-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-xl font-bold tracking-tight bg-gradient-to-r from-white to-white/70 bg-clip-text text-transparent">Event Timeline</h2>
          <p className="text-xs text-white/30 mt-0.5">Real-time cluster event stream with filtering</p>
        </div>
        <div className="flex items-center gap-3">
          <span className="text-[11px] text-white/20 font-mono">
            {filteredEvents.length} / {events.length}
          </span>
          <label className="flex items-center gap-1.5 text-[11px] text-white/30 cursor-pointer select-none">
            <input type="checkbox" checked={autoScroll} onChange={(e) => setAutoScroll(e.target.checked)}
              className="rounded border-white/10 bg-white/[0.04] text-brand-500 focus:ring-brand-500/30 w-3.5 h-3.5" />
            Auto-scroll
          </label>
        </div>
      </div>

      {/* Filter Bar */}
      <div className="flex items-center gap-2 flex-wrap">
        <div className="relative">
          <input type="text" value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="Search events…"
            className="glass-input pl-8 pr-3 py-1.5 text-xs w-52" />
          <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-white/20" />
        </div>
        <div className="w-px h-5 bg-white/[0.06]" />
        {(Object.keys(CATEGORY_CONFIG) as EventCategory[]).map((cat) => {
          const cfg = CATEGORY_CONFIG[cat];
          const Icon = cfg.icon;
          const active = eventFilters.has(cat);
          return (
            <button key={cat} onClick={() => toggleEventFilter(cat)}
              className={`flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-[11px] border transition-all duration-200 ${
                active
                  ? `${cfg.color} ${cfg.accent} font-medium`
                  : 'text-white/15 bg-white/[0.01] border-white/[0.03] hover:text-white/30'
              }`}>
              <Icon className="w-3 h-3" /> {cat}
            </button>
          );
        })}
        {eventFilters.size < Object.keys(CATEGORY_CONFIG).length && (
          <button onClick={clearFilters}
            className="text-[11px] text-white/25 hover:text-white/50 flex items-center gap-1 transition-colors">
            <X className="w-3 h-3" /> Reset
          </button>
        )}
      </div>

      {/* Timeline */}
      <div ref={listRef} className="flex-1 overflow-y-auto space-y-0.5 pr-1">
        <AnimatePresence initial={false}>
          {filteredEvents.map((event) => {
            const cfg = CATEGORY_CONFIG[event.category as EventCategory] || {
              icon: Radio, color: 'text-white/40', accent: 'bg-white/[0.03] border-white/[0.04]',
            };
            const Icon = cfg.icon;
            const isExpanded = expandedEventId === event.id;

            return (
              <motion.div key={event.id}
                initial={{ opacity: 0, y: -6 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0 }}
                layout
                className={`rounded-xl border cursor-pointer transition-all duration-150 hover:bg-white/[0.02] ${cfg.accent}`}
                onClick={() => setExpandedEventId(isExpanded ? null : event.id)}>
                <div className="flex items-center gap-2.5 px-3.5 py-2.5">
                  <div className={`w-6 h-6 rounded-lg flex items-center justify-center flex-shrink-0 bg-white/[0.04]`}>
                    <Icon className={`w-3 h-3 ${cfg.color}`} />
                  </div>
                  <span className="text-[10px] font-mono text-white/20 w-20 shrink-0">{formatTime(event.timestamp)}</span>
                  <span className={`text-[10px] font-semibold w-20 shrink-0 uppercase tracking-wider ${cfg.color} opacity-60`}>
                    {event.category}
                  </span>
                  <span className="text-[11px] font-mono text-white/25 w-16 shrink-0">{event.nodeId}</span>
                  <span className="text-xs text-white/50 flex-1 truncate">{event.message}</span>
                  {event.data && Object.keys(event.data).length > 0 && (
                    <span className="text-white/15">
                      {isExpanded ? <ChevronDown className="w-3 h-3" /> : <ChevronRight className="w-3 h-3" />}
                    </span>
                  )}
                </div>
                <AnimatePresence>
                  {isExpanded && event.data && (
                    <motion.div initial={{ height: 0, opacity: 0 }} animate={{ height: 'auto', opacity: 1 }} exit={{ height: 0, opacity: 0 }} className="overflow-hidden">
                      <div className="px-3.5 pb-3 pt-0">
                        <pre className="text-[10px] font-mono text-white/30 bg-black/20 rounded-xl p-3 overflow-x-auto border border-white/[0.03]">
                          {JSON.stringify(event.data, null, 2)}
                        </pre>
                      </div>
                    </motion.div>
                  )}
                </AnimatePresence>
              </motion.div>
            );
          })}
        </AnimatePresence>

        {filteredEvents.length === 0 && (
          <div className="flex flex-col items-center justify-center py-16 gap-2">
            <Filter className="w-8 h-8 text-white/10" />
            <p className="text-white/20 text-sm">No events match filters</p>
            <p className="text-white/10 text-xs">Try adjusting your search or category filters</p>
          </div>
        )}
      </div>
    </div>
  );
}
