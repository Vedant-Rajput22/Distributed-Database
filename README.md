# Mini Distributed Database — Raft + MVCC

<div align="center">

A **production-grade distributed key-value store** built entirely from scratch with **Raft consensus**, **Multi-Version Concurrency Control (MVCC)**, **Two-Phase Commit (2PC) distributed transactions**, and a **real-time React dashboard** for visualization and chaos engineering.

![Architecture](https://img.shields.io/badge/Architecture-Distributed-blue)
![Consensus](https://img.shields.io/badge/Consensus-Raft-green)
![Storage](https://img.shields.io/badge/Storage-RocksDB-orange)
![Frontend](https://img.shields.io/badge/UI-React%20%2B%20D3.js-purple)
![Java](https://img.shields.io/badge/Java-21-red)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.2-brightgreen)
![gRPC](https://img.shields.io/badge/gRPC-1.61.0-blueviolet)
![License](https://img.shields.io/badge/License-MIT-yellow)

</div>

---

## Table of Contents

- [What Is This Project?](#what-is-this-project)
- [Architecture Overview](#architecture-overview)
- [Features at a Glance](#features-at-a-glance)
- [Feature Deep Dives](#feature-deep-dives)
  - [1. Raft Consensus Engine](#1-raft-consensus-engine)
    - [1.1 Leader Election](#11-leader-election)
    - [1.2 Log Replication](#12-log-replication)
    - [1.3 Commit Index Advancement](#13-commit-index-advancement)
    - [1.4 Linearizable Reads (ReadIndex Protocol)](#14-linearizable-reads-readindex-protocol)
    - [1.5 Snapshotting & Crash Recovery](#15-snapshotting--crash-recovery)
    - [1.6 Persistent State](#16-persistent-state)
  - [2. MVCC (Multi-Version Concurrency Control)](#2-mvcc-multi-version-concurrency-control)
    - [2.1 Versioned Storage via Key Encoding](#21-versioned-storage-via-key-encoding)
    - [2.2 Point-in-Time (Snapshot) Reads](#22-point-in-time-snapshot-reads)
    - [2.3 Tombstone Deletes](#23-tombstone-deletes)
    - [2.4 Version History Browser](#24-version-history-browser)
    - [2.5 Garbage Collection](#25-garbage-collection)
  - [3. Distributed Transactions (Two-Phase Commit)](#3-distributed-transactions-two-phase-commit)
  - [4. Storage Engine (RocksDB)](#4-storage-engine-rocksdb)
  - [5. gRPC Inter-Node Communication](#5-grpc-inter-node-communication)
  - [6. Leader Forwarding](#6-leader-forwarding)
  - [7. Real-Time Event System](#7-real-time-event-system)
  - [8. React Dashboard](#8-react-dashboard)
    - [8.1 Topology View](#81-topology-view)
    - [8.2 Raft Log Viewer](#82-raft-log-viewer)
    - [8.3 KV Explorer](#83-kv-explorer)
    - [8.4 Metrics Dashboard](#84-metrics-dashboard)
    - [8.5 Event Timeline](#85-event-timeline)
    - [8.6 Chaos Panel](#86-chaos-panel)
  - [9. Chaos Engineering](#9-chaos-engineering)
  - [10. Observability & Monitoring](#10-observability--monitoring)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Option 1: Local Development (Recommended)](#option-1-local-development-recommended)
  - [Option 2: Docker Compose](#option-2-docker-compose)
  - [Option 3: Kubernetes](#option-3-kubernetes)
- [API Reference](#api-reference)
  - [KV Operations](#kv-operations)
  - [Cluster Management](#cluster-management)
  - [Chaos Engineering Endpoints](#chaos-engineering-endpoints)
  - [Metrics Endpoints](#metrics-endpoints)
  - [gRPC Services](#grpc-services)
- [How It All Fits Together](#how-it-all-fits-together)
- [Configuration Reference](#configuration-reference)
- [Testing Guide](#testing-guide)
  - [Dashboard Testing Walkthrough](#dashboard-testing-walkthrough)
  - [Chaos Scenarios to Try](#chaos-scenarios-to-try)
- [Design Decisions & Trade-offs](#design-decisions--trade-offs)

---

## What Is This Project?

This is a **miniature distributed database** — a fully working, from-scratch implementation of the core systems powering databases like CockroachDB, TiKV, and etcd. It demonstrates how distributed consensus, multi-version concurrency control, and fault tolerance work together in a real system.

**The three pillars:**

| Pillar               | What It Does                                                      | Real-World Equivalent         |
| -------------------- | ----------------------------------------------------------------- | ----------------------------- |
| **Raft Consensus**   | Ensures all 3 nodes agree on the same data, even when nodes crash | etcd, Consul, CockroachDB     |
| **MVCC**             | Stores multiple versions of every key so reads never block writes | PostgreSQL, CockroachDB, TiKV |
| **2PC Transactions** | Atomic multi-key operations across the cluster                    | Spanner, CockroachDB          |

The project also includes a **real-time React dashboard** that lets you visually observe leader elections, log replication, key-value operations, and chaos engineering scenarios as they happen.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                     React Dashboard (Vite + D3.js)                 │
│                                                                     │
│   Topology  │  Raft Log  │  KV Explorer  │  Metrics  │  Events  │  │
│    View     │  Viewer    │  + MVCC + TXN  │  Charts   │ Timeline │  │
│             │            │               │           │  + Chaos  │  │
└──────────────────┬──────────────────────────┬───────────────────────┘
                   │  REST API + WebSocket    │
                   │      (STOMP/SockJS)      │
┌──────────────────┴──────────────────────────┴───────────────────────┐
│                    Spring Boot Node (× 3)                          │
│                                                                     │
│  ┌──────────────┐  ┌────────────────┐  ┌─────────────────────────┐  │
│  │  REST API    │  │  gRPC Service  │  │  WebSocket Publisher    │  │
│  │  Controllers │  │  (Raft + KV)   │  │  (STOMP over SockJS)   │  │
│  └──────┬───────┘  └──────┬─────────┘  └────────────┬────────────┘  │
│         │                 │                         │               │
│  ┌──────┴─────────────────┴─────────────────────────┴────────────┐  │
│  │                  Raft Consensus Engine                        │  │
│  │                                                               │  │
│  │  Leader Election  │  Log Replication  │  Snapshot Manager     │  │
│  │  (ElectionTimer)  │  (AppendEntries)  │  (RocksDB Checkpoint) │  │
│  └───────────────────────────┬───────────────────────────────────┘  │
│                              │                                      │
│  ┌───────────────────────────┴───────────────────────────────────┐  │
│  │           MVCC Layer (Multi-Version Concurrency Control)      │  │
│  │                                                               │  │
│  │  MvccStore  │  KeyEncoding  │  GarbageCollector               │  │
│  │  (put/get/  │  (inverted    │  (scheduled cleanup             │  │
│  │   scan/del) │   timestamps) │   of old versions)              │  │
│  └───────────────────────────┬───────────────────────────────────┘  │
│                              │                                      │
│  ┌───────────────────────────┴───────────────────────────────────┐  │
│  │              RocksDB Storage Engine                           │  │
│  │                                                               │  │
│  │  64MB Write Buffer  │  SNAPPY + ZSTD Compression             │  │
│  │  Atomic WriteBatch  │  Native Checkpoint API                 │  │
│  └───────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘

                    gRPC (Protobuf) inter-node communication
         ┌──────────────┬──────────────┬──────────────────────┐
         │              │              │                      │
    ┌────┴────┐    ┌────┴────┐    ┌────┴────┐
    │ Node-1  │◄──►│ Node-2  │◄──►│ Node-3  │
    │ :8080   │    │ :8082   │    │ :8084   │
    │ :9090   │    │ :9092   │    │ :9094   │
    └─────────┘    └─────────┘    └─────────┘
     HTTP/gRPC      HTTP/gRPC      HTTP/gRPC
```

**Data flow for a write operation:**

```
Client PUT(key, value)
       │
       ▼
  Any Node (REST API)
       │
       │ (if not leader → HTTP forward to leader)
       ▼
  Leader Node
       │
       ├──► Append to local Raft log
       │
       ├──► Send AppendEntries RPC to all followers (gRPC)
       │
       │    Follower-1: ack ✓
       │    Follower-2: ack ✓
       │
       ├──► Majority replicated → advance commitIndex
       │
       ├──► Apply to MVCC store (versioned write)
       │
       └──► Return success to client
```

---

## Features at a Glance

| Category          | Feature              | Description                                               |
| ----------------- | -------------------- | --------------------------------------------------------- |
| **Consensus**     | Raft Leader Election | Randomized timeouts with parallel `RequestVote` RPCs      |
| **Consensus**     | Log Replication      | `AppendEntries` with consistency checks and backtracking  |
| **Consensus**     | Linearizable Reads   | ReadIndex protocol — confirms leadership before reads     |
| **Consensus**     | Snapshotting         | RocksDB checkpoint-based snapshots for crash recovery     |
| **Consensus**     | Persistent State     | Term, votedFor, and log survive restarts                  |
| **Storage**       | MVCC Versioning      | Every write creates a new version with a timestamp        |
| **Storage**       | Point-in-Time Reads  | Query any key at any historical timestamp                 |
| **Storage**       | Tombstone Deletes    | Deletes preserve history as tombstone markers             |
| **Storage**       | Version History      | Browse all versions of any key with timestamps            |
| **Storage**       | Garbage Collection   | Background cleanup of old MVCC versions                   |
| **Storage**       | RocksDB Engine       | LSM-tree storage with SNAPPY/ZSTD compression             |
| **Transactions**  | Two-Phase Commit     | Atomic multi-key operations (Prepare → Commit/Abort)      |
| **Networking**    | gRPC Communication   | Protobuf-serialized RPCs between nodes                    |
| **Networking**    | Leader Forwarding    | Any node accepts writes, forwards to leader automatically |
| **Observability** | Real-Time Events     | WebSocket event stream (STOMP/SockJS)                     |
| **Observability** | Prometheus Metrics   | Counters, gauges, histograms for all operations           |
| **Observability** | Health Checks        | Spring Boot Actuator + RocksDB health indicator           |
| **Dashboard**     | Cluster Topology     | D3.js interactive graph with animated heartbeats          |
| **Dashboard**     | Raft Log Viewer      | Side-by-side log columns per node                         |
| **Dashboard**     | KV Explorer          | Query console + data browser + transaction builder        |
| **Dashboard**     | Metrics Charts       | Live Recharts graphs (throughput, latency, Raft state)    |
| **Dashboard**     | Event Timeline       | Filterable, searchable, real-time event stream            |
| **Dashboard**     | Chaos Panel          | Kill nodes, partition networks, trigger scenarios         |
| **Deployment**    | Docker Compose       | Full stack: 3 nodes + UI + Prometheus + Grafana           |
| **Deployment**    | Kubernetes           | StatefulSet with PVCs, services, and probes               |

---

## Feature Deep Dives

### 1. Raft Consensus Engine

The Raft consensus algorithm is the backbone of this database. It ensures that all three nodes maintain an identical, ordered log of operations — even when nodes crash, restart, or become temporarily unreachable.

**Core idea:** At any point in time, exactly one node is the **Leader**. Only the leader accepts writes. It replicates every write to the **Followers** before considering it committed. If the leader crashes, a new election happens automatically.

**Key files:**

- `RaftNode.java` (786 lines) — the state machine implementing the Raft protocol
- `RaftLog.java` (200 lines) — persistent log storage
- `ElectionTimer.java` — randomized election timeout
- `SnapshotManager.java` — RocksDB checkpoint-based snapshots

#### 1.1 Leader Election

**How it works:**

Every node starts as a **Follower**. Each follower runs an `ElectionTimer` with a randomized timeout between **3,000ms and 5,000ms**. If a follower doesn't hear from a leader within that window (no heartbeat or `AppendEntries`), it assumes the leader is dead and starts an election:

```
Follower (timeout expires)
    │
    ├──► Increment currentTerm
    ├──► Transition to CANDIDATE
    ├──► Vote for self
    ├──► Send RequestVote RPCs to all peers (in parallel via CompletableFuture)
    │
    │    Peer-1 response: voteGranted=true ✓
    │    Peer-2 response: voteGranted=true ✓
    │
    ├──► Received majority (2 of 3) → become LEADER
    └──► Start sending heartbeats immediately
```

**Vote granting rules (safety):**

1. A node grants its vote only **once per term** (`votedFor` is persisted)
2. The candidate's log must be **at least as up-to-date** as the voter's log:
   - Compare last log entry terms: higher term wins
   - If terms are equal: longer log wins
3. If the candidate's term is stale (lower than voter's current term), the vote is rejected

**Why randomized timeouts?** If all nodes timed out simultaneously, they'd all start elections at once and split the vote. Randomization ensures one node almost always wins first.

**Term numbers:** Every election increments a monotonically-increasing `term` counter. Terms act like logical clocks — any node that sees a higher term than its own immediately steps down to follower. This prevents stale leaders from continuing to operate.

#### 1.2 Log Replication

Once elected, the leader replicates all client writes to followers using the `AppendEntries` RPC:

```
Leader's Log:    [1:T1:NOOP] [2:T1:PUT(a,1)] [3:T1:PUT(b,2)] [4:T1:DEL(a)]
                                                                ↑ nextIndex[peer-1]
Follower's Log:  [1:T1:NOOP] [2:T1:PUT(a,1)] [3:T1:PUT(b,2)]
```

**AppendEntries RPC contains:**

- `prevLogIndex` / `prevLogTerm` — for consistency checking
- `entries[]` — new log entries to append
- `leaderCommit` — leader's commit index (so follower knows what's safe to apply)

**Consistency check:** The follower verifies that `prevLogIndex` and `prevLogTerm` match its own log. If they don't match, the follower rejects the request. The leader then **backtracks** by decrementing `nextIndex` for that follower and retries with earlier entries until a match is found. This guarantees logs are identical up to the match point.

**Log entry structure:**

```
Entry = (index, term, CommandType, key, value)
CommandType: NOOP | PUT | DELETE | TXN_PREPARE | TXN_COMMIT | TXN_ABORT
```

Entries are serialized via `DataOutputStream` and stored in RocksDB with keys formatted as `__raft_log__:<020d-padded-index>` for correct lexicographic ordering.

#### 1.3 Commit Index Advancement

An entry is **committed** when it has been replicated to a majority of nodes (2 out of 3). The leader tracks replication progress per peer via `matchIndex[]`:

```java
// Leader checks: what's the highest index replicated to a majority?
for (long n = lastLogIndex; n > commitIndex; n--) {
    if (raftLog.getTermAt(n) == currentTerm) {
        int replicationCount = 1; // leader has it
        for (long matchIdx : matchIndex.values()) {
            if (matchIdx >= n) replicationCount++;
        }
        if (replicationCount > (peers.size() + 1) / 2) {
            commitIndex = n;
            break;
        }
    }
}
```

**Important safety rule:** The leader only commits entries from its **own term**. Entries from previous terms are committed indirectly when a new-term entry pushes the commit index forward. This is why a newly-elected leader appends a **NO-OP** entry — to ensure previous-term entries get committed.

Once committed, entries are **applied** to the MVCC state machine by a background thread running every 10ms.

#### 1.4 Linearizable Reads (ReadIndex Protocol)

A naive read from any node might return stale data (e.g., a newly-elected leader that hasn't received the latest entries). The ReadIndex protocol ensures **linearizable reads** — every read reflects all previously committed writes:

```
1. Client sends GET(key) with consistent=true
2. Leader records its current commitIndex as readIndex
3. Leader sends a heartbeat round to confirm it's still the leader
4. If majority responds → leadership confirmed
5. Wait until lastApplied >= readIndex (entries are applied)
6. Serve the read from the MVCC store
```

This avoids the overhead of writing a no-op entry for every read while still guaranteeing linearizability.

#### 1.5 Snapshotting & Crash Recovery

As the Raft log grows, it would eventually consume unbounded storage. Snapshotting solves this:

1. **Creating snapshots:** When the log exceeds `snapshotInterval` entries (default: 1000) past the last snapshot, `SnapshotManager` creates a RocksDB checkpoint — a space-efficient hard-linked copy of the database files.

2. **Installing snapshots:** If a follower is far behind (e.g., after a long outage), the leader sends its snapshot instead of replaying thousands of log entries. The follower receives the snapshot in chunks, replaces its database, and resumes replication from the snapshot point.

3. **Recovery:** On restart, each node loads its persisted `currentTerm`, `votedFor`, and replays committed log entries from RocksDB.

#### 1.6 Persistent State

Raft requires certain state to survive crashes:

| State         | Storage                                 | Purpose                                                  |
| ------------- | --------------------------------------- | -------------------------------------------------------- |
| `currentTerm` | RocksDB key `__raft_meta__:currentTerm` | Prevents voting for multiple candidates in the same term |
| `votedFor`    | RocksDB key `__raft_meta__:votedFor`    | Prevents double-voting                                   |
| Log entries   | RocksDB keys `__raft_log__:<index>`     | Durability of committed data                             |

Volatile state (`commitIndex`, `lastApplied`, `nextIndex[]`, `matchIndex[]`) is reconstructed on startup from the persisted log.

---

### 2. MVCC (Multi-Version Concurrency Control)

MVCC is the technique that allows **reads and writes to happen concurrently without blocking each other**. Instead of overwriting a value in-place, every write creates a new _version_ of the key with a unique timestamp. Reads can then query any point in time.

**Key files:**

- `MvccStore.java` (270 lines) — versioned read/write operations
- `KeyEncoding.java` (100 lines) — the encoding scheme that makes it work
- `GarbageCollector.java` — background cleanup of old versions

#### 2.1 Versioned Storage via Key Encoding

The core trick is how keys are stored in RocksDB:

```
Physical Key Format:   <user_key_bytes> + ':' + <8-byte inverted timestamp>
                       ────────────────   ─    ──────────────────────────────
                        e.g. "user:1"    sep    Long.MAX_VALUE - timestamp
```

**Why invert the timestamp?** RocksDB stores keys in **ascending lexicographic order**. By inverting the timestamp (`Long.MAX_VALUE - timestamp`), **newer versions sort before older versions**. This means a simple prefix scan from `user:1:` returns the newest version first — no need to scan all versions.

**Example:**

```
user:1 written at T=1000:  key = "user:1" + ":" + bytes(MAX_VALUE - 1000)
user:1 written at T=2000:  key = "user:1" + ":" + bytes(MAX_VALUE - 2000)

In RocksDB order (ascending bytes):
  "user:1:<MAX-2000>"  ← newer (smaller inverted value)
  "user:1:<MAX-1000>"  ← older (larger inverted value)

Prefix scan "user:1:" → returns T=2000 first ✓
```

#### 2.2 Point-in-Time (Snapshot) Reads

You can read the value of any key as it existed at any past timestamp:

```java
// Read "user:1" as it was at timestamp 1500
MvccResult result = mvccStore.get("user:1", 1500);
// Returns the version with the highest timestamp ≤ 1500
```

This is implemented by prefix-scanning all versions of the key and returning the first one whose timestamp is ≤ the requested `readTimestamp`.

**Use cases:**

- **Time-travel queries**: "What was the value 5 minutes ago?"
- **Consistent snapshot reads**: "Read all keys at the same point in time"
- **Debugging**: Inspect the exact state of the database at any moment

#### 2.3 Tombstone Deletes

When you delete a key, the system doesn't actually remove it. Instead, it writes a **tombstone marker** (`__TOMBSTONE__` bytes) at the current timestamp:

```
Before delete:
  user:1:<T2> → "Alice-v2"
  user:1:<T1> → "Alice-v1"

After DELETE(user:1) at T3:
  user:1:<T3> → __TOMBSTONE__    ← tombstone
  user:1:<T2> → "Alice-v2"       ← still exists for history
  user:1:<T1> → "Alice-v1"       ← still exists for history
```

A `get("user:1")` at current time sees the tombstone and returns "not found". But a `get("user:1", T2)` (point-in-time read) still returns `"Alice-v2"`.

#### 2.4 Version History Browser

The `getVersionHistory(key)` method returns **all versions** of a key, ordered newest-first. The dashboard's KV Explorer lets you click any key to see its full version history with timestamps — including tombstones.

```
GET /api/kv/versions/user:1

Response:
{
  "versions": [
    { "version": 3, "timestamp": 1772283515705, "value": null, "deleted": true },
    { "version": 2, "timestamp": 1772283513400, "value": "Alice-v2" },
    { "version": 1, "timestamp": 1772283510200, "value": "Alice-v1" }
  ]
}
```

#### 2.5 Garbage Collection

Without cleanup, MVCC versions would accumulate forever. The `GarbageCollector` runs as a `@Scheduled` Spring component every **60 seconds** (configurable):

```
For each user key:
  1. Group all versions by user key
  2. Keep ALL versions newer than (now - retentionMs)      [default: 5 minutes]
  3. Keep at least 1 version per key (never delete the last one)
  4. Delete all remaining old versions
```

This balances between keeping history for point-in-time reads and reclaiming storage.

---

### 3. Distributed Transactions (Two-Phase Commit)

When you need to atomically update multiple keys (e.g., transfer money between accounts), a single PUT isn't enough. The 2PC protocol ensures **all-or-nothing** semantics:

**Phase 1 — Prepare:**

```
Client: "I want to PUT(balance:A, 900) AND PUT(balance:B, 1100)"
    │
    ▼
Coordinator validates all operations
    │
    ├── All valid? → Status = PREPARED
    └── Any invalid? → Status = ABORTED (immediate)
```

**Phase 2 — Commit:**

```
Coordinator submits all operations through Raft consensus (in parallel)
    │
    ├── All committed by Raft? → Status = COMMITTED ✓
    └── Any failed? → Status = ABORTED ✗
```

**API usage:**

```json
POST /api/kv/txn
{
  "operations": [
    { "type": "PUT", "key": "balance:A", "value": "900" },
    { "type": "PUT", "key": "balance:B", "value": "1100" }
  ]
}
```

Because each operation goes through Raft, the transaction is **replicated and durable** across all nodes.

---

### 4. Storage Engine (RocksDB)

RocksDB is a high-performance embedded key-value store (developed by Facebook, used by CockroachDB, TiKV, and more). It uses a **Log-Structured Merge-Tree (LSM-tree)** internally.

**Our configuration:**

| Parameter         | Value                                | Purpose                                  |
| ----------------- | ------------------------------------ | ---------------------------------------- |
| Write Buffer Size | 64 MB                                | Buffer writes in memory before flushing  |
| Max Write Buffers | 3                                    | Allow concurrent flushes                 |
| Compression       | SNAPPY (regular) + ZSTD (bottommost) | Balance speed vs. space                  |
| Background Jobs   | 4                                    | Parallel compaction threads              |
| Checkpointing     | RocksDB `Checkpoint` API             | Space-efficient snapshots via hard links |

**The `StorageEngine` interface abstracts RocksDB:**

```java
public interface StorageEngine {
    void put(byte[] key, byte[] value);
    byte[] get(byte[] key);
    void delete(byte[] key);
    List<Map.Entry<byte[], byte[]>> scan(byte[] start, byte[] end, int limit);
    void writeBatch(Map<byte[], byte[]> entries);
    void createCheckpoint(String path);
    Map<String, String> getStats();
    boolean isHealthy();
}
```

Both the **Raft log** and the **MVCC store** share the same RocksDB instance but use different key prefixes (`__raft_log__:` for log entries, user keys for MVCC data). System keys starting with `__` are filtered out of user-facing scans.

---

### 5. gRPC Inter-Node Communication

Nodes communicate using **gRPC** with Protocol Buffers for serialization. Three services are defined:

**`RaftService`** — Core consensus RPCs:
| RPC | Purpose |
|-----|---------|
| `RequestVote` | Candidate asks peers to vote during election |
| `AppendEntries` | Leader replicates log entries + heartbeats |
| `InstallSnapshot` | Leader sends full snapshot to far-behind follower |

**`KvService`** — Key-value operations:
| RPC | Purpose |
|-----|---------|
| `Put` / `Delete` | Write operations (leader-only) |
| `Get` | Simple read |
| `ConsistentGet` | Linearizable read via ReadIndex |
| `Scan` | Range scan with prefix/bounds |

**`TxnService`** — Transaction coordination:
| RPC | Purpose |
|-----|---------|
| `Prepare` | Phase 1 of 2PC |
| `Commit` / `Abort` | Phase 2 of 2PC |
| `GetTxnStatus` | Query transaction state |

**Connection details:**

- Plaintext channels (no TLS in development)
- 64 MB max message size (for snapshot transfer)
- Virtual thread executor (Java 21)
- Peer format in config: `node-id@host:grpcPort` (e.g., `node-2@localhost:9092`)

---

### 6. Leader Forwarding

In Raft, only the **leader** can accept writes. But in a real system, a client might connect to any node. Leader forwarding solves this transparently:

```
Client → PUT request → Node-1 (FOLLOWER)
                           │
                           │ "I'm not the leader. Let me forward this."
                           │
                           ├──► Determine leader ID from Raft state
                           ├──► Look up leader's HTTP URL from peer config
                           │      (gRPC port 9092 → HTTP port 8082)
                           ├──► Forward the request via HTTP
                           │
                           ◄── Response from leader
                           │
Client ◄── Response ◄───── ┘
```

The HTTP port is derived from the gRPC port by convention: `HTTP port = gRPC port - 1010`.

This means the **Vite dev server proxy** (which always points to node-1 on port 8080) works correctly regardless of which node is the current leader. The dashboard user never needs to know or care about leadership.

Both `KvController` (PUT/DELETE) and `ChaosController` (kill/recover) implement forwarding.

---

### 7. Real-Time Event System

The database publishes events for everything significant that happens:

**Event categories:**
| Category | Example Events |
|----------|---------------|
| `RAFT` | Leader elected, term changed, vote granted/denied |
| `REPLICATION` | Entry replicated, commit index advanced |
| `KV` | Key put, key deleted, key read |
| `HEARTBEAT` | Heartbeat sent/received |
| `SNAPSHOT` | Snapshot created, snapshot installed |
| `TXN` | Transaction prepared, committed, aborted |
| `MVCC` | GC completed, versions purged |
| `CHAOS` | Node killed, node recovered, partition added |

**Architecture:**

```
Event Source (RaftNode, MvccStore, etc.)
    │
    ▼
EventBus (in-memory pub/sub, ConcurrentLinkedDeque, max 10,000 events)
    │
    ├──► WebSocketPublisher → STOMP topics (/topic/events, /topic/raft, ...)
    │                              │
    │                              ▼
    │                        Dashboard (real-time updates)
    │
    └──► REST API (GET /api/events) for historical queries
```

The frontend connects via **SockJS** (with STOMP protocol) for reliable WebSocket communication with automatic reconnection.

---

### 8. React Dashboard

The dashboard is a single-page React application providing full observability and control over the cluster.

**Tech stack:** React 18, TypeScript, Vite 5, Tailwind CSS 3.4, D3.js 7, Recharts 2, Framer Motion 11, Zustand 4.

#### 8.1 Topology View

An interactive **D3.js force-directed graph** showing all cluster nodes and their connections:

- **Node colors**: Green = Leader, Blue = Follower, Yellow = Candidate, Red = Down/Killed
- **Animated heartbeats**: Green dots travel from the leader to followers along connection lines
- **Leader glow**: The leader node has a pulsing glow animation
- **Connection lines**: Solid = healthy, dashed red = partitioned
- **Icons**: Crown (leader), ballot (candidate), satellite (follower), skull (killed)
- **Quick actions**: Kill/Recover buttons directly on the topology

#### 8.2 Raft Log Viewer

A **side-by-side columnar view** of each node's Raft log:

- Shows entry index, term, command type, and key for each log entry
- **Status icons**: ✅ Applied, ⏳ Committed but not yet applied, 🔄 Replicating
- Highlights the commit index entry with a distinct bar
- Auto-refreshes every 2 seconds
- Legend explaining each status

#### 8.3 KV Explorer

Three sub-tabs for full database interaction:

**Query Console:**

- Input fields for Key and Value
- Buttons: **PUT** (create/update), **GET** (read), **DEL** (delete)
- Output console showing operation results and latency

**Data Browser:**

- Prefix filter input + **Scan** button
- Results table with key, value, and timestamp columns
- Click any row to expand its **MVCC version history** (all versions with timestamps)
- Refresh button

**Transactions:**

- Add multiple operations (PUT/DELETE) with key/value
- Remove individual operations
- **Execute** button runs the 2PC transaction
- Shows result (committed/aborted) with latency

#### 8.4 Metrics Dashboard

Four live Recharts charts, auto-updated via polling:

| Chart          | What It Shows                                                |
| -------------- | ------------------------------------------------------------ |
| Operations/sec | Area chart of PUT, GET, DELETE throughput                    |
| Latency        | Line chart of p50, p95, p99 latencies                        |
| Raft State     | Line chart of term progression, commit index, election count |
| Log & Storage  | Area chart of log entries, compaction events                 |

Plus stat cards showing: current term, commit index, last applied, node role, total elections, log size.

#### 8.5 Event Timeline

A filterable, searchable, real-time event stream:

- **Category toggles**: Click to show/hide event categories (RAFT, KV, CHAOS, etc.)
- **Text search**: Filter events by content
- **Auto-scroll**: Toggle to automatically scroll to newest events
- **Expandable**: Click any event to see its full JSON data payload
- **Framer Motion**: Smooth entry animations for new events

#### 8.6 Chaos Panel

Controls for chaos engineering experiments:

**Quick Scenarios** (one-click buttons):
| Scenario | What It Does |
|----------|-------------|
| Kill Leader | Kills the current leader node, triggering a re-election |
| Network Partition | Isolates a node from the cluster |
| Kill Majority | Kills 2 of 3 nodes (cluster loses quorum — writes stop) |
| Recover All | Recovers all nodes and heals all partitions |

**Per-Node Controls:** Individual Kill / Recover / Partition / Heal buttons for each node in the cluster.

**Activity Log:** Timestamped log of all chaos actions taken during the session.

---

### 9. Chaos Engineering

Chaos engineering lets you intentionally break parts of the system to verify it handles failures correctly. The system supports four failure modes:

| Failure Mode          | What Happens                               | Expected Behavior                                                                 |
| --------------------- | ------------------------------------------ | --------------------------------------------------------------------------------- |
| **Kill Node**         | Node stops processing all RPCs             | If follower killed: cluster continues. If leader killed: new election within 3-5s |
| **Recover Node**      | Node resumes processing                    | Re-joins cluster, catches up via log replication or snapshot                      |
| **Network Partition** | Node can't communicate with specific peers | May cause split-brain briefly, resolved by term numbers                           |
| **Heal Partition**    | Communication restored                     | Nodes reconcile, stale leader steps down                                          |

**Architecture:** Each chaos action only affects the targeted node. The `ChaosController` forwards kill/recover requests to the target node's HTTP endpoint, so you can kill any node from the dashboard (which connects through node-1).

**Scenarios to test:**

1. **Leader Failover**: Kill the leader → watch a new leader get elected → PUT still works
2. **Minority Failure**: Kill 1 follower → cluster still has quorum (2/3) → writes still work
3. **Majority Failure**: Kill 2 nodes → cluster loses quorum → writes fail → recover nodes → cluster resumes
4. **Network Partition**: Partition the leader → it can't reach majority → steps down → remaining nodes elect a new leader
5. **Split-Brain Recovery**: Partition + heal → stale leader sees higher term → steps down automatically

---

### 10. Observability & Monitoring

**Prometheus Metrics** (scraped at `/actuator/prometheus`):

- `raft_elections_total` — total election count
- `raft_log_entries` — current log size
- `raft_current_term` — current Raft term
- `raft_commit_index` / `raft_last_applied` — replication progress
- `kv_operations_total{type=PUT|GET|DELETE}` — operation counters
- `kv_operation_latency_seconds` — operation latency histogram

**Spring Boot Actuator**:

- `GET /actuator/health` — health status including RocksDB health check
- `GET /actuator/prometheus` — Prometheus metrics endpoint
- `GET /actuator/info` — application info

**Grafana Dashboard** (via Docker Compose):

- Pre-built dashboard at `monitoring/grafana/dashboards/minidb.json`
- Panels for throughput, latency, Raft state, storage metrics
- Auto-provisioned via Grafana provisioning configs

---

## Tech Stack

### Backend

| Technology       | Version          | Purpose                                                      |
| ---------------- | ---------------- | ------------------------------------------------------------ |
| Java             | 21 (LTS)         | Language runtime with virtual threads                        |
| Spring Boot      | 3.2.2            | Application framework (web, actuator, websocket, scheduling) |
| gRPC             | 1.61.0           | High-performance RPC between nodes                           |
| Protocol Buffers | 3.25.2           | Binary serialization for gRPC messages                       |
| RocksDB          | 9.0.0            | Embedded LSM-tree key-value storage                          |
| Micrometer       | (Spring managed) | Metrics collection with Prometheus registry                  |
| Jackson          | (Spring managed) | JSON serialization                                           |
| SockJS + STOMP   | (Spring managed) | Reliable WebSocket communication                             |

### Frontend

| Technology    | Version | Purpose                             |
| ------------- | ------- | ----------------------------------- |
| React         | 18      | UI component framework              |
| TypeScript    | 5.x     | Type-safe JavaScript                |
| Vite          | 5       | Fast build tool and dev server      |
| Tailwind CSS  | 3.4     | Utility-first CSS framework         |
| D3.js         | 7       | Cluster topology visualization      |
| Recharts      | 2       | Metrics charts and graphs           |
| Framer Motion | 11      | UI animations                       |
| Zustand       | 4       | Lightweight global state management |
| SockJS Client | 1.6     | WebSocket transport with fallbacks  |
| STOMP.js      | 7       | STOMP protocol over WebSocket       |

### Infrastructure

| Technology              | Purpose                                       |
| ----------------------- | --------------------------------------------- |
| Docker + Docker Compose | Container orchestration for local development |
| Kubernetes              | Production-grade container orchestration      |
| Nginx                   | Reverse proxy for frontend (production)       |
| Prometheus              | Metrics collection and storage                |
| Grafana                 | Metrics visualization dashboards              |
| Maven                   | Build tool and dependency management          |

---

## Project Structure

```
Mini-Distributed-Database/
│
├── start-cluster.ps1                    # One-click start (Windows)
├── stop-cluster.ps1                     # One-click stop (Windows)
├── start-cluster.sh                     # One-click start (Linux/macOS)
├── stop-cluster.sh                      # One-click stop (Linux/macOS)
│
├── proto/                               # Protocol Buffer definitions
│   ├── raft_service.proto               #   Raft RPCs (vote, append, snapshot)
│   ├── kv_service.proto                 #   KV RPCs (put, get, delete, scan)
│   └── txn_service.proto                #   Transaction RPCs (2PC)
│
├── backend/                             # Java Spring Boot application
│   ├── pom.xml                          #   Maven build config + dependencies
│   └── src/
│       ├── main/java/com/minidb/
│       │   ├── MiniDbApplication.java   #   Spring Boot entry point
│       │   │
│       │   ├── raft/                    #   Raft consensus engine
│       │   │   ├── RaftNode.java        #     Core state machine (786 lines)
│       │   │   ├── RaftLog.java         #     Persistent log (RocksDB-backed)
│       │   │   ├── ElectionTimer.java   #     Randomized election timeout
│       │   │   ├── SnapshotManager.java #     RocksDB checkpoint snapshots
│       │   │   └── RaftPeerClient.java  #     Peer communication interface
│       │   │
│       │   ├── storage/                 #   Storage abstraction layer
│       │   │   ├── StorageEngine.java   #     Interface (put/get/scan/batch)
│       │   │   └── RocksDbEngine.java   #     RocksDB implementation (230 lines)
│       │   │
│       │   ├── mvcc/                    #   Multi-Version Concurrency Control
│       │   │   ├── MvccStore.java       #     Versioned reads/writes (270 lines)
│       │   │   ├── KeyEncoding.java     #     Inverted-timestamp key format
│       │   │   └── GarbageCollector.java#     Scheduled old-version cleanup
│       │   │
│       │   ├── api/                     #   REST API controllers
│       │   │   ├── KvController.java    #     KV operations + leader forwarding
│       │   │   ├── ClusterController.java#    Cluster status + node info
│       │   │   ├── ChaosController.java #     Chaos engineering + peer forwarding
│       │   │   └── MetricsController.java#    Metrics endpoints
│       │   │
│       │   ├── grpc/                    #   gRPC service implementations
│       │   │   ├── RaftGrpcService.java #     Raft RPC handlers
│       │   │   ├── KvGrpcService.java   #     KV RPC handlers
│       │   │   └── RaftPeerGrpcClient.java#   Outgoing RPC client
│       │   │
│       │   ├── events/                  #   Real-time event system
│       │   │   ├── ClusterEvent.java    #     Event model (15+ event types)
│       │   │   ├── EventBus.java        #     In-memory pub/sub bus
│       │   │   └── WebSocketPublisher.java#   STOMP WebSocket bridge
│       │   │
│       │   ├── config/                  #   Spring configuration
│       │   │   ├── RaftConfig.java      #     Raft parameter bindings
│       │   │   ├── StorageConfig.java   #     RocksDB bean configuration
│       │   │   ├── WebConfig.java       #     CORS configuration
│       │   │   ├── WebSocketConfig.java #     STOMP/SockJS config
│       │   │   └── RocksDbHealthIndicator.java # Actuator health check
│       │   │
│       │   └── txn/                     #   Distributed transactions
│       │       └── TxnCoordinator.java  #     Two-Phase Commit coordinator
│       │
│       ├── main/resources/
│       │   └── application.yml          #   All configuration defaults
│       │
│       └── test/java/com/minidb/
│           └── MiniDbApplicationTests.java # Unit tests (RocksDB, MVCC, RaftLog)
│
├── frontend/                            # React dashboard application
│   ├── package.json                     #   Dependencies
│   ├── vite.config.ts                   #   Vite + proxy config
│   ├── tailwind.config.ts               #   Tailwind CSS config
│   ├── index.html                       #   Entry HTML (includes global polyfill)
│   └── src/
│       ├── App.tsx                       #   Main layout + tab navigation
│       ├── main.tsx                      #   Entry point + ErrorBoundary
│       ├── index.css                     #   Tailwind + custom animations
│       │
│       ├── components/
│       │   ├── TopologyView.tsx          #   D3.js cluster visualization
│       │   ├── RaftLogViewer.tsx         #   Side-by-side Raft log display
│       │   ├── KvExplorer.tsx           #   CRUD + scan + MVCC + transactions
│       │   ├── MetricsDashboard.tsx     #   Recharts live charts
│       │   ├── EventTimeline.tsx        #   Filterable event stream
│       │   ├── ChaosPanel.tsx           #   Chaos engineering controls
│       │   └── Sidebar.tsx              #   Cluster info sidebar
│       │
│       ├── hooks/
│       │   ├── useClusterState.ts       #   Polling hook (1s interval)
│       │   └── useWebSocket.ts          #   STOMP/SockJS real-time connection
│       │
│       ├── stores/
│       │   └── clusterStore.ts          #   Zustand global state store
│       │
│       └── types/
│           └── events.ts                #   TypeScript interfaces
│
├── Docker & Kubernetes
│   ├── docker-compose.yml               #   Full stack (3 nodes + UI + monitoring)
│   ├── Dockerfile.backend               #   Multi-stage Java build
│   ├── Dockerfile.frontend              #   Multi-stage React build
│   ├── nginx.conf                       #   Production reverse proxy
│   └── k8s/
│       ├── statefulset.yaml             #   3-replica StatefulSet + PVCs
│       ├── service.yaml                 #   Headless + client Services
│       ├── configmap.yaml               #   Environment configuration
│       └── ui-deployment.yaml           #   Frontend Deployment + NodePort
│
└── monitoring/
    ├── prometheus.yml                   #   Scrape config for 3 nodes
    └── grafana/
        ├── dashboards/minidb.json       #   Pre-built Grafana dashboard
        └── provisioning/                #   Auto-provisioning configs
```

---

## Getting Started

### Prerequisites

- **Java 21+** (JDK) — [Download](https://adoptium.net/)
- **Maven 3.8+** — [Download](https://maven.apache.org/download.cgi)
- **Node.js 18+** — [Download](https://nodejs.org/)
- **Docker + Docker Compose** (optional) — [Download](https://docs.docker.com/get-docker/)

---

### Option 1: One-Click Launch (Recommended)

Run a single command to build, start all 3 nodes, and launch the dashboard:

**Windows (PowerShell):**

```powershell
.\start-cluster.ps1
```

**Linux / macOS (Bash):**

```bash
chmod +x start-cluster.sh stop-cluster.sh   # first time only
./start-cluster.sh
```

That's it! The script will:

1. Build the backend JAR (Maven)
2. Start 3 Raft nodes as background processes
3. Install frontend dependencies (if needed)
4. Launch the Vite dev server

**Options:**

| Flag                          | Description                           |
| ----------------------------- | ------------------------------------- |
| `-Nodes 5` / `-n 5`           | Start up to 5 nodes instead of 3      |
| `-SkipBuild` / `--skip-build` | Skip Maven build (reuse existing JAR) |
| `-SkipFrontend` / `--skip-ui` | Don't start the React dashboard       |

**Stopping the cluster:**

```powershell
.\stop-cluster.ps1          # Windows
.\stop-cluster.ps1 -Clean   # also delete logs/ and data/
```

```bash
./stop-cluster.sh            # Linux / macOS
./stop-cluster.sh --clean    # also delete logs/ and data/
```

All node logs are saved to the `logs/` directory for debugging.

**Dashboard:** http://localhost:3000 — a leader will be elected within ~10 seconds.

---

<details>
<summary><strong>Manual Setup (Alternative)</strong></summary>

If you prefer to start each node in a separate terminal:

**Step 1: Build the backend**

```bash
cd backend
mvn clean package -DskipTests
```

**Step 2: Start 3 nodes** (each in a separate terminal)

**Windows (PowerShell):**

```powershell
# Terminal 1 — Node 1 (HTTP: 8080, gRPC: 9090)
$env:SERVER_PORT="8080"; $env:GRPC_SERVER_PORT="9090"; $env:NODE_ID="node-1"
$env:CLUSTER_PEERS="node-2@localhost:9092,node-3@localhost:9094"
java -jar target\mini-distributed-db-1.0.0-SNAPSHOT-boot.jar

# Terminal 2 — Node 2 (HTTP: 8082, gRPC: 9092)
$env:SERVER_PORT="8082"; $env:GRPC_SERVER_PORT="9092"; $env:NODE_ID="node-2"
$env:CLUSTER_PEERS="node-1@localhost:9090,node-3@localhost:9094"
java -jar target\mini-distributed-db-1.0.0-SNAPSHOT-boot.jar

# Terminal 3 — Node 3 (HTTP: 8084, gRPC: 9094)
$env:SERVER_PORT="8084"; $env:GRPC_SERVER_PORT="9094"; $env:NODE_ID="node-3"
$env:CLUSTER_PEERS="node-1@localhost:9090,node-2@localhost:9092"
java -jar target\mini-distributed-db-1.0.0-SNAPSHOT-boot.jar
```

**Linux / macOS (Bash):**

```bash
# Terminal 1 — Node 1
SERVER_PORT=8080 GRPC_SERVER_PORT=9090 NODE_ID=node-1 \
  CLUSTER_PEERS="node-2@localhost:9092,node-3@localhost:9094" \
  java -jar target/mini-distributed-db-1.0.0-SNAPSHOT-boot.jar

# Terminal 2 — Node 2
SERVER_PORT=8082 GRPC_SERVER_PORT=9092 NODE_ID=node-2 \
  CLUSTER_PEERS="node-1@localhost:9090,node-3@localhost:9094" \
  java -jar target/mini-distributed-db-1.0.0-SNAPSHOT-boot.jar

# Terminal 3 — Node 3
SERVER_PORT=8084 GRPC_SERVER_PORT=9094 NODE_ID=node-3 \
  CLUSTER_PEERS="node-1@localhost:9090,node-2@localhost:9092" \
  java -jar target/mini-distributed-db-1.0.0-SNAPSHOT-boot.jar
```

**Step 3: Start the dashboard**

```bash
cd frontend
npm install
npm run dev
```

**Step 4: Open the dashboard**

Navigate to **http://localhost:3000** (or the port Vite assigns if 3000 is taken).

Within ~5 seconds, a leader will be elected and shown on the Topology view.

</details>

---

### Option 2: Docker Compose

```bash
# Build and start everything (3 nodes + UI + Prometheus + Grafana)
docker-compose up --build -d

# View logs
docker-compose logs -f node-1 node-2 node-3
```

| Service    | URL                                  |
| ---------- | ------------------------------------ |
| Dashboard  | http://localhost:3000                |
| Node 1 API | http://localhost:8081                |
| Node 2 API | http://localhost:8082                |
| Node 3 API | http://localhost:8083                |
| Prometheus | http://localhost:9090                |
| Grafana    | http://localhost:3001 (admin/minidb) |

---

### Option 3: Kubernetes

```bash
# Apply all manifests
kubectl apply -f k8s/

# Wait for pods to become ready
kubectl get pods -w

# Port-forward the dashboard
kubectl port-forward svc/minidb-ui 3000:80
```

Or access via NodePort at `http://<node-ip>:30300`.

---

## API Reference

### KV Operations

| Method | Endpoint                 | Request Body                   | Response                                          | Description                         |
| ------ | ------------------------ | ------------------------------ | ------------------------------------------------- | ----------------------------------- |
| POST   | `/api/kv/put`            | `{"key": "k", "value": "v"}`   | `{"success": true, "latencyMs": 10}`              | Create or update a key-value pair   |
| POST   | `/api/kv/get`            | `{"key": "k", "timestamp": 0}` | `{"found": true, "value": "v", "timestamp": 123}` | Read a key (optional point-in-time) |
| POST   | `/api/kv/delete`         | `{"key": "k"}`                 | `{"success": true, "latencyMs": 8}`               | Delete a key (writes tombstone)     |
| POST   | `/api/kv/scan`           | `{"prefix": "", "limit": 100}` | `{"pairs": [...], "count": N}`                    | Scan keys by prefix                 |
| GET    | `/api/kv/versions/{key}` | —                              | `{"versions": [...]}`                             | Get MVCC version history            |
| POST   | `/api/kv/txn`            | `{"operations": [...]}`        | `{"success": true, "txnId": "..."}`               | Execute 2PC transaction             |

**Examples:**

```bash
# Put a key
curl -X POST http://localhost:8080/api/kv/put \
  -H "Content-Type: application/json" \
  -d '{"key": "user:1", "value": "Alice"}'

# Get a key
curl -X POST http://localhost:8080/api/kv/get \
  -H "Content-Type: application/json" \
  -d '{"key": "user:1"}'

# Point-in-time read (value as of timestamp 1772283510000)
curl -X POST http://localhost:8080/api/kv/get \
  -H "Content-Type: application/json" \
  -d '{"key": "user:1", "timestamp": 1772283510000}'

# Scan all keys with prefix "user:"
curl -X POST http://localhost:8080/api/kv/scan \
  -H "Content-Type: application/json" \
  -d '{"prefix": "user:", "limit": 50}'

# Delete a key
curl -X POST http://localhost:8080/api/kv/delete \
  -H "Content-Type: application/json" \
  -d '{"key": "user:1"}'

# Get version history
curl http://localhost:8080/api/kv/versions/user:1

# Execute a transaction
curl -X POST http://localhost:8080/api/kv/txn \
  -H "Content-Type: application/json" \
  -d '{"operations": [
    {"type": "PUT", "key": "balance:A", "value": "900"},
    {"type": "PUT", "key": "balance:B", "value": "1100"}
  ]}'
```

### Cluster Management

| Method | Endpoint                                          | Description                                              |
| ------ | ------------------------------------------------- | -------------------------------------------------------- |
| GET    | `/api/cluster/status`                             | Node ID, role, term, leader, commit/applied index, peers |
| GET    | `/api/nodes`                                      | All nodes with roles, indices, and status                |
| GET    | `/api/node/{id}/log?page=0&size=50`               | Paginated Raft log entries                               |
| GET    | `/api/node/{id}/state`                            | Full node state + snapshot info + storage stats          |
| GET    | `/api/events?limit=100&category=RAFT&search=text` | Filtered event history                                   |

### Chaos Engineering Endpoints

| Method | Endpoint                                        | Description                                         |
| ------ | ----------------------------------------------- | --------------------------------------------------- |
| POST   | `/api/chaos/kill-node/{id}`                     | Simulate node crash (auto-forwards to target node)  |
| POST   | `/api/chaos/recover/{id}`                       | Recover crashed node (auto-forwards to target node) |
| POST   | `/api/chaos/partition/{id}`                     | Add network partition to peer                       |
| POST   | `/api/chaos/heal-partition/{id}`                | Remove network partition                            |
| POST   | `/api/chaos/recover-all`                        | Recover all nodes + heal all partitions             |
| POST   | `/api/chaos/notify-peer-status/{id}?alive=bool` | Internal: broadcast peer alive/dead status          |

### Metrics Endpoints

| Method | Endpoint                | Description                                        |
| ------ | ----------------------- | -------------------------------------------------- |
| GET    | `/api/metrics/overview` | Node role, term, commit index, log size, uptime    |
| GET    | `/api/metrics/storage`  | RocksDB stats (keys, data size, cache, compaction) |
| GET    | `/api/metrics/snapshot` | Last snapshot index, term, size, time              |
| GET    | `/api/metrics/gc`       | Garbage collection stats (versions purged)         |
| GET    | `/actuator/health`      | Spring Boot health check (includes RocksDB)        |
| GET    | `/actuator/prometheus`  | Prometheus scrape endpoint                         |

### gRPC Services

| Service       | RPCs                                              | Default Ports      |
| ------------- | ------------------------------------------------- | ------------------ |
| `RaftService` | `RequestVote`, `AppendEntries`, `InstallSnapshot` | 9090 / 9092 / 9094 |
| `KvService`   | `Put`, `Get`, `ConsistentGet`, `Delete`, `Scan`   | 9090 / 9092 / 9094 |
| `TxnService`  | `Prepare`, `Commit`, `Abort`, `GetTxnStatus`      | 9090 / 9092 / 9094 |

---

## How It All Fits Together

Here's a complete walkthrough of what happens when you click **PUT** in the dashboard:

```
 1. Browser (KvExplorer.tsx)
    │  fetch('/api/kv/put', {key: 'user:1', value: 'Alice'})
    │
 2. Vite Dev Server Proxy
    │  Forward to http://localhost:8080/api/kv/put
    │
 3. KvController.java (Node-1, port 8080)
    │  Receives request
    │  Check: Am I the leader?
    │  ├── YES → proceed to step 4
    │  └── NO → forward request to leader's HTTP URL → return leader's response
    │
 4. RaftNode.submitPut("user:1", "Alice")
    │  Create log entry: Entry(index=5, term=3, PUT, "user:1", "Alice")
    │  Append to local RaftLog
    │  (persisted in RocksDB as __raft_log__:00000000000000000005)
    │  Return CompletableFuture<Boolean>
    │
 5. Leader's heartbeat/replication cycle (every 1 second)
    │  For each follower:
    │    Build AppendEntries RPC:
    │      prevLogIndex=4, prevLogTerm=3
    │      entries=[Entry(5, 3, PUT, "user:1", "Alice")]
    │      leaderCommit=4
    │    Send via gRPC to follower
    │
 6. RaftGrpcService.appendEntries() on each follower
    │  Verify: prevLogIndex=4 has term=3 locally ✓
    │  Append Entry(5, ...) to local log
    │  Update commitIndex to min(leaderCommit, lastNewEntryIndex)
    │  Reply: success=true, matchIndex=5
    │
 7. Leader processes responses
    │  matchIndex[node-2] = 5, matchIndex[node-3] = 5
    │  Count: 3 nodes have index 5 → majority (3 >= 2) ✓
    │  Advance commitIndex to 5
    │
 8. applyCommittedEntries() (runs every 10ms)
    │  lastApplied < commitIndex → apply entry 5
    │  MvccStore.put("user:1", "Alice")
    │    → KeyEncoding.encode("user:1", timestamp)
    │    → RocksDB.put("user:1:<inverted-ts>", "Alice")
    │  lastApplied = 5
    │  CompletableFuture completes with true ✓
    │
 9. KvController returns to client
    │  {success: true, latencyMs: 15}
    │
10. EventBus publishes KV_PUT event
    │  → WebSocketPublisher → STOMP /topic/events
    │  → Dashboard receives event → EventTimeline updates
    │  → Sidebar mini-feed updates
```

---

## Configuration Reference

All configuration is in `backend/src/main/resources/application.yml` and can be overridden via environment variables:

| Property                           | Env Variable           | Default                          | Description                                |
| ---------------------------------- | ---------------------- | -------------------------------- | ------------------------------------------ |
| `server.port`                      | `SERVER_PORT`          | `8080`                           | HTTP REST API port                         |
| `grpc.server.port`                 | `GRPC_SERVER_PORT`     | `9090`                           | gRPC port for inter-node communication     |
| `minidb.node.id`                   | `NODE_ID`              | `node-1`                         | Unique identifier for this node            |
| `minidb.cluster.peers`             | `CLUSTER_PEERS`        | (empty)                          | Comma-separated peer list (`id@host:port`) |
| `minidb.storage.path`              | `STORAGE_PATH`         | `data/rocksdb-${minidb.node.id}` | RocksDB data directory                     |
| `minidb.raft.election-timeout-min` | `ELECTION_TIMEOUT_MIN` | `3000`                           | Min election timeout (ms)                  |
| `minidb.raft.election-timeout-max` | `ELECTION_TIMEOUT_MAX` | `5000`                           | Max election timeout (ms)                  |
| `minidb.raft.heartbeat-interval`   | `HEARTBEAT_INTERVAL`   | `1000`                           | Leader heartbeat interval (ms)             |
| `minidb.raft.snapshot-interval`    | `SNAPSHOT_INTERVAL`    | `1000`                           | Log entries between snapshots              |
| `minidb.mvcc.retention-ms`         | `MVCC_RETENTION_MS`    | `300000`                         | MVCC version retention (5 min)             |
| `minidb.mvcc.gc-interval-ms`       | `MVCC_GC_INTERVAL_MS`  | `60000`                          | GC run interval (60 sec)                   |

**Port convention:** `HTTP port = gRPC port − 1010`

---

## Testing Guide

### Unit Tests

```bash
cd backend
mvn test
```

Tests cover:

1. **RocksDB** — open, put/get, delete, health check
2. **Storage Engine** — prefix scan with bounds
3. **Key Encoding** — descending timestamp order, roundtrip encode/decode
4. **Raft Log** — append, retrieve, index/term tracking

### Dashboard Testing Walkthrough

1. **Open** http://localhost:3000 (or assigned port)

2. **Topology Tab** — verify 3 nodes are shown. One should be gold/green (Leader), two blue (Follower)

3. **KV Explorer → Query Console**:
   - Key: `user:1`, Value: `Alice` → click **PUT** → see `success: true`
   - Key: `user:2`, Value: `Bob` → click **PUT**
   - Key: `user:1` → click **GET** → see `value: Alice`
   - Key: `user:1`, Value: `Alice-v2` → click **PUT** (creates version 2)

4. **KV Explorer → Data Browser**:
   - Click **Scan** → see both keys listed
   - Type `user:` in prefix → **Scan** → filtered results
   - Click the `user:1` row → see version history (v1: Alice, v2: Alice-v2)

5. **KV Explorer → Transactions**:
   - Add operation: PUT `balance:A` = `900`
   - Add operation: PUT `balance:B` = `1100`
   - Click **Execute** → see transaction committed

6. **Chaos Panel**:
   - Click **Kill Leader** → switch to Topology → watch new leader get elected (~3-5s)
   - Go back to KV Explorer → PUT still works (forwarding handles leader change)
   - Click **Recover All** → all 3 nodes back online

7. **Events Tab** — see the full timeline of everything that happened

8. **Metrics Tab** — see charts updating with operation data

### Chaos Scenarios to Try

| #   | Scenario              | Steps                                                                 | What to Observe                                                    |
| --- | --------------------- | --------------------------------------------------------------------- | ------------------------------------------------------------------ |
| 1   | **Leader Failover**   | Kill leader → PUT a key → Recover                                     | New leader elected in ~3-5s; writes work after election            |
| 2   | **Minority Failure**  | Kill 1 follower → PUT keys → Recover                                  | Cluster still works (2/3 = quorum); recovered node catches up      |
| 3   | **Majority Failure**  | Kill 2 nodes → try PUT → Recover all                                  | Writes fail (no quorum); writes resume after recovery              |
| 4   | **Network Partition** | Partition the leader → wait → Heal                                    | Partitioned leader steps down; new leader elected; heal reconciles |
| 5   | **Rolling Restart**   | Kill node-1 → recover → Kill node-2 → recover → Kill node-3 → recover | Cluster stays available throughout (always have quorum)            |

---

## Design Decisions & Trade-offs

| Decision                                                   | Rationale                                                                                                                                           |
| ---------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Shared RocksDB instance** for Raft log + MVCC data       | Simplifies deployment (single data directory per node). System keys use `__` prefix to avoid conflicts with user data.                              |
| **HTTP leader forwarding** instead of gRPC forwarding      | Simpler implementation; the REST API already uses HTTP. Avoids circular gRPC dependencies between controllers and services.                         |
| **Inverted timestamps** in MVCC keys                       | Leverages RocksDB's lexicographic ordering so prefix scans naturally return newest versions first — no sorting needed.                              |
| **Tombstone deletes** instead of real deletes              | Preserves history for point-in-time reads and version browsing. Garbage collector cleans up after the retention period.                             |
| **SockJS + STOMP** instead of raw WebSocket                | Provides automatic fallback transports (long-polling, etc.) and reconnection out of the box. Well-supported by Spring Boot's WebSocket module.      |
| **Java 21 virtual threads** for concurrency                | Enables lightweight concurrency for gRPC callbacks and scheduled tasks without manual thread pool tuning.                                           |
| **Zustand** instead of Redux for frontend state            | Minimal boilerplate for the dashboard's modest state management needs. No action types or reducers to maintain.                                     |
| **D3.js** for topology instead of a prebuilt graph library | Full control over the visualization — enabled heartbeat animations, glow effects, custom SVG icons, and interactive controls.                       |
| **Port convention** (HTTP = gRPC − 1010)                   | Enables nodes to discover each other's HTTP endpoints from the gRPC peer config without requiring extra configuration parameters.                   |
| **In-memory event bus** with bounded deque (10K max)       | Good enough for a development/educational tool. A production system would use a durable event store like Kafka.                                     |
| **NO-OP entry on leader election**                         | Required by Raft specification to commit entries from previous terms. Without it, previously-replicated entries might never be marked as committed. |

---

<div align="center">

**Built from scratch to demonstrate distributed systems concepts in action.**

Raft Consensus · MVCC · 2PC Transactions · RocksDB · gRPC · React Dashboard · Chaos Engineering

</div>
