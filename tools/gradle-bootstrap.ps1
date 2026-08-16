param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs
)

$ErrorActionPreference = 'Stop'
$GradleVersion = '8.8'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$BootstrapRoot = Join-Path $ProjectRoot '.gradle-bootstrap'
$GradleHome = Join-Path $BootstrapRoot "gradle-$GradleVersion"
$GradleBat = Join-Path $GradleHome 'bin\gradle.bat'
$ZipPath = Join-Path $BootstrapRoot "gradle-$GradleVersion-bin.zip"
$DistributionUrl = "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip"

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw 'Java was not found. Install JDK 17 and make sure java is on PATH.'
}

# java -version writes its version text to stderr. Windows PowerShell 5.1 can
# turn native stderr into ErrorRecord objects when ErrorActionPreference=Stop,
# so capture it through cmd.exe instead.
$JavaVersionText = (& cmd.exe /d /c 'java -version 2>&1' | Select-Object -First 1) -join ''
if ($JavaVersionText -notmatch 'version "17[.]') {
    Write-Warning "Forge 1.20.1 expects Java 17. Current Java: $JavaVersionText"
}

if (-not (Test-Path $GradleBat)) {
    New-Item -ItemType Directory -Force -Path $BootstrapRoot | Out-Null
    Write-Host "Downloading Gradle $GradleVersion..."
    Invoke-WebRequest -Uri $DistributionUrl -OutFile $ZipPath -UseBasicParsing

    Write-Host 'Extracting Gradle...'
    Expand-Archive -Path $ZipPath -DestinationPath $BootstrapRoot -Force
    Remove-Item $ZipPath -Force
}

Push-Location $ProjectRoot
try {
    & $GradleBat @GradleArgs
    $GradleExitCode = $LASTEXITCODE
}
finally {
    Pop-Location
}

exit $GradleExitCode
