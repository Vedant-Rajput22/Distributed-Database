# Cloud Benchmarking on Azure for Students

> **Cost:** ~$0.50 total for a complete 3/5/7-node benchmark run  
> **Time:** ~30 minutes including setup  
> **Credits:** $100 Azure for Students (expires in 365 days)

## Prerequisites

1. **Azure for Students** account activated ($100 free credits)
2. **Azure CLI** installed:
   ```powershell
   winget install Microsoft.AzureCLI
   ```
3. **SSH** available (built into Windows 10/11)

## Quick Start (3 commands)

```powershell
# Step 1: Deploy 3-node cluster (~10 minutes)
.\deploy\deploy-azure.ps1 -Nodes 3

# Step 2: Run benchmarks (~10 minutes)
.\deploy\cloud-benchmark.ps1

# Step 3: DESTROY everything (IMPORTANT - stops billing!)
.\deploy\cleanup-azure.ps1
```

## Full Benchmark Run (all cluster sizes)

```powershell
# ── 3-node cluster ──
.\deploy\deploy-azure.ps1 -Nodes 3
.\deploy\cloud-benchmark.ps1
.\deploy\cleanup-azure.ps1
# Type DELETE to confirm

# ── 5-node cluster ──
.\deploy\deploy-azure.ps1 -Nodes 5
.\deploy\cloud-benchmark.ps1
.\deploy\cleanup-azure.ps1

# ── 7-node cluster ──
.\deploy\deploy-azure.ps1 -Nodes 7
.\deploy\cloud-benchmark.ps1
.\deploy\cleanup-azure.ps1
```

Results are saved to:

- `deploy/cloud-results-3-node.json`
- `deploy/cloud-results-5-node.json`
- `deploy/cloud-results-7-node.json`

## What Each Script Does

### `deploy-azure.ps1`

1. Creates Azure resource group, VNet, and NSG
2. Launches N VMs (Ubuntu 24.04, B2s by default)
3. Installs Docker + Git on each VM via cloud-init
4. Clones the repo and builds the Docker image on each VM (parallel)
5. Configures `/etc/hosts` so nodes can find each other by name
6. Starts one MiniDB container per VM with `--network host`
7. Waits for leader election and displays cluster status

### `cloud-benchmark.ps1`

1. Reads `cluster-info.json` to find VM IPs
2. Finds the current Raft leader
3. Runs N benchmark iterations (default: 3) with standard + speculative modes
4. Runs failure injection tests (correctness + leader crash)
5. Computes averages and displays summary table
6. Saves full results to JSON

### `cleanup-azure.ps1`

1. Lists resources to be deleted
2. Asks for confirmation (type `DELETE`)
3. Deletes the entire resource group (all VMs, disks, NICs, IPs)
4. Billing stops immediately

## Cost Breakdown

| Resource    | Size        | Cost/hr | 3 nodes  | 5 nodes  | 7 nodes  |
| ----------- | ----------- | ------- | -------- | -------- | -------- |
| **B2s VM**  | 2 vCPU, 4GB | $0.042  | $0.13/hr | $0.21/hr | $0.29/hr |
| **B2ms VM** | 2 vCPU, 8GB | $0.083  | $0.25/hr | $0.42/hr | $0.58/hr |

**Typical total cost for all 3 cluster sizes:** ~$0.50 (well under $1)

## Troubleshooting

### "Docker not available after 5 minutes"

SSH into the VM and check: `ssh minidb@<ip> 'sudo systemctl status docker'`

### "No leader found"

Wait longer (election timeout is 3-5s). Check logs: `ssh minidb@<ip> 'sudo docker logs minidb-node --tail 50'`

### "Build failed"

Check if the VM has enough disk space: `ssh minidb@<ip> 'df -h'`
The Docker build needs ~2GB free disk space.

### "Benchmark timeout"

The benchmark runs 1000 operations. On B2s VMs, each run takes 1-3 minutes. Increase timeout if needed.

## Why Cloud Benchmarks Matter

**Localhost limitations that cloud fixes:**

- All nodes share one CPU → cloud gives each node dedicated vCPUs
- Docker bridge network → cloud uses real TCP between VMs (~0.5ms RTT)
- Shared disk I/O → cloud gives each VM its own SSD
- p95 inflation from resource contention → eliminated with separate VMs

**Expected improvements on cloud:**

- p95 latency should be much better (no CPU contention)
- p50 speculative latency should stay similar (~1-5ms, it's already local)
- Throughput should be similar or better
- The p95 gap between speculative and standard should shrink or reverse

These results directly address the "localhost illusion" critique in the paper.
