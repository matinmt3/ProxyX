param(
    [Parameter(Mandatory=$true)][string]$Apk,
    [Parameter(Mandatory=$true)][string]$ApkUrl,
    [Parameter(Mandatory=$true)][long]$VersionCode,
    [Parameter(Mandatory=$true)][string]$VersionName,
    [ValidateSet('stable','beta')][string]$Channel = 'stable',
    [Parameter(Mandatory=$true)][string]$OutputPath,
    [string]$Notes = ''
)
$ErrorActionPreference = 'Stop'
$uri = [Uri]$ApkUrl
if ($uri.Scheme -ne 'https' -or $uri.UserInfo -or $uri.Fragment -or $uri.Port -ne 443) { throw 'A public HTTPS APK URL is required.' }
if (Test-Path -LiteralPath $OutputPath) { throw 'Output exists; refusing overwrite.' }
$file = Get-Item -LiteralPath $Apk
if ($file.Length -le 0 -or $file.Length -gt 128MB) { throw 'Invalid APK size.' }
$now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$payload = [ordered]@{
    schemaVersion = 1; channel = $Channel; packageName = 'com.aistudio.proxyx.vbnmkw'
    versionCode = $VersionCode; versionName = $VersionName; minSdk = 24
    apkUrl = $ApkUrl; sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    sizeBytes = $file.Length; issuedAt = $now; expiresAt = $now + 30L * 24 * 3600 * 1000; notes = $Notes
}
[IO.File]::WriteAllText([IO.Path]::GetFullPath($OutputPath), ($payload | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
Write-Output 'Unsigned release payload created. Check package/version with apkanalyzer, then sign offline.'
