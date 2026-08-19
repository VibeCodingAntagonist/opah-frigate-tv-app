[CmdletBinding()]
param(
    [Parameter()]
    [string]$Root = '',

    [Parameter()]
    [switch]$AllowMissingLicense,

    [Parameter()]
    [switch]$AllowPlaceholders
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Root)) {
    $Root = Split-Path -Parent $PSScriptRoot
}
$rootPath = [IO.Path]::GetFullPath($Root)
$failures = [Collections.Generic.List[string]]::new()

function Add-Failure {
    param([string]$Message)
    $failures.Add($Message)
}

$requiredFiles = @(
    'README.md',
    'CHANGELOG.md',
    'CONTRIBUTING.md',
    'THIRD_PARTY_NOTICES.md',
    '.github/workflows/android-ci.yml',
    '.github/workflows/release.yml',
    'docs/installation.md',
    'docs/release-process.md',
    'docs/security-model.md'
)
if (-not $AllowMissingLicense) {
    $requiredFiles += 'LICENSE'
}

foreach ($relativePath in $requiredFiles) {
    if (-not (Test-Path -LiteralPath (Join-Path $rootPath $relativePath) -PathType Leaf)) {
        Add-Failure "Missing required public-release file: $relativePath"
    }
}

$gitDirectory = Join-Path $rootPath '.git'
if (Test-Path -LiteralPath $gitDirectory) {
    $trackedOutput = & git -C $rootPath ls-files --cached --others --exclude-standard 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to enumerate tracked files: $trackedOutput"
    }
    $relativeFiles = @($trackedOutput | Where-Object { $_ })
} else {
    $relativeFiles = @(
        Get-ChildItem -LiteralPath $rootPath -Recurse -File |
            ForEach-Object { [IO.Path]::GetRelativePath($rootPath, $_.FullName).Replace('\', '/') } |
            Where-Object {
                $_ -notmatch '^(?:\.git|\.gradle|\.idea|\.toolchains|\.artifacts|[^/]+/build)(?:/|$)' -and
                $_ -notmatch '(?:^|/)build(?:/|$)'
            }
    )
}

$forbiddenPathPattern = '(?i)(?:^|/)(?:local\.properties|keystore\.properties|signing\.properties|\.env(?:\..+)?)$|\.(?:apk|aab|jks|keystore|p12|pfx|pem|key|secrets\.json)$|(?:^|/)(?:captures|diagnostics-private|local|dist)(?:/|$)'
foreach ($relativePath in $relativeFiles) {
    if ($relativePath.Replace('\', '/') -match $forbiddenPathPattern) {
        Add-Failure "Forbidden tracked release/private file: $relativePath"
    }
}

$binaryExtensions = @('.jar', '.png', '.webp', '.jpg', '.jpeg', '.gif', '.ico')
$contentPatterns = [ordered]@{
    'private IPv4 address' = '(?<!\d)(?:10(?:\.\d{1,3}){3}|192\.168(?:\.\d{1,3}){2}|172\.(?:1[6-9]|2\d|3[01])(?:\.\d{1,3}){2})(?!\d)'
    'credential-bearing URL' = '(?i)\b(?:https?|rtsp)://[^\s/:@]+:[^@\s]+@'
    'private key material' = '-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----'
    'GitHub token shape' = '(?i)\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\b'
    'AWS access key shape' = '\b(?:AKIA|ASIA)[A-Z0-9]{16}\b'
}
$contentPatternAllowlist = @{
    'credential-bearing URL' = @(
        'app/src/test/java/app/opah/tv/data/ConnectionProfileFactoryTest.kt',
        'app/src/test/java/app/opah/tv/diagnostics/SecretRedactorTest.kt'
    )
}

foreach ($relativePath in $relativeFiles) {
    $fullPath = Join-Path $rootPath $relativePath
    if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
        Add-Failure "Tracked path is missing from the tree: $relativePath"
        continue
    }
    if ([IO.Path]::GetExtension($fullPath).ToLowerInvariant() -in $binaryExtensions) {
        continue
    }

    try {
        $content = [IO.File]::ReadAllText($fullPath)
    } catch {
        Add-Failure "Could not inspect text file: $relativePath"
        continue
    }

    foreach ($entry in $contentPatterns.GetEnumerator()) {
        $normalizedPath = $relativePath.Replace('\', '/')
        if ($normalizedPath -in @($contentPatternAllowlist[$entry.Key])) {
            continue
        }
        if ($content -match $entry.Value) {
            Add-Failure "$($entry.Key) found in $relativePath"
        }
    }
    $githubPlaceholder = 'OWNER' + '/' + 'REPOSITORY'
    if (-not $AllowPlaceholders -and $content.Contains($githubPlaceholder)) {
        Add-Failure "GitHub owner/repository placeholder remains in $relativePath"
    }
}

$workflowFiles = @(
    $relativeFiles |
        Where-Object { $_.Replace('\', '/') -match '^\.github/workflows/.+\.ya?ml$' }
)
foreach ($workflowFile in $workflowFiles) {
    $content = [IO.File]::ReadAllText((Join-Path $rootPath $workflowFile))
    foreach ($match in [regex]::Matches($content, '(?m)^\s*uses:\s*([^\s#]+)')) {
        $action = $match.Groups[1].Value
        if ($action.StartsWith('./')) {
            continue
        }
        $reference = ($action -split '@', 2)[1]
        if (-not $reference -or $reference -notmatch '^[0-9a-fA-F]{40}$') {
            Add-Failure "GitHub Action is not pinned to a full commit SHA in ${workflowFile}: $action"
        }
    }
}

if ($failures.Count -gt 0) {
    Write-Host "Public-tree verification failed with $($failures.Count) issue(s):" -ForegroundColor Red
    foreach ($failure in $failures) {
        Write-Host " - $failure" -ForegroundColor Red
    }
    exit 1
}

Write-Host "Public-tree verification passed for $($relativeFiles.Count) files." -ForegroundColor Green
