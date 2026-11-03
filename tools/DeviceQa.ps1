param(
    [string]$Serial = 'emulator-5554',
    [string]$ArtifactsDirectory,
    [string]$ExpectedPackage
)
$Adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$QaArtifacts = if ($ArtifactsDirectory) {
    [IO.Path]::GetFullPath($ArtifactsDirectory)
} else {
    [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\artifacts\neon-device'))
}
New-Item -ItemType Directory -Path $QaArtifacts -Force | Out-Null

function Assert-ProxyXFocus {
    if ($ExpectedPackage) {
        $dump = @(& $Adb -s $Serial shell dumpsys window windows)
        $focus = [regex]::Match(($dump -join "`n"), 'mCurrentFocus=Window\{([^\s]+)')
        $start = -1
        if ($focus.Success) {
            $header = '^\s*Window #\d+ Window\{' + [regex]::Escape($focus.Groups[1].Value) + '\s'
            for ($i = 0; $i -lt $dump.Count; $i++) {
                if ($dump[$i] -match $header) { $start = $i; break }
            }
        }
        $owned = $false
        if ($start -ge 0) {
            $end = $start + 1
            while ($end -lt $dump.Count -and $dump[$end] -notmatch '^\s*Window #\d+ Window\{') { $end++ }
            $block = $dump[$start..($end - 1)] -join "`n"
            $owned = $block -match ('(?:^|\s)package=' + [regex]::Escape($ExpectedPackage) + '(?:\s|$)')
        }
        if (-not $owned) {
            throw 'The expected test app is not focused; refusing to capture another application.'
        }
    }
}

function Get-ProxyXUi {
    Assert-ProxyXFocus
    $remote = '/sdcard/proxyx-qa-' + [Guid]::NewGuid().ToString('N') + '.xml'
    & $Adb -s $Serial shell uiautomator dump $remote | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Unable to dump device UI.' }
    $path = Join-Path $QaArtifacts 'window.xml'
    & $Adb -s $Serial pull $remote $path 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'No fresh UI hierarchy was produced; refusing stale UI data.' }
    & $Adb -s $Serial shell rm $remote | Out-Null
    [xml]$tree = Get-Content -LiteralPath $path -Raw -Encoding utf8
    if ($ExpectedPackage -and $tree.hierarchy.node.package -ne $ExpectedPackage) {
        Remove-Item -LiteralPath $path
        throw 'The foreground hierarchy belongs to a system or external surface; refusing to inspect it.'
    }
    $tree
}

function Get-ProxyXLabels {
    $tree = Get-ProxyXUi
    $tree.SelectNodes('//node') | Where-Object { $_.text -or $_.'content-desc' } |
        Select-Object text,content-desc,class,checked,enabled,bounds
}

function Invoke-ProxyXNode {
    param([Parameter(Mandatory=$true)][string]$Label, [switch]$Description, [switch]$Contains, [switch]$Last)
    $tree = Get-ProxyXUi
    $attribute = if ($Description) { 'content-desc' } else { 'text' }
    $matches = @($tree.SelectNodes('//node') | Where-Object {
        $value = $_.GetAttribute($attribute)
        if ($Contains) { $value.Contains($Label) } else { $value -eq $Label }
    })
    $node = if ($Last) { $matches | Select-Object -Last 1 } else { $matches | Select-Object -First 1 }
    if (-not $node) { throw "UI control not found: $Label" }
    $numbers = [regex]::Matches($node.bounds, '\d+') | ForEach-Object { [int]$_.Value }
    if ($numbers.Count -ne 4) { throw 'Invalid node bounds.' }
    $x = [int](($numbers[0] + $numbers[2]) / 2)
    $y = [int](($numbers[1] + $numbers[3]) / 2)
    & $Adb -s $Serial shell input tap $x $y | Out-Null
    Start-Sleep -Milliseconds 400
}

function Save-ProxyXScreenshot {
    param([Parameter(Mandatory=$true)][ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$Name)
    Assert-ProxyXFocus
    Get-ProxyXUi | Out-Null
    $remote = '/sdcard/proxyx-qa-' + [Guid]::NewGuid().ToString('N') + '.png'
    & $Adb -s $Serial shell screencap -p $remote | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Unable to capture a fresh screenshot.' }
    $path = Join-Path $QaArtifacts "$Name.png"
    & $Adb -s $Serial pull $remote $path 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Screenshot transfer failed; refusing stale screenshot data.' }
    & $Adb -s $Serial shell rm $remote | Out-Null
    if ((Get-Item -LiteralPath $path).Length -eq 0) { throw 'The captured screenshot was empty.' }
    $path
}

function Set-ProxyXNumericField {
    param(
        [Parameter(Mandatory=$true)][string]$CurrentText,
        [Parameter(Mandatory=$true)][ValidatePattern('^[0-9]*$')][AllowEmptyString()][string]$Value,
        [Parameter(Mandatory=$true)][string]$ScreenMarker
    )
    $tree = Get-ProxyXUi
    if (-not @($tree.SelectNodes('//node') | Where-Object { $_.text.Contains($ScreenMarker) }).Count) {
        throw 'The expected numeric-input screen is not visible.'
    }
    $fields = @($tree.SelectNodes('//node') | Where-Object {
        $_.class -eq 'android.widget.EditText' -and $_.text -eq $CurrentText -and
        $_.password -ne 'true' -and $_.enabled -eq 'true'
    })
    if ($fields.Count -ne 1 -or $CurrentText.Length -gt 32) { throw 'Numeric field is not uniquely identified.' }
    $bounds = @([regex]::Matches($fields[0].bounds, '\d+') | ForEach-Object { [int]$_.Value })
    & $Adb -s $Serial shell input tap ([int](($bounds[0]+$bounds[2])/2)) ([int](($bounds[1]+$bounds[3])/2)) | Out-Null
    $focused = @((Get-ProxyXUi).SelectNodes('//node') | Where-Object {
        $_.class -eq 'android.widget.EditText' -and $_.focused -eq 'true' -and $_.text -eq $CurrentText
    })
    if ($focused.Count -ne 1) { throw 'The numeric field did not receive focus.' }
    $events = @('123') + (@('67') * $CurrentText.Length)
    & $Adb -s $Serial shell input keyevent @events | Out-Null
    if ($Value.Length) { & $Adb -s $Serial shell input text $Value | Out-Null }
    & $Adb -s $Serial shell input keyevent 4 | Out-Null
}

function Move-ProxyXList {
    param([switch]$Up)
    $tree = Get-ProxyXUi
    $regions = @($tree.SelectNodes('//node') | Where-Object { $_.scrollable -eq 'true' -and $_.enabled -eq 'true' } | ForEach-Object {
        $bounds = @([regex]::Matches($_.bounds, '\d+') | ForEach-Object { [int]$_.Value })
        if ($bounds.Count -eq 4 -and $bounds[2] -gt $bounds[0] -and $bounds[3] -gt $bounds[1]) {
            [pscustomobject]@{ Bounds=$bounds; Area=($bounds[2]-$bounds[0])*($bounds[3]-$bounds[1]) }
        }
    })
    $region = $regions | Sort-Object Area -Descending | Select-Object -First 1
    if (-not $region) { throw 'No visible scrollable UI region was found.' }
    $bounds = $region.Bounds
    $height = $bounds[3] - $bounds[1]
    $x = [int](($bounds[0] + $bounds[2]) / 2)
    $start = [int]($bounds[1] + $height * 0.85)
    $end = [int]($bounds[1] + $height * 0.15)
    if ($Up) { $swap = $start; $start = $end; $end = $swap }
    & $Adb -s $Serial shell input swipe $x $start $x $end 500 | Out-Null
    Start-Sleep -Milliseconds 500
}

function Add-ProxyXQaResult {
    param(
        [Parameter(Mandatory=$true)][string]$Case,
        [Parameter(Mandatory=$true)][ValidateSet('PASS','FAIL','LIMITED')][string]$Status,
        [Parameter(Mandatory=$true)][string]$Evidence,
        [string]$Run = 'neon-20261103'
    )
    if ($Run -notmatch '^[a-zA-Z0-9_-]+$') { throw 'Invalid QA run name.' }
    [pscustomobject]@{
        Timestamp = [DateTime]::UtcNow.ToString('o')
        Case = $Case
        Status = $Status
        Evidence = $Evidence
    } | Export-Csv -LiteralPath (Join-Path $QaArtifacts "$Run-cases.csv") -NoTypeInformation -Append -Encoding utf8
}
