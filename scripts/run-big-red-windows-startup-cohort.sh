#!/usr/bin/env bash
set -euo pipefail

# Runs the existing interactive Windows cohort task from the Big Red host while retaining the
# host/guest execution state that the in-guest runner cannot observe. The scheduled task remains the
# UI-session owner; QGA only configures, starts, and observes it.

vm="win11-starsector"
task="StarsectorPreflightStartupCohort"
condition="preflight"
iterations=1
cooldown=0
preset="recommended"
share="/home/leo/Windows-Share/Diagnostics"
guest_repo='C:\Projects\starsector-preflight'
guest_jar='C:\Projects\starsector-preflight\preflight-cli\target\preflight.jar'
guest_runs='C:\Users\Leo\Documents\Starsector Preflight Cohorts'
guest_share='Z:\Diagnostics'
check_only=false

usage() {
    cat <<'EOF'
Usage: run-big-red-windows-startup-cohort.sh [options]
  --condition NAME       One condition accepted by run-windows-startup-cohort.ps1 (default: preflight)
  --iterations N         1-20 (default: 1)
  --cooldown-seconds N   0-600 (default: 0)
  --preset NAME          recommended or conservative (default: recommended)
  --vm NAME              libvirt domain (default: win11-starsector)
  --task NAME            Windows scheduled task name
  --share PATH           Big Red diagnostics share
  --check                Verify host, VM, guest agent, and scheduled task without launching
EOF
}

while (($#)); do
    case "$1" in
        --condition) condition="$2"; shift 2 ;;
        --iterations) iterations="$2"; shift 2 ;;
        --cooldown-seconds) cooldown="$2"; shift 2 ;;
        --preset) preset="$2"; shift 2 ;;
        --vm) vm="$2"; shift 2 ;;
        --task) task="$2"; shift 2 ;;
        --share) share="$2"; shift 2 ;;
        --check) check_only=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

case "$condition" in
    starsector|preflight|preflight-kaleidoscope|fast-rendering|preflight-fast-rendering|preflight-fast-rendering-prepared) ;;
    *) echo "Unsupported condition: $condition" >&2; exit 2 ;;
esac
[[ "$iterations" =~ ^([1-9]|1[0-9]|20)$ ]] || { echo "Iterations must be 1-20" >&2; exit 2; }
[[ "$cooldown" =~ ^([0-9]|[1-9][0-9]|[1-5][0-9][0-9]|600)$ ]] || {
    echo "Cooldown must be 0-600" >&2; exit 2;
}
[[ "$preset" == recommended || "$preset" == conservative ]] || {
    echo "Preset must be recommended or conservative" >&2; exit 2;
}
for command in virsh jq iconv base64 sensors powerprofilesctl; do
    command -v "$command" >/dev/null || { echo "Missing command: $command" >&2; exit 1; }
done
mkdir -p "$share"

qga_ps() {
    local source="$1" encoded payload started pid status exited code out err deadline
    source="\$ProgressPreference = 'SilentlyContinue'; \$ErrorActionPreference = 'Stop'; $source"
    encoded="$(printf %s "$source" | iconv -f UTF-8 -t UTF-16LE | base64 -w0)"
    payload="$(jq -nc --arg encoded "$encoded" '{execute:"guest-exec",arguments:{path:"C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",arg:["-NoProfile","-NonInteractive","-EncodedCommand",$encoded],"capture-output":true}}')"
    started="$(sudo -n virsh qemu-agent-command "$vm" "$payload")"
    pid="$(jq -er '.return.pid' <<<"$started")"
    deadline=$((SECONDS + 120))
    while ((SECONDS < deadline)); do
        status="$(sudo -n virsh qemu-agent-command "$vm" "$(jq -nc --argjson pid "$pid" '{execute:"guest-exec-status",arguments:{pid:$pid}}')")"
        exited="$(jq -r '.return.exited' <<<"$status")"
        if [[ "$exited" == true ]]; then
            out="$(jq -r '.return["out-data"] // ""' <<<"$status" | base64 -d)"
            err="$(jq -r '.return["err-data"] // ""' <<<"$status" | base64 -d)"
            code="$(jq -r '.return.exitcode // 1' <<<"$status")"
            [[ -z "$err" ]] || printf '%s\n' "$err" >&2
            printf '%s' "$out"
            return "$code"
        fi
        sleep 1
    done
    echo "Timed out waiting for QGA PowerShell pid $pid" >&2
    return 124
}

wait_for_agent() {
    local deadline=$((SECONDS + 240))
    while ((SECONDS < deadline)); do
        if sudo -n virsh qemu-agent-command "$vm" '{"execute":"guest-ping"}' >/dev/null 2>&1; then
            return 0
        fi
        sleep 5
    done
    echo "QEMU guest agent did not become ready for $vm" >&2
    return 1
}

state="$(sudo -n virsh domstate "$vm" | tr -d '\r')"
if [[ "$state" != running ]]; then
    sudo -n virsh start "$vm" >/dev/null
fi
wait_for_agent

if [[ "$check_only" == true ]]; then
    printf 'hostPowerProfile=%s\n' "$(powerprofilesctl get)"
    qga_ps "
\$scheduled = Get-ScheduledTask -TaskName '$task'
[ordered]@{vm='$vm';processorCount=[Environment]::ProcessorCount;sysMainStatus=[string](Get-Service SysMain).Status;taskState=[string]\$scheduled.State;taskArguments=\$scheduled.Actions[0].Arguments;gameProcesses=@(Get-Process java,javaw,starsector -ErrorAction SilentlyContinue).Count} | ConvertTo-Json
"
    exit 0
fi

host_power_before="$(powerprofilesctl get | tr -d '[:space:]')"
case "$host_power_before" in
    performance|balanced|power-saver) ;;
    *) echo "Unsupported host power profile: $host_power_before" >&2; exit 1 ;;
esac
powerprofilesctl set performance
host_power_during="$(powerprofilesctl get | tr -d '[:space:]')"
[[ "$host_power_during" == performance ]] || {
    echo "Could not apply the host performance profile: $host_power_during" >&2
    exit 1
}

restore_task() {
    local ps
    ps=$(cat <<EOF
\$script = "$guest_repo\\scripts\\run-windows-startup-cohort.ps1"
\$args = '-NoProfile -ExecutionPolicy Bypass -File "' + \$script + '" -PreflightJar "$guest_jar" -Iterations 1 -CooldownSeconds 0 -Conditions preflight -OptimizationPreset recommended'
Set-ScheduledTask -TaskName "$task" -Action (New-ScheduledTaskAction -Execute 'powershell.exe' -Argument \$args) | Out-Null
EOF
)
    qga_ps "$ps" >/dev/null 2>&1 || true
}

cleanup() {
    restore_task
    powerprofilesctl set "$host_power_before" >/dev/null 2>&1 || true
    if [[ -n "${temp_dir:-}" && "$temp_dir" == /tmp/preflight-windows-host.* ]]; then
        rm -rf -- "$temp_dir"
    fi
}
trap cleanup EXIT

guest_before="$(qga_ps "
\$task = Get-ScheduledTask -TaskName '$task'
if ([string]\$task.State -ne 'Ready') { throw 'Scheduled task is not Ready: ' + [string]\$task.State }
\$top = Get-CimInstance Win32_PerfFormattedData_PerfProc_Process | Where-Object { \$_.Name -notin @('_Total','Idle') -and [int64]\$_.PercentProcessorTime -ge 5 } | Sort-Object {[int64]\$_.PercentProcessorTime} -Descending | Select-Object -First 8 Name,IDProcess,PercentProcessorTime,WorkingSetPrivate,IODataBytesPersec
\$os = Get-CimInstance Win32_OperatingSystem
[ordered]@{observedAt=(Get-Date).ToString('o');processorCount=[Environment]::ProcessorCount;freePhysicalMemoryKiB=[int64]\$os.FreePhysicalMemory;sysMainStatus=[string](Get-Service SysMain).Status;taskState=[string]\$task.State;competingProcesses=@(\$top)} | ConvertTo-Json -Depth 5
")"

host_started="$(date --iso-8601=ns)"
cpu_model="$(lscpu | awk -F: '/Model name/ {sub(/^[[:space:]]+/, "", $2); print $2; exit}')"
capacities="$(for path in /sys/devices/system/cpu/cpu*/cpu_capacity; do
    [[ -r "$path" ]] || continue
    jq -nc --arg cpu "$(basename "$(dirname "$path")")" --argjson capacity "$(<"$path")" '{cpu:$cpu,capacity:$capacity}'
done | jq -s '.')"
vcpu_count="$(sudo -n virsh vcpucount "$vm" --live | tr -d '[:space:]')"
[[ "$vcpu_count" =~ ^[0-9]+$ ]] || {
    echo "Could not read the live vCPU count for $vm: $vcpu_count" >&2
    exit 1
}
vcpu_pin="$(sudo -n virsh vcpupin "$vm")"
temp_dir="$(mktemp -d /tmp/preflight-windows-host.XXXXXX)"
samples="$temp_dir/host-samples.jsonl"

average_frequency_for_capacity() {
    local wanted="$1" path capacity frequency frequency_path sum=0 count=0
    for path in /sys/devices/system/cpu/cpu*/cpu_capacity; do
        [[ -r "$path" ]] || continue
        capacity="$(<"$path")"
        [[ "$capacity" == "$wanted" ]] || continue
        frequency_path="$(dirname "$path")/cpufreq/scaling_cur_freq"
        [[ -r "$frequency_path" ]] || continue
        frequency="$(<"$frequency_path")"
        [[ "$frequency" =~ ^[0-9]+$ ]] || continue
        sum=$((sum + frequency))
        count=$((count + 1))
    done
    ((count > 0)) && awk -v sum="$sum" -v count="$count" 'BEGIN {printf "%.0f", sum / count}' || printf null
}

run_ps=$(cat <<EOF
\$script = "$guest_repo\\scripts\\run-windows-startup-cohort.ps1"
\$args = '-NoProfile -ExecutionPolicy Bypass -File "' + \$script + '" -PreflightJar "$guest_jar" -Iterations $iterations -CooldownSeconds $cooldown -Conditions $condition -OptimizationPreset $preset'
Set-ScheduledTask -TaskName "$task" -Action (New-ScheduledTaskAction -Execute 'powershell.exe' -Argument \$args) | Out-Null
Start-ScheduledTask -TaskName "$task"
Start-Sleep -Seconds 2
[ordered]@{state=[string](Get-ScheduledTask -TaskName "$task").State;arguments=(Get-ScheduledTask -TaskName "$task").Actions[0].Arguments} | ConvertTo-Json
EOF
)
qga_ps "$run_ps"
echo

run_deadline=$((SECONDS + 1800))
while :; do
    if ((SECONDS >= run_deadline)); then
        echo "Timed out waiting 30 minutes for scheduled task $task" >&2
        exit 124
    fi
    task_state="$(qga_ps "[string](Get-ScheduledTask -TaskName '$task').State" | tr -d '\r\n')"
    qemu_pid="$(pgrep -f "qemu-system.*guest=$vm" | head -n 1 || true)"
    qemu_cpu=null
    if [[ -n "$qemu_pid" ]] && command -v pidstat >/dev/null; then
        # The Average row omits the clock column, so locate process-wide %CPU relative to the
        # stable trailing CPU/Command columns instead of assuming the live-row field number.
        measured="$(pidstat -p "$qemu_pid" 1 1 | awk '/Average:/ {print $(NF - 2); exit}')"
        [[ "$measured" =~ ^[0-9]+([.][0-9]+)?$ ]] && qemu_cpu="$measured"
    else
        sleep 1
    fi
    package_temp="$(sensors 2>/dev/null | awk '/Package id 0:/ {gsub(/[+°C]/, "", $4); print $4; exit}')"
    [[ "$package_temp" =~ ^[0-9]+([.][0-9]+)?$ ]] || package_temp=null
    # Host sensors are best-effort evidence. A missing or locale-shaped numeric reading must not
    # abort an already-running Windows cohort; retain null plus the raw value instead.
    jq -nc \
        --arg observedAt "$(date --iso-8601=ns)" \
        --arg taskState "$task_state" \
        --arg qemuCpuPercent "$qemu_cpu" \
        --arg packageTempC "$package_temp" \
        --arg pCoreFrequencyKHz "$(average_frequency_for_capacity 1024)" \
        --arg eCoreFrequencyKHz "$(average_frequency_for_capacity 774)" \
        --arg lowPowerCoreFrequencyKHz "$(average_frequency_for_capacity 312)" \
        --arg loadAverage "$(cut -d' ' -f1-3 /proc/loadavg)" \
        --arg memoryAvailableKiB "$(awk '/MemAvailable/ {print $2}' /proc/meminfo)" \
        --arg swapFreeKiB "$(awk '/SwapFree/ {print $2}' /proc/meminfo)" \
        'def numberOrNull: tonumber? // null;
        {observedAt:$observedAt,taskState:$taskState,
         qemuCpuPercent:($qemuCpuPercent|numberOrNull),
         packageTempC:($packageTempC|numberOrNull),
         pCoreFrequencyKHz:($pCoreFrequencyKHz|numberOrNull),
         eCoreFrequencyKHz:($eCoreFrequencyKHz|numberOrNull),
         lowPowerCoreFrequencyKHz:($lowPowerCoreFrequencyKHz|numberOrNull),
         loadAverage:$loadAverage,
         memoryAvailableKiB:($memoryAvailableKiB|numberOrNull),
         swapFreeKiB:($swapFreeKiB|numberOrNull),
         rawNumericReadings:{qemuCpuPercent:$qemuCpuPercent,packageTempC:$packageTempC,
           pCoreFrequencyKHz:$pCoreFrequencyKHz,eCoreFrequencyKHz:$eCoreFrequencyKHz,
           lowPowerCoreFrequencyKHz:$lowPowerCoreFrequencyKHz,
           memoryAvailableKiB:$memoryAvailableKiB,swapFreeKiB:$swapFreeKiB}}' >>"$samples"
    [[ "$task_state" == Ready ]] && break
    sleep 9
done

completion="$(qga_ps "
\$info = Get-ScheduledTaskInfo -TaskName '$task'
\$latest = Get-ChildItem '$guest_runs' -Directory | Sort-Object LastWriteTime -Descending | Select-Object -First 1
\$summary = Join-Path \$latest.FullName 'summary.json'
if (\$info.LastTaskResult -ne 0) { throw 'Scheduled task failed: ' + \$info.LastTaskResult }
if (-not (Test-Path \$summary)) { throw 'Latest cohort has no summary: ' + \$latest.FullName }
\$archive = '$guest_share\\' + \$latest.Name + '.zip'
Compress-Archive -Path \$latest.FullName -DestinationPath \$archive -Force -CompressionLevel Optimal
\$top = Get-CimInstance Win32_PerfFormattedData_PerfProc_Process | Where-Object { \$_.Name -notin @('_Total','Idle') -and [int64]\$_.PercentProcessorTime -ge 5 } | Sort-Object {[int64]\$_.PercentProcessorTime} -Descending | Select-Object -First 8 Name,IDProcess,PercentProcessorTime,WorkingSetPrivate,IODataBytesPersec
\$os = Get-CimInstance Win32_OperatingSystem
[ordered]@{sessionName=\$latest.Name;summary=(Get-Content \$summary -Raw | ConvertFrom-Json);archive=[ordered]@{path=\$archive;bytes=(Get-Item \$archive).Length;sha256=(Get-FileHash \$archive -Algorithm SHA256).Hash.ToLowerInvariant()};guestAfter=[ordered]@{observedAt=(Get-Date).ToString('o');processorCount=[Environment]::ProcessorCount;freePhysicalMemoryKiB=[int64]\$os.FreePhysicalMemory;sysMainStatus=[string](Get-Service SysMain).Status;competingProcesses=@(\$top)}} | ConvertTo-Json -Depth 12
")"

session_name="$(jq -er '.sessionName' <<<"$completion")"
host_output="$share/$session_name-host.json"
jq -n \
    --arg format starsector-preflight-big-red-windows-host-v1 \
    --arg observedFrom "$host_started" \
    --arg observedTo "$(date --iso-8601=ns)" \
    --arg host "$(hostname)" \
    --arg cpuModel "$cpu_model" \
    --arg vm "$vm" \
    --arg hostPowerProfileBefore "$host_power_before" \
    --arg hostPowerProfileDuring "$host_power_during" \
    --argjson vcpuCount "$vcpu_count" \
    --arg vcpuPin "$vcpu_pin" \
    --argjson cpuCapacities "$capacities" \
    --argjson guestBefore "$guest_before" \
    --argjson completion "$completion" \
    --slurpfile samples "$samples" \
    '{format:$format,observedFrom:$observedFrom,observedTo:$observedTo,host:$host,
      cpuModel:$cpuModel,vm:$vm,hostPowerProfileBefore:$hostPowerProfileBefore,
      hostPowerProfileDuring:$hostPowerProfileDuring,vcpuCount:$vcpuCount,vcpuPin:$vcpuPin,
      cpuCapacities:$cpuCapacities,guestBefore:$guestBefore,completion:$completion,
      hostSamples:$samples}' >"$host_output"
restore_task
powerprofilesctl set "$host_power_before"
trap - EXIT
rm -rf -- "$temp_dir"
printf 'Cohort: %s\nHost fingerprint: %s\n' "$(jq -c '.summary.conditions' <<<"$completion")" "$host_output"
