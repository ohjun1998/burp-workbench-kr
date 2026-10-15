$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
docker compose up -d --build --wait --wait-timeout 900
if ($LASTEXITCODE -ne 0) { throw "Docker Compose 시작 실패. docker compose logs 를 확인하세요." }
Start-Process "http://127.0.0.1:8088"
