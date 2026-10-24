#!/usr/bin/env bash
# CI-side watchdog (copied into the debug branch by bin/ci-run).
# watchdog.sh <outdir> -- <command...>
# Runs the command, declares a hang after STALL_S seconds without output or
# TIMEOUT_S seconds total, snapshots the native test process (and jstacks the
# JVMs) BEFORE killing anything, and writes <outdir>/result.json.
set -uo pipefail
out=$1; shift; [[ $1 == -- ]] && shift
mkdir -p "$out/dumps"
STALL_S=${STALL_S:-300}; TIMEOUT_S=${TIMEOUT_S:-2400}
here=$(cd "$(dirname "$0")" && pwd)
log=$out/test.log
t0=$(date +%s)
if [[ -n ${CPUS:-} ]]; then set -- taskset -c "$CPUS" "$@"; fi
echo "[watchdog] nproc=$(nproc) CPUS=${CPUS:-all} cmd: $*" | tee "$log"
setsid "$@" > >(tee -a "$log") 2>&1 &
pid=$!
outcome= reason= cpu=
descendants() { local p; for p in $(pgrep -P "$1"); do echo "$p"; descendants "$p"; done; }
native_pid() {  # non-JVM, non-wrapper descendant with most threads
  local best= bn=0 p c n
  for p in $(descendants "$pid"); do
    c=$(cat /proc/$p/comm 2>/dev/null) || continue
    case $c in java|sbt|bash|sh|tee|timeout|taskset|stress-ng) continue;; esac
    n=$(ls /proc/$p/task 2>/dev/null | wc -l); (( n > bn )) && { best=$p; bn=$n; }
  done; echo "$best"
}
while kill -0 $pid 2>/dev/null; do
  sleep 5
  now=$(date +%s); silent=$(( now - $(stat -c %Y "$log") ))
  if (( now - t0 > TIMEOUT_S )); then reason=timeout; elif (( silent > STALL_S )); then reason="stall>${STALL_S}s"; fi
  if [[ -n $reason ]]; then
    outcome=hang
    np=$(native_pid)
    echo "[watchdog] HANG ($reason), native pid=${np:-none}" | tee -a "$out/watchdog.txt"
    ps -eo pid,ppid,stat,pcpu,nlwp,etime,args --forest | cut -c1-250 > "$out/ps-forest.txt"
    top -b -n1 > "$out/top-all.txt"
    for j in $(pgrep -x java); do jcmd $j Thread.print > "$out/dumps/jstack-$j.txt" 2>&1; done
    if [[ -n $np ]]; then
      sudo -E env "PATH=$PATH" HOME=$HOME bash "$here/snapshot" "$np" "$out/dumps/native" 2>"$out/snapshot.log"
      cpu=$(cat "$out/dumps/native/cpu_pct" 2>/dev/null)
      sudo chown -R "$(id -u)" "$out/dumps"
    fi
    kill -KILL -- -$pid 2>/dev/null; pkill -KILL -P $pid 2>/dev/null
    break
  fi
done
wait $pid; rc=$?
[[ -z $outcome ]] && { (( rc == 0 )) && outcome=pass || outcome=fail; }
secs=$(( $(date +%s) - t0 ))
last=$(grep -v '^\s*$' "$log" | tail -1 | cut -c1-300)
jq -n --arg outcome "$outcome" --argjson exit "$rc" --argjson seconds "$secs" --arg reason "$reason" \
  --arg cpu "$cpu" --arg last "$last" --arg nproc "$(nproc)" --arg cpus "${CPUS:-all}" \
  '{outcome:$outcome, exit:$exit, seconds:$seconds, hang_reason:$reason, hang_cpu_pct:$cpu, last_line:$last, nproc_runner:$nproc, cpus:$cpus}' \
  > "$out/result.json"
cat "$out/result.json"
# compress the core so artifacts stay small
for c in "$out"/dumps/native/core.*; do [[ -f $c ]] && zstd -q --rm -T0 "$c"; done
exit 0
