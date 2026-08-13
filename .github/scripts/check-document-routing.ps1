[CmdletBinding()]
param(
    [Parameter(Mandatory)] [ValidateNotNullOrEmpty()] [string] $BaseRef,
    [Parameter(Mandatory)] [ValidateNotNullOrEmpty()] [string] $HeadRef
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Test-CommitRef {
    param([Parameter(Mandatory)] [string] $Ref)

    [void](& git rev-parse --verify --quiet "$Ref`^{commit}" 2>$null)
    return $LASTEXITCODE -eq 0
}

[void](& git rev-parse --is-inside-work-tree 2>$null)
if ($LASTEXITCODE -ne 0) {
    [Console]::Error.WriteLine('Document routing guard must run inside a Git work tree.')
    exit 2
}

foreach ($ref in @($BaseRef, $HeadRef)) {
    if (-not (Test-CommitRef -Ref $ref)) {
        [Console]::Error.WriteLine("Document routing guard could not resolve commit ref: $ref")
        exit 2
    }
}

$forbiddenPathspecs = @(
    ':(top,literal)docs/superpowers/plans',
    ':(top,literal)docs/superpowers/specs'
)
$diffLines = @(& git -c core.quotepath=false diff --name-status --no-renames --diff-filter=AMTUXB "$BaseRef...$HeadRef" -- @forbiddenPathspecs 2>&1)
if ($LASTEXITCODE -ne 0) {
    [Console]::Error.WriteLine("Document routing guard could not compare $BaseRef...$HeadRef.")
    [Console]::Error.WriteLine($diffLines -join [Environment]::NewLine)
    exit 2
}

$violations = [System.Collections.Generic.List[string]]::new()

foreach ($line in $diffLines) {
    if ([string]::IsNullOrWhiteSpace($line)) {
        continue
    }

    $parts = $line -split "`t", 2
    if ($parts.Count -ne 2) {
        [Console]::Error.WriteLine("Document routing guard received an unexpected diff record: $line")
        exit 2
    }

    $violations.Add("$($parts[0])`t$($parts[1])")
}

if ($violations.Count -gt 0) {
    [Console]::Error.WriteLine('Non-canonical SDD artifacts cannot be added or modified in docs/superpowers/.')
    foreach ($violation in $violations | Sort-Object -Unique) {
        $parts = $violation -split "`t", 2
        [Console]::Error.WriteLine("[$($parts[0])] $($parts[1])")
    }
    [Console]::Error.WriteLine('Write temporary plans and designs under the ignored .superpowers/sdd/ directory.')
    exit 1
}

Write-Output "Document routing guard passed for $BaseRef...$HeadRef."
exit 0
