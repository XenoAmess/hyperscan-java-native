English | [中文](windows-multi-variant.zh.md)

# Windows Multi-Variant Support Plan

| Field | Value |
|------|-----|
| Document version | v1.1 |
| Scope | `hyperscan-java-native` (fork) future versions |
| Repository root | `/home/xenoamess/workspace/hyperscan-java-native` |
| Status | Implemented (baseline + AVX2; AVX-512 not supported yet) |

---

## 1. Overall strategy

Use **plan C (adjusted)**:

- Linux (x86_64 / arm64) continues to build multi-variant native libraries from the **VectorCamp/vectorscan** source; the plan is unchanged.
- Windows supports **x86_64 only**. It compiles three ISA tiers from the **Intel Hyperscan source**, packages them by variant, and aligns the loading/packaging logic with Linux.

Reasons:
- vectorscan officially does not support native Windows compilation;
- Intel Hyperscan official releases **do not ship precompiled Windows binaries** (only source packages);
- Third-party package managers (Conan / vcpkg) usually provide single-tier Windows packages, which do not satisfy the multi-variant requirement;
- Intel Hyperscan itself **does not support Windows arm64** (vcpkg marks it `!arm`), so Windows arm64 is dropped;
- JavaCPP only needs consistent C headers and `hs_*` exported symbols to generate bindings, so the source of native libraries for Windows and Linux can differ.

---

## 2. Target platforms and CPU tiers

### 2.1 Windows x86_64

| Tier | classifier | Minimum instruction set | Representative microarchitecture |
|------|-----------|-----------|-----------|
| 1 | `windows-x86_64-baseline` | SSE4.2 + POPCNT | Intel Westmere / AMD Bulldozer |
| 2 | `windows-x86_64` | AVX2 + BMI2 | Intel Haswell / AMD Zen |

Windows has no Linux-style `ifunc` fat runtime, so each variant is an independently compiled static/dynamic library selected at runtime by Java based on CPU features. AVX-512 is not supported yet: the Intel Hyperscan 5.4.2 MSVC path does not set `SKYLAKE_FLAG` in `cmake/arch.cmake`, so enabling `BUILD_AVX512` fails outright.

### 2.2 Windows arm64

**Not supported.** Neither Intel Hyperscan nor vectorscan has Windows arm64 build support. Can be extended in the future if upstream support appears.

---

## 3. JavaCPP preset changes

Add to the `@Platform` list in `src/main/java/com/gliwka/hyperscan/jni/JavaCppPreset.java`:

```java
"windows-x86_64",
"windows-x86_64-baseline",
```

And ensure:
- `compiler = "cpp11"` still applies on Windows;
- `include` and `link` configurations are consistent with Linux (`hs/hs_common.h`, `hs/hs_compile.h`, `hs/hs_runtime.h`, `hs/hs.h`, linking `hs`, `hs_runtime`).

---

## 4. Runtime loader changes

Extend `HyperscanNativeLoader`:

### 4.1 Platform detection

Detect Windows inside `selectPlatform()`:

```java
boolean isWindows = os.contains("windows");

if (isWindows && isX86_64) {
    return selectWindowsX86_64Variant();
}
```

### 4.2 Windows x86_64 selection logic

Use a JDK built-in way to read CPUID or call a Windows API:

| CPU feature | Selected variant |
|----------|---------------|
| `avx2` + `bmi2` | `windows-x86_64` |
| `sse4_2` + `popcnt` | `windows-x86_64-baseline` |
| Others | `windows-x86_64-baseline` |

> Note: AVX-512 hosts also fall back to `windows-x86_64` (AVX2 build), because upstream Intel Hyperscan 5.4.2 cannot compile an AVX-512 variant under MSVC.

Candidate implementations:
- Use JNA to call `kernel32.GetNativeSystemInfo` + `IsProcessorFeaturePresent`;
- If JNA is not desired, use JNI or parse the Windows registry key `HKEY_LOCAL_MACHINE\HARDWARE\DESCRIPTION\System\CentralProcessor\0` value `FeatureSet`.

### 4.3 Windows arm64 selection logic

**Not supported.**

---

## 5. Build script

### 5.1 Add `build-windows.sh`

Responsibilities:
1. Determine the target tier based on `DETECTED_PLATFORM`;
2. Download the Intel Hyperscan source (e.g. `v5.4.2.tar.gz`) and verify its checksum;
3. Download boost headers (required for building Hyperscan);
4. Compile using MSVC (via GitHub Actions `windows-latest`) or MinGW-w64;
5. Put headers under `cppbuild/include/hs/` and DLL/LIB files under `cppbuild/lib/`;
6. Run `mvn -B -DskipTests -Dorg.bytedeco.javacpp.platform=$DETECTED_PLATFORM` so JavaCPP generates bindings and packages them as a classifier jar.

CMake parameters differ by tier:

| variant | MSVC arch flag | `BUILD_AVX2` | `BUILD_AVX512` | `BUILD_AVX512VBMI` | `FAT_RUNTIME` |
|---------|---------------|--------------|----------------|--------------------|---------------|
| `windows-x86_64-baseline` | default x86_64 | OFF | OFF | OFF | OFF |
| `windows-x86_64` | `/arch:AVX2` | ON | OFF | OFF | OFF |

> MSVC's `/arch` option cannot directly specify SSE4.2; Hyperscan's SSE4.2 code is compiled through intrinsics and works as long as the target CPU supports it. The baseline uses the default x86_64 compilation, but still requires SSE4.2 + POPCNT at runtime.

### 5.2 Header source

Use the headers from the Intel Hyperscan source. If they differ from the vectorscan headers (added/removed APIs):
- Perform `Info` mapping or skip in `JavaCppPreset.map(InfoMap)`;
- Ensure the common public API between Linux and Windows is consistent so consumer code compiles.

---

## 6. GitHub Actions workflow

### 6.1 New build-native matrix entries

```yaml
- os: windows
  runner: windows-latest
  platform: windows-x86_64-baseline
- os: windows
  runner: windows-latest
  platform: windows-x86_64
```

### 6.2 Windows build steps

- Use `actions/checkout`;
- Use `actions/setup-java` (Temurin JDK 21, or JDK 8/11/17/21 as required);
- Install dependencies:
  - CMake (usually preinstalled on Windows runners, or use `actions/setup-cmake`);
  - Python (required for building Hyperscan);
  - MSVC (`windows-latest` includes Visual Studio 2022);
  - boost headers;
- Run `./build-windows.sh` in Git Bash or PowerShell;
- Upload the `target/staging-deploy` artifact.

### 6.3 Aggregate job extension

Rename `package-linux-native` to `package-native`:
- Download variant artifacts for Linux x86_64 / arm64;
- Download variant artifacts for Windows x86_64;
- Merge them respectively into:
  - `native-<version>-linux-x86_64.jar`
  - `native-<version>-linux-arm64.jar`
  - `native-<version>-windows-x86_64.jar`
- Put all classifier jars into the same staging repo.

### 6.4 Test job

Add:
- `test-windows-x86_64`: run `EndToEndTest` on the `windows-latest` runner, covering default / baseline.

### 6.5 publish job

Add the Windows test job to `needs`:

```yaml
needs:
  - package-native
  - test-linux-x86_64
  - test-linux-arm64
  - test-windows-x86_64
```

---

## 7. Artifact structure

Final Maven Central release:

```
native-<version>.jar
native-<version>-sources.jar
native-<version>-javadoc.jar
native-<version>-linux-x86_64.jar     # contains linux-x86_64 / linux-x86_64-avx2 / linux-x86_64-baseline
native-<version>-linux-arm64.jar      # contains linux-arm64 / linux-arm64-baseline
native-<version>-windows-x86_64.jar   # contains windows-x86_64 / windows-x86_64-baseline
```

Example internal structure of the Windows classifier jar:

```
com/gliwka/hyperscan/jni/windows-x86_64/hs.dll
com/gliwka/hyperscan/jni/windows-x86_64/hs_runtime.dll
com/gliwka/hyperscan/jni/windows-x86_64/jnihyperscan.dll

com/gliwka/hyperscan/jni/windows-x86_64-baseline/hs.dll
...
```

---

## 8. Risks and dependencies

| Risk | Impact | Mitigation |
|------|------|---------|
| Intel Hyperscan source API not fully consistent with vectorscan 5.4.12 | JavaCPP generated code or consumer compilation fails | Use a unified minimal common header subset; skip differing APIs via `InfoMap` if necessary |
| Compiling Intel Hyperscan source with MSVC is complex | CI build failures | Do a PoC locally/in CI first; fall back to MinGW-w64 if necessary |
| Windows has no fat runtime, so each variant binary is large | Aggregate jar size increases | Acceptable, consistent with Linux plan |
| Windows CPU feature detection is complex | Wrong variant selection causes IllegalInstruction | Prefer conservative fallback to baseline; implement detection with JNA or JNI |
| JavaCPP platform naming and loader interaction on Windows | JavaCPP cannot find resources after loader sets platform property | Strictly follow JavaCPP official classifier names (`windows-x86_64`, etc.) |
| Windows arm64 not supported | Cannot cover WoA devices | Document clearly; wait for upstream support |

---

## 9. Implementation order

1. **PoC**: On GitHub Actions `windows-latest`, compile only `windows-x86_64-baseline` and verify that `EndToEndTest` passes.
2. **Extend x86_64**: Add `windows-x86_64` (AVX2); drop AVX-512 (upstream MSVC path does not support it).
3. **Loader**: Implement Windows x86_64 CPU feature detection.
4. **CI integration**: Update workflow, aggregate job, test job, and publish job.
5. **Documentation and release**: Update architecture docs, bump version, tag and release.

---

## 10. References

- `docs/architecture/linux-x86_64-multi-variant.en.md`
- `docs/architecture/linux-arm64-multi-variant.en.md`
- `src/main/java/com/gliwka/hyperscan/jni/HyperscanNativeLoader.java`
- `src/main/java/com/gliwka/hyperscan/jni/JavaCppPreset.java`
- Intel Hyperscan releases: https://github.com/intel/hyperscan/releases
- Intel Hyperscan build instructions for Windows: https://github.com/intel/hyperscan/blob/v5.4.2/README.md

---

**End of document.**
