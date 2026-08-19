[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,

    [Parameter()]
    [string]$OutputDirectory = '',

    [Parameter()]
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $repositoryRoot '.toolchains\android-sdk\platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) {
    throw "ADB is unavailable at $adb"
}
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $repositoryRoot 'docs\screenshots'
}
$outputRoot = [IO.Path]::GetFullPath($OutputDirectory)
if (-not (Test-Path -LiteralPath $outputRoot)) {
    New-Item -ItemType Directory -Path $outputRoot | Out-Null
}

function Invoke-DocumentationAdb {
    param([Parameter(Mandatory = $true)][string[]]$ArgumentList)

    $output = & $adb -s $Serial @ArgumentList
    if ($LASTEXITCODE -ne 0) {
        throw "ADB failed ($LASTEXITCODE): $($ArgumentList -join ' ')`n$output"
    }
    return $output
}

function Start-DocumentationScenario {
    param(
        [string]$Scenario = 'HOME',
        [string]$Destination = 'HOME',
        [string]$SettingsPage = '',
        [string]$InformationTab = ''
    )

    Invoke-DocumentationAdb @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') | Out-Null
    Invoke-DocumentationAdb @('shell', 'am', 'force-stop', 'app.opah.tv.docs') | Out-Null
    $arguments = @(
        'shell', 'am', 'start', '-W',
        '-n', 'app.opah.tv.docs/app.opah.tv.MainActivity',
        '--es', 'documentationScenario', $Scenario,
        '--es', 'documentationDestination', $Destination
    )
    if (-not [string]::IsNullOrWhiteSpace($SettingsPage)) {
        $arguments += @('--es', 'documentationSettingsPage', $SettingsPage)
    }
    if (-not [string]::IsNullOrWhiteSpace($InformationTab)) {
        $arguments += @('--es', 'documentationInformationTab', $InformationTab)
    }
    Invoke-DocumentationAdb $arguments | Out-Null
    Start-Sleep -Milliseconds 2600
}

function Save-DocumentationScreenshot {
    param([Parameter(Mandatory = $true)][string]$FileName)

    $destination = [IO.Path]::GetFullPath((Join-Path $outputRoot $FileName))
    if (-not $destination.StartsWith($outputRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Screenshot path escaped the output directory: $destination"
    }
    if ((Test-Path -LiteralPath $destination) -and -not $Force) {
        throw "Screenshot already exists. Use -Force to replace it: $destination"
    }
    $remote = "/sdcard/Download/opah-doc-$FileName"
    Invoke-DocumentationAdb @('shell', 'screencap', '-p', $remote) | Out-Null
    Invoke-DocumentationAdb @('pull', $remote, $destination) | Out-Null
    Invoke-DocumentationAdb @('shell', 'rm', $remote) | Out-Null
    $png = [IO.File]::ReadAllBytes($destination)
    if ($png.Length -lt 24) {
        throw "Screenshot is not a complete PNG: $destination"
    }
    $width = ([int]$png[16] * 16MB) + ([int]$png[17] * 64KB) + ([int]$png[18] * 256) + [int]$png[19]
    $height = ([int]$png[20] * 16MB) + ([int]$png[21] * 64KB) + ([int]$png[22] * 256) + [int]$png[23]
    if ($width -ne 1920 -or $height -ne 1080) {
        throw "Unexpected screenshot dimensions for ${FileName}: ${width}x${height}"
    }
    Write-Output $destination
}

function Collapse-DocumentationNavigation {
    Invoke-DocumentationAdb @('shell', 'input', 'keyevent', 'KEYCODE_DPAD_RIGHT') | Out-Null
    Start-Sleep -Milliseconds 650
}

function Move-DocumentationFocusToTop {
    1..12 | ForEach-Object {
        Invoke-DocumentationAdb @('shell', 'input', 'keyevent', 'KEYCODE_DPAD_UP') | Out-Null
    }
    Start-Sleep -Milliseconds 650
}

$captures = [Collections.Generic.List[string]]::new()

Start-DocumentationScenario -Scenario 'CONNECTING'
$captures.Add((Save-DocumentationScreenshot '01-connecting.png'))

Start-DocumentationScenario -Scenario 'SETUP'
$captures.Add((Save-DocumentationScreenshot '02-connection-setup.png'))

Start-DocumentationScenario -Scenario 'RECOVERY'
$captures.Add((Save-DocumentationScreenshot '03-connection-recovery.png'))

Start-DocumentationScenario -Scenario 'HOME' -Destination 'HOME'
$captures.Add((Save-DocumentationScreenshot '04-home.png'))

Start-DocumentationScenario -Scenario 'HOME' -Destination 'HOME'
Invoke-DocumentationAdb @('shell', 'input', 'keyevent', 'KEYCODE_DPAD_DOWN') | Out-Null
Invoke-DocumentationAdb @('shell', 'input', 'keyevent', 'KEYCODE_DPAD_UP') | Out-Null
Start-Sleep -Milliseconds 500
$captures.Add((Save-DocumentationScreenshot '05-navigation-expanded.png'))

Start-DocumentationScenario -Scenario 'HOME' -Destination 'CAMERAS'
$captures.Add((Save-DocumentationScreenshot '06-cameras.png'))

Start-DocumentationScenario -Scenario 'HOME' -Destination 'BIRDSEYE'
$captures.Add((Save-DocumentationScreenshot '07-birdseye.png'))

Start-DocumentationScenario -Scenario 'HOME' -Destination 'REVIEW'
$captures.Add((Save-DocumentationScreenshot '08-review.png'))

Start-DocumentationScenario -Scenario 'REVIEW_DETAIL' -Destination 'REVIEW'
Collapse-DocumentationNavigation
$captures.Add((Save-DocumentationScreenshot '09-review-detail.png'))

Start-DocumentationScenario -Scenario 'LIVE_PLAYBACK'
$captures.Add((Save-DocumentationScreenshot '10-live-playback.png'))

Start-DocumentationScenario -Scenario 'RECORDED_PLAYBACK'
$captures.Add((Save-DocumentationScreenshot '11-recorded-playback.png'))

Start-DocumentationScenario -Scenario 'HOME' -Destination 'INFORMATION' -InformationTab 'PERFORMANCE'
$captures.Add((Save-DocumentationScreenshot '12-information-performance.png'))

Start-DocumentationScenario -Scenario 'HOME' -Destination 'INFORMATION' -InformationTab 'STORAGE'
$captures.Add((Save-DocumentationScreenshot '13-information-storage.png'))

Start-DocumentationScenario -Scenario 'HOME' -Destination 'SETTINGS'
$captures.Add((Save-DocumentationScreenshot '14-settings.png'))

Start-DocumentationScenario -Scenario 'CUSTOM_THEME' -Destination 'SETTINGS'
$captures.Add((Save-DocumentationScreenshot '15-custom-theme.png'))

Start-DocumentationScenario -Scenario 'HOME' -Destination 'SETTINGS' -SettingsPage 'DIAGNOSTICS'
Collapse-DocumentationNavigation
Move-DocumentationFocusToTop
$captures.Add((Save-DocumentationScreenshot '16-diagnostics.png'))

Start-DocumentationScenario -Scenario 'LIVE_PLAYBACK'
Invoke-DocumentationAdb @('shell', 'input', 'keyevent', 'KEYCODE_DPAD_DOWN') | Out-Null
Start-Sleep -Milliseconds 300
Invoke-DocumentationAdb @('shell', 'input', 'keyevent', 'KEYCODE_DPAD_CENTER') | Out-Null
Start-Sleep -Milliseconds 1800
Invoke-DocumentationAdb @(
    'shell', 'am', 'start', '-W', '-f', '0x10000000',
    '-n', 'app.opah.tv.docs/app.opah.tv.DocumentationBackdropActivity'
) | Out-Null
Start-Sleep -Milliseconds 2200
$pipState = (Invoke-DocumentationAdb @('shell', 'dumpsys', 'activity', 'activities')) -join "`n"
if (-not $pipState.Contains('app.opah.tv.docs') -or -not $pipState.Contains('mode=pinned')) {
    throw 'The documentation app did not enter picture-in-picture mode.'
}
$captures.Add((Save-DocumentationScreenshot '17-picture-in-picture.png'))

Start-DocumentationScenario -Scenario 'HOME' -Destination 'HOME'

Write-Output "Captured $($captures.Count) documentation screenshots in $outputRoot"
