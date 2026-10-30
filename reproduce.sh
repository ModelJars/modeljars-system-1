#!/usr/bin/env bash
# Runs every script the article quotes, in the order the article uses them, one log per script.
#
#   bash reproduce.sh              # logs land in ./out
#   OUT=/somewhere bash reproduce.sh
#
# The first run downloads the Harriet base (2.55 GiB) and Laya (449 MB) into ~/.modeljars and
# verifies them; the open time of the first script that touches each model includes that download.
# If TYPESAFE_API_KEY is set, the two Jev scripts under jev/ run as well, so the same questions get
# a hosted column from this client; the key is read from the environment and never written out.
set -uo pipefail
cd "$(dirname "$0")" || exit 1
OUT="${OUT:-$PWD/out}"
mkdir -p "$OUT"

{
  echo "date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "os: $(uname -srm)"
  if sysctl -n machdep.cpu.brand_string >/dev/null 2>&1; then
    echo "cpu: $(sysctl -n machdep.cpu.brand_string), $(sysctl -n hw.physicalcpu) cores / $(sysctl -n hw.logicalcpu) threads, $(( $(sysctl -n hw.memsize) / 1073741824 )) GiB"
  elif command -v lscpu >/dev/null 2>&1; then
    lscpu | grep -E 'Model name|^CPU\(s\)|Core\(s\) per socket'
    free -g | sed -n 2p
  fi
  java -version 2>&1 | head -1
  echo "jbang $(jbang version 2>/dev/null)"
} > "$OUT/ENVIRONMENT.txt"
cat "$OUT/ENVIRONMENT.txt"

failed=0
run() {
  local script=$1 log="$OUT/${1%.java}.log"
  echo "== $script -> $log"
  { time jbang "$script"; } > "$log" 2>&1
  local status=$?
  echo "exit=$status" >> "$log"
  if [ "$status" -ne 0 ]; then
    echo "   FAILED (exit $status); see the log"
    failed=1
  fi
}

run HarrietDiag.java
run HarrietHello.java
run HarrietDemoTimed.java
run LayaHello.java
run HarrietGroundingEvaluator.java
run HarrietTicketsApp.java

if [ -n "${TYPESAFE_API_KEY:-}" ]; then
  mkdir -p "$OUT/jev"
  for py in jev_column.py jev_tickets.py; do
    echo "== jev/$py -> $OUT/jev/${py%.py}.log"
    OUT="$OUT/jev" python3 "jev/$py" > "$OUT/jev/${py%.py}.log" 2>&1 || { echo "   FAILED; see the log"; failed=1; }
  done
else
  echo "== TYPESAFE_API_KEY not set; skipping the hosted Jev column"
fi

echo "done; logs in $OUT"
exit $failed
