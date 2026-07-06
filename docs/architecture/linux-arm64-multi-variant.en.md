English | [中文](linux-arm64-multi-variant.zh.md)

# Linux ARM64 Multi-Variant Refactoring Plan

| Field | Value |
|------|-----|
| Document version | v1.0 |
| Scope | `hyperscan-java-native` (fork) 5.4.12-2.0.4-x3+ |
| Repository root | `/home/xenoamess/workspace/hyperscan-java-native` |
| Status | Implemented |

---

## 1. Background

Similar to x86_64, vectorscan on ARM64 also builds multiple SIMD runtime variants (NEON, SVE, SVE2) through `FAT_RUNTIME`. When the build machine supports SVE/SVE2 and `-march` is not explicitly restricted, common code may leak SVE/SVE2 instructions, causing `SIGILL` on CPUs that only support ARMv8.0/NEON.

The original `linux-arm64` configuration used:

```bash
CC="clang" CXX="clang++" cmake ... -DFAT_RUNTIME=on -DBUILD_SVE=on -DBUILD_SVE2=on .
```

And did not explicitly specify `-march`. The actual compatibility level was determined by the build machine, creating a risk of instruction leakage.

---

## 2. Target CPU tiers

Meaningful SIMD tiers for vectorscan on ARM64:

| Tier | classifier | Minimum instruction set | Representative microarchitecture | Approx. year |
|------|-----------|-----------|-----------|---------|
| 1 | `linux-arm64-baseline` | ARMv8.0 + NEON/ASIMD | Cortex-A53 / A72 / Ampere Altra / AWS Graviton2 | 2016+ |
| 2 | `linux-arm64` | ARMv9 / SVE2 | AWS Graviton4 / Azure Cobalt 100 / Apple M4+ | 2023+ |

> Standalone SVE-only (ARMv8.2+, e.g. Fujitsu A64FX, AWS Graviton3) is classified as `linux-arm64-baseline` in this plan because SVE2 is not backward compatible with SVE-only CPUs. If dedicated optimization is needed in the future, a separate `linux-arm64-sve` can be added.

---

## 3. Artifact structure

When published to Maven Central, `native-<version>-linux-arm64.jar` is an **aggregate jar** containing both sets of `.so` files:

```
com/gliwka/hyperscan/jni/linux-arm64/libhs.so              # SVE2 build
com/gliwka/hyperscan/jni/linux-arm64/libhs_runtime.so
com/gliwka/hyperscan/jni/linux-arm64/libjnihyperscan.so

com/gliwka/hyperscan/jni/linux-arm64-baseline/libhs.so     # ARMv8.0/NEON build
com/gliwka/hyperscan/jni/linux-arm64-baseline/libhs_runtime.so
com/gliwka/hyperscan/jni/linux-arm64-baseline/libjnihyperscan.so
```

---

## 4. build.sh implementation

The `linux-arm64*` branch of `build.sh` chooses `-march` and SVE/SVE2 switches based on `DETECTED_PLATFORM`:

```bash
linux-arm64|linux-arm64-baseline)
  case "$DETECTED_PLATFORM" in
    linux-arm64-baseline)
      MARCH="armv8-a"
      BUILD_SVE=OFF
      BUILD_SVE2=OFF
      FAT_RUNTIME=off
      ;;
    linux-arm64)
      MARCH="armv9-a"
      BUILD_SVE=ON
      BUILD_SVE2=ON
      FAT_RUNTIME=off
      ;;
  esac

  CC="clang" CXX="clang++" cmake \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$(pwd)/.." \
        -DCMAKE_INSTALL_LIBDIR="lib" \
        -DPCRE_SOURCE="." \
        -DFAT_RUNTIME=$FAT_RUNTIME \
        -DBUILD_SHARED_LIBS=on \
        -DBUILD_SVE=$BUILD_SVE \
        -DBUILD_SVE2=$BUILD_SVE2 \
        -DCMAKE_C_FLAGS="-march=$MARCH" \
        -DCMAKE_CXX_FLAGS="-march=$MARCH" \
        -DBUILD_BENCHMARKS=false \
        -DBUILD_EXAMPLES=false \
        .
  make -j $THREADS install/strip
  ;;
```

Key points:
- All ARM64 variants use `FAT_RUNTIME=off` and a strict `-march` to lock the instruction set.
- The baseline explicitly disables `BUILD_SVE` and `BUILD_SVE2`, keeping only NEON.
- The SVE2 variant uses `-march=armv9-a` (which in clang enables SVE2).

---

## 5. Java runtime loading and CPU detection

`HyperscanNativeLoader` reads the `Features` field from `/proc/cpuinfo` on ARM64 Linux:

| CPU features | Selected variant |
|--------------|---------------|
| Contains `sve2` | `linux-arm64` |
| Others | `linux-arm64-baseline` (safe fallback) |

On ARM64, `/proc/cpuinfo` names the feature field `Features` (whereas x86 uses `flags`). Therefore `readLinuxCpuFlags()` supports both field names.

---

## 6. GitHub Actions implementation

### 6.1 build-native matrix

Add the ARM64 baseline entry:

```yaml
matrix:
  include:
    ...
    - os: linux
      runner: ubuntu-24.04-arm
      platform: linux-arm64-baseline
    - os: linux
      runner: ubuntu-24.04-arm
      platform: linux-arm64
```

### 6.2 Aggregate package job

After merging the three x86_64 variants, the `package-linux-native` job continues to merge the two arm64 variants:

1. Download the staging artifacts for `linux-arm64-baseline` and `linux-arm64`.
2. Overlay the baseline native directory into the SVE2 jar.
3. Produce the unified `native-<version>-linux-arm64.jar`.

### 6.3 Test job

`test-linux-arm64` job:

- Runs on the `ubuntu-24.04-arm` runner.
- Default platform test: the runner supports SVE2, so `linux-arm64` is selected.
- Forced `linux-arm64-baseline` test: verifies that the baseline variant in the aggregate jar runs correctly.

---

## 7. Risks and caveats

### 7.1 SVE2 build must not be the default path

JavaCPP auto-detection maps a normal ARM64 Linux host to `linux-arm64`, so `HyperscanNativeLoader` must rewrite the platform property before loading to the correct variant.

### 7.2 Consumer usage

`hyperscan-java` or other consumers should ensure that the following is called before the first use of the hyperscan API:

```java
HyperscanNativeLoader.load();
```

Or force a variant through the JavaCPP system property:

```bash
-Dorg.bytedeco.javacpp.platform=linux-arm64-baseline
```

### 7.3 Version

This plan was released with `5.4.12-2.0.4-x3`.

---

## 8. References

- `docs/architecture/linux-x86_64-multi-variant.en.md`: x86_64 three-variant refactoring plan
- `docs/architecture/linux-x86_64-baseline.en.md`: baseline refactoring plan
- `src/main/java/com/gliwka/hyperscan/jni/HyperscanNativeLoader.java`: runtime loader
- `src/main/java/com/gliwka/hyperscan/jni/JavaCppPreset.java`: JavaCPP preset

---

**End of document.**
