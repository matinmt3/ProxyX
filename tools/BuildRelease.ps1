param(
    [switch]$Offline,
    [string]$SigningConfigFile,
    [string]$TelegramConfigFile
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Set-Location -LiteralPath $root
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
if (-not (Test-Path -LiteralPath "$env:JAVA_HOME\bin\java.exe")) {
    throw 'The Android Studio JDK is not installed at the configured path.'
}
$artifacts = Join-Path $root 'artifacts'
New-Item -ItemType Directory -Path $artifacts -Force | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$log = Join-Path $artifacts "release-build-$stamp.log"
$statusFile = Join-Path $artifacts 'release-build-status.json'
$status = [ordered]@{ pid=$PID; phase='running'; startedAt=(Get-Date -Format o); log=$log; exitCode=$null }
$status | ConvertTo-Json | Set-Content -LiteralPath $statusFile -Encoding utf8
$arguments = @(':app:testDebugUnitTest', ':app:lintRelease', ':app:assembleRelease',
    ':app:bundleRelease', ':baselineprofile:assembleBenchmarkRelease', '--no-daemon', '--max-workers=1',
    '-Pproxyx.requireReleaseSigning=true')
if ($SigningConfigFile) { $arguments += "-Pproxyx.signingConfigFile=$([IO.Path]::GetFullPath($SigningConfigFile))" }
if ($TelegramConfigFile) { $arguments += "-Pproxyx.telegramConfigFile=$([IO.Path]::GetFullPath($TelegramConfigFile))" }
if ($Offline) { $arguments += '--offline' }
$exitCode = 1
try {
    $ErrorActionPreference = 'Continue'
    & (Join-Path $root 'gradlew.bat') @arguments 2>&1 | Tee-Object -FilePath $log
    $exitCode = $LASTEXITCODE
} finally {
    $status.phase = if ($exitCode -eq 0) { 'succeeded' } else { 'failed' }
    $status.exitCode = $exitCode
    $status.finishedAt = Get-Date -Format o
    $status | ConvertTo-Json | Set-Content -LiteralPath $statusFile -Encoding utf8
}
exit $exitCode
