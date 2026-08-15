[CmdletBinding()]
param(
    [ValidateSet('Common', 'Gradle', 'WindowsNative')]
    [string[]] $Suite = @('Common', 'Gradle', 'WindowsNative')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:Passed = 0
$script:Failed = 0

function Assert-Equal {
    param(
        [Parameter(Mandatory)] $Expected,
        [Parameter(Mandatory)] $Actual,
        [Parameter(Mandatory)] [string] $Because
    )

    if ($Expected -ne $Actual) {
        throw "Expected [$Expected] but got [$Actual]: $Because"
    }
}

function Assert-True {
    param(
        [Parameter(Mandatory)] [bool] $Condition,
        [Parameter(Mandatory)] [string] $Because
    )

    if (-not $Condition) {
        throw "Expected condition to be true: $Because"
    }
}

function Invoke-ContractCase {
    param(
        [Parameter(Mandatory)] [string] $Name,
        [Parameter(Mandatory)] [scriptblock] $Body
    )

    try {
        & $Body
        $script:Passed++
        Write-Host "PASS $Name"
    }
    catch {
        $script:Failed++
        Write-Host "FAIL $Name`n$($_.Exception.Message)" -ForegroundColor Red
    }
}

function Assert-Throws {
    param(
        [Parameter(Mandatory)] [scriptblock] $Body,
        [Parameter(Mandatory)] [string] $MessagePattern,
        [Parameter(Mandatory)] [string] $Because
    )

    $caught = $null
    try {
        & $Body
    }
    catch {
        $caught = $_.Exception
    }

    if ($null -eq $caught) {
        throw "Expected an exception matching [$MessagePattern]: $Because"
    }
    if ($caught.Message -notmatch $MessagePattern) {
        throw "Expected exception matching [$MessagePattern] but got [$($caught.Message)]: $Because"
    }
}

function New-FakeVerificationChild {
    param(
        [Parameter(Mandatory)] [string] $FixtureRoot,
        [Parameter(Mandatory)] [string] $Id,
        [Parameter(Mandatory)] [int] $DelayMilliseconds,
        [int] $ExitCode = 0
    )

    $fixturePath = Join-Path $FixtureRoot 'fake-verification-child.ps1'
    if (-not (Test-Path -LiteralPath $fixturePath -PathType Leaf)) {
        @'
param(
    [Parameter(Mandatory)]
    [string] $Id,
    [Parameter(Mandatory)]
    [string] $TimelinePath,
    [Parameter(Mandatory)]
    [string] $ResultDirectory,
    [Parameter(Mandatory)]
    [int] $DelayMilliseconds,
    [Parameter(Mandatory)]
    [int] $RequestedExitCode
)

$startTicks = [DateTimeOffset]::UtcNow.Ticks
Start-Sleep -Milliseconds $DelayMilliseconds
$null = New-Item -ItemType Directory -Path $ResultDirectory -Force
'<testsuite tests="1" failures="0" errors="0" skipped="0" />' |
    Set-Content -LiteralPath (Join-Path $ResultDirectory "TEST-$Id.xml") -Encoding utf8NoBOM
$endTicks = [DateTimeOffset]::UtcNow.Ticks
[pscustomobject]@{
    Id = $Id
    StartTicks = $startTicks
    EndTicks = $endTicks
} | ConvertTo-Json -Compress | Set-Content -LiteralPath $TimelinePath -Encoding utf8NoBOM
[Console]::Out.WriteLine("completed:$Id")
[Console]::Error.WriteLine("stderr:$Id")
exit $RequestedExitCode
'@ | Set-Content -LiteralPath $fixturePath -Encoding utf8NoBOM
    }

    $childRoot = Join-Path $FixtureRoot $Id
    return [pscustomobject]@{
        Id = $Id
        Tasks = @($Id)
        ReportTask = $Id
        Executable = [System.Diagnostics.Process]::GetCurrentProcess().MainModule.FileName
        Arguments = @(
            '-NoProfile',
            '-File',
            $fixturePath,
            '-Id',
            $Id,
            '-TimelinePath',
            (Join-Path $FixtureRoot "$Id.timeline.json"),
            '-ResultDirectory',
            (Join-Path $childRoot 'test-results'),
            '-DelayMilliseconds',
            $DelayMilliseconds,
            '-RequestedExitCode',
            $ExitCode
        )
        BuildDirectory = $childRoot
        ProjectCacheDirectory = Join-Path $childRoot 'project-cache'
        StdoutPath = Join-Path $childRoot 'stdout.log'
        StderrPath = Join-Path $childRoot 'stderr.log'
        ResultDirectory = Join-Path $childRoot 'test-results'
        TimelinePath = Join-Path $FixtureRoot "$Id.timeline.json"
    }
}

$runnerPath = Join-Path $PSScriptRoot 'run-backend-full-verification.ps1'
if (-not (Test-Path -LiteralPath $runnerPath -PathType Leaf)) {
    Write-Error "Runner does not exist: $runnerPath"
    exit 1
}

. $runnerPath

if ($Suite -contains 'Common') {
    Invoke-ContractCase 'Auto selects the documented integration worker boundary' {
        $cases = @(
            @{ AvailableBytes = 20GB; LogicalProcessors = 8; Expected = 4 },
            @{ AvailableBytes = 12GB; LogicalProcessors = 4; Expected = 2 },
            @{ AvailableBytes = 11GB; LogicalProcessors = 16; Expected = 1 },
            @{ AvailableBytes = 32GB; LogicalProcessors = 3; Expected = 1 }
        )

        foreach ($case in $cases) {
            $selection = Resolve-ParallelShardSelection `
                -Requested Auto `
                -AvailableBytes $case.AvailableBytes `
                -LogicalProcessors $case.LogicalProcessors

            Assert-Equal -Expected $case.Expected -Actual $selection.Value `
                -Because "RAM=$($case.AvailableBytes), CPU=$($case.LogicalProcessors)"
        }
    }

    Invoke-ContractCase 'Resource warnings preserve the documented safety boundary' {
        $lowMemory = Resolve-ParallelShardSelection `
            -Requested Auto `
            -AvailableBytes 7GB `
            -LogicalProcessors 16
        Assert-Equal -Expected 1 -Actual $lowMemory.Value -Because 'low memory fallback value'
        Assert-True -Condition (($lowMemory.Warnings -join "`n") -match 'not a safety guarantee') `
            -Because 'Auto=1 must not claim low-memory safety'
        Assert-True -Condition (($lowMemory.Warnings -join "`n") -match 'Docker.*WSL2') `
            -Because 'Auto must disclose the Docker/WSL2 memory observation boundary'

        $detectionFailure = Resolve-ParallelShardSelection `
            -Requested Auto `
            -AvailableBytes $null `
            -LogicalProcessors $null
        Assert-Equal -Expected 1 -Actual $detectionFailure.Value -Because 'detection failure fallback value'
        Assert-True -Condition (($detectionFailure.Warnings -join "`n") -match 'detection failed') `
            -Because 'resource detection failure must be visible'

        $explicit = Resolve-ParallelShardSelection `
            -Requested 4 `
            -AvailableBytes 8GB `
            -LogicalProcessors 2
        Assert-Equal -Expected 4 -Actual $explicit.Value -Because 'explicit override remains authoritative'
        Assert-True -Condition (($explicit.Warnings -join "`n") -match 'below.*20GB.*8') `
            -Because 'explicit override below the Auto threshold must warn'
    }

    Invoke-ContractCase 'JUnit XML counts are aggregated from real report files' {
        $fixtureRoot = Join-Path ([System.IO.Path]::GetTempPath()) "miriyum-xml-$([guid]::NewGuid().ToString('N'))"
        try {
            $null = New-Item -ItemType Directory -Path $fixtureRoot
            @'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="first" tests="3" failures="0" errors="0" skipped="0" />
'@ | Set-Content -LiteralPath (Join-Path $fixtureRoot 'TEST-first.xml') -Encoding utf8NoBOM
            @'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="second" tests="5" failures="0" errors="0" skipped="0" />
'@ | Set-Content -LiteralPath (Join-Path $fixtureRoot 'TEST-second.xml') -Encoding utf8NoBOM

            $summary = Read-TestResultSummary -ResultDirectory $fixtureRoot
            Assert-Equal -Expected 2 -Actual $summary.FileCount -Because 'report file count'
            Assert-Equal -Expected 8 -Actual $summary.Tests -Because 'total executed tests'
            Assert-Equal -Expected 0 -Actual $summary.Failures -Because 'total failures'
            Assert-Equal -Expected 0 -Actual $summary.Errors -Because 'total errors'
            Assert-Equal -Expected 0 -Actual $summary.Skipped -Because 'total skipped'
        }
        finally {
            if (Test-Path -LiteralPath $fixtureRoot) {
                Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
            }
        }
    }

    Invoke-ContractCase 'Missing malformed empty failed errored or skipped XML is rejected' {
        $fixtureRoot = Join-Path ([System.IO.Path]::GetTempPath()) "miriyum-invalid-xml-$([guid]::NewGuid().ToString('N'))"
        try {
            Assert-Throws -Body { Read-TestResultSummary -ResultDirectory $fixtureRoot } `
                -MessagePattern 'does not exist' -Because 'missing result directory'

            $null = New-Item -ItemType Directory -Path $fixtureRoot
            Assert-Throws -Body { Read-TestResultSummary -ResultDirectory $fixtureRoot } `
                -MessagePattern 'was not found' -Because 'empty result directory'

            $reportPath = Join-Path $fixtureRoot 'TEST-fixture.xml'
            '<testsuite' | Set-Content -LiteralPath $reportPath -Encoding utf8NoBOM
            Assert-Throws -Body { Read-TestResultSummary -ResultDirectory $fixtureRoot } `
                -MessagePattern 'Malformed' -Because 'malformed XML'

            foreach ($case in @(
                @{ Xml = '<testsuite tests="0" failures="0" errors="0" skipped="0" />'; Pattern = 'zero tests'; Name = 'empty suite' },
                @{ Xml = '<testsuite tests="2" failures="1" errors="0" skipped="0" />'; Pattern = 'not successful'; Name = 'failed suite' },
                @{ Xml = '<testsuite tests="2" failures="0" errors="1" skipped="0" />'; Pattern = 'not successful'; Name = 'errored suite' },
                @{ Xml = '<testsuite tests="2" failures="0" errors="0" skipped="1" />'; Pattern = 'not successful'; Name = 'skipped suite' },
                @{ Xml = '<testsuite tests="2" failures="0" errors="0" />'; Pattern = 'missing or invalid'; Name = 'missing attribute' }
            )) {
                $case.Xml | Set-Content -LiteralPath $reportPath -Encoding utf8NoBOM
                Assert-Throws -Body { Read-TestResultSummary -ResultDirectory $fixtureRoot } `
                    -MessagePattern $case.Pattern -Because $case.Name
            }
        }
        finally {
            if (Test-Path -LiteralPath $fixtureRoot) {
                Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
            }
        }
    }

    Invoke-ContractCase 'Remaining timeout budget is conservative and never presented as ETA' {
        $timeout = [TimeSpan]::FromMinutes(35)
        $cases = @(
            @{ Pending = 3; Running = 1; Parallel = 1; ExpectedMinutes = 140 },
            @{ Pending = 2; Running = 2; Parallel = 2; ExpectedMinutes = 70 },
            @{ Pending = 0; Running = 4; Parallel = 4; ExpectedMinutes = 35 },
            @{ Pending = 0; Running = 0; Parallel = 4; ExpectedMinutes = 0 }
        )

        foreach ($case in $cases) {
            $budget = Get-RemainingTimeoutBudget `
                -PendingCount $case.Pending `
                -RunningIntegrationCount $case.Running `
                -ParallelShards $case.Parallel `
                -ChildTimeout $timeout
            Assert-Equal -Expected $case.ExpectedMinutes -Actual $budget.TotalMinutes `
                -Because "pending=$($case.Pending), running=$($case.Running), parallel=$($case.Parallel)"
        }
    }

    Invoke-ContractCase 'Gradle child definitions isolate mutable paths and pin runner arguments' {
        $backendRoot = Split-Path -Parent $PSScriptRoot
        $runRoot = Join-Path $backendRoot 'build/local-verification/contract-run'
        $unit = New-GradleChildDefinition `
            -Id unit `
            -Tasks @('test', 'assemble') `
            -ReportTask test `
            -BackendRoot $backendRoot `
            -RunRoot $runRoot
        $shard = New-GradleChildDefinition `
            -Id shard-a `
            -Tasks @('integrationTestShardA') `
            -ReportTask integrationTestShardA `
            -BackendRoot $backendRoot `
            -RunRoot $runRoot

        Assert-True -Condition ($unit.BuildDirectory -ne $shard.BuildDirectory) `
            -Because 'children must not share build directories'
        Assert-True -Condition ($unit.ProjectCacheDirectory -ne $shard.ProjectCacheDirectory) `
            -Because 'children must not share project caches'
        Assert-True -Condition ($unit.StdoutPath -ne $shard.StdoutPath -and $unit.StderrPath -ne $shard.StderrPath) `
            -Because 'children must not share logs'
        Assert-Equal -Expected (Join-Path $unit.BuildDirectory 'test-results/test') `
            -Actual $unit.ResultDirectory -Because 'unit report directory'
        Assert-Equal -Expected (Join-Path $shard.BuildDirectory 'test-results/integrationTestShardA') `
            -Actual $shard.ResultDirectory -Because 'shard report directory'

        $arguments = $unit.Arguments
        foreach ($required in @(
            '--no-daemon',
            '--rerun-tasks',
            '--console=plain',
            '--init-script',
            '--project-cache-dir',
            '-Dorg.gradle.jvmargs=-Xmx1g',
            'test',
            'assemble'
        )) {
            Assert-True -Condition ($arguments -contains $required) `
                -Because "child argument [$required]"
        }
        Assert-True -Condition ($arguments -contains "-Dmiriyum.verification.build-dir=$($unit.BuildDirectory)") `
            -Because 'child-specific build directory property'
    }

    Invoke-ContractCase 'Backend full verification definitions preserve unit and shard task topology' {
        $backendRoot = Split-Path -Parent $PSScriptRoot
        $runRoot = Join-Path ([System.IO.Path]::GetTempPath()) "miriyum-definitions-$([guid]::NewGuid().ToString('N'))"
        try {
            $definitions = New-BackendVerificationChildDefinitions `
                -BackendRoot $backendRoot `
                -RunRoot $runRoot

            Assert-Equal -Expected 'unit' -Actual $definitions.Unit.Id -Because 'unit child id'
            Assert-Equal -Expected 'test,assemble' -Actual ($definitions.Unit.Tasks -join ',') `
                -Because 'unit and assemble task order'
            Assert-Equal -Expected 'test' -Actual $definitions.Unit.ReportTask `
                -Because 'unit report task'

            $expectedShards = @(
                @{ Id = 'shard-a'; Task = 'integrationTestShardA' },
                @{ Id = 'shard-b'; Task = 'integrationTestShardB' },
                @{ Id = 'shard-c'; Task = 'integrationTestShardC' },
                @{ Id = 'shard-d'; Task = 'integrationTestShardD' }
            )
            Assert-Equal -Expected 4 -Actual $definitions.Integrations.Count `
                -Because 'four integration shard children'
            for ($index = 0; $index -lt $expectedShards.Count; $index++) {
                $expected = $expectedShards[$index]
                $actual = $definitions.Integrations[$index]
                Assert-Equal -Expected $expected.Id -Actual $actual.Id `
                    -Because "integration child[$index] id"
                Assert-Equal -Expected $expected.Task -Actual $actual.Tasks[0] `
                    -Because "integration child[$index] task"
                Assert-Equal -Expected $expected.Task -Actual $actual.ReportTask `
                    -Because "integration child[$index] report task"
            }

            $allChildren = @($definitions.Unit) + @($definitions.Integrations)
            foreach ($propertyName in @(
                'BuildDirectory',
                'ProjectCacheDirectory',
                'StdoutPath',
                'StderrPath',
                'ResultDirectory'
            )) {
                Assert-Equal -Expected 5 -Actual @(
                    $allChildren.$propertyName | Sort-Object -Unique
                ).Count -Because "unique child $propertyName"
            }
            Assert-True -Condition (-not (Test-Path -LiteralPath $runRoot)) `
                -Because 'definition construction must not create mutable directories'
        }
        finally {
            if (Test-Path -LiteralPath $runRoot) {
                Remove-Item -LiteralPath $runRoot -Recurse -Force
            }
        }
    }

    Invoke-ContractCase 'Child completion summary is fully formatted before it is written' {
        $startedAt = [DateTimeOffset]::Parse('2026-08-15T00:00:00Z')
        $result = [pscustomobject]@{
            Id = 'unit'
            Definition = [pscustomobject]@{
                Tasks = @('test', 'assemble')
                StdoutPath = 'stdout.log'
                StderrPath = 'stderr.log'
                ResultDirectory = 'test-results/test'
            }
            Succeeded = $true
            ProcessResult = [pscustomobject]@{
                ProcessId = 42
                StartedAt = $startedAt
                ExitedAt = $startedAt.AddSeconds(3)
                ExitCode = 0
            }
            TestSummary = [pscustomobject]@{
                Tests = 7
                Failures = 0
                Errors = 0
                Skipped = 0
            }
            Failure = $null
        }

        $line = Format-VerificationChildSummary -Result $result

        Assert-True -Condition ($line -notmatch '\{\d+\}| -f ') `
            -Because 'format placeholders and the format operator must not leak into runner output'
        Assert-True -Condition ($line -match 'id=unit tasks=\[test,assemble\] succeeded=True') `
            -Because 'summary identifies the completed child and task list'
        Assert-True -Condition ($line -match 'exit=0 tests=7 failures=0 errors=0 skipped=0') `
            -Because 'summary reports the verified process and XML counts'
    }

    Invoke-ContractCase 'Process bridge preserves Unicode argv stdin stdout stderr and exit code' {
        $fixtureRoot = Join-Path ([System.IO.Path]::GetTempPath()) "miriyum-process-$([guid]::NewGuid().ToString('N'))"
        try {
            $null = New-Item -ItemType Directory -Path $fixtureRoot
            $fixturePath = Join-Path $fixtureRoot 'echo-contract.ps1'
            @'
$stdinText = [Console]::In.ReadToEnd()
[pscustomobject]@{
    Stdin = $stdinText
    Arguments = @($args)
} | ConvertTo-Json -Compress -Depth 4
[Console]::Error.WriteLine('fixture-stderr')
'@ | Set-Content -LiteralPath $fixturePath -Encoding utf8NoBOM

            $expectedArguments = @(
                '"',
                'space " value',
                '한글"값',
                '&"',
                '("',
                ')"',
                '^"',
                '%"',
                '!"'
            )
            $stdinNonce = 'nonce-한글-&()^%!"'
            $stdoutPath = Join-Path $fixtureRoot 'stdout.log'
            $stderrPath = Join-Path $fixtureRoot 'stderr.log'
            $request = New-Object 'Miriyum.Verification.ProcessRequest'
            $request.FileName = [System.Diagnostics.Process]::GetCurrentProcess().MainModule.FileName
            $request.Arguments = @('-NoProfile', '-File', $fixturePath) + $expectedArguments
            $request.WorkingDirectory = $fixtureRoot
            $request.StdoutPath = $stdoutPath
            $request.StderrPath = $stderrPath
            $request.StdinText = $stdinNonce
            $request.Timeout = [TimeSpan]::FromSeconds(20)
            $request.TerminationGrace = [TimeSpan]::FromSeconds(2)

            $result = [Miriyum.Verification.ProcessBridge]::RunAsync(
                $request,
                [System.Threading.CancellationToken]::None
            ).GetAwaiter().GetResult()

            Assert-Equal -Expected 0 -Actual $result.ExitCode -Because 'real child exit code'
            Assert-True -Condition (-not $result.TimedOut -and -not $result.Cancelled) `
                -Because 'successful child must not be classified as timeout or cancellation'
            Assert-True -Condition $result.ContainmentSucceeded `
                -Because 'successful child must be contained through its platform primitive'
            $payload = Get-Content -Raw -LiteralPath $stdoutPath | ConvertFrom-Json
            Assert-Equal -Expected $stdinNonce -Actual $payload.Stdin -Because 'Unicode stdin round trip'
            Assert-Equal -Expected $expectedArguments.Count -Actual $payload.Arguments.Count `
                -Because 'argv element count'
            for ($index = 0; $index -lt $expectedArguments.Count; $index++) {
                Assert-Equal -Expected $expectedArguments[$index] -Actual $payload.Arguments[$index] `
                    -Because "argv[$index] round trip"
            }
            Assert-True -Condition ((Get-Content -Raw -LiteralPath $stderrPath) -match 'fixture-stderr') `
                -Because 'stderr must be drained to its independent log'
        }
        finally {
            if (Test-Path -LiteralPath $fixtureRoot) {
                Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
            }
        }
    }

    Invoke-ContractCase 'Process bridge drains stdout and stderr beyond pipe capacity before verdict' {
        $fixtureRoot = Join-Path ([System.IO.Path]::GetTempPath()) "miriyum-streams-$([guid]::NewGuid().ToString('N'))"
        try {
            $null = New-Item -ItemType Directory -Path $fixtureRoot
            $fixturePath = Join-Path $fixtureRoot 'large-streams.ps1'
            @'
$length = 256KB
[Console]::Out.Write('O' * $length)
[Console]::Error.Write('E' * $length)
exit 23
'@ | Set-Content -LiteralPath $fixturePath -Encoding utf8NoBOM

            $request = New-Object 'Miriyum.Verification.ProcessRequest'
            $request.FileName = [System.Diagnostics.Process]::GetCurrentProcess().MainModule.FileName
            $request.Arguments = @('-NoProfile', '-File', $fixturePath)
            $request.WorkingDirectory = $fixtureRoot
            $request.StdoutPath = Join-Path $fixtureRoot 'stdout.log'
            $request.StderrPath = Join-Path $fixtureRoot 'stderr.log'
            $request.Timeout = [TimeSpan]::FromSeconds(20)
            $request.TerminationGrace = [TimeSpan]::FromSeconds(2)

            $result = [Miriyum.Verification.ProcessBridge]::RunAsync(
                $request,
                [System.Threading.CancellationToken]::None
            ).GetAwaiter().GetResult()

            Assert-Equal -Expected 23 -Actual $result.ExitCode -Because 'real non-zero child exit code'
            $stdout = Get-Content -Raw -LiteralPath $request.StdoutPath
            $stderr = Get-Content -Raw -LiteralPath $request.StderrPath
            Assert-Equal -Expected 256KB -Actual $stdout.Length -Because 'complete stdout byte-sized ASCII payload'
            Assert-Equal -Expected 256KB -Actual $stderr.Length -Because 'complete stderr byte-sized ASCII payload'
            Assert-True -Condition ($stdout[0] -eq 'O' -and $stdout[-1] -eq 'O') `
                -Because 'stdout first and last data survive pump completion'
            Assert-True -Condition ($stderr[0] -eq 'E' -and $stderr[-1] -eq 'E') `
                -Because 'stderr first and last data survive pump completion'
        }
        finally {
            if (Test-Path -LiteralPath $fixtureRoot) {
                Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
            }
        }
    }

    Invoke-ContractCase 'Process bridge timeout and cancellation leave no contained OS descendants' {
        foreach ($case in @(
            @{ Name = 'timeout'; Timeout = [TimeSpan]::FromSeconds(2); CancelAfterMilliseconds = $null },
            @{ Name = 'cancellation'; Timeout = [TimeSpan]::FromSeconds(20); CancelAfterMilliseconds = 700 }
        )) {
            $fixtureRoot = Join-Path ([System.IO.Path]::GetTempPath()) "miriyum-cleanup-$($case.Name)-$([guid]::NewGuid().ToString('N'))"
            $cancellationSource = $null
            try {
                $null = New-Item -ItemType Directory -Path $fixtureRoot
                $descendantFixture = Join-Path $fixtureRoot 'descendant.ps1'
                'Start-Sleep -Seconds 120' |
                    Set-Content -LiteralPath $descendantFixture -Encoding utf8NoBOM
                $parentFixture = Join-Path $fixtureRoot 'parent.ps1'
                @'
param(
    [Parameter(Mandatory)]
    [string] $DescendantFixture,
    [Parameter(Mandatory)]
    [string] $IdentityPath
)

$executable = [System.Diagnostics.Process]::GetCurrentProcess().MainModule.FileName
$descendant = Start-Process -FilePath $executable `
    -ArgumentList @('-NoProfile', '-File', $DescendantFixture) `
    -PassThru
[pscustomobject]@{
    Pid = $descendant.Id
    StartTimeUtcTicks = $descendant.StartTime.ToUniversalTime().Ticks
} | ConvertTo-Json -Compress | Set-Content -LiteralPath $IdentityPath -Encoding utf8NoBOM
[Console]::Out.WriteLine("spawned:$($descendant.Id)")
[Console]::Error.WriteLine('parent-waiting')
Start-Sleep -Seconds 120
'@ | Set-Content -LiteralPath $parentFixture -Encoding utf8NoBOM

                $identityPath = Join-Path $fixtureRoot 'descendant.json'
                $request = New-Object 'Miriyum.Verification.ProcessRequest'
                $request.FileName = [System.Diagnostics.Process]::GetCurrentProcess().MainModule.FileName
                $request.Arguments = @(
                    '-NoProfile',
                    '-File',
                    $parentFixture,
                    '-DescendantFixture',
                    $descendantFixture,
                    '-IdentityPath',
                    $identityPath
                )
                $request.WorkingDirectory = $fixtureRoot
                $request.StdoutPath = Join-Path $fixtureRoot 'stdout.log'
                $request.StderrPath = Join-Path $fixtureRoot 'stderr.log'
                $request.Timeout = $case.Timeout
                $request.TerminationGrace = [TimeSpan]::FromSeconds(3)

                $token = [System.Threading.CancellationToken]::None
                if ($null -ne $case.CancelAfterMilliseconds) {
                    $cancellationSource = [System.Threading.CancellationTokenSource]::new()
                    $cancellationSource.CancelAfter($case.CancelAfterMilliseconds)
                    $token = $cancellationSource.Token
                }

                $result = [Miriyum.Verification.ProcessBridge]::RunAsync(
                    $request,
                    $token
                ).GetAwaiter().GetResult()

                Assert-Equal -Expected ($case.Name -eq 'timeout') -Actual $result.TimedOut `
                    -Because "$($case.Name) timeout classification"
                Assert-Equal -Expected ($case.Name -eq 'cancellation') -Actual $result.Cancelled `
                    -Because "$($case.Name) cancellation classification"
                Assert-True -Condition $result.ContainmentSucceeded `
                    -Because "$($case.Name) containment result"
                Assert-Equal -Expected 0 -Actual $result.ActiveProcessesAfterCleanup `
                    -Because "$($case.Name) active contained processes after cleanup"
                Assert-True -Condition (Test-Path -LiteralPath $identityPath -PathType Leaf) `
                    -Because "$($case.Name) fixture must prove a descendant was created"
                $identity = Get-Content -Raw -LiteralPath $identityPath | ConvertFrom-Json
                $sameProcessSurvives = $false
                try {
                    $survivor = [System.Diagnostics.Process]::GetProcessById([int] $identity.Pid)
                    $sameProcessSurvives = (
                        $survivor.StartTime.ToUniversalTime().Ticks -eq
                        [long] $identity.StartTimeUtcTicks
                    )
                }
                catch {
                    $sameProcessSurvives = $false
                }
                Assert-True -Condition (-not $sameProcessSurvives) `
                    -Because "$($case.Name) must not leave the recorded descendant alive"
                Assert-True -Condition ((Get-Content -Raw -LiteralPath $request.StdoutPath) -match 'spawned:') `
                    -Because "$($case.Name) stdout log remains after cleanup"
                Assert-True -Condition ((Get-Content -Raw -LiteralPath $request.StderrPath) -match 'parent-waiting') `
                    -Because "$($case.Name) stderr log remains after cleanup"
            }
            finally {
                if ($null -ne $cancellationSource) {
                    $cancellationSource.Dispose()
                }
                if (Test-Path -LiteralPath $fixtureRoot) {
                    Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
                }
            }
        }
    }

    Invoke-ContractCase 'Verification schedule keeps unit separate and enforces integration concurrency 1 2 and 4' {
        foreach ($parallelShards in @(1, 2, 4)) {
            $fixtureRoot = Join-Path ([System.IO.Path]::GetTempPath()) "miriyum-schedule-$parallelShards-$([guid]::NewGuid().ToString('N'))"
            try {
                $null = New-Item -ItemType Directory -Path $fixtureRoot
                $unit = New-FakeVerificationChild -FixtureRoot $fixtureRoot -Id unit -DelayMilliseconds 700
                $integrations = @(
                    New-FakeVerificationChild -FixtureRoot $fixtureRoot -Id shard-a -DelayMilliseconds 280
                    New-FakeVerificationChild -FixtureRoot $fixtureRoot -Id shard-b -DelayMilliseconds 280
                    New-FakeVerificationChild -FixtureRoot $fixtureRoot -Id shard-c -DelayMilliseconds 280
                    New-FakeVerificationChild -FixtureRoot $fixtureRoot -Id shard-d -DelayMilliseconds 280
                )

                $schedule = Invoke-VerificationSchedule `
                    -UnitChild $unit `
                    -IntegrationChildren $integrations `
                    -ParallelShards $parallelShards `
                    -ChildTimeout ([TimeSpan]::FromSeconds(20))

                Assert-True -Condition $schedule.Succeeded `
                    -Because "parallel=$parallelShards successful fake schedule"
                Assert-Equal -Expected 5 -Actual $schedule.Results.Count `
                    -Because "parallel=$parallelShards child result count"

                $intervals = @{}
                foreach ($child in @($unit) + $integrations) {
                    Assert-True -Condition (Test-Path -LiteralPath $child.TimelinePath -PathType Leaf) `
                        -Because "parallel=$parallelShards timeline for $($child.Id)"
                    $intervals[$child.Id] = Get-Content -Raw -LiteralPath $child.TimelinePath |
                        ConvertFrom-Json
                }

                $timelineEvents = foreach ($child in $integrations) {
                    $interval = $intervals[$child.Id]
                    [pscustomobject]@{ Ticks = [long] $interval.StartTicks; Delta = 1 }
                    [pscustomobject]@{ Ticks = [long] $interval.EndTicks; Delta = -1 }
                }
                $running = 0
                $observedMaximum = 0
                foreach ($timelineEvent in @($timelineEvents | Sort-Object Ticks, Delta)) {
                    $running += $timelineEvent.Delta
                    $observedMaximum = [math]::Max($observedMaximum, $running)
                }
                Assert-Equal -Expected $parallelShards -Actual $observedMaximum `
                    -Because "integration concurrency cap $parallelShards"

                $unitInterval = $intervals.unit
                $unitOverlappedIntegration = $false
                foreach ($child in $integrations) {
                    $interval = $intervals[$child.Id]
                    if (
                        [long] $interval.StartTicks -lt [long] $unitInterval.EndTicks -and
                        [long] $interval.EndTicks -gt [long] $unitInterval.StartTicks
                    ) {
                        $unitOverlappedIntegration = $true
                        break
                    }
                }
                Assert-True -Condition $unitOverlappedIntegration `
                    -Because "unit child must use a slot separate from integration cap $parallelShards"
            }
            finally {
                if (Test-Path -LiteralPath $fixtureRoot) {
                    Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
                }
            }
        }
    }

    Invoke-ContractCase 'Verification schedule is non-fast and reports conservative progress without ETA' {
        $fixtureRoot = Join-Path ([System.IO.Path]::GetTempPath()) "miriyum-schedule-failure-$([guid]::NewGuid().ToString('N'))"
        try {
            $null = New-Item -ItemType Directory -Path $fixtureRoot
            $unit = New-FakeVerificationChild -FixtureRoot $fixtureRoot -Id unit -DelayMilliseconds 300
            $integrations = @(
                New-FakeVerificationChild -FixtureRoot $fixtureRoot -Id shard-a -DelayMilliseconds 180 -ExitCode 9
                New-FakeVerificationChild -FixtureRoot $fixtureRoot -Id shard-b -DelayMilliseconds 180
                New-FakeVerificationChild -FixtureRoot $fixtureRoot -Id shard-c -DelayMilliseconds 180
                New-FakeVerificationChild -FixtureRoot $fixtureRoot -Id shard-d -DelayMilliseconds 180
            )
            $childTimeout = [TimeSpan]::FromMinutes(35)

            $schedule = Invoke-VerificationSchedule `
                -UnitChild $unit `
                -IntegrationChildren $integrations `
                -ParallelShards 1 `
                -ChildTimeout $childTimeout

            Assert-True -Condition (-not $schedule.Succeeded) `
                -Because 'one non-zero child makes the final schedule fail'
            Assert-Equal -Expected 5 -Actual $schedule.Results.Count `
                -Because 'fail-fast=false gathers every child result'
            foreach ($child in @($unit) + $integrations) {
                Assert-True -Condition (Test-Path -LiteralPath $child.TimelinePath -PathType Leaf) `
                    -Because "later queued child $($child.Id) still started"
            }
            $failed = @($schedule.Results | Where-Object { -not $_.Succeeded })
            Assert-Equal -Expected 1 -Actual $failed.Count -Because 'one failed child result'
            Assert-Equal -Expected 'shard-a' -Actual $failed[0].Id -Because 'failed child identity'
            Assert-Equal -Expected 9 -Actual $failed[0].ProcessResult.ExitCode -Because 'failed child exit code'

            Assert-True -Condition ($schedule.ProgressSnapshots.Count -ge 2) `
                -Because 'progress is captured at start and state changes'
            $initial = $schedule.ProgressSnapshots[0]
            Assert-Equal -Expected 140 -Actual $initial.RemainingTimeoutBudget.TotalMinutes `
                -Because 'four serial integration waves have a 140 minute conservative budget'
            $final = $schedule.ProgressSnapshots[-1]
            Assert-Equal -Expected 5 -Actual $final.Completed.Count -Because 'final completed set'
            Assert-Equal -Expected 0 -Actual $final.Running.Count -Because 'final running set'
            Assert-Equal -Expected 0 -Actual $final.Pending.Count -Because 'final pending set'
            Assert-Equal -Expected 0 -Actual $final.RemainingTimeoutBudget.TotalMinutes `
                -Because 'no remaining child timeout budget after completion'
            Assert-True -Condition ($final.Elapsed -is [TimeSpan]) `
                -Because 'progress exposes elapsed duration'
            Assert-True -Condition (($schedule.ProgressLines -join [Environment]::NewLine) -notmatch '(?i)ETA|estimated') `
                -Because 'progress must not invent an ETA'
        }
        finally {
            if (Test-Path -LiteralPath $fixtureRoot) {
                Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
            }
        }
    }
}

if ($Suite -contains 'Gradle') {
    Invoke-ContractCase 'Gradle init profile is runner-local and isolates the build directory' {
        $initScriptPath = Join-Path $PSScriptRoot 'backend-full-verification.init.gradle'
        Assert-True -Condition (Test-Path -LiteralPath $initScriptPath -PathType Leaf) `
            -Because 'the runner init script must exist'

        $fixtureRoot = Join-Path ([System.IO.Path]::GetTempPath()) "miriyum-gradle-$([guid]::NewGuid().ToString('N'))"
        $isolatedBuild = Join-Path $fixtureRoot 'isolated-build'
        try {
            $testSource = Join-Path $fixtureRoot 'src/test/java/ProbeTest.java'
            $null = New-Item -ItemType Directory -Path (Split-Path -Parent $testSource)
            @'
rootProject.name = 'miriyum-runner-profile-fixture'
'@ | Set-Content -LiteralPath (Join-Path $fixtureRoot 'settings.gradle') -Encoding utf8NoBOM
            @'
plugins {
    id 'java'
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation 'junit:junit:4.13.2'
}

tasks.named('test') {
    maxHeapSize = '2g'
    maxParallelForks = 3
    doFirst {
        def profile = new File(project.buildDir, 'profile.txt')
        profile.parentFile.mkdirs()
        profile.text = "${maxHeapSize}|${maxParallelForks}|${project.buildDir.absolutePath}"
    }
}
'@ | Set-Content -LiteralPath (Join-Path $fixtureRoot 'build.gradle') -Encoding utf8NoBOM
            @'
import org.junit.Test;

public class ProbeTest {
    @Test
    public void passes() {
    }
}
'@ | Set-Content -LiteralPath $testSource -Encoding utf8NoBOM

            $backendRoot = Split-Path -Parent $PSScriptRoot
            $wrapperPath = if ($IsWindows) {
                Join-Path $backendRoot 'gradlew.bat'
            }
            else {
                Join-Path $backendRoot 'gradlew'
            }

            & $wrapperPath -p $fixtureRoot test --no-daemon --rerun-tasks --console=plain | Out-Host
            Assert-Equal -Expected 0 -Actual $LASTEXITCODE -Because 'fixture without runner init'
            $defaultProfile = Get-Content -Raw -LiteralPath (Join-Path $fixtureRoot 'build/profile.txt')
            Assert-True -Condition ($defaultProfile.StartsWith('2g|3|')) `
                -Because 'ordinary Gradle execution must retain its own Test profile'

            & $wrapperPath -p $fixtureRoot test --no-daemon --rerun-tasks --console=plain `
                --init-script $initScriptPath `
                "-Dmiriyum.verification.build-dir=$isolatedBuild" | Out-Host
            Assert-Equal -Expected 0 -Actual $LASTEXITCODE -Because 'fixture with runner init'
            $runnerProfile = Get-Content -Raw -LiteralPath (Join-Path $isolatedBuild 'profile.txt')
            Assert-True -Condition ($runnerProfile.StartsWith('1g|1|')) `
                -Because 'runner init must enforce the final Test profile'
            Assert-True -Condition ($runnerProfile.EndsWith([System.IO.Path]::GetFullPath($isolatedBuild))) `
                -Because 'runner init must use the requested isolated build directory'
        }
        finally {
            if (Test-Path -LiteralPath $fixtureRoot) {
                Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
            }
        }
    }
}

if ($Suite -contains 'WindowsNative') {
    Invoke-ContractCase 'Windows native lifecycle enforces handle allowlist and assign before resume' {
        Assert-True -Condition $IsWindows -Because 'WindowsNative suite requires Windows'
        $fixtureRoot = Join-Path ([System.IO.Path]::GetTempPath()) "miriyum-native-$([guid]::NewGuid().ToString('N'))"
        try {
            $null = New-Item -ItemType Directory -Path $fixtureRoot
            $fixturePath = Join-Path $fixtureRoot 'success.ps1'
            @'
[Console]::Out.WriteLine('native-success')
[Console]::Error.WriteLine('native-stderr')
'@ | Set-Content -LiteralPath $fixturePath -Encoding utf8NoBOM

            $request = New-Object 'Miriyum.Verification.ProcessRequest'
            $request.FileName = [System.Diagnostics.Process]::GetCurrentProcess().MainModule.FileName
            $request.Arguments = @('-NoProfile', '-File', $fixturePath)
            $request.WorkingDirectory = $fixtureRoot
            $request.StdoutPath = Join-Path $fixtureRoot 'stdout.log'
            $request.StderrPath = Join-Path $fixtureRoot 'stderr.log'
            $request.Timeout = [TimeSpan]::FromSeconds(20)
            $request.TerminationGrace = [TimeSpan]::FromSeconds(2)

            $result = [Miriyum.Verification.ProcessBridge]::RunAsync(
                $request,
                [System.Threading.CancellationToken]::None
            ).GetAwaiter().GetResult()

            Assert-Equal -Expected 3 -Actual $result.InheritedHandleCount `
                -Because 'only stdin stdout and stderr child handles are allowlisted'
            Assert-True -Condition (-not $result.SentinelHandleInherited) `
                -Because 'inheritable sentinel outside the allowlist must not reach the child'
            Assert-True -Condition $result.AttributeListReleased `
                -Because 'initialized attribute list and buffer must be released'
            Assert-Equal -Expected $result.NativeStartupInfoExSize -Actual $result.NativeStartupInfoCb `
                -Because 'STARTUPINFOEX.StartupInfo.cb must equal sizeof STARTUPINFOEX'
            Assert-Equal -Expected 1 -Actual $result.ResumeCount `
                -Because 'the assigned child thread resumes exactly once'

            $expectedOrder = @(
                'CreateProcessSuspended',
                'CloseChildPipeHandles',
                'StartOutputPumps',
                'AssignProcessToJob',
                'ResumeThread',
                'CloseThreadHandle',
                'ProcessExitConfirmed',
                'PumpsCompleted',
                'ActiveProcessesZero'
            )
            $previousIndex = -1
            foreach ($eventName in $expectedOrder) {
                $eventIndex = [array]::IndexOf($result.LifecycleEvents, $eventName)
                Assert-True -Condition ($eventIndex -gt $previousIndex) `
                    -Because "native lifecycle event order for $eventName"
                $previousIndex = $eventIndex
            }
        }
        finally {
            if (Test-Path -LiteralPath $fixtureRoot) {
                Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
            }
        }
    }

    Invoke-ContractCase 'Windows assignment failure never resumes and confirms terminated process without handle growth' {
        Assert-True -Condition $IsWindows -Because 'WindowsNative suite requires Windows'
        $fixtureRoot = Join-Path ([System.IO.Path]::GetTempPath()) "miriyum-native-failure-$([guid]::NewGuid().ToString('N'))"
        try {
            $null = New-Item -ItemType Directory -Path $fixtureRoot
            $fixturePath = Join-Path $fixtureRoot 'must-not-run.ps1'
            "'resumed' | Set-Content -LiteralPath '$($fixtureRoot.Replace("'", "''"))\resumed.txt'" |
                Set-Content -LiteralPath $fixturePath -Encoding utf8NoBOM
            $request = New-Object 'Miriyum.Verification.ProcessRequest'
            $request.FileName = [System.Diagnostics.Process]::GetCurrentProcess().MainModule.FileName
            $request.Arguments = @('-NoProfile', '-File', $fixturePath)
            $request.WorkingDirectory = $fixtureRoot
            $request.StdoutPath = Join-Path $fixtureRoot 'stdout.log'
            $request.StderrPath = Join-Path $fixtureRoot 'stderr.log'
            $request.Timeout = [TimeSpan]::FromSeconds(20)
            $request.TerminationGrace = [TimeSpan]::FromSeconds(2)
            $request.InjectJobAssignmentFailure = $true

            $parent = [System.Diagnostics.Process]::GetCurrentProcess()
            $baselineHandles = $parent.HandleCount
            $result = $null
            foreach ($iteration in 1..8) {
                $result = [Miriyum.Verification.ProcessBridge]::RunAsync(
                    $request,
                    [System.Threading.CancellationToken]::None
                ).GetAwaiter().GetResult()
            }
            [GC]::Collect()
            [GC]::WaitForPendingFinalizers()
            $parent.Refresh()
            $finalHandles = $parent.HandleCount

            Assert-True -Condition $result.AssignmentFailed `
                -Because 'injected assignment failure is reported'
            Assert-Equal -Expected 0 -Actual $result.ResumeCount `
                -Because 'assignment failure must never resume the suspended thread'
            Assert-True -Condition $result.TerminateProcessCalled `
                -Because 'pre-assignment failure must call TerminateProcess'
            Assert-True -Condition $result.ProcessExitConfirmed `
                -Because 'the terminated suspended process must be waited and confirmed'
            Assert-Equal -Expected 0 -Actual $result.ActiveProcessesAfterCleanup `
                -Because 'pre-assignment failure leaves no child process'
            Assert-True -Condition (-not (Test-Path -LiteralPath (Join-Path $fixtureRoot 'resumed.txt'))) `
                -Because 'the fixture body must never execute'
            Assert-True -Condition ($finalHandles -le ($baselineHandles + 1)) `
                -Because "repeated failure cleanup must not grow parent handles: baseline=$baselineHandles final=$finalHandles"
            Assert-True -Condition (
                [array]::IndexOf($result.LifecycleEvents, 'TerminateProcess') -gt
                [array]::IndexOf($result.LifecycleEvents, 'InjectJobAssignmentFailure')
            ) -Because 'TerminateProcess follows the injected assignment failure'
            Assert-True -Condition (
                [array]::IndexOf($result.LifecycleEvents, 'ProcessExitConfirmed') -gt
                [array]::IndexOf($result.LifecycleEvents, 'TerminateProcess')
            ) -Because 'process exit is confirmed after TerminateProcess'
            Assert-Equal -Expected -1 -Actual ([array]::IndexOf($result.LifecycleEvents, 'ResumeThread')) `
                -Because 'resume is absent from the failure lifecycle'
        }
        finally {
            if (Test-Path -LiteralPath $fixtureRoot) {
                Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
            }
        }
    }

    Invoke-ContractCase 'Windows post-assignment startup failure terminates the job before returning' {
        Assert-True -Condition $IsWindows -Because 'WindowsNative suite requires Windows'
        $fixtureRoot = Join-Path ([System.IO.Path]::GetTempPath()) "miriyum-native-post-assign-$([guid]::NewGuid().ToString('N'))"
        try {
            $null = New-Item -ItemType Directory -Path $fixtureRoot
            $fixturePath = Join-Path $fixtureRoot 'must-not-resume.ps1'
            "'resumed' | Set-Content -LiteralPath '$($fixtureRoot.Replace("'", "''"))\resumed.txt'" |
                Set-Content -LiteralPath $fixturePath -Encoding utf8NoBOM
            $request = New-Object 'Miriyum.Verification.ProcessRequest'
            $request.FileName = [System.Diagnostics.Process]::GetCurrentProcess().MainModule.FileName
            $request.Arguments = @('-NoProfile', '-File', $fixturePath)
            $request.WorkingDirectory = $fixtureRoot
            $request.StdoutPath = Join-Path $fixtureRoot 'stdout.log'
            $request.StderrPath = Join-Path $fixtureRoot 'stderr.log'
            $request.Timeout = [TimeSpan]::FromSeconds(20)
            $request.TerminationGrace = [TimeSpan]::FromSeconds(2)
            $request.InjectPostAssignmentFailure = $true

            $result = [Miriyum.Verification.ProcessBridge]::RunAsync(
                $request,
                [System.Threading.CancellationToken]::None
            ).GetAwaiter().GetResult()

            Assert-Equal -Expected 0 -Actual $result.ResumeCount `
                -Because 'post-assignment startup failure occurs before resume'
            Assert-True -Condition $result.TerminateJobCalled `
                -Because 'assigned process cleanup explicitly calls TerminateJobObject'
            Assert-True -Condition $result.ProcessExitConfirmed `
                -Because 'assigned process exit is confirmed'
            Assert-Equal -Expected 0 -Actual $result.ActiveProcessesAfterCleanup `
                -Because 'job reports zero active processes'
            Assert-True -Condition (-not (Test-Path -LiteralPath (Join-Path $fixtureRoot 'resumed.txt'))) `
                -Because 'assigned suspended fixture never executes'
            Assert-True -Condition (
                [array]::IndexOf($result.LifecycleEvents, 'TerminateJob') -gt
                [array]::IndexOf($result.LifecycleEvents, 'InjectPostAssignmentFailure')
            ) -Because 'TerminateJob follows the injected post-assignment failure'
            Assert-Equal -Expected -1 -Actual ([array]::IndexOf($result.LifecycleEvents, 'ResumeThread')) `
                -Because 'resume is absent from the post-assignment failure lifecycle'
        }
        finally {
            if (Test-Path -LiteralPath $fixtureRoot) {
                Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
            }
        }
    }

    Invoke-ContractCase 'Windows batch targets use the cmd adapter and preserve logs and exit code' {
        Assert-True -Condition $IsWindows -Because 'WindowsNative suite requires Windows'
        $fixtureRoot = Join-Path ([System.IO.Path]::GetTempPath()) "miriyum-batch-$([guid]::NewGuid().ToString('N'))"
        try {
            $null = New-Item -ItemType Directory -Path $fixtureRoot
            $batchPath = Join-Path $fixtureRoot 'batch fixture.bat'
            @'
@echo off
echo batch-stdout
echo batch-stderr 1>&2
exit /b 17
'@ | Set-Content -LiteralPath $batchPath -Encoding ascii

            $request = New-Object 'Miriyum.Verification.ProcessRequest'
            $request.FileName = $batchPath
            $request.Arguments = @()
            $request.WorkingDirectory = $fixtureRoot
            $request.StdoutPath = Join-Path $fixtureRoot 'stdout.log'
            $request.StderrPath = Join-Path $fixtureRoot 'stderr.log'
            $request.Timeout = [TimeSpan]::FromSeconds(20)
            $request.TerminationGrace = [TimeSpan]::FromSeconds(2)

            $result = [Miriyum.Verification.ProcessBridge]::RunAsync(
                $request,
                [System.Threading.CancellationToken]::None
            ).GetAwaiter().GetResult()

            Assert-Equal -Expected 17 -Actual $result.ExitCode -Because 'batch exit code'
            Assert-True -Condition ($result.LifecycleEvents -contains 'CmdBatchAdapter') `
                -Because 'batch target must use cmd.exe /d /s /c adapter'
            Assert-True -Condition ((Get-Content -Raw -LiteralPath $request.StdoutPath) -match 'batch-stdout') `
                -Because 'batch stdout'
            Assert-True -Condition ((Get-Content -Raw -LiteralPath $request.StderrPath) -match 'batch-stderr') `
                -Because 'batch stderr'
        }
        finally {
            if (Test-Path -LiteralPath $fixtureRoot) {
                Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
            }
        }
    }
}

Write-Host "Contract results: passed=$script:Passed failed=$script:Failed"
if ($script:Failed -gt 0) {
    exit 1
}
