<#
.SYNOPSIS
    Destroy all Azure resources created by deploy-azure.ps1.

.DESCRIPTION
    Deletes the entire resource group, which removes all VMs, disks, NICs,
    public IPs, VNet, and NSG. This stops all billing immediately.

.EXAMPLE
    .\deploy\cleanup-azure.ps1
#>

param(
    [string]$ResourceGroup = "minidb-bench"
)

Write-Host ""
Write-Host "=============================================" -ForegroundColor Red
Write-Host "  WARNING: This will PERMANENTLY DELETE:" -ForegroundColor Red
Write-Host "=============================================" -ForegroundColor Red
Write-Host ""

# Show what exists
try {
    $vms = az vm list --resource-group $ResourceGroup --output json 2>$null | ConvertFrom-Json
    if ($vms) {
        Write-Host "  Resource group: $ResourceGroup" -ForegroundColor Yellow
        Write-Host "  VMs to delete:" -ForegroundColor Yellow
        foreach ($vm in $vms) {
            Write-Host "    - $($vm.name) ($($vm.hardwareProfile.vmSize))" -ForegroundColor White
        }
    } else {
        Write-Host "  Resource group '$ResourceGroup' has no VMs (or doesn't exist)." -ForegroundColor Gray
    }
} catch {
    Write-Host "  Could not list resources. Group may not exist." -ForegroundColor Gray
}

Write-Host ""
$confirm = Read-Host "Type 'DELETE' to confirm (or anything else to cancel)"

if ($confirm -eq "DELETE") {
    Write-Host ""
    Write-Host "Deleting resource group '$ResourceGroup'..." -ForegroundColor Yellow
    az group delete --name $ResourceGroup --yes --no-wait
    Write-Host "Deletion initiated. All resources will be removed in 2-5 minutes." -ForegroundColor Green
    Write-Host "Billing stops immediately." -ForegroundColor Green

    # Clean up local files
    if (Test-Path "deploy/cluster-info.json") {
        Remove-Item "deploy/cluster-info.json" -Force
        Write-Host "Removed deploy/cluster-info.json" -ForegroundColor Gray
    }
} else {
    Write-Host "Cancelled. No resources were deleted." -ForegroundColor Yellow
}

Write-Host ""
