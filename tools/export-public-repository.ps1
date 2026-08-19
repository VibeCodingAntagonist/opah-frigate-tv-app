[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$Destination
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$destinationPath = [IO.Path]::GetFullPath($Destination)
$repositoryPrefix = $repositoryRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
$artifactPrefix = (Join-Path $repositoryRoot '.artifacts').TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar

if ($destinationPath -eq $repositoryRoot) {
    throw 'Destination must not be the private repository.'
}
if ($destinationPath.StartsWith($repositoryPrefix, [StringComparison]::OrdinalIgnoreCase) -and
    -not $destinationPath.StartsWith($artifactPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'A destination inside the private repository is allowed only under .artifacts for a local rehearsal.'
}

$status = & git -C $repositoryRoot status --porcelain=v1 --untracked-files=all 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "Unable to inspect the private repository: $status"
}
if ($status) {
    throw 'The private repository must be clean before a public export.'
}

& (Join-Path $PSScriptRoot 'verify-public-tree.ps1') -Root $repositoryRoot
if ($LASTEXITCODE -ne 0) {
    throw 'The private tree did not pass public-release verification.'
}

if (Test-Path -LiteralPath $destinationPath) {
    if (-not (Test-Path -LiteralPath $destinationPath -PathType Container)) {
        throw 'Destination exists and is not a directory.'
    }
    if (Get-ChildItem -LiteralPath $destinationPath -Force | Select-Object -First 1) {
        throw 'Destination directory must be empty.'
    }
} else {
    [void](New-Item -ItemType Directory -Path $destinationPath)
}

$archivePath = Join-Path ([IO.Path]::GetTempPath()) "opah-public-$([Guid]::NewGuid().ToString('N')).zip"
try {
    & git -C $repositoryRoot archive --format=zip --output=$archivePath HEAD
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $archivePath -PathType Leaf)) {
        throw 'git archive did not create the public export.'
    }

    Expand-Archive -LiteralPath $archivePath -DestinationPath $destinationPath
    if (Test-Path -LiteralPath (Join-Path $destinationPath '.git')) {
        throw 'Safety check failed: the public export unexpectedly contains Git history.'
    }

    & (Join-Path $destinationPath 'tools\verify-public-tree.ps1') -Root $destinationPath
    if ($LASTEXITCODE -ne 0) {
        throw 'The exported tree did not pass public-release verification.'
    }
} finally {
    if (Test-Path -LiteralPath $archivePath -PathType Leaf) {
        Remove-Item -LiteralPath $archivePath -Force
    }
}

Write-Host "Clean public tree exported to: $destinationPath" -ForegroundColor Green
Write-Host 'No Git history was included. Inspect the files before initializing a new repository.'
