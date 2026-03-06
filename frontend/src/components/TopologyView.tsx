import { useEffect, useRef, useCallback } from 'react';
import * as d3 from 'd3';
import { motion } from 'framer-motion';
import { useClusterStore } from '../stores/clusterStore';
import type { NodeInfo } from '../types/events';
import { Crown, Trash2, RotateCcw, RefreshCw, Wifi, WifiOff, ShieldAlert, Skull, Activity, Zap } from 'lucide-react';

const ROLE_COLORS: Record<string, string> = {
  LEADER: '#22c55e',
  FOLLOWER: '#6366f1',
  CANDIDATE: '#f59e0b',
};
const DOWN_COLOR = '#ef4444';
const PARTITIONED_COLOR = '#f97316';

/** Map port numbers to friendly node names */
const PORT_NODE_MAP: Record<string, string> = {
  '9090': 'node-1', '8080': 'node-1',
  '9092': 'node-2', '8082': 'node-2',
  '9094': 'node-3', '8084': 'node-3',
  '9096': 'node-4', '8086': 'node-4',
  '9098': 'node-5', '8088': 'node-5',
};

/** Convert an ID like "localhost:9092" or "node-2@localhost:9092" to a short label */
function shortLabel(id: string): string {
  // If already short (e.g. "node-1"), return as-is
  if (!id.includes(':') && !id.includes('@')) return id;
  // Extract port from patterns like "node-2@localhost:9092" or "localhost:9092"
  const portMatch = id.match(/:(\d+)$/);
  if (portMatch) {
    const port = portMatch[1];
    const name = PORT_NODE_MAP[port];
    if (name) return `${name} (:${port})`;
    return `:${port}`;
  }
  return id;
}

/** Return the effective API base from the store */
function getApiBase() {
  return useClusterStore.getState().apiBaseUrl || '/api';
}

export default function TopologyView() {
  const svgRef = useRef<SVGSVGElement>(null);
  const clusterStatus = useClusterStore((s) => s.clusterStatus);
  const nodes = useClusterStore((s) => s.nodes);

  // Determine all node IDs from both cluster status and nodes array
  const allNodeIds = useCallback((): string[] => {
    const ids = new Set<string>();
    if (clusterStatus) {
      ids.add(clusterStatus.nodeId);
      clusterStatus.peers?.forEach((p) => ids.add(p));
    }
    nodes.forEach((n) => ids.add(n.id));
    return Array.from(ids);
  }, [clusterStatus, nodes]);

  // Get merged node info — use nodes[] data (from /api/nodes) which now has real health
  const getNodeInfo = useCallback(
    (id: string): Partial<NodeInfo> & { statusLabel: string; statusColor: string } => {
      const found = nodes.find((n) => n.id === id);

      // For the "self" node (the one we're connected to via proxy)
      if (id === clusterStatus?.nodeId) {
        const killed = clusterStatus?.killed ?? false;
        const role = clusterStatus?.role ?? 'FOLLOWER';
        return {
          id,
          role: killed ? undefined : role,
          term: clusterStatus?.term,
          commitIndex: clusterStatus?.commitIndex,
          isUp: !killed,
          isAlive: !killed,
          isPartitioned: false,
          statusLabel: killed ? 'KILLED' : role,
          statusColor: killed ? DOWN_COLOR : (ROLE_COLORS[role] || ROLE_COLORS.FOLLOWER),
        };
      }

      // For peer nodes — use real health data from /api/nodes
      if (found) {
        const isUp = found.isUp !== false;
        const isAlive = found.isAlive !== false;
        const isPartitioned = found.isPartitioned === true;
        const role = found.role || 'FOLLOWER';

        let statusLabel: string = role;
        let statusColor: string = ROLE_COLORS[role] || ROLE_COLORS.FOLLOWER;

        if (!isAlive) {
          statusLabel = 'DEAD';
          statusColor = DOWN_COLOR;
        } else if (isPartitioned) {
          statusLabel = 'PARTITIONED';
          statusColor = PARTITIONED_COLOR;
        } else if (!isUp) {
          statusLabel = 'DOWN';
          statusColor = DOWN_COLOR;
        }

        return {
          ...found,
          isUp,
          isAlive,
          isPartitioned,
          statusLabel,
          statusColor,
        };
      }

      // Also check peerHealth from clusterStatus for peers not in nodes array
      const peerHealthAlive = clusterStatus?.peerHealth?.[id];
      const isPartitioned = clusterStatus?.partitionedPeers?.includes(id) ?? false;

      if (peerHealthAlive === false) {
        return { id, isUp: false, isAlive: false, isPartitioned, statusLabel: 'DEAD', statusColor: DOWN_COLOR };
      }
      if (isPartitioned) {
        return { id, isUp: false, isAlive: true, isPartitioned: true, statusLabel: 'PARTITIONED', statusColor: PARTITIONED_COLOR };
      }

      return { id, isUp: true, isAlive: true, isPartitioned: false, statusLabel: 'FOLLOWER', statusColor: ROLE_COLORS.FOLLOWER };
    },
    [nodes, clusterStatus],
  );

  useEffect(() => {
    if (!svgRef.current) return;

    const svg = d3.select(svgRef.current);
    const width = svgRef.current.clientWidth || 800;
    const height = svgRef.current.clientHeight || 500;

    svg.selectAll('*').remove();

    const defs = svg.append('defs');

    // Glow filter for alive nodes
    const glowFilter = defs.append('filter').attr('id', 'glow').attr('x', '-50%').attr('y', '-50%').attr('width', '200%').attr('height', '200%');
    glowFilter.append('feGaussianBlur').attr('stdDeviation', '6').attr('result', 'coloredBlur');
    const feMerge = glowFilter.append('feMerge');
    feMerge.append('feMergeNode').attr('in', 'coloredBlur');
    feMerge.append('feMergeNode').attr('in', 'SourceGraphic');

    // Red glow filter for dead nodes
    const deadGlow = defs.append('filter').attr('id', 'deadGlow').attr('x', '-50%').attr('y', '-50%').attr('width', '200%').attr('height', '200%');
    deadGlow.append('feGaussianBlur').attr('stdDeviation', '10').attr('result', 'blur');
    deadGlow.append('feFlood').attr('flood-color', '#ef4444').attr('flood-opacity', '0.4').attr('result', 'color');
    deadGlow.append('feComposite').attr('in', 'color').attr('in2', 'blur').attr('operator', 'in').attr('result', 'redBlur');
    const dMerge = deadGlow.append('feMerge');
    dMerge.append('feMergeNode').attr('in', 'redBlur');
    dMerge.append('feMergeNode').attr('in', 'SourceGraphic');

    // Line glow
    const lineGlow = defs.append('filter').attr('id', 'lineGlow').attr('x', '-20%').attr('y', '-20%').attr('width', '140%').attr('height', '140%');
    lineGlow.append('feGaussianBlur').attr('stdDeviation', '2').attr('result', 'blur');
    const lMerge = lineGlow.append('feMerge');
    lMerge.append('feMergeNode').attr('in', 'blur');
    lMerge.append('feMergeNode').attr('in', 'SourceGraphic');

    const nodeIds = allNodeIds();
    if (nodeIds.length === 0) return;

    const cx = width / 2;
    const cy = height / 2;
    const radius = Math.min(width, height) * 0.28;

    const nodePositions = nodeIds.map((id, i) => {
      const angle = (2 * Math.PI * i) / nodeIds.length - Math.PI / 2;
      return {
        id,
        x: cx + radius * Math.cos(angle),
        y: cy + radius * Math.sin(angle),
        info: getNodeInfo(id),
      };
    });

    // ──── Draw edges ────
    const edgeGroup = svg.append('g').attr('class', 'edges');
    for (let i = 0; i < nodePositions.length; i++) {
      for (let j = i + 1; j < nodePositions.length; j++) {
        const a = nodePositions[i];
        const b = nodePositions[j];

        const aAlive = a.info.isAlive !== false && a.info.isUp !== false;
        const bAlive = b.info.isAlive !== false && b.info.isUp !== false;
        const bothAlive = aAlive && bAlive;

        const isPartitioned =
          a.info.isPartitioned === true ||
          b.info.isPartitioned === true ||
          clusterStatus?.partitionedPeers?.includes(a.id) ||
          clusterStatus?.partitionedPeers?.includes(b.id);

        const eitherDead = !aAlive || !bAlive;

        let strokeColor = 'rgba(99, 102, 241, 0.2)';
        let strokeWidth = 1;
        let dashArray = 'none';

        if (eitherDead) {
          // One or both nodes down → red dashed line
          strokeColor = 'rgba(239, 68, 68, 0.4)';
          strokeWidth = 1.5;
          dashArray = '6,4';
        } else if (isPartitioned) {
          // Partitioned → orange dashed
          strokeColor = 'rgba(249, 115, 22, 0.5)';
          strokeWidth = 1.5;
          dashArray = '8,6';
        } else if (bothAlive) {
          // Both alive → green solid
          strokeColor = 'rgba(34, 197, 94, 0.3)';
          strokeWidth = 1.5;
        }

        edgeGroup
          .append('line')
          .attr('x1', a.x).attr('y1', a.y)
          .attr('x2', b.x).attr('y2', b.y)
          .attr('stroke', strokeColor)
          .attr('stroke-width', strokeWidth)
          .attr('stroke-dasharray', dashArray)
          .attr('filter', bothAlive && !isPartitioned ? 'url(#lineGlow)' : 'none');

        // "X" marker on dead connections
        if (eitherDead) {
          const mx = (a.x + b.x) / 2;
          const my = (a.y + b.y) / 2;
          edgeGroup.append('text')
            .attr('x', mx).attr('y', my)
            .attr('text-anchor', 'middle')
            .attr('dominant-baseline', 'central')
            .attr('fill', '#ef4444')
            .attr('font-size', 12)
            .attr('font-weight', 'bold')
            .attr('opacity', 0.6)
            .text('\u00D7'); // × symbol
        }
      }
    }

    // ──── Heartbeat pulse animations (only between alive, non-partitioned nodes) ────
    const leaderNode = nodePositions.find(
      (n) => n.info.statusLabel === 'LEADER' || (n.id === clusterStatus?.leaderId && n.info.isAlive !== false),
    );
    if (leaderNode && leaderNode.info.isAlive !== false) {
      nodePositions
        .filter((n) => n.id !== leaderNode.id && n.info.isAlive !== false && n.info.isPartitioned !== true)
        .forEach((follower) => {
          const marker = edgeGroup
            .append('circle')
            .attr('r', 3)
            .attr('fill', '#22c55e')
            .attr('opacity', 0);

          const animateP = () => {
            marker
              .attr('cx', leaderNode.x).attr('cy', leaderNode.y)
              .attr('opacity', 0.8)
              .transition().duration(1400).ease(d3.easeQuadOut)
              .attr('cx', follower.x).attr('cy', follower.y)
              .attr('opacity', 0)
              .on('end', () => setTimeout(animateP, 600 + Math.random() * 500));
          };
          setTimeout(animateP, Math.random() * 1200);
        });
    }

    // ──── Draw nodes ────
    const nodeGroup = svg.append('g').attr('class', 'nodes');
    nodePositions.forEach((node) => {
      const g = nodeGroup.append('g').attr('transform', `translate(${node.x}, ${node.y})`);

      const isUp = node.info.isUp !== false;
      const isAlive = node.info.isAlive !== false;
      const isPartitioned = node.info.isPartitioned === true;
      const statusLabel = node.info.statusLabel;
      const color = node.info.statusColor;
      const role = node.info.role || 'FOLLOWER';
      const isNodeDown = !isUp || !isAlive;

      // ── Pulsing red ring for dead nodes ──
      if (isNodeDown) {
        g.append('circle')
          .attr('r', 56)
          .attr('fill', 'none')
          .attr('stroke', DOWN_COLOR)
          .attr('stroke-width', 2)
          .attr('opacity', 0.3)
          .append('animate')
          .attr('attributeName', 'opacity').attr('values', '0.3;0.1;0.3')
          .attr('dur', '2s').attr('repeatCount', 'indefinite');

        g.append('circle')
          .attr('r', 64)
          .attr('fill', 'none')
          .attr('stroke', DOWN_COLOR)
          .attr('stroke-width', 1)
          .attr('opacity', 0.15)
          .append('animate')
          .attr('attributeName', 'r').attr('values', '60;68;60')
          .attr('dur', '2.5s').attr('repeatCount', 'indefinite');
      }

      // ── Leader glow rings ──
      if (statusLabel === 'LEADER' && !isNodeDown) {
        g.append('circle')
          .attr('r', 60)
          .attr('fill', 'none')
          .attr('stroke', color)
          .attr('stroke-width', 1)
          .attr('opacity', 0.2)
          .append('animate')
          .attr('attributeName', 'r').attr('values', '56;64;56')
          .attr('dur', '3s').attr('repeatCount', 'indefinite');

        g.append('circle')
          .attr('r', 70)
          .attr('fill', 'none')
          .attr('stroke', color)
          .attr('stroke-width', 0.5)
          .attr('opacity', 0.1)
          .append('animate')
          .attr('attributeName', 'r').attr('values', '66;76;66')
          .attr('dur', '4s').attr('repeatCount', 'indefinite');
      }

      // ── Partitioned warning ring ──
      if (isPartitioned && isAlive) {
        g.append('circle')
          .attr('r', 58)
          .attr('fill', 'none')
          .attr('stroke', PARTITIONED_COLOR)
          .attr('stroke-width', 1.5)
          .attr('stroke-dasharray', '6,4')
          .attr('opacity', 0.3)
          .append('animate')
          .attr('attributeName', 'stroke-dashoffset').attr('values', '0;20')
          .attr('dur', '2s').attr('repeatCount', 'indefinite');
      }

      // ── Main node circle ──
      const safeId = node.id.replace(/[^a-zA-Z0-9_-]/g, '_');
      const gradient = defs.append('radialGradient')
        .attr('id', `grad-${safeId}`)
        .attr('cx', '30%').attr('cy', '30%');
      gradient.append('stop').attr('offset', '0%').attr('stop-color', color).attr('stop-opacity', isNodeDown ? 0.15 : 0.25);
      gradient.append('stop').attr('offset', '100%').attr('stop-color', color).attr('stop-opacity', isNodeDown ? 0.02 : 0.05);

      g.append('circle')
        .attr('r', 48)
        .attr('fill', `url(#grad-${safeId})`)
        .attr('stroke', color)
        .attr('stroke-width', isNodeDown ? 2 : 1.5)
        .attr('stroke-opacity', isNodeDown ? 0.7 : 0.5)
        .attr('filter', isNodeDown ? 'url(#deadGlow)' : 'url(#glow)');

      // Inner dashed circle
      g.append('circle')
        .attr('r', 36)
        .attr('fill', 'none')
        .attr('stroke', color)
        .attr('stroke-width', 0.5)
        .attr('stroke-opacity', 0.15)
        .attr('stroke-dasharray', '4,4');

      // ── Status icon ──
      const icon = isNodeDown ? '\u2620' : isPartitioned ? '\u26A0' : role === 'LEADER' ? '\u265B' : role === 'CANDIDATE' ? '\u2606' : '\u25CE';
      g.append('text')
        .attr('text-anchor', 'middle')
        .attr('dy', -6)
        .attr('font-size', 18)
        .attr('fill', color)
        .attr('opacity', isNodeDown ? 0.7 : 0.9)
        .text(icon);

      // ── Node ID ──
      g.append('text')
        .attr('text-anchor', 'middle')
        .attr('dy', 14)
        .attr('fill', isNodeDown ? 'rgba(255,255,255,0.4)' : 'white')
        .attr('font-size', 10)
        .attr('font-weight', 600)
        .attr('font-family', 'Inter, sans-serif')
        .attr('opacity', 0.9)
        .text(shortLabel(node.id));

      // ── Status label pill ──
      const pillText = statusLabel;
      const pillW = Math.max(52, pillText.length * 6 + 14);
      const pillColor = color;

      g.append('rect')
        .attr('x', -pillW / 2).attr('y', 24)
        .attr('width', pillW).attr('height', 16)
        .attr('rx', 8)
        .attr('fill', pillColor)
        .attr('opacity', isNodeDown ? 0.25 : 0.15);
      g.append('text')
        .attr('text-anchor', 'middle')
        .attr('dy', 36)
        .attr('fill', pillColor)
        .attr('font-size', 8)
        .attr('font-weight', 700)
        .attr('letter-spacing', '0.05em')
        .attr('font-family', 'Inter, sans-serif')
        .text(pillText);

      // ── Term label ──
      g.append('text')
        .attr('text-anchor', 'middle')
        .attr('dy', 54)
        .attr('fill', isNodeDown ? 'rgba(255,255,255,0.15)' : 'rgba(255,255,255,0.25)')
        .attr('font-size', 9)
        .attr('font-family', 'JetBrains Mono, monospace')
        .text(`T${node.info.term ?? '?'}`);

      // ── Alive/Dead indicator dot ──
      g.append('circle')
        .attr('cx', 38).attr('cy', -38)
        .attr('r', 5)
        .attr('fill', isNodeDown ? DOWN_COLOR : '#22c55e')
        .attr('stroke', 'rgba(0,0,0,0.3)')
        .attr('stroke-width', 1);

      if (!isNodeDown) {
        // Pulse on the alive dot
        g.append('circle')
          .attr('cx', 38).attr('cy', -38)
          .attr('r', 5)
          .attr('fill', 'none')
          .attr('stroke', '#22c55e')
          .attr('stroke-width', 1)
          .attr('opacity', 0.5)
          .append('animate')
          .attr('attributeName', 'r').attr('values', '5;10;5')
          .attr('dur', '2s').attr('repeatCount', 'indefinite');
      }
    });

    // ──── Central status text ────
    const aliveCount = nodePositions.filter(n => n.info.isAlive !== false && n.info.isUp !== false).length;
    const totalCount = nodePositions.length;
    const healthPct = totalCount > 0 ? Math.round((aliveCount / totalCount) * 100) : 0;
    const healthColor = healthPct === 100 ? '#22c55e' : healthPct >= 50 ? '#f59e0b' : '#ef4444';

    svg.append('text')
      .attr('x', cx).attr('y', cy - 8)
      .attr('text-anchor', 'middle')
      .attr('fill', healthColor)
      .attr('font-size', 24)
      .attr('font-weight', 700)
      .attr('font-family', 'JetBrains Mono, monospace')
      .attr('opacity', 0.3)
      .text(`${aliveCount}/${totalCount}`);

    svg.append('text')
      .attr('x', cx).attr('y', cy + 12)
      .attr('text-anchor', 'middle')
      .attr('fill', 'rgba(255,255,255,0.15)')
      .attr('font-size', 9)
      .attr('font-family', 'Inter, sans-serif')
      .text('NODES ALIVE');

  }, [allNodeIds, getNodeInfo, clusterStatus]);

  return (
    <div className="h-full flex flex-col gap-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-xl font-bold tracking-tight bg-gradient-to-r from-white to-white/70 bg-clip-text text-transparent">Cluster Topology</h2>
          <p className="text-xs text-white/30 mt-0.5">Live cluster health • auto-refreshing every second</p>
        </div>
        <div className="flex items-center gap-4 text-[11px] text-white/40">
          {[
            { color: 'bg-emerald-500', label: 'Leader', glow: 'shadow-emerald-500/30' },
            { color: 'bg-indigo-500', label: 'Follower', glow: 'shadow-indigo-500/30' },
            { color: 'bg-amber-500', label: 'Candidate', glow: 'shadow-amber-500/30' },
            { color: 'bg-orange-500', label: 'Partitioned', glow: 'shadow-orange-500/30' },
            { color: 'bg-red-500', label: 'Dead', glow: 'shadow-red-500/30' },
          ].map((item) => (
            <span key={item.label} className="flex items-center gap-1.5">
              <span className={`w-2.5 h-2.5 rounded-full ${item.color} shadow-sm ${item.glow}`} />
              <span className="font-medium">{item.label}</span>
            </span>
          ))}
        </div>
      </div>

      {/* Node status cards */}
      <div className="grid grid-cols-3 gap-3">
        {allNodeIds().map((id) => {
          const info = getNodeInfo(id);
          const isDown = info.isAlive === false || info.isUp === false;
          const isLeader = info.statusLabel === 'LEADER';
          const isSelf = id === clusterStatus?.nodeId;
          return (
            <motion.div
              key={id}
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              className={`relative px-4 py-3 rounded-2xl border transition-all duration-300 overflow-hidden ${
                isDown
                  ? 'bg-red-500/[0.06] border-red-500/15'
                  : info.isPartitioned
                    ? 'bg-orange-500/[0.06] border-orange-500/15'
                    : isLeader
                      ? 'bg-emerald-500/[0.04] border-emerald-500/15 ring-1 ring-emerald-500/10'
                      : 'bg-white/[0.02] border-white/[0.06]'
              }`}
            >
              {/* Subtle gradient overlay */}
              {isLeader && !isDown && (
                <div className="absolute inset-0 bg-gradient-to-br from-emerald-500/[0.04] to-transparent pointer-events-none" />
              )}
              <div className="relative flex items-center justify-between">
                <div className="flex items-center gap-2.5">
                  <div className={`w-8 h-8 rounded-lg flex items-center justify-center ${
                    isDown ? 'bg-red-500/10' : isLeader ? 'bg-emerald-500/10' : 'bg-white/[0.04]'
                  }`}>
                    {isDown ? <Skull className="w-4 h-4 text-red-400" /> :
                      isLeader ? <Crown className="w-4 h-4 text-emerald-400" /> :
                      info.isPartitioned ? <ShieldAlert className="w-4 h-4 text-orange-400" /> :
                      <Activity className="w-4 h-4 text-blue-400" />}
                  </div>
                  <div>
                    <div className="flex items-center gap-1.5">
                      <span className="text-sm font-semibold text-white/80">{shortLabel(id)}</span>
                      {isSelf && <span className="text-[8px] font-bold text-brand-400/50 bg-brand-500/10 px-1.5 py-0.5 rounded-full">YOU</span>}
                    </div>
                    <div className={`text-[11px] mt-0.5 font-bold tracking-wide ${
                      isDown ? 'text-red-400' : info.isPartitioned ? 'text-orange-400' : isLeader ? 'text-emerald-400' : 'text-blue-400'
                    }`}>
                      {info.statusLabel}
                      {info.term !== undefined && <span className="text-white/20 ml-1.5 font-mono font-normal">T{info.term}</span>}
                    </div>
                  </div>
                </div>
                <div className={`w-3 h-3 rounded-full ${
                  isDown ? 'bg-red-500' : info.isPartitioned ? 'bg-orange-500' : 'bg-emerald-500'
                } ${!isDown ? 'shadow-sm shadow-emerald-500/50' : ''}`} />
              </div>
            </motion.div>
          );
        })}
      </div>

      {/* SVG Canvas */}
      <motion.div
        className="flex-1 glass-card overflow-hidden relative"
        initial={{ opacity: 0, scale: 0.98 }}
        animate={{ opacity: 1, scale: 1 }}
        transition={{ duration: 0.4 }}
      >
        {/* Subtle grid pattern */}
        <div className="absolute inset-0 opacity-[0.03]" style={{
          backgroundImage: 'radial-gradient(circle, rgba(255,255,255,0.3) 1px, transparent 1px)',
          backgroundSize: '30px 30px',
        }} />
        <svg ref={svgRef} className="w-full h-full relative z-10" />
      </motion.div>

      {/* Quick Actions */}
      <div className="flex items-center gap-2">
        <QuickAction
          icon={Trash2}
          label="Kill This Node"
          variant="danger"
          onClick={async () => {
            const base = getApiBase();
            await fetch(`${base}/chaos/kill-node/${clusterStatus?.nodeId}`, { method: 'POST' });
          }}
        />
        <QuickAction
          icon={RotateCcw}
          label="Recover This Node"
          variant="success"
          onClick={async () => {
            const base = getApiBase();
            await fetch(`${base}/chaos/recover/${clusterStatus?.nodeId}`, { method: 'POST' });
          }}
        />
        <QuickAction
          icon={RefreshCw}
          label="Recover All Nodes"
          variant="primary"
          onClick={async () => {
            const base = getApiBase();
            await fetch(`${base}/chaos/recover-all`, { method: 'POST' });
          }}
        />
        <QuickAction
          icon={Zap}
          label="Trigger Election"
          variant="warning"
          onClick={async () => {
            const base = getApiBase();
            await fetch(`${base}/chaos/trigger-election`, { method: 'POST' });
          }}
        />
      </div>
    </div>
  );
}

function QuickAction({ icon: Icon, label, variant, onClick }: {
  icon: typeof Crown; label: string; variant: 'danger' | 'success' | 'primary' | 'warning'; onClick: () => void;
}) {
  const styles = {
    danger: 'bg-red-500/8 border-red-500/15 text-red-400 hover:bg-red-500/15 hover:border-red-500/25',
    success: 'bg-emerald-500/8 border-emerald-500/15 text-emerald-400 hover:bg-emerald-500/15 hover:border-emerald-500/25',
    primary: 'bg-brand-500/8 border-brand-500/15 text-brand-400 hover:bg-brand-500/15 hover:border-brand-500/25',
    warning: 'bg-amber-500/8 border-amber-500/15 text-amber-400 hover:bg-amber-500/15 hover:border-amber-500/25',
  };
  return (
    <button
      onClick={onClick}
      className={`flex items-center gap-2 px-4 py-2.5 rounded-xl text-xs font-medium border transition-all duration-200 active:scale-[0.97] ${styles[variant]}`}
    >
      <Icon className="w-3.5 h-3.5" />
      {label}
    </button>
  );
}
