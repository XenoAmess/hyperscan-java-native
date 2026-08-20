#!/bin/bash

set -euo pipefail

SANITIZER="${SANITIZER:?SANITIZER must be address or undefined}"
export SANITIZER
export DETECTED_PLATFORM="${DETECTED_PLATFORM:-linux-x86_64-baseline}"

./build.sh

mkdir -p target/sanitizer
case "$SANITIZER" in
  address)
    RUNTIME=$(clang -print-file-name=libclang_rt.asan-x86_64.so)
    if [ ! -f "$RUNTIME" ]; then
      RUNTIME=$(gcc -print-file-name=libasan.so)
    fi
    export LD_PRELOAD="$RUNTIME${LD_PRELOAD:+:$LD_PRELOAD}"
    export ASAN_OPTIONS="abort_on_error=1:detect_leaks=0:halt_on_error=1:log_path=$PWD/target/sanitizer/asan:strict_string_checks=1"
    ;;
  undefined)
    RUNTIME=$(clang -print-file-name=libclang_rt.ubsan_standalone-x86_64.so)
    if [ ! -f "$RUNTIME" ]; then
      RUNTIME=$(gcc -print-file-name=libubsan.so)
    fi
    export LD_PRELOAD="$RUNTIME${LD_PRELOAD:+:$LD_PRELOAD}"
    export UBSAN_OPTIONS="halt_on_error=1:print_stacktrace=1:log_path=$PWD/target/sanitizer/ubsan"
    ;;
  *)
    echo "Unsupported sanitizer: $SANITIZER" >&2
    exit 1
    ;;
esac

test -f "$RUNTIME"
mvn -B -Dtest=SmokeTest test \
  -Dorg.bytedeco.javacpp.platform="$DETECTED_PLATFORM"
