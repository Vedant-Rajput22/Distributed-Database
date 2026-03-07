<#
.SYNOPSIS
    Deploy MiniDB cluster to Azure for cloud benchmarking.

.DESCRIPTION
    Creates N Azure VMs (each on separate hardware), builds Docker images,
    and starts the MiniDB cluster with real network latency between nodes.
    Uses Azure for Students $100 credits. Total cost: ~$0.15-0.50 for a full run.

    The script auto-detects available VM sizes if the requested one is not available.

.PARAMETER Nodes
    Number of cluster nodes (3, 5, or 7). Default: 3.

.PARAMETER VmSize
    Azure VM size. Default: auto (tries multiple sizes until one works).
    Override with e.g. -VmSize Standard_D2s_v3

.PARAMETER Location
    Azure region. Default: auto (tries multiple regions until one works).
    Override with e.g. -Location westeurope

.EXAMPLE
    .\deploy\deploy-azure.ps1 -Nodes 3
    .\deploy\deploy-azure.ps1 -Nodes 5 -VmSize Standard_D2s_v3
    .\deploy\deploy-azure.ps1 -Nodes 7 -Location westeurope
#>

param(
    [ValidateSet(3, 5, 7)]
    [int]$Nodes = 3,

    [string]$ResourceGroup = "minidb-bench",
    [string]$Location = "auto",
    [string]$VmSize = "auto"
)

$ErrorActionPreference = "Stop"
$AdminUser = "minidb"
$RepoUrl = "https://github.com/Vedant-Rajput22/Distributed-Database.git"

# SSH options: skip host key checking (new VMs every time)
function Invoke-Ssh {
    param([string]$Ip, [string]$Command)
    ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=NUL -o LogLevel=ERROR -o ConnectTimeout=10 "${AdminUser}@${Ip}" $Command
}

function Log {
    param([string]$Msg, [string]$Color = "White")
    Write-Host $Msg -ForegroundColor $Color
}

# ============================================================
Log ""
Log "  =============================================" Cyan
Log "    MiniDB Azure Cloud Deployment" Cyan
Log "  =============================================" Cyan
Log ""
Log "  Nodes:    $Nodes"
Log "  VM Size:  $VmSize"
Log "  Region:   $Location"
Log ""

# ── Step 1: Prerequisites ──
Log "[1/9] Checking prerequisites..." Yellow

# Refresh PATH in case Azure CLI was just installed
$env:Path = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path","User")

if (-not (Get-Command az -ErrorAction SilentlyContinue)) {
    Log "  Azure CLI not found. Install it:" Red
    Log "    winget install Microsoft.AzureCLI" Red
    Log "  Then restart PowerShell and run this script again." Red
    exit 1
}

if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) {
    Log "  SSH not found. Enable OpenSSH in Windows Settings > Apps > Optional Features." Red
    exit 1
}

# Check Azure login
try {
    $acct = az account show 2>$null | ConvertFrom-Json
    if (-not $acct) { throw "not logged in" }
} catch {
    Log "  Not logged in. Opening browser for Azure login..." Yellow
    az login --output none
    $acct = az account show | ConvertFrom-Json
}
Log "  Account:      $($acct.user.name)" Green
Log "  Subscription: $($acct.name)" Green

# ── Step 2: Build candidate list ──
Log ""
Log "[2/9] Preparing VM size/region candidates..." Yellow

# VM sizes to try (cheapest first, all have 2+ vCPUs and 4+ GB RAM)
$vmSizeCandidates = @(
    "Standard_B2s",
    "Standard_B2ms",
    "Standard_B2as_v2",
    "Standard_B2s_v2",
    "Standard_D2s_v3",
    "Standard_D2as_v4",
    "Standard_D2s_v5",
    "Standard_D2as_v5",
    "Standard_DS2_v2",
    "Standard_A2_v2"
)

# Regions to try (popular, likely to have capacity)
$locationCandidates = @(
    "eastus", "eastus2", "westus2", "westus3",
    "centralus", "northeurope", "westeurope",
    "southeastasia", "centralindia", "australiaeast",
    "uksouth", "canadacentral", "japaneast"
)

# If user specified values, use only those
if ($VmSize -ne "auto") { $vmSizeCandidates = @($VmSize) }
if ($Location -ne "auto") { $locationCandidates = @($Location) }

# Build flat list of (size, region) candidates - NO slow pre-check.
# We try creating VMs directly and retry on capacity failures (much faster).
# Strategy: try all SIZES in one region before switching regions (avoids expensive re-setup).
$candidates = [System.Collections.ArrayList]::new()
foreach ($tryLoc in $locationCandidates) {
    foreach ($trySize in $vmSizeCandidates) {
        [void]$candidates.Add(@{ Size = $trySize; Location = $tryLoc })
    }
}

Log "  $($candidates.Count) size/region combinations to try." Green
Log "  Will try each during VM creation until one works (~30s per attempt)." Green

# Start with the first candidate
$Location = $candidates[0].Location
$VmSize = $candidates[0].Size

# ── Step 3: Resource Group ──
Log ""
Log "[3/9] Creating resource group '$ResourceGroup' in '$Location'..." Yellow
az group create --name $ResourceGroup --location $Location --output none
Log "  Done." Green

# ── Step 4: Networking ──
Log ""
Log "[4/9] Creating VNet + NSG..." Yellow

function Create-Networking {
    param([string]$rg, [string]$loc)

    # Virtual network
    az network vnet create `
        --resource-group $rg `
        --name minidb-vnet `
        --address-prefix 10.0.0.0/16 `
        --subnet-name minidb-subnet `
        --subnet-prefix 10.0.1.0/24 `
        --location $loc `
        --output none 2>$null

    # Network security group
    az network nsg create `
        --resource-group $rg `
        --name minidb-nsg `
        --location $loc `
        --output none 2>$null

    # NSG rules
    az network nsg rule create --resource-group $rg --nsg-name minidb-nsg `
        --name AllowSSH --priority 100 --destination-port-ranges 22 `
        --access Allow --protocol Tcp --direction Inbound --output none 2>$null

    az network nsg rule create --resource-group $rg --nsg-name minidb-nsg `
        --name AllowHTTP --priority 200 --destination-port-ranges 8080 `
        --access Allow --protocol Tcp --direction Inbound --output none 2>$null

    az network nsg rule create --resource-group $rg --nsg-name minidb-nsg `
        --name AllowGRPC --priority 300 --destination-port-ranges 9090 `
        --access Allow --protocol Tcp --direction Inbound --output none 2>$null
}

Create-Networking -rg $ResourceGroup -loc $Location
Log "  VNet + NSG configured." Green

# ── Step 5: Create VMs (with retry across candidates) ──
Log ""
Log "[5/9] Creating $Nodes VMs..." Yellow
Log "  Will retry with different VM sizes if capacity is unavailable." Gray

# Cloud-init script: installs Docker + Git on first boot
$cloudInit = @"
#cloud-config
package_update: true
packages:
  - docker.io
  - git
runcmd:
  - systemctl enable docker
  - systemctl start docker
  - usermod -aG docker $AdminUser
"@

$cloudInitPath = Join-Path $env:TEMP "minidb-cloud-init.yml"
Set-Content -Path $cloudInitPath -Value $cloudInit -Encoding utf8

# Try creating node-1 SYNCHRONOUSLY first to validate the size actually works.
# If it fails with SkuNotAvailable/capacity error, try next candidate.
$vmCreated = $false
$currentLocation = $Location
$blockedRegions = @{}  # Regions blocked by subscription policy

foreach ($candidate in $candidates) {
    $trySize = $candidate.Size
    $tryLoc = $candidate.Location

    # Skip regions we already know are blocked by policy
    if ($blockedRegions.ContainsKey($tryLoc)) {
        continue
    }

    # If region changed, recreate networking
    if ($tryLoc -ne $currentLocation) {
        Log "  Switching to region: $tryLoc ..." Yellow

        # Delete old resource group and recreate in new region
        $prevEAP = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        az group delete --name $ResourceGroup --yes --no-wait --output none 2>$null
        Start-Sleep -Seconds 5
        # Wait for deletion to complete
        for ($wait = 0; $wait -lt 60; $wait++) {
            $rgExists = az group exists --name $ResourceGroup 2>$null
            if ($rgExists -eq "false") { break }
            Start-Sleep -Seconds 5
        }
        az group create --name $ResourceGroup --location $tryLoc --output none 2>$null
        $ErrorActionPreference = $prevEAP

        # Try creating networking - if policy blocks this region, skip all candidates here
        try {
            $prevEAP = $ErrorActionPreference
            $ErrorActionPreference = "Stop"
            Create-Networking -rg $ResourceGroup -loc $tryLoc
            $ErrorActionPreference = $prevEAP
            $currentLocation = $tryLoc
            Log "  Resources recreated in $tryLoc." Green
        } catch {
            $errMsg = $_.Exception.Message
            if ($errMsg -match "RequestDisallowedByAzure|policy|disallowed") {
                Log "    Region $tryLoc blocked by subscription policy. Skipping." Yellow
                $blockedRegions[$tryLoc] = $true
            } else {
                Log "    Failed to setup networking in $tryLoc. Skipping." Yellow
                $blockedRegions[$tryLoc] = $true
            }
            $ErrorActionPreference = "Stop"
            continue
        }
    }

    Log "  Trying: $trySize in $tryLoc ..." Cyan
    Log "    Creating node-1 (testing size, ~60s)..."

    # Try creating node-1 synchronously (NOT --no-wait) to test real availability
    # Use "SilentlyContinue" to prevent az CLI warnings (stderr) from killing the script
    # IMPORTANT: Do NOT check $LASTEXITCODE — PowerShell falsely sets it to 1 when 
    # Azure CLI writes WARNING messages to stderr. Instead, check output for publicIpAddress.
    $prevEAP = $ErrorActionPreference
    $ErrorActionPreference = "SilentlyContinue"
    $createOutput = az vm create `
        --resource-group $ResourceGroup `
        --name "node-1" `
        --image Ubuntu2204 `
        --size $trySize `
        --admin-username $AdminUser `
        --generate-ssh-keys `
        --vnet-name minidb-vnet `
        --subnet minidb-subnet `
        --nsg minidb-nsg `
        --public-ip-sku Standard `
        --custom-data $cloudInitPath `
        --output json 2>&1 | ForEach-Object { "$_" }
    $ErrorActionPreference = $prevEAP

    # Check success by looking for publicIpAddress in output (NOT exit code — PowerShell
    # falsely reports exit=1 when az CLI writes WARNING to stderr)
    $outputStr = ($createOutput | Out-String)
    if ($outputStr -match '"publicIpAddress"') {
        Log "    node-1 created successfully with $trySize!" Green
        $VmSize = $trySize
        $Location = $tryLoc
        $vmCreated = $true
        break
    } else {
        if ($outputStr -match "SkuNotAvailable|Capacity|quota|not available") {
            Log "    $trySize not available in $tryLoc (capacity). Trying next..." Yellow
        } else {
            Log "    $trySize failed in $tryLoc. Error:" Yellow
            # Show just the relevant error lines
            $createOutput | Where-Object { $_ -match "error|Error|Message:|Code:" } | ForEach-Object { Log "      $_" Red }
        }
        # Clean up any leftover resources from the failed attempt
        $prevEAP = $ErrorActionPreference
        $ErrorActionPreference = "SilentlyContinue"
        az vm delete --resource-group $ResourceGroup --name "node-1" --yes --no-wait --output none 2>$null
        az network nic delete --resource-group $ResourceGroup --name "node-1VMNic" --no-wait --output none 2>$null
        az network public-ip delete --resource-group $ResourceGroup --name "node-1PublicIP" --no-wait --output none 2>$null
        $ErrorActionPreference = $prevEAP
        Start-Sleep -Seconds 3
    }
}

if (-not $vmCreated) {
    Log ""
    Log "  ERROR: Could not create a VM with any size in any region." Red
    Log "  All $($candidates.Count) candidates failed due to capacity restrictions." Red
    Log "  This is common with Azure for Students subscriptions." Red
    Log ""
    Log "  Options:" Yellow
    Log "    1. Wait 30-60 minutes and try again (capacity fluctuates)" White
    Log "    2. Try a specific region: .\deploy\deploy-azure.ps1 -Location japaneast" White
    Log "    3. Use Azure Portal to manually check available sizes:" White
    Log "       portal.azure.com > Virtual Machines > Create > check Size dropdown" White
    exit 1
}

# Create remaining VMs in parallel (--no-wait) with the working size
$prevEAP = $ErrorActionPreference
$ErrorActionPreference = "SilentlyContinue"
for ($i = 2; $i -le $Nodes; $i++) {
    Log "  Launching node-$i ($VmSize)..."
    az vm create `
        --resource-group $ResourceGroup `
        --name "node-$i" `
        --image Ubuntu2204 `
        --size $VmSize `
        --admin-username $AdminUser `
        --generate-ssh-keys `
        --vnet-name minidb-vnet `
        --subnet minidb-subnet `
        --nsg minidb-nsg `
        --public-ip-sku Standard `
        --custom-data $cloudInitPath `
        --output none `
        --no-wait 2>$null
}
$ErrorActionPreference = $prevEAP

# Wait for remaining VMs to finish provisioning (node-1 already done)
for ($i = 2; $i -le $Nodes; $i++) {
    Log "  Waiting for node-$i to be provisioned (2-3 min)..." Gray
    $prevEAP2 = $ErrorActionPreference
    $ErrorActionPreference = "SilentlyContinue"
    az vm wait --resource-group $ResourceGroup --name "node-$i" --created --output none 2>$null
    $ErrorActionPreference = $prevEAP2
    Log "  node-$i provisioned." Green
}
Log "  All $Nodes VMs ready." Green

# ── Step 6: Collect IPs ──
Log ""
Log "[6/9] Collecting IP addresses..." Yellow

$publicIps = @()
$privateIps = @()

for ($i = 1; $i -le $Nodes; $i++) {
    $ipInfo = az vm list-ip-addresses `
        --resource-group $ResourceGroup `
        --name "node-$i" `
        --output json | ConvertFrom-Json

    $pub  = $ipInfo[0].virtualMachine.network.publicIpAddresses[0].ipAddress
    $priv = $ipInfo[0].virtualMachine.network.privateIpAddresses[0]
    $publicIps  += $pub
    $privateIps += $priv
    Log "  node-$i  public=$pub  private=$priv"
}

# Save cluster info for benchmark/cleanup scripts
$clusterInfo = @{
    nodes       = $Nodes
    publicIps   = $publicIps
    privateIps  = $privateIps
    vmSize      = $VmSize
    location    = $Location
    resourceGroup = $ResourceGroup
    adminUser   = $AdminUser
    created     = (Get-Date -Format "yyyy-MM-dd HH:mm:ss")
}
$clusterInfo | ConvertTo-Json | Set-Content -Path "deploy/cluster-info.json" -Encoding utf8
Log "  Saved to deploy/cluster-info.json" Green

# ── Step 7: Wait for Docker ──
Log ""
Log "[7/9] Waiting for Docker to be ready on all VMs..." Yellow

for ($i = 1; $i -le $Nodes; $i++) {
    $ip = $publicIps[$i - 1]
    $ready = $false

    for ($attempt = 1; $attempt -le 30; $attempt++) {
        try {
            $result = Invoke-Ssh -Ip $ip -Command "docker --version 2>/dev/null"
            if ($result -match "Docker version") {
                Log "  node-$i Docker ready." Green
                $ready = $true
                break
            }
        } catch { }

        if ($attempt % 6 -eq 0) {
            Log "    Still waiting for node-$i... ($($attempt * 10)s)" Gray
        }
        Start-Sleep -Seconds 10
    }

    if (-not $ready) {
        Log "  ERROR: Docker not available on node-$i after 5 minutes." Red
        Log "  Try: ssh ${AdminUser}@${ip} 'sudo systemctl status docker'" Red
        exit 1
    }
}

# ── Step 8: Clone & Build Docker Image ──
Log ""
Log "[8/9] Building Docker images on all VMs in parallel..." Yellow
Log "  This takes 5-8 minutes. Go grab coffee." Gray

# Launch parallel builds using PowerShell background jobs
$buildJobs = @()
for ($i = 1; $i -le $Nodes; $i++) {
    $ip = $publicIps[$i - 1]
    $nodeNum = $i

    $job = Start-Job -ScriptBlock {
        param($user, $ip, $repo)
        $result = ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=NUL -o LogLevel=ERROR `
            "${user}@${ip}" `
            "cd /home/${user} && ([ -d Distributed-Database ] || git clone --depth 1 ${repo}) && cd Distributed-Database && sudo docker build -f Dockerfile.backend -t minidb-backend . 2>&1 | tail -5"
        return $result
    } -ArgumentList $AdminUser, $ip, $RepoUrl

    $buildJobs += @{ Job = $job; Node = $nodeNum }
    Log "  node-$nodeNum build started (background)"
}

# Wait for all builds
foreach ($entry in $buildJobs) {
    $result = Receive-Job -Job $entry.Job -Wait
    Remove-Job -Job $entry.Job

    if ($LASTEXITCODE -ne 0 -and $result -match "error|ERROR|FAILED") {
        Log "  WARNING: Build may have issues on node-$($entry.Node):" Yellow
        Log "  $result"
    } else {
        Log "  node-$($entry.Node) build complete." Green
    }
}

# ── Step 9: Configure /etc/hosts (DNS for node names) ──
Log ""
Log "[9/9] Configuring hostname resolution + starting cluster..." Yellow

# Build hosts entries: each VM needs to know all other nodes by name
$hostsLines = ""
for ($i = 1; $i -le $Nodes; $i++) {
    $hostsLines += "$($privateIps[$i - 1]) node-$i`n"
}

for ($i = 1; $i -le $Nodes; $i++) {
    $ip = $publicIps[$i - 1]
    # Use printf to handle newlines reliably
    Invoke-Ssh -Ip $ip -Command "printf '$hostsLines' | sudo tee -a /etc/hosts > /dev/null"
    Log "  node-$i hosts configured." Green
}

# Start containers
Log ""
Log "  Starting MiniDB containers..." Cyan

for ($i = 1; $i -le $Nodes; $i++) {
    $ip = $publicIps[$i - 1]

    # Build CLUSTER_PEERS: all nodes except self
    $peers = @()
    for ($j = 1; $j -le $Nodes; $j++) {
        if ($j -ne $i) { $peers += "node-${j}:9090" }
    }
    $peersStr = $peers -join ","

    $dockerCmd = @(
        "sudo docker rm -f minidb-node 2>/dev/null;"
        "sudo docker run -d"
        "--name minidb-node"
        "--network host"
        "--restart unless-stopped"
        "-e NODE_ID=node-$i"
        "-e CLUSTER_PEERS=$peersStr"
        "-e STORAGE_PATH=/app/data/rocksdb"
        "-e SERVER_PORT=8080"
        "-e GRPC_SERVER_PORT=9090"
        "minidb-backend"
    ) -join " "

    Invoke-Ssh -Ip $ip -Command $dockerCmd | Out-Null
    Log "  node-$i started (peers: $peersStr)" Green
}

# ── Wait for leader election ──
Log ""
Log "Waiting 25 seconds for leader election..." Yellow
Start-Sleep -Seconds 25

# Check cluster status
Log ""
Log "Cluster Status:" Cyan
$leaderIp = $null

for ($i = 1; $i -le $Nodes; $i++) {
    $ip = $publicIps[$i - 1]
    try {
        $status = Invoke-RestMethod -Uri "http://${ip}:8080/api/cluster/status" -TimeoutSec 10
        $role = $status.role
        if ($role -eq "LEADER") {
            $leaderIp = $ip
            Log "  node-$i ($ip) : LEADER  (term $($status.term))" Green
        } else {
            Log "  node-$i ($ip) : $role  (term $($status.term))" White
        }
    } catch {
        Log "  node-$i ($ip) : NOT RESPONDING (may need more time)" Yellow
    }
}

# ── Done! ──
Log ""
Log "=============================================" Green
Log "  CLUSTER DEPLOYED SUCCESSFULLY!" Green
Log "=============================================" Green
Log ""
Log "Endpoints:" Cyan
for ($i = 1; $i -le $Nodes; $i++) {
    $mark = if ($publicIps[$i - 1] -eq $leaderIp) { " (LEADER)" } else { "" }
    Log "  node-${i}: http://$($publicIps[$i - 1]):8080${mark}"
}
Log ""
Log "Next steps:" Yellow
Log "  1. Run benchmarks:  .\deploy\cloud-benchmark.ps1" Cyan
Log "  2. When done:       .\deploy\cleanup-azure.ps1" Red
Log ""
Log "Cost so far: ~`$$([math]::Round($Nodes * 0.042 * 0.15, 2)) (VM creation time)" Gray
Log "Running cost: ~`$$([math]::Round($Nodes * 0.042, 2))/hour" Gray
Log ""
