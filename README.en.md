# hyperscan-java-native

This repository is a maintained fork of [gliwka/hyperscan-java-native](https://github.com/gliwka/hyperscan-java-native), providing native binaries for [hyperscan-java](https://github.com/gliwka/hyperscan-java).

[中文](README.md) | English

- Maintainer: XenoAmess
- Current version: `5.4.12-2.0.4-x9`
- Maven coordinates: `com.xenoamess.hyperscan:native:5.4.12-2.0.4-x9`
- JavaCPP version: `1.5.11`

## Key Improvements

### Multi-ISA Variant Builds with Runtime Auto-Selection

The original build used `FAT_RUNTIME=on`, which only dispatches at entry points such as `hs_scan`. General code still leaks AVX/AVX-512 instructions, causing `Illegal instruction` (SIGILL) on older CPUs or virtualized environments where AVX-512 is masked. This fork changes the approach to:

- Compile each ISA tier independently (`FAT_RUNTIME=off`) and lock its instruction set with a strict `-march` flag.
- Package multiple variants inside the same classifier jar.
- Automatically select the usable variant at startup by reading CPU features via `HyperscanNativeLoader`.

#### Linux x86_64 (three variants)

| Variant | Minimum ISA | Description |
|---------|-------------|-------------|
| `linux-x86_64-baseline` | SSE4.2 + POPCNT | Compatible with mainstream x86-64 CPUs since Westmere / Bulldozer (2010+) |
| `linux-x86_64-avx2` | AVX2 + BMI2 | Haswell / Zen and newer; ~30%–50% higher scan throughput than baseline |
| `linux-x86_64` | AVX-512 (F/BW/VL) + VBMI | Skylake-X / Ice Lake / Sapphire Rapids and newer |

> **Note**: Starting with `5.4.12-2.0.4-x9`, even when the CPU advertises AVX-512 support the loader defaults to `linux-x86_64-avx2`, because many virtualized or containerized environments expose AVX-512 flags but cannot reliably execute AVX-512 instructions. If you are sure the host supports it, force the AVX-512 build with `-Dorg.bytedeco.javacpp.platform=linux-x86_64`.

#### Linux arm64 (two variants)

| Variant | Minimum ISA | Description |
|---------|-------------|-------------|
| `linux-arm64-baseline` | ARMv8.0 + NEON/ASIMD | Graviton2 / Ampere Altra / mainstream ARMv8 servers |
| `linux-arm64` | ARMv9 + SVE2 | Graviton4 / Cobalt 100 / Apple M4+ |

#### Windows x86_64 (two variants)

| Variant | Minimum ISA | Description |
|---------|-------------|-------------|
| `windows-x86_64-baseline` | SSE4.2 + POPCNT | Compatible with mainstream x86-64 CPUs |
| `windows-x86_64` | AVX2 + BMI2 | Haswell / Zen and newer |

Windows libraries are built from the Intel Hyperscan source (VectorCamp/vectorscan does not officially support Windows). Because Intel Hyperscan 5.4.2's MSVC path does not set `SKYLAKE_FLAG`, **Windows currently has no AVX-512 variant**.

#### macOS

`macosx-x86_64` / `macosx-arm64` keep the original build strategy unchanged.

### Runtime Loader `HyperscanNativeLoader`

`src/main/java/com/gliwka/hyperscan/jni/HyperscanNativeLoader.java` is responsible for:

1. Reading CPU features from `/proc/cpuinfo` (Linux) or `PROCESSOR_IDENTIFIER` (Windows).
2. Setting the `org.bytedeco.javacpp.platform` system property before the first JavaCPP `Loader.load()` call.
3. Letting JavaCPP load the correct variant library.

Usage:

```java
import com.gliwka.hyperscan.jni.HyperscanNativeLoader;

HyperscanNativeLoader.load();
// Then use the hyperscan-java API
```

To force a specific variant:

```bash
-Dorg.bytedeco.javacpp.platform=linux-x86_64-avx2
```

The generated `hyperscan.java` static initializer is post-processed by `maven-antrun-plugin` to `static { HyperscanNativeLoader.load(); }`, so consumers usually do not need to call it manually.

### CI and ISA Verification

`.github/workflows/build.yml` builds each platform matrix entry independently and merges them into unified classifier jars in the `package-native` job:

- `native-<version>-linux-x86_64.jar`: contains baseline / avx2 / avx512 `.so` sets
- `native-<version>-linux-arm64.jar`: contains baseline / sve2 `.so` sets
- `native-<version>-windows-x86_64.jar`: contains baseline / avx2 `.dll` sets

CI runs `objdump` verification per x86_64 variant:

- baseline: no `v`-prefixed mnemonics (VEX/EVEX) allowed.
- avx2: VEX allowed, EVEX (`62` prefix, AVX-512) forbidden.
- avx512: no extra restriction.

Releases are published to Maven Central via [JReleaser](https://jreleaser.org/).

## Using with hyperscan-java

This repository only publishes native binaries. Business code still uses the upstream `com.gliwka.hyperscan:hyperscan-java`. To replace the native libraries while keeping the Java package name unchanged (still `com.gliwka.hyperscan`), exclude the upstream `hyperscan-java`'s bundled `com.gliwka.hyperscan:native` and explicitly add this fork's `com.xenoamess.hyperscan:native`.

```xml
<dependency>
    <groupId>com.gliwka.hyperscan</groupId>
    <artifactId>hyperscan-java</artifactId>
    <version>5.4.12-2.0.4</version>
    <exclusions>
        <exclusion>
            <groupId>com.gliwka.hyperscan</groupId>
            <artifactId>native</artifactId>
        </exclusion>
    </exclusions>
</dependency>

<dependency>
    <groupId>com.xenoamess.hyperscan</groupId>
    <artifactId>native</artifactId>
    <version>5.4.12-2.0.4-x9</version>
    <classifier>${os.detected.classifier}</classifier>
</dependency>
```

> **The package name does not change**: the `hyperscan-java` Java API remains in the `com.gliwka.hyperscan` package, and `HyperscanNativeLoader` also stays in `com.gliwka.hyperscan.jni`, so business code usually requires no modification—only the native Maven coordinates change.

Use the [os-maven-plugin](https://github.com/trustin/os-maven-plugin) to resolve `${os.detected.classifier}` automatically.

## Smoke Tests

Full end-to-end examples and smoke tests are in a separate repository:

- [XenoAmess/hyperscan-java-test](https://github.com/XenoAmess/hyperscan-java-test)

That repository demonstrates how to exclude the default native dependency from upstream `hyperscan-java` and use this fork, covering default and forced variant paths on Linux x86_64 / arm64 and Windows x86_64.

## Building

### Linux

```bash
# Build for the current platform
./build.sh

# Build a specific variant
DETECTED_PLATFORM=linux-x86_64-avx2 ./build.sh
```

### Windows

On the `windows-latest` GitHub Actions runner (or local Git Bash + MSYS2 + MSVC):

```bash
DETECTED_PLATFORM=windows-x86_64 ./build-windows.sh
```

## Architecture Documents

- [Linux x86_64 multi-variant plan](docs/architecture/linux-x86_64-multi-variant.en.md)
- [Linux x86_64 baseline plan](docs/architecture/linux-x86_64-baseline.en.md)
- [Linux arm64 multi-variant plan](docs/architecture/linux-arm64-multi-variant.en.md)
- [Windows multi-variant plan](docs/architecture/windows-multi-variant.en.md)
- [Original jar AVX instruction leak investigation](docs/architecture/investigating-avx-leak-in-original-jar.en.md)
- [Linux x86_64 baseline benchmark](docs/performance/linux-x86_64-baseline-benchmark.en.md)

## License

3-Clause BSD License
