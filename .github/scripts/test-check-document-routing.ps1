[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$env:GIT_CONFIG_GLOBAL = if ([System.Environment]::OSVersion.Platform -eq [System.PlatformID]::Win32NT) { 'NUL' } else { '/dev/null' }
$env:GIT_CONFIG_NOSYSTEM = '1'

$guardScript = Join-Path $PSScriptRoot 'check-document-routing.ps1'
$powerShellExecutable = if ($PSVersionTable.PSEdition -eq 'Core') {
    Join-Path $PSHOME 'pwsh'
}
else {
    Join-Path $PSHOME 'powershell.exe'
}

if (-not (Test-Path -LiteralPath $guardScript -PathType Leaf)) {
    Write-Error "Guard script not found: $guardScript"
    exit 1
}

function Write-Utf8NoBom {
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [string] $Value
    )

    [System.IO.File]::WriteAllText($Path, $Value, [System.Text.UTF8Encoding]::new($false))
}

function Invoke-Git {
    param(
        [Parameter(Mandatory)] [string] $Repository,
        [Parameter(Mandatory)] [string[]] $Arguments
    )

    Push-Location -LiteralPath $Repository
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = @(& git @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
        Pop-Location
    }

    if ($exitCode -ne 0) {
        throw "git $($Arguments -join ' ') failed with exit $exitCode`n$($output -join "`n")"
    }

    return $output
}

function Invoke-Guard {
    param(
        [Parameter(Mandatory)] [string] $Repository,
        [Parameter(Mandatory)] [string] $BaseRef,
        [Parameter(Mandatory)] [string] $HeadRef
    )

    Push-Location -LiteralPath $Repository
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = @(& $powerShellExecutable -NoProfile -File $guardScript -BaseRef $BaseRef -HeadRef $HeadRef 2>&1)
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
        Pop-Location
    }

    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = $output -join "`n"
    }
}

function Assert-GuardResult {
    param(
        [Parameter(Mandatory)] [string] $Name,
        [Parameter(Mandatory)] [pscustomobject] $Result,
        [Parameter(Mandatory)] [int] $ExpectedExitCode,
        [string[]] $ExpectedFragments = @()
    )

    if ($Result.ExitCode -ne $ExpectedExitCode) {
        throw "[$Name] expected exit $ExpectedExitCode but got $($Result.ExitCode).`n$($Result.Output)"
    }

    foreach ($fragment in $ExpectedFragments) {
        if (-not $Result.Output.Contains($fragment)) {
            throw "[$Name] output did not contain '$fragment'.`n$($Result.Output)"
        }
    }

    Write-Output "PASS $Name"
}

function Invoke-ChangeCase {
    param(
        [Parameter(Mandatory)] [string] $Repository,
        [Parameter(Mandatory)] [string] $BaseRef,
        [Parameter(Mandatory)] [string] $Name,
        [Parameter(Mandatory)] [scriptblock] $Change,
        [Parameter(Mandatory)] [int] $ExpectedExitCode,
        [string[]] $ExpectedFragments = @()
    )

    [void](Invoke-Git -Repository $Repository -Arguments @('switch', '-C', "case-$Name", $BaseRef))

    Push-Location -LiteralPath $Repository
    try {
        & $Change
    }
    finally {
        Pop-Location
    }

    [void](Invoke-Git -Repository $Repository -Arguments @('add', '-A'))
    [void](Invoke-Git -Repository $Repository -Arguments @('commit', '-q', '-m', "test case $Name"))
    $headRef = ((Invoke-Git -Repository $Repository -Arguments @('rev-parse', 'HEAD')) -join "`n").Trim()
    $result = Invoke-Guard -Repository $Repository -BaseRef $BaseRef -HeadRef $headRef
    Assert-GuardResult -Name $Name -Result $result -ExpectedExitCode $ExpectedExitCode -ExpectedFragments $ExpectedFragments
}

$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) "document-routing-guard-$([guid]::NewGuid().ToString('N'))"
$resolvedTempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$resolvedTestRoot = [System.IO.Path]::GetFullPath($testRoot)
if (-not $resolvedTestRoot.StartsWith($resolvedTempRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to create fixture outside temp: $resolvedTestRoot"
}

New-Item -ItemType Directory -Path $testRoot | Out-Null
$env:XDG_CONFIG_HOME = Join-Path $testRoot 'xdg'

try {
    [void](Invoke-Git -Repository $testRoot -Arguments @('init', '-q'))
    [void](Invoke-Git -Repository $testRoot -Arguments @('config', 'user.name', 'Document Routing Test'))
    [void](Invoke-Git -Repository $testRoot -Arguments @('config', 'user.email', 'document-routing-test@example.invalid'))
    [void](Invoke-Git -Repository $testRoot -Arguments @('config', 'core.autocrlf', 'false'))

    New-Item -ItemType Directory -Path (Join-Path $testRoot 'docs/superpowers/plans') -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $testRoot 'docs/superpowers/specs') -Force | Out-Null
    Write-Utf8NoBom -Path (Join-Path $testRoot 'README.md') -Value 'baseline'
    Write-Utf8NoBom -Path (Join-Path $testRoot 'allowed-source.md') -Value 'move me'
    Write-Utf8NoBom -Path (Join-Path $testRoot 'docs/superpowers/plans/history.md') -Value 'historical plan'
    Write-Utf8NoBom -Path (Join-Path $testRoot 'docs/superpowers/specs/history.md') -Value 'historical spec'
    [void](Invoke-Git -Repository $testRoot -Arguments @('add', '-A'))
    [void](Invoke-Git -Repository $testRoot -Arguments @('commit', '-q', '-m', 'baseline'))
    $baseRef = ((Invoke-Git -Repository $testRoot -Arguments @('rev-parse', 'HEAD')) -join "`n").Trim()

    Invoke-ChangeCase -Repository $testRoot -BaseRef $baseRef -Name 'allowed-change' -ExpectedExitCode 0 -Change {
        Write-Utf8NoBom -Path (Join-Path $testRoot 'README.md') -Value 'allowed change'
    }

    Invoke-ChangeCase -Repository $testRoot -BaseRef $baseRef -Name 'forbidden-add' -ExpectedExitCode 1 -ExpectedFragments @(
        'docs/superpowers/plans/new-plan.md',
        '.superpowers/sdd/'
    ) -Change {
        Write-Utf8NoBom -Path (Join-Path $testRoot 'docs/superpowers/plans/new-plan.md') -Value 'new plan'
    }

    Invoke-ChangeCase -Repository $testRoot -BaseRef $baseRef -Name 'forbidden-non-ascii-add' -ExpectedExitCode 1 -ExpectedFragments @(
        'docs/superpowers/plans/계획.md',
        '.superpowers/sdd/'
    ) -Change {
        Write-Utf8NoBom -Path (Join-Path $testRoot 'docs/superpowers/plans/계획.md') -Value 'new plan'
    }

    Invoke-ChangeCase -Repository $testRoot -BaseRef $baseRef -Name 'forbidden-modify' -ExpectedExitCode 1 -ExpectedFragments @(
        'docs/superpowers/specs/history.md',
        '.superpowers/sdd/'
    ) -Change {
        Write-Utf8NoBom -Path (Join-Path $testRoot 'docs/superpowers/specs/history.md') -Value 'changed history'
    }

    Invoke-ChangeCase -Repository $testRoot -BaseRef $baseRef -Name 'allowed-delete' -ExpectedExitCode 0 -Change {
        Remove-Item -LiteralPath 'docs/superpowers/plans/history.md'
    }

    Invoke-ChangeCase -Repository $testRoot -BaseRef $baseRef -Name 'forbidden-move-in' -ExpectedExitCode 1 -ExpectedFragments @(
        'docs/superpowers/plans/moved-in.md',
        '.superpowers/sdd/'
    ) -Change {
        & git mv -- 'allowed-source.md' 'docs/superpowers/plans/moved-in.md'
        if ($LASTEXITCODE -ne 0) { throw 'git mv into forbidden path failed' }
    }

    Invoke-ChangeCase -Repository $testRoot -BaseRef $baseRef -Name 'forbidden-copy-in' -ExpectedExitCode 1 -ExpectedFragments @(
        'docs/superpowers/specs/copied-in.md',
        '.superpowers/sdd/'
    ) -Change {
        Copy-Item -LiteralPath 'allowed-source.md' -Destination 'docs/superpowers/specs/copied-in.md'
    }

    Invoke-ChangeCase -Repository $testRoot -BaseRef $baseRef -Name 'allowed-move-out' -ExpectedExitCode 0 -Change {
        New-Item -ItemType Directory -Path 'archive' -Force | Out-Null
        & git mv -- 'docs/superpowers/specs/history.md' 'archive/history.md'
        if ($LASTEXITCODE -ne 0) { throw 'git mv out of forbidden path failed' }
    }

    $invalidRefResult = Invoke-Guard -Repository $testRoot -BaseRef 'missing-base-ref' -HeadRef $baseRef
    Assert-GuardResult -Name 'invalid-ref-fails-closed' -Result $invalidRefResult -ExpectedExitCode 2 -ExpectedFragments @('missing-base-ref')
}
finally {
    if (Test-Path -LiteralPath $resolvedTestRoot) {
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
    }
}

exit 0
