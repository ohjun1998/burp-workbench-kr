$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$lab = Join-Path $root 'project/burp-development-workspace/community-security-lab'
$initial = Get-Location
$checks = New-Object 'System.Collections.Generic.List[string]'
$global:WorkbenchDockerCalls = New-Object 'System.Collections.Generic.List[string]'
$global:WorkbenchBrowserCalls = New-Object 'System.Collections.Generic.List[string]'
$global:WorkbenchDockerExit = 0
function global:docker {
    $global:WorkbenchDockerCalls.Add(($args -join ' '))
    $global:LASTEXITCODE = $global:WorkbenchDockerExit
}
function global:Start-Process {
    param([string]$FilePath)
    $global:WorkbenchBrowserCalls.Add($FilePath)
}
try {
    foreach ($name in @('start.ps1', 'verify-stack.ps1')) {
        $tokens = $null; $errors = $null
        [System.Management.Automation.Language.Parser]::ParseFile((Join-Path $lab $name), [ref]$tokens, [ref]$errors) | Out-Null
        if ($errors.Count) { throw "Windows PowerShell parser rejected ${name}: $($errors[0].Message)" }
        $checks.Add("Windows PowerShell parsed $name")
    }
    & (Join-Path $lab 'start.ps1')
    if ($global:WorkbenchDockerCalls.Count -ne 1 -or $global:WorkbenchDockerCalls[0] -ne 'compose up -d --build --wait --wait-timeout 900') { throw 'Compose start invocation changed' }
    $checks.Add('start.ps1 invoked the expected Compose command through a controlled command adapter')
    if ($global:WorkbenchBrowserCalls.Count -ne 1 -or $global:WorkbenchBrowserCalls[0] -ne 'http://127.0.0.1:8088') { throw 'Start URL mismatch' }
    $checks.Add('start.ps1 requested the loopback browser URL after successful command completion')
    $global:WorkbenchDockerExit = 17
    $failed = $false
    try { & (Join-Path $lab 'start.ps1') } catch { $failed = $true }
    if (-not $failed) { throw 'Nonzero Docker exit was ignored' }
    $checks.Add('start.ps1 rejects a nonzero Docker exit')
    if ($global:WorkbenchBrowserCalls.Count -ne 1) { throw 'Browser requested after failed startup' }
    $checks.Add('start.ps1 does not request a browser after failed startup')
    $out = Join-Path $root 'tmp/durability/windows-scripts.json'
    New-Item -ItemType Directory -Force (Split-Path $out) | Out-Null
    [ordered]@{ result='passed'; checks=$checks.Count; powershell=$PSVersionTable.PSVersion.ToString(); os=[Environment]::OSVersion.VersionString; dockerBackendExecuted=$false; browserLaunched=$false; passedChecks=$checks.ToArray() } | ConvertTo-Json -Depth 4 | Out-File -Encoding utf8 $out
    Get-Content $out
} finally {
    Set-Location $initial
    Remove-Item Function:\docker -ErrorAction SilentlyContinue
    Remove-Item Function:\Start-Process -ErrorAction SilentlyContinue
}
