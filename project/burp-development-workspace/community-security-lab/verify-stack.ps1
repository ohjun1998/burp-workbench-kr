$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw "Docker Desktop을 설치하고 실행한 뒤 다시 시작하세요." }
$results = Join-Path $PSScriptRoot "verification-results"
New-Item -ItemType Directory -Force -Path $results | Out-Null
function Invoke-Docker {
    & docker @args
    if ($LASTEXITCODE -ne 0) { throw "Docker 명령 실패: $($args -join ' ') (exit $LASTEXITCODE)" }
}
$failed = $false
try {
    Invoke-Docker info
    Invoke-Docker compose version
    Invoke-Docker compose -f compose.yaml -f compose.verify.yaml config --quiet
    Invoke-Docker compose up -d --build --wait --wait-timeout 900
    $health = Invoke-WebRequest -Uri "http://127.0.0.1:8088/health" -UseBasicParsing -TimeoutSec 30
    $health.Content | Out-File -Encoding utf8 (Join-Path $results "host-health.json")
    if ($health.StatusCode -ne 200 -or (($health.Content | ConvertFrom-Json).database -ne "Oracle Free")) { throw "호스트에서 Oracle 연결 확인 실패" }
    Invoke-Docker compose -f compose.yaml -f compose.verify.yaml run --rm verify
    Write-Host "검증 통과. 사이트: http://127.0.0.1:8088"
} catch {
    $failed = $true
    $_ | Out-String | Out-File -Encoding utf8 (Join-Path $results "failure.txt")
    Write-Host "실행 또는 검증 실패. verification-results 폴더의 로그를 확인하세요."
    Write-Host $_
} finally {
    & docker compose ps --all --format json 2>&1 | Out-File -Encoding utf8 (Join-Path $results "container-status.json")
    & docker compose images --format json 2>&1 | Out-File -Encoding utf8 (Join-Path $results "container-images.json")
    & docker compose logs --no-color --tail 300 db was web protocols 2>&1 | Out-File -Encoding utf8 (Join-Path $results "containers.log")
}
if ($failed) { exit 1 }
