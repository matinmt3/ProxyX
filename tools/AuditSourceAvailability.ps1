param([string]$OutputPath = (Join-Path $PSScriptRoot '..\artifacts\audit-20261026-source-http.json'))
$ErrorActionPreference = 'Stop'
$registry = Join-Path $PSScriptRoot '..\app\src\main\java\com\example\data\network\ProxySourceManager.kt'
$urls = [regex]::Matches((Get-Content -LiteralPath $registry -Raw), 'url = "(https://[^"\r\n]+)"') |
    ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique
$handler = [Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect = $false
$client = [Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(10)
$client.MaxResponseContentBufferSize = 8MB
$client.DefaultRequestHeaders.UserAgent.ParseAdd('ProxyX-Source-Audit/1.0')
$results = [Collections.Generic.List[object]]::new()
try {
    foreach ($url in $urls) {
        $watch = [Diagnostics.Stopwatch]::StartNew()
        try {
            $response = $client.GetAsync($url).GetAwaiter().GetResult()
            try {
                $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                $results.Add([pscustomobject]@{
                    Url = $url; HttpStatus = [int]$response.StatusCode
                    Bytes = [Text.Encoding]::UTF8.GetByteCount($body)
                    CandidateLinks = [regex]::Matches($body, '(?i)(?:tg://proxy|https://t\.me/proxy)\?').Count
                    DurationMs = $watch.ElapsedMilliseconds; Error = ''
                })
            } finally { $response.Dispose() }
        } catch {
            $results.Add([pscustomobject]@{Url=$url; HttpStatus=0; Bytes=0; CandidateLinks=0;
                DurationMs=$watch.ElapsedMilliseconds; Error=$_.Exception.GetType().Name})
        }
    }
} finally { $client.Dispose(); $handler.Dispose() }
New-Item -ItemType Directory -Path (Split-Path -Parent $OutputPath) -Force | Out-Null
[pscustomobject]@{ CheckedAtUtc=[DateTime]::UtcNow.ToString('o');
    Scope='Direct source HTTP only; candidate link count is not protocol verification'; Results=$results } |
    ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $OutputPath -Encoding utf8
$results | Group-Object HttpStatus | Select-Object Name,Count
Write-Output $OutputPath
