#!/usr/bin/env bash

set -euo pipefail
shopt -s nullglob

files=(
  hs_err_pid*.log
  target/surefire-reports/*.dump
  target/surefire-reports/*.dumpstream
  target/sanitizer/asan.*
  target/sanitizer/ubsan.*
)

if [ "${#files[@]}" -ne 0 ]; then
  echo "::error::Sanitizer or native/JVM crash diagnostics were generated:"
  printf '  %s\n' "${files[@]}"
  exit 1
fi
