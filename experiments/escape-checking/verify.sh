#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

scala_cli="${SCALA_CLI:-scala-cli}"
work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

echo "Running accepted capture-aware examples"
"$scala_cli" run project.scala src/Dsl.scala src/Accepted.scala --server=false

echo "Reproducing the legacy unchecked behavior"
"$scala_cli" run project.scala src/Dsl.scala legacy/Unchecked.scala --server=false

for source in negative/*.scala; do
  name="$(basename "$source" .scala)"
  diagnostic="$work_dir/$name.log"

  echo "Checking that $source is rejected"
  if "$scala_cli" compile project.scala src/Dsl.scala "$source" --server=false >"$diagnostic" 2>&1; then
    echo "ERROR: $source compiled, but failure was expected" >&2
    exit 1
  fi

  if ! grep -Eq 'except\[Control\]|outlives its scope|cannot flow into capture set' "$diagnostic"; then
    echo "ERROR: $source failed for an unexpected reason" >&2
    sed -n '1,160p' "$diagnostic" >&2
    exit 1
  fi
done

echo "All escape-checking experiments behaved as expected"

