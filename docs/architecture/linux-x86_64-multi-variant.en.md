English | [中文](linux-x86_64-multi-variant.zh.md)

# Linux Multi-Variant Refactoring Plan

| Field | Value |
|------|-----|
| Document version | v2.1 |
| Scope | `hyperscan-java-native` (fork) 5.4.12-2.0.4-x3+ |
| Repository root | `/home/xenoamess/workspace/hyperscan-java-native` |
| Status | Implemented |

---

## 1. Background

The single baseline `linux-x86_64-baseline` (SSE4.2 + POPCNT) solved the SIGILL problem on older CPUs, but pure scanning scenarios lost about **28%–35%** performance.

To make the library usable on old SSE4.2 CPUs, run at full speed on mainstream AVX2 CPUs, and extract maximum performance on high-end AVX-512 CPUs, this repository implemented a three-variant plan: a single Maven classifier jar packages three native libraries at the same time, and the runtime automatically selects the appropriate one based on CPU capabilities.

The ARM64 side follows the same idea, splitting into `linux-arm64-baseline` (ARMv8.0/NEON) and `linux-arm64` (SVE2). See `docs/architecture/linux-arm64-multi-variant.en.md` for details.

---

## 2. Why the original `FAT_RUNTIME=on` was not enough

See `docs/architecture/linux-x86_64-baseline.en.md` and `docs/architecture/investigating-avx-leak-in-original-jar.en.md`. The core issues are:

- The original `libhs.so` / `libhs_runtime.so` leaked **20,000+ EVEX (AVX-512) instructions** into ordinary code.
- `FAT_RUNTIME` only protects dispatch at entry points such as `hs_scan`; it cannot block these leaks.
- As a result, even pure AVX2 CPUs may trigger SIGILL.

Therefore, in the new version every variant uses `FAT_RUNTIME=off` and a strict `-march` to lock the instruction set of the corresponding variant.

---

## 3. Target CPU tiers

On x86-64, vectorscan can use only three SIMD tiers:

| Tier | classifier | Minimum instruction set | Representative microarchitecture | Approx. year |
|------|-----------|-----------|-----------|---------|
| 1 | `linux-x86_64-baseline` | SSE4.2 + POPCNT | Intel Westmere / AMD Bulldozer | 2010+ |
| 2 | `linux-x86_64-avx2` | AVX2 + BMI2 | Intel Haswell / AMD Zen | 2013+ |
| 3 | `linux-x86_64` | AVX-512 (F/BW/VL) + VBMI | Intel Skylake-X / Ice Lake / Sapphire Rapids | 2017+ |

> AVX-only (Sandy Bridge/Ivy Bridge, 2011-2012) has no independent value; vectorscan has no AVX-only implementation path, so it falls back to the SSE4.2 baseline.

---

## 4. Artifact structure

When published to Maven Central, `native-<version>-linux-x86_64.jar` is an **aggregate jar** that contains all three sets of `.so` files:

```
com/gliwka/hyperscan/jni/linux-x86_64/libhs.so              # AVX-512 build
com/gliwka/hyperscan/jni/linux-x86_64/libhs_runtime.so
com/gliwka/hyperscan/jni/linux-x86_64/libjnihyperscan.so

com/gliwka/hyperscan/jni/linux-x86_64-avx2/libhs.so         # AVX2 build
com/gliwka/hyperscan/jni/linux-x86_64-avx2/libhs_runtime.so
com/gliwka/hyperscan/jni/linux-x86_64-avx2/libjnihyperscan.so

com/gliwka/hyperscan/jni/linux-x86_64-baseline/libhs.so     # SSE4.2 build
com/gliwka/hyperscan/jni/linux-x86_64-baseline/libhs_runtime.so
com/gliwka/hyperscan/jni/linux-x86_64-baseline/libjnihyperscan.so
```

`linux-arm64` is also split into `linux-arm64-baseline` and `linux-arm64`, packaged together into `native-<version>-linux-arm64.jar`. See `docs/architecture/linux-arm64-multi-variant.en.md` for details.

---

## 5. build.sh implementation

The `linux-x86_64*` branch of `build.sh` chooses `-march` and AVX switches based on `DETECTED_PLATFORM`:

```bash
linux-x86_64|linux-x86_64-avx2|linux-x86_64-baseline)
  case "$DETECTED_PLATFORM" in
    linux-x86_64-baseline)
      MARCH="westmere"
      BUILD_AVX2=OFF
      BUILD_AVX512=OFF
      BUILD_AVX512VBMI=OFF
      ;;
    linux-x86_64-avx2)
      MARCH="haswell"
      BUILD_AVX2=ON
      BUILD_AVX512=OFF
      BUILD_AVX512VBMI=OFF
      ;;
    linux-x86_64)
      MARCH="skylake-avx512"
      BUILD_AVX2=ON
      BUILD_AVX512=ON
      BUILD_AVX512VBMI=ON
      ;;
  esac

  cmake -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$(pwd)/.." \
        -DCMAKE_INSTALL_LIBDIR="lib" \
        -DPCRE_SOURCE="." \
        -DFAT_RUNTIME=off \
        -DBUILD_SHARED_LIBS=on \
        -DBUILD_AVX2=$BUILD_AVX2 \
        -DBUILD_AVX512=$BUILD_AVX512 \
        -DBUILD_AVX512VBMI=$BUILD_AVX512VBMI \
        -DBUILD_BENCHMARKS=false \
        -DBUILD_EXAMPLES=false \
        -DBUILD_TOOLS=false \
        -DCMAKE_C_FLAGS="-march=$MARCH" \
        -DCMAKE_CXX_FLAGS="-march=$MARCH" \
        .
  make -j $THREADS all unit install/strip
  ;;
```

GCC 9 compatibility:
- Only the baseline variant needs to replace vectorscan's internal `x86-64-v2` alias with `westmere`.
- All variants remove `-Wno-stringop-overread`, which GCC 9 does not support.

---

## 6. Java runtime loading and CPU detection

A new `HyperscanNativeLoader` was added:

```java
public final class HyperscanNativeLoader {
    public static synchronized void load() {
        if (loaded) return;

        // Respect an externally set platform property (e.g. CI single-variant builds).
        if (System.getProperty("org.bytedeco.javacpp.platform") == null) {
            String platform = selectPlatform();
            if (platform != null) {
                System.setProperty("org.bytedeco.javacpp.platform", platform);
            }
        }

        Loader.load(JavaCppPreset.class);
        loaded = true;
    }
}
```

Linux x86_64 selection logic:

| CPU flags | Selected variant |
|-----------|---------------|
| `avx512f` + `avx512bw` + `avx512vl` (+ `avx512vbmi`) | `linux-x86_64` |
| `avx2` + `bmi2` | `linux-x86_64-avx2` |
| `sse4_2` + `popcnt` | `linux-x86_64-baseline` |
| Others | `linux-x86_64-baseline` (safe fallback) |

To ensure that any reference to a `hyperscan` class automatically goes through CPU detection, the generated `hyperscan.java` static block is post-processed into:

```java
static { HyperscanNativeLoader.load(); }
```

This post-processing is done by `maven-antrun-plugin` during the `generate-sources` phase:

```xml
<replaceregexp
    file="${project.build.directory}/generated-sources/com/gliwka/hyperscan/jni/hyperscan.java"
    match="static\s*\{[^}]*Loader\.load\([^)]*\)\s*;[^}]*\}"
    replace="static { HyperscanNativeLoader.load(); }"
    byline="false"
    flags="g"
/>
```

`JavaCppPreset` no longer contains `static { Loader.load(); }`; it only serves as the holder of the `@Properties` annotations.

---

## 7. GitHub Actions implementation

### 7.1 build-native matrix

```yaml
matrix:
  include:
    - os: linux
      runner: ubuntu-24.04
      platform: linux-x86_64-baseline
    - os: linux
      runner: ubuntu-24.04
      platform: linux-x86_64-avx2
    - os: linux
      runner: ubuntu-24.04
      platform: linux-x86_64
    - os: linux
      runner: ubuntu-24.04-arm
      platform: linux-arm64-baseline
    - os: linux
      runner: ubuntu-24.04-arm
      platform: linux-arm64
```

Each matrix entry runs `./build.sh` inside a CentOS 7 container, with `DETECTED_PLATFORM` set to the corresponding value.

All x86_64 and arm64 variants are aggregated into unified classifier jars in `package-linux-native`. The final artifacts published to Maven Central are:

- `native-<version>.jar` (Java classes, including `HyperscanNativeLoader`)
- `native-<version>-linux-x86_64.jar` (aggregate of three x86_64 `.so` sets)
- `native-<version>-linux-arm64.jar` (aggregate of two arm64 `.so` sets)
- sources / javadoc

### 7.2 ISA verification

Verification is performed per variant:

| variant | Allowed | Forbidden |
|---------|------|------|
| `linux-x86_64-baseline` | SSE family | Any mnemonic starting with `v` (VEX/EVEX) |
| `linux-x86_64-avx2` | VEX (AVX/AVX2) | EVEX (`62` prefix, AVX-512) |
| `linux-x86_64` | VEX / EVEX | None |

### 7.3 Aggregate package job

A new `package-linux-native` job:

1. Downloads the staging artifacts for the three x86_64 variants and the two arm64 variants.
2. Extracts each classifier jar.
3. Overlays the native directories of `linux-x86_64-avx2` and `linux-x86_64-baseline` into the `linux-x86_64` jar.
4. Overlays the native directory of `linux-arm64-baseline` into the `linux-arm64` jar.
5. Produces the unified `native-<version>-linux-x86_64.jar` and `native-<version>-linux-arm64.jar`.

The final artifacts published to Maven Central are:

- `native-<version>.jar` (Java classes, including `HyperscanNativeLoader`)
- `native-<version>-linux-x86_64.jar` (aggregate of three x86_64 `.so` sets)
- `native-<version>-linux-arm64.jar` (aggregate of two arm64 `.so` sets)
- sources / javadoc

---

## 8. Risks and caveats

### 8.1 AVX2 build must not leak AVX-512

CI intercepts the `62` prefix (EVEX) by scanning. If a leak occurs, pure AVX2 CPUs will still trigger SIGILL.

### 8.2 AVX-512 build must not be the default path

Because JavaCPP auto-detection maps a normal x86_64 Linux host to `linux-x86_64`, `HyperscanNativeLoader` must rewrite the platform property before loading. Any path that bypasses the loader and directly triggers `Loader.load()` before referencing a `hyperscan` class may select the wrong variant.

### 8.3 Consumer usage

`hyperscan-java` or other consumers should ensure that the following is called before the first use of the hyperscan API:

```java
HyperscanNativeLoader.load();
```

Or force a variant through the JavaCPP system property:

```bash
-Dorg.bytedeco.javacpp.platform=linux-x86_64-avx2
```

### 8.4 Version

This plan was released with `5.4.12-2.0.4-x3`.

---

## 9. Expected performance

| variant | Performance relative to baseline | Covered CPUs |
|---------|-------------------|---------|
| `linux-x86_64-baseline` | 100% | SSE4.2+ |
| `linux-x86_64-avx2` | +30%–50% | Haswell+/Zen+ |
| `linux-x86_64` | +40%–60% (relative to baseline, depending on AVX-512 scenario) | Skylake-X+/Ice Lake+ |

For concrete numbers see `docs/performance/linux-x86_64-baseline-benchmark.en.md`.

---

## 10. References

- `docs/architecture/linux-x86_64-baseline.en.md`: single-baseline refactoring plan
- `docs/architecture/linux-arm64-multi-variant.en.md`: ARM64 multi-variant refactoring plan
- `docs/architecture/investigating-avx-leak-in-original-jar.en.md`: original jar instruction-leak investigation
- `docs/performance/linux-x86_64-baseline-benchmark.en.md`: performance benchmark report
- `src/main/java/com/gliwka/hyperscan/jni/HyperscanNativeLoader.java`: runtime loader
- `src/main/java/com/gliwka/hyperscan/jni/JavaCppPreset.java`: JavaCPP preset

---

**End of document.**
