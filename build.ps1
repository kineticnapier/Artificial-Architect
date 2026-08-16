$ErrorActionPreference = 'Stop'
& "$PSScriptRoot\gradlew.bat" clean build
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$jar = Get-ChildItem "$PSScriptRoot\build\libs\*.jar" |
    Where-Object { $_.Name -notmatch '(-sources|-dev|-slim)\.jar$' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if ($jar) {
    Write-Host ""
    Write-Host "Built: $($jar.FullName)"
}
