#requires -Version 7.2
param(
    [Parameter(Mandatory=$true)][string]$DestinationDirectory,
    [string]$Version = '8.0.0-rc5'
)
$ErrorActionPreference = 'Stop'
if ($Version -notmatch '^[0-9A-Za-z.-]+$') { throw 'Invalid version.' }
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$destination = [IO.Path]::GetFullPath($DestinationDirectory)
$stage = [IO.Path]::GetFullPath((Join-Path $destination "ProxyX-$Version-source"))
$archive = Join-Path $destination "ProxyX-$Version-source.zip"
if (-not $stage.StartsWith($destination.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Source staging path escaped the destination.'
}
if ((Test-Path -LiteralPath $stage) -or (Test-Path -LiteralPath $archive)) {
    throw 'Refusing to overwrite an existing source deliverable.'
}
$relativeFiles = @(
    '.gitignore', 'README.md', 'PRODUCT.md', 'DESIGN.md', 'build.gradle.kts',
    'settings.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat',
    'local.properties.example', 'app/build.gradle.kts', 'app/proguard-rules.pro',
    'baselineprofile/build.gradle.kts', '.impeccable/config.json',
    '.impeccable/design.json', '.impeccable/mocks/04-neon-four-screens.png'
)
foreach ($directory in @('app/src', 'app/schemas', 'app/libs', 'baselineprofile/src',
        'gradle', 'third_party', 'docs', 'tools')) {
    $path = Join-Path $root $directory
    if (Test-Path -LiteralPath $path) {
        foreach ($file in Get-ChildItem -LiteralPath $path -Recurse -File) {
            if ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw 'A linked file is not permitted in source staging.'
            }
            $relativeFiles += [IO.Path]::GetRelativePath($root, $file.FullName)
        }
    }
}
$relativeFiles = @($relativeFiles | Sort-Object -Unique)
foreach ($relative in $relativeFiles) {
    if ($relative -match '(?i)(^|[\\/])(local\.properties|keystore\.properties|\.env|tdata|tdlib-db)([\\/]|$)' -or
        $relative -match '(?i)\.(keystore|jks|p12|pfx|pk8|key|pem|db|db-shm|db-wal)$') {
        throw 'A private configuration or account-data file entered the allowlist.'
    }
    $source = [IO.Path]::GetFullPath((Join-Path $root $relative))
    if (-not $source.StartsWith($root + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Source path escaped the project.'
    }
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "Missing source: $relative" }
}
New-Item -ItemType Directory -Path $stage -Force | Out-Null
foreach ($relative in $relativeFiles) {
    $target = Join-Path $stage $relative
    New-Item -ItemType Directory -Path (Split-Path $target -Parent) -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $root $relative) -Destination $target
}
$manifest = foreach ($relative in $relativeFiles) {
    $hash = (Get-FileHash -LiteralPath (Join-Path $stage $relative) -Algorithm SHA256).Hash
    "$hash  $($relative.Replace('\', '/'))"
}
$manifest | Set-Content -LiteralPath (Join-Path $stage 'SOURCE-MANIFEST.sha256') -Encoding utf8
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory($stage, $archive, [IO.Compression.CompressionLevel]::Optimal, $false)
$zip = [IO.Compression.ZipFile]::OpenRead($archive)
try {
    if ($zip.Entries.Count -ne $relativeFiles.Count + 1) { throw 'Source archive entry count mismatch.' }
    foreach ($entry in $zip.Entries) {
        if ($entry.FullName -eq 'SOURCE-MANIFEST.sha256') { continue }
        $stream = $entry.Open()
        try { $hash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)) }
        finally { $stream.Dispose() }
        $expected = (Get-FileHash -LiteralPath (Join-Path $stage $entry.FullName) -Algorithm SHA256).Hash
        if ($hash -ne $expected) { throw "Archive content mismatch: $($entry.FullName)" }
    }
} finally { $zip.Dispose() }
[pscustomobject]@{
    Stage = $stage
    Archive = $archive
    FileCount = $relativeFiles.Count
    SHA256 = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
    Bytes = (Get-Item -LiteralPath $archive).Length
}
