[CmdletBinding()]
param(
    [ValidateSet('Auto', '1', '2', '4')]
    [string] $ParallelShards = 'Auto'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Initialize-ProcessBridge {
    if ('Miriyum.Verification.ProcessBridge' -as [type]) {
        return
    }

    $processBridgeSource = @'
using Microsoft.Win32.SafeHandles;
using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace Miriyum.Verification
{
    public sealed class ProcessRequest
    {
        public string FileName { get; set; }
        public string[] Arguments { get; set; } = Array.Empty<string>();
        public string WorkingDirectory { get; set; }
        public string StdoutPath { get; set; }
        public string StderrPath { get; set; }
        public string StdinText { get; set; } = String.Empty;
        public TimeSpan Timeout { get; set; } = TimeSpan.FromMinutes(35);
        public TimeSpan TerminationGrace { get; set; } = TimeSpan.FromSeconds(5);
        public bool InjectJobAssignmentFailure { get; set; }
        public bool InjectPostAssignmentFailure { get; set; }
    }

    public sealed class ProcessResult
    {
        public int ProcessId { get; set; }
        public DateTimeOffset StartedAt { get; set; }
        public DateTimeOffset ExitedAt { get; set; }
        public int ExitCode { get; set; }
        public bool TimedOut { get; set; }
        public bool Cancelled { get; set; }
        public bool ContainmentSucceeded { get; set; }
        public uint ActiveProcessesAfterCleanup { get; set; }
        public string[] LifecycleEvents { get; set; } = Array.Empty<string>();
        public string StdoutPath { get; set; }
        public string StderrPath { get; set; }
        public int InheritedHandleCount { get; set; }
        public bool SentinelHandleInherited { get; set; }
        public bool AttributeListReleased { get; set; }
        public int NativeStartupInfoExSize { get; set; }
        public int NativeStartupInfoCb { get; set; }
        public int ResumeCount { get; set; }
        public bool AssignmentFailed { get; set; }
        public bool TerminateProcessCalled { get; set; }
        public bool TerminateJobCalled { get; set; }
        public bool ProcessExitConfirmed { get; set; }
    }

    public static class ProcessBridge
    {
        private const uint CREATE_SUSPENDED = 0x00000004;
        private const uint EXTENDED_STARTUPINFO_PRESENT = 0x00080000;
        private const uint STARTF_USESTDHANDLES = 0x00000100;
        private const uint HANDLE_FLAG_INHERIT = 0x00000001;
        private const uint PROC_THREAD_ATTRIBUTE_HANDLE_LIST = 0x00020002;
        private const uint JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000;
        private const uint DUPLICATE_SAME_ACCESS = 0x00000002;
        private const uint WAIT_OBJECT_0 = 0x00000000;
        private const uint WAIT_TIMEOUT = 0x00000102;
        private const int ERROR_INVALID_HANDLE = 6;
        private const int JobObjectBasicAccountingInformation = 1;
        private const int JobObjectExtendedLimitInformation = 9;
        private const int SIGTERM = 15;
        private const int SIGKILL = 9;

        public static Task<ProcessResult> RunAsync(ProcessRequest request, CancellationToken cancellationToken)
        {
            if (request == null)
            {
                throw new ArgumentNullException(nameof(request));
            }

            return Task.Run(() =>
            {
                return RuntimeInformation.IsOSPlatform(OSPlatform.Windows)
                    ? RunWindows(request, cancellationToken)
                    : RunUnix(request, cancellationToken);
            });
        }

        private static ProcessResult RunWindows(ProcessRequest request, CancellationToken cancellationToken)
        {
            ValidateRequest(request);
            Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(request.StdoutPath)));
            Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(request.StderrPath)));

            var events = new List<string>();
            IntPtr stdinRead = IntPtr.Zero;
            IntPtr stdinWrite = IntPtr.Zero;
            IntPtr stdoutRead = IntPtr.Zero;
            IntPtr stdoutWrite = IntPtr.Zero;
            IntPtr stderrRead = IntPtr.Zero;
            IntPtr stderrWrite = IntPtr.Zero;
            IntPtr sentinel = IntPtr.Zero;
            IntPtr attributeList = IntPtr.Zero;
            IntPtr handleList = IntPtr.Zero;
            IntPtr job = IntPtr.Zero;
            PROCESS_INFORMATION processInformation = new PROCESS_INFORMATION();
            Task stdoutPump = null;
            Task stderrPump = null;
            bool processCreated = false;
            bool processAssigned = false;
            bool threadResumed = false;
            bool sentinelInherited = false;
            bool attributeListReleased = false;
            bool processExitConfirmed = false;
            bool terminateProcessCalled = false;
            bool terminateJobCalled = false;
            int resumeCount = 0;
            int startupInfoExSize = Marshal.SizeOf<STARTUPINFOEX>();

            try
            {
                CreateStandardPipe(out stdinRead, out stdinWrite, parentReads: false);
                CreateStandardPipe(out stdoutWrite, out stdoutRead, parentReads: true);
                CreateStandardPipe(out stderrWrite, out stderrRead, parentReads: true);
                sentinel = CreateInheritableSentinel();

                var startup = new STARTUPINFOEX();
                startup.StartupInfo.cb = startupInfoExSize;
                startup.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
                startup.StartupInfo.hStdInput = stdinRead;
                startup.StartupInfo.hStdOutput = stdoutWrite;
                startup.StartupInfo.hStdError = stderrWrite;
                EnsureInheritableHandle(stdinRead, "stdin");
                EnsureInheritableHandle(stdoutWrite, "stdout");
                EnsureInheritableHandle(stderrWrite, "stderr");
                EnsureInheritableHandle(sentinel, "sentinel");

                IntPtr attributeListSize = IntPtr.Zero;
                InitializeProcThreadAttributeList(IntPtr.Zero, 1, 0, ref attributeListSize);
                attributeList = Marshal.AllocHGlobal(attributeListSize);
                if (!InitializeProcThreadAttributeList(attributeList, 1, 0, ref attributeListSize))
                {
                    ThrowLastWin32("InitializeProcThreadAttributeList");
                }

                handleList = Marshal.AllocHGlobal(IntPtr.Size * 3);
                Marshal.WriteIntPtr(handleList, 0, stdinRead);
                Marshal.WriteIntPtr(handleList, IntPtr.Size, stdoutWrite);
                Marshal.WriteIntPtr(handleList, IntPtr.Size * 2, stderrWrite);
                if (!UpdateProcThreadAttribute(
                    attributeList,
                    0,
                    (IntPtr)PROC_THREAD_ATTRIBUTE_HANDLE_LIST,
                    handleList,
                    (IntPtr)(IntPtr.Size * 3),
                    IntPtr.Zero,
                    IntPtr.Zero))
                {
                    ThrowLastWin32("UpdateProcThreadAttribute");
                }
                startup.lpAttributeList = attributeList;

                string applicationName = request.FileName;
                string commandLine;
                if (IsBatchTarget(request.FileName))
                {
                    applicationName = Environment.GetEnvironmentVariable("ComSpec");
                    if (String.IsNullOrWhiteSpace(applicationName))
                    {
                        applicationName = Path.Combine(Environment.SystemDirectory, "cmd.exe");
                    }
                    commandLine = BuildCmdBatchCommandLine(
                        applicationName,
                        request.FileName,
                        request.Arguments);
                    events.Add("CmdBatchAdapter");
                }
                else
                {
                    commandLine = BuildWindowsCommandLine(request.FileName, request.Arguments);
                }

                var mutableCommandLine = new StringBuilder(commandLine);
                events.Add("CreateProcessSuspended");
                if (!CreateProcessW(
                    applicationName,
                    mutableCommandLine,
                    IntPtr.Zero,
                    IntPtr.Zero,
                    true,
                    CREATE_SUSPENDED | EXTENDED_STARTUPINFO_PRESENT,
                    IntPtr.Zero,
                    Path.GetFullPath(request.WorkingDirectory),
                    ref startup,
                    out processInformation))
                {
                    ThrowLastWin32("CreateProcessW");
                }
                processCreated = true;
                DateTimeOffset startedAt = DateTimeOffset.UtcNow;
                sentinelInherited = ProbeSentinelInheritance(
                    processInformation.hProcess,
                    sentinel);

                CloseRawHandle(ref stdinRead);
                CloseRawHandle(ref stdoutWrite);
                CloseRawHandle(ref stderrWrite);
                events.Add("CloseChildPipeHandles");

                ReleaseAttributeList(ref attributeList, ref handleList);
                attributeListReleased = true;

                stdoutPump = StartPipePump(stdoutRead, request.StdoutPath);
                stdoutRead = IntPtr.Zero;
                stderrPump = StartPipePump(stderrRead, request.StderrPath);
                stderrRead = IntPtr.Zero;
                events.Add("StartOutputPumps");

                using (var stdin = new FileStream(
                    new SafeFileHandle(stdinWrite, ownsHandle: true),
                    FileAccess.Write,
                    4096,
                    false))
                {
                    stdinWrite = IntPtr.Zero;
                    byte[] input = Console.InputEncoding.GetBytes(
                        request.StdinText ?? String.Empty);
                    stdin.Write(input, 0, input.Length);
                }

                job = CreateJobObjectW(IntPtr.Zero, null);
                if (job == IntPtr.Zero)
                {
                    ThrowLastWin32("CreateJobObjectW");
                }
                ConfigureKillOnClose(job);

                if (request.InjectJobAssignmentFailure)
                {
                    events.Add("InjectJobAssignmentFailure");
                    terminateProcessCalled = true;
                    events.Add("TerminateProcess");
                    if (!TerminateProcess(processInformation.hProcess, 1))
                    {
                        ThrowLastWin32("TerminateProcess");
                    }
                    uint failureWait = WaitForSingleObject(
                        processInformation.hProcess,
                        (uint)ToMilliseconds(request.TerminationGrace));
                    if (failureWait != WAIT_OBJECT_0)
                    {
                        throw new TimeoutException(
                            "Suspended process did not exit after TerminateProcess.");
                    }
                    processExitConfirmed = true;
                    events.Add("ProcessExitConfirmed");
                    CloseRawHandle(ref processInformation.hThread);

                    uint failureExitCode;
                    if (!GetExitCodeProcess(processInformation.hProcess, out failureExitCode))
                    {
                        ThrowLastWin32("GetExitCodeProcess");
                    }
                    Task.WaitAll(stdoutPump, stderrPump);
                    events.Add("PumpsCompleted");
                    events.Add("ActiveProcessesZero");
                    return new ProcessResult
                    {
                        ProcessId = unchecked((int)processInformation.dwProcessId),
                        StartedAt = startedAt,
                        ExitedAt = DateTimeOffset.UtcNow,
                        ExitCode = unchecked((int)failureExitCode),
                        ContainmentSucceeded = true,
                        ActiveProcessesAfterCleanup = 0,
                        LifecycleEvents = events.ToArray(),
                        StdoutPath = Path.GetFullPath(request.StdoutPath),
                        StderrPath = Path.GetFullPath(request.StderrPath),
                        InheritedHandleCount = 3,
                        SentinelHandleInherited = sentinelInherited,
                        AttributeListReleased = attributeListReleased,
                        NativeStartupInfoExSize = startupInfoExSize,
                        NativeStartupInfoCb = startup.StartupInfo.cb,
                        ResumeCount = resumeCount,
                        AssignmentFailed = true,
                        TerminateProcessCalled = terminateProcessCalled,
                        ProcessExitConfirmed = processExitConfirmed
                    };
                }

                if (!AssignProcessToJobObject(job, processInformation.hProcess))
                {
                    ThrowLastWin32("AssignProcessToJobObject");
                }
                processAssigned = true;
                events.Add("AssignProcessToJob");

                if (request.InjectPostAssignmentFailure)
                {
                    events.Add("InjectPostAssignmentFailure");
                    terminateJobCalled = true;
                    events.Add("TerminateJob");
                    if (!TerminateJobObject(job, 1))
                    {
                        ThrowLastWin32("TerminateJobObject");
                    }
                    uint startupFailureWait = WaitForSingleObject(
                        processInformation.hProcess,
                        (uint)ToMilliseconds(request.TerminationGrace));
                    if (startupFailureWait != WAIT_OBJECT_0)
                    {
                        throw new TimeoutException(
                            "Assigned suspended process did not exit after TerminateJobObject.");
                    }
                    uint activeAfterStartupFailure = WaitForJobEmpty(
                        job,
                        request.TerminationGrace);
                    processExitConfirmed = true;
                    events.Add("ProcessExitConfirmed");
                    CloseRawHandle(ref processInformation.hThread);

                    uint startupFailureExitCode;
                    if (!GetExitCodeProcess(
                        processInformation.hProcess,
                        out startupFailureExitCode))
                    {
                        ThrowLastWin32("GetExitCodeProcess");
                    }
                    Task.WaitAll(stdoutPump, stderrPump);
                    events.Add("PumpsCompleted");
                    events.Add(
                        activeAfterStartupFailure == 0
                            ? "ActiveProcessesZero"
                            : "ActiveProcessesRemaining");
                    return new ProcessResult
                    {
                        ProcessId = unchecked((int)processInformation.dwProcessId),
                        StartedAt = startedAt,
                        ExitedAt = DateTimeOffset.UtcNow,
                        ExitCode = unchecked((int)startupFailureExitCode),
                        ContainmentSucceeded = activeAfterStartupFailure == 0,
                        ActiveProcessesAfterCleanup = activeAfterStartupFailure,
                        LifecycleEvents = events.ToArray(),
                        StdoutPath = Path.GetFullPath(request.StdoutPath),
                        StderrPath = Path.GetFullPath(request.StderrPath),
                        InheritedHandleCount = 3,
                        SentinelHandleInherited = sentinelInherited,
                        AttributeListReleased = attributeListReleased,
                        NativeStartupInfoExSize = startupInfoExSize,
                        NativeStartupInfoCb = startup.StartupInfo.cb,
                        ResumeCount = resumeCount,
                        TerminateJobCalled = terminateJobCalled,
                        ProcessExitConfirmed = processExitConfirmed
                    };
                }

                uint resumeResult = ResumeThread(processInformation.hThread);
                if (resumeResult == UInt32.MaxValue)
                {
                    ThrowLastWin32("ResumeThread");
                }
                threadResumed = true;
                resumeCount++;
                events.Add("ResumeThread");
                CloseRawHandle(ref processInformation.hThread);
                events.Add("CloseThreadHandle");

                bool timedOut = false;
                bool cancelled = false;
                var stopwatch = Stopwatch.StartNew();
                while (true)
                {
                    uint waitResult = WaitForSingleObject(processInformation.hProcess, 50);
                    if (waitResult == WAIT_OBJECT_0)
                    {
                        break;
                    }
                    if (waitResult != WAIT_TIMEOUT)
                    {
                        ThrowLastWin32("WaitForSingleObject");
                    }
                    if (cancellationToken.IsCancellationRequested)
                    {
                        cancelled = true;
                        break;
                    }
                    if (stopwatch.Elapsed >= request.Timeout)
                    {
                        timedOut = true;
                        break;
                    }
                }

                if (timedOut || cancelled)
                {
                    terminateJobCalled = true;
                    events.Add("TerminateJob");
                    if (!TerminateJobObject(job, 1))
                    {
                        ThrowLastWin32("TerminateJobObject");
                    }
                    uint terminationWait = WaitForSingleObject(
                        processInformation.hProcess,
                        (uint)ToMilliseconds(request.TerminationGrace));
                    if (terminationWait != WAIT_OBJECT_0)
                    {
                        throw new TimeoutException(
                            "Contained process did not exit after TerminateJobObject.");
                    }
                }
                processExitConfirmed = true;
                events.Add("ProcessExitConfirmed");

                uint exitCode;
                if (!GetExitCodeProcess(processInformation.hProcess, out exitCode))
                {
                    ThrowLastWin32("GetExitCodeProcess");
                }
                Task.WaitAll(stdoutPump, stderrPump);
                events.Add("PumpsCompleted");
                uint activeProcesses = WaitForJobEmpty(job, request.TerminationGrace);
                events.Add(
                    activeProcesses == 0
                        ? "ActiveProcessesZero"
                        : "ActiveProcessesRemaining");

                return new ProcessResult
                {
                    ProcessId = unchecked((int)processInformation.dwProcessId),
                    StartedAt = startedAt,
                    ExitedAt = DateTimeOffset.UtcNow,
                    ExitCode = unchecked((int)exitCode),
                    TimedOut = timedOut,
                    Cancelled = cancelled,
                    ContainmentSucceeded = activeProcesses == 0,
                    ActiveProcessesAfterCleanup = activeProcesses,
                    LifecycleEvents = events.ToArray(),
                    StdoutPath = Path.GetFullPath(request.StdoutPath),
                    StderrPath = Path.GetFullPath(request.StderrPath),
                    InheritedHandleCount = 3,
                    SentinelHandleInherited = sentinelInherited,
                    AttributeListReleased = attributeListReleased,
                    NativeStartupInfoExSize = startupInfoExSize,
                    NativeStartupInfoCb = startup.StartupInfo.cb,
                    ResumeCount = resumeCount,
                    TerminateJobCalled = terminateJobCalled,
                    ProcessExitConfirmed = processExitConfirmed
                };
            }
            catch
            {
                if (processCreated)
                {
                    if (processAssigned && job != IntPtr.Zero)
                    {
                        TerminateJobObject(job, 1);
                        WaitForJobEmpty(job, request.TerminationGrace);
                    }
                    else if (!threadResumed)
                    {
                        TerminateProcess(processInformation.hProcess, 1);
                    }
                    WaitForSingleObject(
                        processInformation.hProcess,
                        (uint)ToMilliseconds(request.TerminationGrace));
                }
                throw;
            }
            finally
            {
                ReleaseAttributeList(ref attributeList, ref handleList);
                CloseRawHandle(ref stdinRead);
                CloseRawHandle(ref stdinWrite);
                CloseRawHandle(ref stdoutRead);
                CloseRawHandle(ref stdoutWrite);
                CloseRawHandle(ref stderrRead);
                CloseRawHandle(ref stderrWrite);
                CloseRawHandle(ref sentinel);
                CloseRawHandle(ref processInformation.hThread);
                CloseRawHandle(ref processInformation.hProcess);
                CloseRawHandle(ref job);
            }
        }

        private static ProcessResult RunUnix(ProcessRequest request, CancellationToken cancellationToken)
        {
            ValidateRequest(request);
            Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(request.StdoutPath)));
            Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(request.StderrPath)));

            var events = new List<string>();
            var startInfo = new ProcessStartInfo
            {
                FileName = "setsid",
                WorkingDirectory = Path.GetFullPath(request.WorkingDirectory),
                UseShellExecute = false,
                RedirectStandardInput = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                StandardInputEncoding = Encoding.UTF8,
                StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8
            };
            startInfo.ArgumentList.Add("--wait");
            startInfo.ArgumentList.Add(request.FileName);
            foreach (string argument in request.Arguments ?? Array.Empty<string>())
            {
                startInfo.ArgumentList.Add(argument);
            }

            using (var process = new Process { StartInfo = startInfo })
            {
                events.Add("StartProcessGroup");
                if (!process.Start())
                {
                    throw new InvalidOperationException("setsid process did not start.");
                }
                DateTimeOffset startedAt = DateTimeOffset.UtcNow;
                int processId = process.Id;
                Task stdoutPump = PumpReaderAsync(process.StandardOutput, request.StdoutPath);
                Task stderrPump = PumpReaderAsync(process.StandardError, request.StderrPath);
                process.StandardInput.Write(request.StdinText ?? String.Empty);
                process.StandardInput.Close();

                bool timedOut = false;
                bool cancelled = false;
                var stopwatch = Stopwatch.StartNew();
                while (!process.WaitForExit(50))
                {
                    if (cancellationToken.IsCancellationRequested)
                    {
                        cancelled = true;
                        break;
                    }
                    if (stopwatch.Elapsed >= request.Timeout)
                    {
                        timedOut = true;
                        break;
                    }
                }

                if (timedOut || cancelled)
                {
                    events.Add("TerminateProcessGroup");
                    TerminateUnixProcessGroup(processId, request.TerminationGrace);
                    process.WaitForExit(ToMilliseconds(request.TerminationGrace));
                }

                process.WaitForExit();
                Task.WaitAll(stdoutPump, stderrPump);
                uint activeProcesses = UnixProcessGroupExists(processId) ? 1u : 0u;
                events.Add("ActiveProcessesZero");
                return new ProcessResult
                {
                    ProcessId = processId,
                    StartedAt = startedAt,
                    ExitedAt = DateTimeOffset.UtcNow,
                    ExitCode = process.ExitCode,
                    TimedOut = timedOut,
                    Cancelled = cancelled,
                    ContainmentSucceeded = activeProcesses == 0,
                    ActiveProcessesAfterCleanup = activeProcesses,
                    LifecycleEvents = events.ToArray(),
                    StdoutPath = Path.GetFullPath(request.StdoutPath),
                    StderrPath = Path.GetFullPath(request.StderrPath)
                };
            }
        }

        private static void ValidateRequest(ProcessRequest request)
        {
            if (String.IsNullOrWhiteSpace(request.FileName))
            {
                throw new ArgumentException("FileName is required.", nameof(request));
            }
            if (String.IsNullOrWhiteSpace(request.WorkingDirectory))
            {
                throw new ArgumentException("WorkingDirectory is required.", nameof(request));
            }
            if (String.IsNullOrWhiteSpace(request.StdoutPath) || String.IsNullOrWhiteSpace(request.StderrPath))
            {
                throw new ArgumentException("StdoutPath and StderrPath are required.", nameof(request));
            }
            if (request.Timeout <= TimeSpan.Zero)
            {
                throw new ArgumentOutOfRangeException(nameof(request), "Timeout must be positive.");
            }
        }

        private static Task StartPipePump(IntPtr readHandle, string destinationPath)
        {
            var safeHandle = new SafeFileHandle(readHandle, ownsHandle: true);
            return Task.Run(() =>
            {
                using (safeHandle)
                using (var source = new FileStream(safeHandle, FileAccess.Read, 4096, false))
                using (var destination = new FileStream(
                    Path.GetFullPath(destinationPath),
                    FileMode.Create,
                    FileAccess.Write,
                    FileShare.Read))
                {
                    source.CopyTo(destination);
                }
            });
        }

        private static async Task PumpReaderAsync(StreamReader reader, string destinationPath)
        {
            using (reader)
            using (var destination = new StreamWriter(
                Path.GetFullPath(destinationPath),
                false,
                new UTF8Encoding(false)))
            {
                char[] buffer = new char[4096];
                int read;
                while ((read = await reader.ReadAsync(buffer, 0, buffer.Length).ConfigureAwait(false)) > 0)
                {
                    await destination.WriteAsync(buffer, 0, read).ConfigureAwait(false);
                }
            }
        }

        private static void CreateStandardPipe(
            out IntPtr childHandle,
            out IntPtr parentHandle,
            bool parentReads)
        {
            var securityAttributes = new SECURITY_ATTRIBUTES
            {
                nLength = Marshal.SizeOf<SECURITY_ATTRIBUTES>(),
                bInheritHandle = true,
                lpSecurityDescriptor = IntPtr.Zero
            };
            IntPtr readHandle;
            IntPtr writeHandle;
            if (!CreatePipe(out readHandle, out writeHandle, ref securityAttributes, 0))
            {
                ThrowLastWin32("CreatePipe");
            }

            childHandle = parentReads ? writeHandle : readHandle;
            parentHandle = parentReads ? readHandle : writeHandle;
            if (!SetHandleInformation(parentHandle, HANDLE_FLAG_INHERIT, 0))
            {
                CloseHandle(readHandle);
                CloseHandle(writeHandle);
                childHandle = IntPtr.Zero;
                parentHandle = IntPtr.Zero;
                ThrowLastWin32("SetHandleInformation");
            }
        }

        private static void EnsureInheritableHandle(IntPtr handle, string name)
        {
            uint flags;
            if (!GetHandleInformation(handle, out flags))
            {
                ThrowLastWin32("GetHandleInformation(" + name + ")");
            }
            if ((flags & HANDLE_FLAG_INHERIT) == 0)
            {
                throw new InvalidOperationException(
                    "Child-side " + name + " handle is not inheritable.");
            }
        }

        private static IntPtr CreateInheritableSentinel()
        {
            var securityAttributes = new SECURITY_ATTRIBUTES
            {
                nLength = Marshal.SizeOf<SECURITY_ATTRIBUTES>(),
                bInheritHandle = true,
                lpSecurityDescriptor = IntPtr.Zero
            };
            IntPtr sentinel = CreateEventW(
                ref securityAttributes,
                true,
                false,
                null);
            if (sentinel == IntPtr.Zero)
            {
                ThrowLastWin32("CreateEventW");
            }
            return sentinel;
        }

        private static bool ProbeSentinelInheritance(
            IntPtr childProcess,
            IntPtr sentinel)
        {
            IntPtr duplicate;
            if (!DuplicateHandle(
                childProcess,
                sentinel,
                GetCurrentProcess(),
                out duplicate,
                0,
                false,
                DUPLICATE_SAME_ACCESS))
            {
                int errorCode = Marshal.GetLastWin32Error();
                if (errorCode == ERROR_INVALID_HANDLE)
                {
                    return false;
                }
                throw new Win32Exception(
                    errorCode,
                    "DuplicateHandle sentinel probe failed.");
            }

            try
            {
                if (!SetEvent(duplicate))
                {
                    return false;
                }
                return WaitForSingleObject(sentinel, 0) == WAIT_OBJECT_0;
            }
            finally
            {
                CloseHandle(duplicate);
            }
        }

        private static void ReleaseAttributeList(
            ref IntPtr attributeList,
            ref IntPtr handleList)
        {
            if (attributeList != IntPtr.Zero)
            {
                DeleteProcThreadAttributeList(attributeList);
                Marshal.FreeHGlobal(attributeList);
                attributeList = IntPtr.Zero;
            }
            if (handleList != IntPtr.Zero)
            {
                Marshal.FreeHGlobal(handleList);
                handleList = IntPtr.Zero;
            }
        }

        private static void ConfigureKillOnClose(IntPtr job)
        {
            var information = new JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
            information.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
            int size = Marshal.SizeOf<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>();
            IntPtr buffer = Marshal.AllocHGlobal(size);
            try
            {
                Marshal.StructureToPtr(information, buffer, false);
                if (!SetInformationJobObject(job, JobObjectExtendedLimitInformation, buffer, (uint)size))
                {
                    ThrowLastWin32("SetInformationJobObject");
                }
            }
            finally
            {
                Marshal.FreeHGlobal(buffer);
            }
        }

        private static uint WaitForJobEmpty(IntPtr job, TimeSpan timeout)
        {
            var stopwatch = Stopwatch.StartNew();
            uint active;
            do
            {
                active = QueryActiveProcesses(job);
                if (active == 0)
                {
                    return 0;
                }
                Thread.Sleep(20);
            }
            while (stopwatch.Elapsed < timeout);
            return active;
        }

        private static uint QueryActiveProcesses(IntPtr job)
        {
            JOBOBJECT_BASIC_ACCOUNTING_INFORMATION information;
            uint returnedLength;
            if (!QueryInformationJobObject(
                job,
                JobObjectBasicAccountingInformation,
                out information,
                (uint)Marshal.SizeOf<JOBOBJECT_BASIC_ACCOUNTING_INFORMATION>(),
                out returnedLength))
            {
                ThrowLastWin32("QueryInformationJobObject");
            }
            return information.ActiveProcesses;
        }

        private static string BuildWindowsCommandLine(string fileName, string[] arguments)
        {
            var commandLine = new StringBuilder(QuoteWindowsArgument(fileName));
            foreach (string argument in arguments ?? Array.Empty<string>())
            {
                commandLine.Append(' ');
                commandLine.Append(QuoteWindowsArgument(argument ?? String.Empty));
            }
            return commandLine.ToString();
        }

        private static bool IsBatchTarget(string fileName)
        {
            string extension = Path.GetExtension(fileName);
            return extension.Equals(".bat", StringComparison.OrdinalIgnoreCase)
                || extension.Equals(".cmd", StringComparison.OrdinalIgnoreCase);
        }

        private static string BuildCmdBatchCommandLine(
            string commandInterpreter,
            string batchPath,
            string[] arguments)
        {
            var command = new StringBuilder();
            command.Append('"');
            command.Append(batchPath.Replace("\"", "\"\""));
            command.Append('"');
            foreach (string argument in arguments ?? Array.Empty<string>())
            {
                command.Append(' ');
                command.Append(QuoteWindowsArgument(argument ?? String.Empty));
            }
            return QuoteWindowsArgument(commandInterpreter)
                + " /d /s /c \""
                + command
                + "\"";
        }

        private static string QuoteWindowsArgument(string argument)
        {
            if (argument.Length > 0 &&
                argument.IndexOfAny(new[] { ' ', '\t', '\n', '\v', '"' }) < 0)
            {
                return argument;
            }

            var result = new StringBuilder();
            result.Append('"');
            int backslashes = 0;
            foreach (char character in argument)
            {
                if (character == '\\')
                {
                    backslashes++;
                    continue;
                }
                if (character == '"')
                {
                    result.Append('\\', backslashes * 2 + 1);
                    result.Append('"');
                    backslashes = 0;
                    continue;
                }
                result.Append('\\', backslashes);
                backslashes = 0;
                result.Append(character);
            }
            result.Append('\\', backslashes * 2);
            result.Append('"');
            return result.ToString();
        }

        private static void TerminateUnixProcessGroup(int processId, TimeSpan grace)
        {
            kill(-processId, SIGTERM);
            var stopwatch = Stopwatch.StartNew();
            while (stopwatch.Elapsed < grace)
            {
                if (!UnixProcessGroupExists(processId))
                {
                    return;
                }
                Thread.Sleep(20);
            }
            kill(-processId, SIGKILL);
        }

        private static bool UnixProcessGroupExists(int processId)
        {
            if (kill(-processId, 0) == 0)
            {
                return true;
            }
            return Marshal.GetLastWin32Error() != 3;
        }

        private static int ToMilliseconds(TimeSpan value)
        {
            if (value <= TimeSpan.Zero)
            {
                return 1;
            }
            return (int)Math.Min(Int32.MaxValue, Math.Ceiling(value.TotalMilliseconds));
        }

        private static void CloseRawHandle(ref IntPtr handle)
        {
            if (handle != IntPtr.Zero && handle != new IntPtr(-1))
            {
                CloseHandle(handle);
                handle = IntPtr.Zero;
            }
        }

        private static void ThrowLastWin32(string operation)
        {
            int errorCode = Marshal.GetLastWin32Error();
            var nativeError = new Win32Exception(errorCode);
            throw new Win32Exception(
                errorCode,
                operation + " failed with Win32 error " + errorCode + ": " + nativeError.Message);
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct SECURITY_ATTRIBUTES
        {
            public int nLength;
            public IntPtr lpSecurityDescriptor;
            [MarshalAs(UnmanagedType.Bool)]
            public bool bInheritHandle;
        }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        private struct STARTUPINFO
        {
            public int cb;
            public string lpReserved;
            public string lpDesktop;
            public string lpTitle;
            public int dwX;
            public int dwY;
            public int dwXSize;
            public int dwYSize;
            public int dwXCountChars;
            public int dwYCountChars;
            public int dwFillAttribute;
            public uint dwFlags;
            public short wShowWindow;
            public short cbReserved2;
            public IntPtr lpReserved2;
            public IntPtr hStdInput;
            public IntPtr hStdOutput;
            public IntPtr hStdError;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct STARTUPINFOEX
        {
            public STARTUPINFO StartupInfo;
            public IntPtr lpAttributeList;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct PROCESS_INFORMATION
        {
            public IntPtr hProcess;
            public IntPtr hThread;
            public uint dwProcessId;
            public uint dwThreadId;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct JOBOBJECT_BASIC_LIMIT_INFORMATION
        {
            public long PerProcessUserTimeLimit;
            public long PerJobUserTimeLimit;
            public uint LimitFlags;
            public UIntPtr MinimumWorkingSetSize;
            public UIntPtr MaximumWorkingSetSize;
            public uint ActiveProcessLimit;
            public UIntPtr Affinity;
            public uint PriorityClass;
            public uint SchedulingClass;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct IO_COUNTERS
        {
            public ulong ReadOperationCount;
            public ulong WriteOperationCount;
            public ulong OtherOperationCount;
            public ulong ReadTransferCount;
            public ulong WriteTransferCount;
            public ulong OtherTransferCount;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct JOBOBJECT_EXTENDED_LIMIT_INFORMATION
        {
            public JOBOBJECT_BASIC_LIMIT_INFORMATION BasicLimitInformation;
            public IO_COUNTERS IoInfo;
            public UIntPtr ProcessMemoryLimit;
            public UIntPtr JobMemoryLimit;
            public UIntPtr PeakProcessMemoryUsed;
            public UIntPtr PeakJobMemoryUsed;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct JOBOBJECT_BASIC_ACCOUNTING_INFORMATION
        {
            public long TotalUserTime;
            public long TotalKernelTime;
            public long ThisPeriodTotalUserTime;
            public long ThisPeriodTotalKernelTime;
            public uint TotalPageFaultCount;
            public uint TotalProcesses;
            public uint ActiveProcesses;
            public uint TotalTerminatedProcesses;
        }

        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool CreatePipe(
            out IntPtr hReadPipe,
            out IntPtr hWritePipe,
            ref SECURITY_ATTRIBUTES lpPipeAttributes,
            uint nSize);

        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool SetHandleInformation(
            IntPtr hObject,
            uint dwMask,
            uint dwFlags);

        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool GetHandleInformation(
            IntPtr hObject,
            out uint lpdwFlags);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr CreateEventW(
            ref SECURITY_ATTRIBUTES lpEventAttributes,
            [MarshalAs(UnmanagedType.Bool)] bool bManualReset,
            [MarshalAs(UnmanagedType.Bool)] bool bInitialState,
            string lpName);

        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool DuplicateHandle(
            IntPtr hSourceProcessHandle,
            IntPtr hSourceHandle,
            IntPtr hTargetProcessHandle,
            out IntPtr lpTargetHandle,
            uint dwDesiredAccess,
            [MarshalAs(UnmanagedType.Bool)] bool bInheritHandle,
            uint dwOptions);

        [DllImport("kernel32.dll")]
        private static extern IntPtr GetCurrentProcess();

        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool SetEvent(IntPtr hEvent);

        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool InitializeProcThreadAttributeList(
            IntPtr lpAttributeList,
            int dwAttributeCount,
            int dwFlags,
            ref IntPtr lpSize);

        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool UpdateProcThreadAttribute(
            IntPtr lpAttributeList,
            uint dwFlags,
            IntPtr attribute,
            IntPtr lpValue,
            IntPtr cbSize,
            IntPtr lpPreviousValue,
            IntPtr lpReturnSize);

        [DllImport("kernel32.dll")]
        private static extern void DeleteProcThreadAttributeList(IntPtr lpAttributeList);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool CreateProcessW(
            string lpApplicationName,
            StringBuilder lpCommandLine,
            IntPtr lpProcessAttributes,
            IntPtr lpThreadAttributes,
            [MarshalAs(UnmanagedType.Bool)] bool bInheritHandles,
            uint dwCreationFlags,
            IntPtr lpEnvironment,
            string lpCurrentDirectory,
            ref STARTUPINFOEX lpStartupInfo,
            out PROCESS_INFORMATION lpProcessInformation);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr CreateJobObjectW(IntPtr lpJobAttributes, string lpName);

        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool SetInformationJobObject(
            IntPtr hJob,
            int jobObjectInformationClass,
            IntPtr lpJobObjectInformation,
            uint cbJobObjectInformationLength);

        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool AssignProcessToJobObject(IntPtr hJob, IntPtr hProcess);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern uint ResumeThread(IntPtr hThread);

        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool TerminateJobObject(IntPtr hJob, uint uExitCode);

        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool TerminateProcess(IntPtr hProcess, uint uExitCode);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern uint WaitForSingleObject(IntPtr hHandle, uint dwMilliseconds);

        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool GetExitCodeProcess(IntPtr hProcess, out uint lpExitCode);

        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool QueryInformationJobObject(
            IntPtr hJob,
            int jobObjectInformationClass,
            out JOBOBJECT_BASIC_ACCOUNTING_INFORMATION lpJobObjectInformation,
            uint cbJobObjectInformationLength,
            out uint lpReturnLength);

        [DllImport("kernel32.dll")]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool CloseHandle(IntPtr hObject);

        [DllImport("libc", SetLastError = true)]
        private static extern int kill(int pid, int signal);
    }
}
'@

    Add-Type -TypeDefinition $processBridgeSource -Language CSharp
}

Initialize-ProcessBridge

function Resolve-ParallelShardSelection {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [ValidateSet('Auto', '1', '2', '4')]
        [string] $Requested,

        [AllowNull()]
        [Nullable[long]] $AvailableBytes,

        [AllowNull()]
        [Nullable[int]] $LogicalProcessors
    )

    $warnings = [System.Collections.Generic.List[string]]::new()

    if ($Requested -ne 'Auto') {
        $requestedValue = [int] $Requested
        $minimums = @{
            1 = @{ Bytes = 8GB; MemoryLabel = '8GB'; Processors = 1 }
            2 = @{ Bytes = 12GB; MemoryLabel = '12GB'; Processors = 4 }
            4 = @{ Bytes = 20GB; MemoryLabel = '20GB'; Processors = 8 }
        }
        $minimum = $minimums[$requestedValue]
        if ($null -eq $AvailableBytes -or $null -eq $LogicalProcessors) {
            $warnings.Add("Resource detection failed; explicit ParallelShards=$requestedValue cannot be compared with its Auto threshold.")
        }
        elseif ($AvailableBytes -lt $minimum.Bytes -or $LogicalProcessors -lt $minimum.Processors) {
            $warnings.Add(
                "Explicit ParallelShards=$requestedValue is below its Auto threshold of $($minimum.MemoryLabel) RAM and $($minimum.Processors) logical processors; the override will still be applied."
            )
        }
        return [pscustomobject]@{
            Value = $requestedValue
            Reason = 'Explicit override'
            Warnings = $warnings.ToArray()
        }
    }

    $warnings.Add(
        'Auto does not observe Docker Engine or Docker Desktop WSL2 VM memory limits; use an explicit override when Docker has a separate lower limit.'
    )

    if ($null -eq $AvailableBytes -or $null -eq $LogicalProcessors) {
        $warnings.Add('Resource detection failed; Auto=1 is a minimum parallel fallback, not a safety guarantee.')
        return [pscustomobject]@{
            Value = 1
            Reason = 'Resource detection failed'
            Warnings = $warnings.ToArray()
        }
    }

    if ($AvailableBytes -ge 20GB -and $LogicalProcessors -ge 8) {
        $value = 4
    }
    elseif ($AvailableBytes -ge 12GB -and $LogicalProcessors -ge 4) {
        $value = 2
    }
    else {
        $value = 1
    }

    if ($value -eq 1) {
        $warnings.Add('Auto=1 is a minimum parallel fallback, not a safety guarantee; the host may remain memory constrained.')
    }

    return [pscustomobject]@{
        Value = $value
        Reason = "Auto from available RAM=$AvailableBytes bytes and logical CPU=$LogicalProcessors"
        Warnings = $warnings.ToArray()
    }
}

function Read-TestResultSummary {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string] $ResultDirectory
    )

    if (-not (Test-Path -LiteralPath $ResultDirectory -PathType Container)) {
        throw "Test result directory does not exist: $ResultDirectory"
    }

    $reportFiles = @(Get-ChildItem -LiteralPath $ResultDirectory -Filter '*.xml' -File)
    if ($reportFiles.Count -eq 0) {
        throw "Test result XML was not found: $ResultDirectory"
    }

    $totals = @{
        Tests = 0L
        Failures = 0L
        Errors = 0L
        Skipped = 0L
    }

    foreach ($reportFile in $reportFiles) {
        try {
            [xml] $document = Get-Content -Raw -LiteralPath $reportFile.FullName
        }
        catch {
            throw "Malformed test result XML '$($reportFile.FullName)': $($_.Exception.Message)"
        }

        $rootName = $document.DocumentElement.LocalName
        $suiteNodes = if ($rootName -eq 'testsuite') {
            @($document.DocumentElement)
        }
        elseif ($rootName -eq 'testsuites') {
            @($document.DocumentElement.SelectNodes('./testsuite'))
        }
        else {
            throw "Unexpected test result root '$rootName' in '$($reportFile.FullName)'."
        }

        if ($suiteNodes.Count -eq 0) {
            throw "Test result XML has no testsuite: $($reportFile.FullName)"
        }

        foreach ($suiteNode in $suiteNodes) {
            foreach ($attributeName in @('tests', 'failures', 'errors', 'skipped')) {
                $attribute = $suiteNode.Attributes[$attributeName]
                [long] $value = 0
                if ($null -eq $attribute -or -not [long]::TryParse($attribute.Value, [ref] $value) -or $value -lt 0) {
                    throw "Test result attribute '$attributeName' is missing or invalid in '$($reportFile.FullName)'."
                }

                switch ($attributeName) {
                    'tests' { $totals.Tests += $value }
                    'failures' { $totals.Failures += $value }
                    'errors' { $totals.Errors += $value }
                    'skipped' { $totals.Skipped += $value }
                }
            }
        }
    }

    if ($totals.Tests -le 0) {
        throw "Test result XML reports zero tests: $ResultDirectory"
    }
    if ($totals.Failures -gt 0 -or $totals.Errors -gt 0 -or $totals.Skipped -gt 0) {
        throw "Test result XML is not successful: tests=$($totals.Tests) failures=$($totals.Failures) errors=$($totals.Errors) skipped=$($totals.Skipped)"
    }

    return [pscustomobject]@{
        FileCount = $reportFiles.Count
        Tests = $totals.Tests
        Failures = $totals.Failures
        Errors = $totals.Errors
        Skipped = $totals.Skipped
    }
}

function Get-RemainingTimeoutBudget {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [ValidateRange(0, 4)]
        [int] $PendingCount,

        [Parameter(Mandatory)]
        [ValidateRange(0, 4)]
        [int] $RunningIntegrationCount,

        [Parameter(Mandatory)]
        [ValidateSet(1, 2, 4)]
        [int] $ParallelShards,

        [Parameter(Mandatory)]
        [TimeSpan] $ChildTimeout
    )

    $runningWave = if ($RunningIntegrationCount -gt 0) { 1 } else { 0 }
    $pendingWaves = [math]::Ceiling($PendingCount / [double] $ParallelShards)
    return [TimeSpan]::FromTicks($ChildTimeout.Ticks * ($runningWave + $pendingWaves))
}

function New-GradleChildDefinition {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [ValidateNotNullOrEmpty()]
        [string] $Id,

        [Parameter(Mandatory)]
        [ValidateNotNullOrEmpty()]
        [string[]] $Tasks,

        [Parameter(Mandatory)]
        [ValidateNotNullOrEmpty()]
        [string] $ReportTask,

        [Parameter(Mandatory)]
        [ValidateNotNullOrEmpty()]
        [string] $BackendRoot,

        [Parameter(Mandatory)]
        [ValidateNotNullOrEmpty()]
        [string] $RunRoot
    )

    $normalizedBackendRoot = [System.IO.Path]::GetFullPath($BackendRoot)
    $normalizedRunRoot = [System.IO.Path]::GetFullPath($RunRoot)
    $buildDirectory = [System.IO.Path]::GetFullPath(
        (Join-Path (Join-Path $normalizedRunRoot 'build') $Id)
    )
    $projectCacheDirectory = [System.IO.Path]::GetFullPath(
        (Join-Path (Join-Path $normalizedRunRoot 'project-cache') $Id)
    )
    $logDirectory = Join-Path $normalizedRunRoot 'logs'
    $stdoutPath = [System.IO.Path]::GetFullPath(
        (Join-Path $logDirectory "$Id.stdout.log")
    )
    $stderrPath = [System.IO.Path]::GetFullPath(
        (Join-Path $logDirectory "$Id.stderr.log")
    )
    $resultDirectory = [System.IO.Path]::GetFullPath(
        (Join-Path (Join-Path $buildDirectory 'test-results') $ReportTask)
    )
    $initScriptPath = [System.IO.Path]::GetFullPath(
        (Join-Path $PSScriptRoot 'backend-full-verification.init.gradle')
    )
    $executable = if ($IsWindows) {
        Join-Path $normalizedBackendRoot 'gradlew.bat'
    }
    else {
        Join-Path $normalizedBackendRoot 'gradlew'
    }
    $arguments = @(
        '--no-daemon'
        '--rerun-tasks'
        '--console=plain'
        '--init-script'
        $initScriptPath
        '--project-cache-dir'
        $projectCacheDirectory
        '-Dorg.gradle.jvmargs=-Xmx1g'
        "-Dmiriyum.verification.build-dir=$buildDirectory"
    ) + @($Tasks)

    return [pscustomobject]@{
        Id = $Id
        Tasks = @($Tasks)
        ReportTask = $ReportTask
        Executable = [System.IO.Path]::GetFullPath($executable)
        BuildDirectory = $buildDirectory
        ProjectCacheDirectory = $projectCacheDirectory
        StdoutPath = $stdoutPath
        StderrPath = $stderrPath
        ResultDirectory = $resultDirectory
        Arguments = $arguments
    }
}

function New-BackendVerificationChildDefinitions {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string] $BackendRoot,

        [Parameter(Mandatory)]
        [string] $RunRoot
    )

    $unit = New-GradleChildDefinition `
        -Id unit `
        -Tasks @('test', 'assemble') `
        -ReportTask test `
        -BackendRoot $BackendRoot `
        -RunRoot $RunRoot
    $integrations = @(
        New-GradleChildDefinition `
            -Id shard-a `
            -Tasks @('integrationTestShardA') `
            -ReportTask integrationTestShardA `
            -BackendRoot $BackendRoot `
            -RunRoot $RunRoot
        New-GradleChildDefinition `
            -Id shard-b `
            -Tasks @('integrationTestShardB') `
            -ReportTask integrationTestShardB `
            -BackendRoot $BackendRoot `
            -RunRoot $RunRoot
        New-GradleChildDefinition `
            -Id shard-c `
            -Tasks @('integrationTestShardC') `
            -ReportTask integrationTestShardC `
            -BackendRoot $BackendRoot `
            -RunRoot $RunRoot
        New-GradleChildDefinition `
            -Id shard-d `
            -Tasks @('integrationTestShardD') `
            -ReportTask integrationTestShardD `
            -BackendRoot $BackendRoot `
            -RunRoot $RunRoot
    )

    return [pscustomobject]@{
        Unit = $unit
        Integrations = $integrations
    }
}

function Invoke-VerificationSchedule {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        $UnitChild,

        [Parameter(Mandatory)]
        [object[]] $IntegrationChildren,

        [Parameter(Mandatory)]
        [ValidateSet(1, 2, 4)]
        [int] $ParallelShards,

        [Parameter(Mandatory)]
        [TimeSpan] $ChildTimeout,

        [System.Threading.CancellationToken] $CancellationToken =
            [System.Threading.CancellationToken]::None
    )

    $pendingIntegrations = [System.Collections.Generic.Queue[object]]::new()
    foreach ($child in $IntegrationChildren) {
        $pendingIntegrations.Enqueue($child)
    }

    $runningIntegrations = [System.Collections.Generic.List[object]]::new()
    $completedResults = [System.Collections.Generic.List[object]]::new()
    $progressSnapshots = [System.Collections.Generic.List[object]]::new()
    $progressLines = [System.Collections.Generic.List[string]]::new()
    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()

    function Start-ScheduledChild {
        param($Child)

        $request = [Miriyum.Verification.ProcessRequest]::new()
        $request.FileName = $Child.Executable
        $request.Arguments = @($Child.Arguments)
        $request.WorkingDirectory = Split-Path -Parent $Child.Executable
        $request.StdoutPath = $Child.StdoutPath
        $request.StderrPath = $Child.StderrPath
        $request.Timeout = $ChildTimeout
        $request.TerminationGrace = [TimeSpan]::FromSeconds(5)
        return [pscustomobject]@{
            Child = $Child
            Task = [Miriyum.Verification.ProcessBridge]::RunAsync(
                $request,
                $CancellationToken
            )
        }
    }

    function Complete-ScheduledChild {
        param($RunningChild)

        $processResult = $null
        $processError = $null
        try {
            $processResult = $RunningChild.Task.GetAwaiter().GetResult()
        }
        catch {
            $processError = $_.Exception.Message
        }

        $testSummary = $null
        $reportError = $null
        try {
            $testSummary = Read-TestResultSummary -ResultDirectory $RunningChild.Child.ResultDirectory
        }
        catch {
            $reportError = $_.Exception.Message
        }

        $succeeded = (
            $null -ne $processResult -and
            $processResult.ExitCode -eq 0 -and
            -not $processResult.TimedOut -and
            -not $processResult.Cancelled -and
            $processResult.ContainmentSucceeded -and
            $null -ne $testSummary
        )
        $failures = @(
            if ($null -ne $processError) { $processError }
            if ($null -ne $processResult -and $processResult.ExitCode -ne 0) {
                "ExitCode=$($processResult.ExitCode)"
            }
            if ($null -ne $processResult -and $processResult.TimedOut) { 'Timed out' }
            if ($null -ne $processResult -and $processResult.Cancelled) { 'Cancelled' }
            if ($null -ne $processResult -and -not $processResult.ContainmentSucceeded) {
                "Containment cleanup failed: active=$($processResult.ActiveProcessesAfterCleanup)"
            }
            if ($null -ne $reportError) { $reportError }
        )

        return [pscustomobject]@{
            Id = $RunningChild.Child.Id
            Definition = $RunningChild.Child
            ProcessResult = $processResult
            TestSummary = $testSummary
            Succeeded = $succeeded
            Failure = $failures -join '; '
        }
    }

    function Add-ProgressSnapshot {
        $runningIds = @(
            if ($null -ne $runningUnit) {
                $runningUnit.Child.Id
            }
            foreach ($entry in $runningIntegrations) {
                $entry.Child.Id
            }
        )
        $pendingIds = @(
            foreach ($entry in $pendingIntegrations.ToArray()) {
                $entry.Id
            }
        )
        $completedIds = @($completedResults | ForEach-Object { $_.Id })
        $remainingBudget = Get-RemainingTimeoutBudget -PendingCount $pendingIntegrations.Count -RunningIntegrationCount $runningIntegrations.Count -ParallelShards $ParallelShards -ChildTimeout $ChildTimeout
        $snapshot = [pscustomobject]@{
            Completed = $completedIds
            Running = $runningIds
            Pending = $pendingIds
            Elapsed = $stopwatch.Elapsed
            RemainingTimeoutBudget = $remainingBudget
        }
        $line = (
            "Progress completed=[{0}] running=[{1}] pending=[{2}] " +
            "elapsed={3:c} remaining-timeout-budget={4:c}"
        ) -f (
            $completedIds -join ','
        ), (
            $runningIds -join ','
        ), (
            $pendingIds -join ','
        ), $snapshot.Elapsed, $remainingBudget
        $null = $progressSnapshots.Add($snapshot)
        $null = $progressLines.Add($line)
        Write-Host $line
    }

    $runningUnit = Start-ScheduledChild -Child $UnitChild
    while (
        $runningIntegrations.Count -lt $ParallelShards -and
        $pendingIntegrations.Count -gt 0
    ) {
        $null = $runningIntegrations.Add(
            (Start-ScheduledChild -Child $pendingIntegrations.Dequeue())
        )
    }
    Add-ProgressSnapshot

    while (
        $null -ne $runningUnit -or
        $runningIntegrations.Count -gt 0 -or
        $pendingIntegrations.Count -gt 0
    ) {
        $runningEntries = @(
            if ($null -ne $runningUnit) {
                $runningUnit
            }
            foreach ($entry in $runningIntegrations) {
                $entry
            }
        )
        $tasks = [System.Threading.Tasks.Task[]] @(
            $runningEntries | ForEach-Object { $_.Task }
        )
        $completedTask = [System.Threading.Tasks.Task]::WhenAny($tasks).
            GetAwaiter().
            GetResult()
        $finishedEntries = @(
            $runningEntries | Where-Object {
                $_.Task -eq $completedTask -or $_.Task.IsCompleted
            }
        )
        foreach ($finishedEntry in $finishedEntries) {
            $null = $completedResults.Add(
                (Complete-ScheduledChild -RunningChild $finishedEntry)
            )
            if (
                $null -ne $runningUnit -and
                $finishedEntry -eq $runningUnit
            ) {
                $runningUnit = $null
            }
            else {
                $null = $runningIntegrations.Remove($finishedEntry)
            }
        }

        while (
            $runningIntegrations.Count -lt $ParallelShards -and
            $pendingIntegrations.Count -gt 0
        ) {
            $null = $runningIntegrations.Add(
                (Start-ScheduledChild -Child $pendingIntegrations.Dequeue())
            )
        }
        Add-ProgressSnapshot
    }

    $stopwatch.Stop()
    $results = @($completedResults.ToArray())
    return [pscustomobject]@{
        Results = $results
        Succeeded = @($results | Where-Object { -not $_.Succeeded }).Count -eq 0
        ProgressSnapshots = @($progressSnapshots.ToArray())
        ProgressLines = @($progressLines.ToArray())
        Elapsed = $stopwatch.Elapsed
    }
}

function Get-AvailablePhysicalMemoryBytes {
    [CmdletBinding()]
    param()

    try {
        if ($IsWindows) {
            $operatingSystem = Get-CimInstance -ClassName Win32_OperatingSystem
            if ($null -eq $operatingSystem.FreePhysicalMemory) {
                return $null
            }
            return [long] $operatingSystem.FreePhysicalMemory * 1KB
        }

        $memAvailable = Get-Content -LiteralPath '/proc/meminfo' |
            Where-Object { $_ -match '^MemAvailable:\s+(\d+)\s+kB$' } |
            Select-Object -First 1
        if ($null -eq $memAvailable) {
            return $null
        }
        return [long] $Matches[1] * 1KB
    }
    catch {
        return $null
    }
}

function Format-VerificationChildSummary {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        $Result
    )

    $process = $Result.ProcessResult
    $summary = $Result.TestSummary
    $elapsed = if ($null -eq $process) {
        'unknown'
    }
    else {
        ($process.ExitedAt - $process.StartedAt).ToString('c')
    }

    return (
        (
            "Child id={0} tasks=[{1}] succeeded={2} pid={3} started={4} ended={5} " +
            "elapsed={6} exit={7} tests={8} failures={9} errors={10} skipped={11} " +
            "stdout={12} stderr={13} report={14} failure={15}"
        ) -f (
            $Result.Id
        ), (
            $Result.Definition.Tasks -join ','
        ), $Result.Succeeded, $(
            if ($null -eq $process) { 'unknown' } else { $process.ProcessId }
        ), $(
            if ($null -eq $process) { 'unknown' } else { $process.StartedAt }
        ), $(
            if ($null -eq $process) { 'unknown' } else { $process.ExitedAt }
        ), $elapsed, $(
            if ($null -eq $process) { 'unknown' } else { $process.ExitCode }
        ), $(
            if ($null -eq $summary) { 'unknown' } else { $summary.Tests }
        ), $(
            if ($null -eq $summary) { 'unknown' } else { $summary.Failures }
        ), $(
            if ($null -eq $summary) { 'unknown' } else { $summary.Errors }
        ), $(
            if ($null -eq $summary) { 'unknown' } else { $summary.Skipped }
        ), $Result.Definition.StdoutPath, $Result.Definition.StderrPath,
            $Result.Definition.ResultDirectory, $Result.Failure
    )
}

function Invoke-BackendFullVerification {
    [CmdletBinding()]
    param(
        [ValidateSet('Auto', '1', '2', '4')]
        [string] $RequestedParallelShards = 'Auto',

        [string] $BackendRoot = (Split-Path -Parent $PSScriptRoot),

        [AllowEmptyString()]
        [string] $RunRoot = ''
    )

    $normalizedBackendRoot = [System.IO.Path]::GetFullPath($BackendRoot)
    if ([string]::IsNullOrWhiteSpace($RunRoot)) {
        $runId = "{0}-{1}" -f (
            [DateTimeOffset]::Now.ToString('yyyyMMddTHHmmss')
        ), ([guid]::NewGuid().ToString('N'))
        $RunRoot = Join-Path $normalizedBackendRoot "build/local-verification/$runId"
    }
    $normalizedRunRoot = [System.IO.Path]::GetFullPath($RunRoot)

    $availableBytes = Get-AvailablePhysicalMemoryBytes
    $logicalProcessors = try {
        [int] [Environment]::ProcessorCount
    }
    catch {
        $null
    }
    $selection = Resolve-ParallelShardSelection `
        -Requested $RequestedParallelShards `
        -AvailableBytes $availableBytes `
        -LogicalProcessors $logicalProcessors

    Write-Host (
        "ParallelShards={0} reason={1} available-bytes={2} logical-cpu={3}" -f
        $selection.Value,
        $selection.Reason,
        $(if ($null -eq $availableBytes) { 'unknown' } else { $availableBytes }),
        $(if ($null -eq $logicalProcessors) { 'unknown' } else { $logicalProcessors })
    )
    foreach ($warning in $selection.Warnings) {
        Write-Warning $warning
    }
    Write-Host "Run root: $normalizedRunRoot"

    $definitions = New-BackendVerificationChildDefinitions `
        -BackendRoot $normalizedBackendRoot `
        -RunRoot $normalizedRunRoot
    $childTimeout = [TimeSpan]::FromMinutes(35)
    $schedule = Invoke-VerificationSchedule `
        -UnitChild $definitions.Unit `
        -IntegrationChildren $definitions.Integrations `
        -ParallelShards $selection.Value `
        -ChildTimeout $childTimeout

    foreach ($result in $schedule.Results) {
        Write-Host (Format-VerificationChildSummary -Result $result)
    }
    Write-Host (
        "Verification complete succeeded={0} elapsed={1:c} parallel-shards={2}" -f
        $schedule.Succeeded,
        $schedule.Elapsed,
        $selection.Value
    )

    return [pscustomobject]@{
        Succeeded = $schedule.Succeeded
        Selection = $selection
        RunRoot = $normalizedRunRoot
        Results = $schedule.Results
        ProgressSnapshots = $schedule.ProgressSnapshots
        ProgressLines = $schedule.ProgressLines
        Elapsed = $schedule.Elapsed
    }
}

if ($MyInvocation.InvocationName -ne '.') {
    try {
        $verification = Invoke-BackendFullVerification `
            -RequestedParallelShards $ParallelShards
        if (-not $verification.Succeeded) {
            exit 1
        }
    }
    catch {
        Write-Error $_
        exit 1
    }
}
