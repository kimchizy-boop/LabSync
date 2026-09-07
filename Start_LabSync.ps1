Set-Location $PSScriptRoot
New-Item -ItemType Directory -Force data | Out-Null
Write-Host "Starting LabSync on http://localhost:8080" -ForegroundColor Magenta
Write-Host "For phones/tablets/Macs on the same Wi-Fi, use this PC's IPv4 address + :8080" -ForegroundColor Gray
mvn spring-boot:run
