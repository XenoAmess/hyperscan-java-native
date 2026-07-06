English | [中文](linux-x86_64-baseline.zh.md)

# Linux x86_64 Minimum Instruction Set Compatibility Refactoring Plan

| Field       | Value                                          |
| ---------- | ------------------------------------------- |
| Document version   | v1.2                                        |
| Scope   | `hyperscan-java-native` (fork) 5.4.12-2.0.4-x2+ |
| Repository root     | `/home/xenoamess/workspace/hyperscan-java-native` |
| Status       | Integrated into the multi-variant plan                       |

> Note: The SSE4.2 baseline build described in this document is still part of the repository, but it is no longer the only x86_64 artifact. See the current full design in `docs/architecture/linux-x86_64-multi-variant.en.md`.

---

## 1. Background

Customers reported that the `5.4.12-2.0.4` / `linux-x86_64` artifact failed to start or raised `Illegal instruction` (SIGILL) at runtime on some machines, indicating an instruction-set compatibility problem.

### 1.1 Current artifact instruction-set status

The `linux-x86_64` branch in `build.sh:78-82`:

```bash
cmake -DCMAKE_BUILD_TYPE=Release \
      -DCMAKE_INSTALL_PREFIX="$(pwd)/.." \
      -DCMAKE_INSTALL_LIBDIR="lib" \
      -DPCRE_SOURCE="." \
      -DFAT_RUNTIME=on \
      -DBUILD_SHARED_LIBS=on \
      -DBUILD_AVX2=yes \
      -DBUILD_AVX512=yes \
      -DBUILD_AVX512VBMI=yes .
```

Meaning:

- `FAT_RUNTIME=on`: the binary keeps multiple ISA implementations and selects at runtime through CPUID.
- `BUILD_AVX2=yes`: the `AVX2` path is linked into the artifact.
- `BUILD_AVX512=yes`: the `AVX-512 F/BW/CD/DQ/VL` path is linked into the artifact.
- `BUILD_AVX512VBMI=yes`: the `AVX-512 VBMI` (Ice Lake feature) path is linked into the artifact.
- The build runs in `ghcr.io/gliwka/centos7-toolchain:main` (CentOS 7, gcc 4.8.5, glibc 2.17).

### 1.2 Why does `FAT_RUNTIME=on` still cause SIGILL?

The original `5.4.12-2.0.4` jar was published on Maven Central. After disassembling that artifact we found:

| `.so` | VEX/EVEX (AVX/AVX2/AVX-512) instruction count |
|-------|--------------------------------------|
| `libjnihyperscan.so` | 0 |
| `libhs.so` | **57,634** |
| `libhs_runtime.so` | **57,634** |

This shows:

1. `FAT_RUNTIME=on` only protects the runtime dispatch at entry points such as `hs_scan`; **it cannot guarantee that the whole `.so` contains no AVX instructions**.
2. Common code, library initialization, compiler-auto-vectorized loops, etc. still leak AVX/AVX2/AVX-512 instructions into the `.text` section.
3. When the customer's CPU/virtualization environment does not support these instructions, the library triggers `Illegal instruction` during loading or initialization.

Therefore, this plan disables AVX at the source and uses CI `objdump` checks to ensure that no `.so` contains VEX/EVEX instructions.

### 1.3 Common failure patterns in customer environments

| Pattern                                              | Trigger condition                                                                | Error signature                             |
| ------------------------------------------------- | ----------------------------------------------------------------------- | ------------------------------------ |
| Customer CPU lacks AVX2                                  | Consumer CPUs before 2013, before AMD K10, certain domestic x86 CPUs                        | `Illegal instruction` (some paths)    |
| Customer CPU lacks AVX-512                                | Most cloud hosts disable AVX-512 by default; older Xeon E5 v3/v4; virtualization passthrough disabled           | `Illegal instruction` (AVX-512 path)|
| Kernel/VM masks AVX-512                              | Old kernels (< 4.x), certain Xen/KVM configurations, BIOS "Memory Encryption" interference       | `Illegal instruction` (AVX-512 path)|
| glibc < 2.17                                       | CentOS 6, certain domestic OS                                                 | `GLIBC_2.14 not found` (ABI problem)   |
| Machine is not x86-64 at all                              | ARM servers, LoongArch, etc. mistakenly using the linux-x86_64 artifact                          | `cannot execute binary file` or SIGILL |

This plan mainly addresses the **first three categories** (instruction-set problems). glibc is unrelated to the instruction set and is handled separately.

---

## 2. Goals and non-goals

### 2.1 Goals

1. **G1**: The `linux-x86_64` artifact must run on CPUs that only support **SSE4.2 + POPCNT** (covering all x86-64 CPUs after Westmere 2010 / Clarkdale / AMD Bulldozer).
2. **G2**: The artifact binary must **not contain** AVX / AVX2 / AVX-512 / AVX-512 VBMI instruction sequences (objectively verifiable with `objdump`).
3. **G3**: The pipeline changes should be minimally intrusive to the existing release flow, and CI should still pass on GitHub Actions.
4. **G4**: Preserve scalability toward higher ISAs so that future needs do not require overturning this plan.

### 2.2 Non-goals

- Does not solve glibc version issues (unrelated to ISA).
- Does not solve `linux-arm64` compatibility (the arm64 side is handled separately by the `linux-arm64-baseline` / `linux-arm64` multi-variant plan, orthogonal to this plan).
- Does not modify vectorscan upstream source code.
- Does not implement runtime multi-variant selection at the JavaCPP layer (see §4.2 alternative, not done this time).

---

## 3. Instruction-set baseline analysis

### 3.1 vectorscan hard minimum requirement

vectorscan (the hyperscan fork) enforces a baseline of **SSE4.2 + POPCNT** in its source code:

- SSE4.2: instructions such as `PCMPGTQ` and `CRC32` are used for character comparison.
- POPCNT: used to accelerate character classification.
- There is no implementation path before SSE4.2.

Therefore, the practical "oldest, lowest-end CPU" bound in the x86 context is:

| Vendor | Minimum microarchitecture                       | Approx. year | Representative models                          |
| ---- | -------------------------------- | -------- | --------------------------------- |
| Intel | Westmere / Clarkdale (1st generation Core i series) | 2010     | Xeon 5600, Core i5/i7 1xxx (desktop), Core i5/i7 Mobile 1xxx |
| AMD   | Bulldozer / Piledriver            | 2011     | FX-8xxx, Opteron 4200/6200        |

Older CPUs (Core 2, AMD K10, K8) cannot run vectorscan even if they support x86-64. This is a project hard limit that cannot be broken without modifying vectorscan.

### 3.2 Baseline locked by this plan

```
-march=westmere     # GCC equivalent to -msse4.2 -msse4.1 -mssse3 -msse3 -mpopcnt -mcx16 -msahf
```

`westmere` is GCC's standard `-march` value for the Intel Westmere microarchitecture, equivalent to the minimal superset of `-msse4.2 -mpopcnt`. It covers all Intel and AMD CPUs from 2010 onwards.

> **Important notes**:
> - The equivalent flags of `-march=westmere` include `CMPXCHG16B` (`-mcx16`) and `SAHF/LAHF` (`-msahf`). These two extensions are extremely common on x86-64 and are supported by all CPUs after Westmere, but very old K8-class CPUs do not support them. Because vectorscan hard-requires SSE4.2 + POPCNT, any "oldest, lowest-end" CPU that can actually run it necessarily satisfies these two extensions as well, so compatibility is not additionally restricted.
> - This plan **does not enable `-mtune=native`**, to avoid leaking the build machine's CPU-specific tuning options into the target binary.

---

## 4. Design

### 4.1 Recommended plan A: single variant, lowest baseline

**Core idea**: change the existing `linux-x86_64` artifact from "multi-ISA FAT_RUNTIME + AVX2/AVX512/VBMI" to "single baseline SSE4.2 + POPCNT, no runtime dispatch". The published `linux-x86_64` jar will run on any CPU that meets vectorscan's minimum requirements.

#### 4.1.1 Change scope

| File                                 | Change                                            |
| ------------------------------------ | ----------------------------------------------- |
| `build.sh`                           | linux-x86_64 branch cmake line: disable FAT_RUNTIME and the three AVX switches, explicitly add `-march=westmere` |
| `.github/workflows/build.yml`       | Add ISA verification step (`objdump` disassembly check)       |
| `doc/architecture/linux-x86_64-baseline.en.md` | This document                                          |

#### 4.1.2 Why `FAT_RUNTIME=off` is mandatory

The dispatch logic in `src/dispatcher.c` is:

```c
if (check_avx512vbmi()) { ... }
else if (check_avx512()) { ... }
else if (check_avx2()) { ... }
else if (check_sse42() && check_popcnt()) { ... }
else if (check_ssse3()) { ... }
else { error }
```

This file only supports disabling AVX-512 dispatch through the `DISABLE_AVX512_DISPATCH` / `DISABLE_AVX512VBMI_DISPATCH` macros; **there is no `DISABLE_AVX2_DISPATCH` macro**. If `FAT_RUNTIME=on` is kept while only setting `BUILD_AVX2=no`:

- The `avx2_*` implementations are not compiled into object files;
- But the dispatcher still resolves function pointers to undefined `avx2_*` symbols on AVX2 CPUs;
- Result: **undefined symbol / segmentation fault** at runtime on AVX2 machines.

Therefore `FAT_RUNTIME` must be turned off as well, so that the dispatcher is no longer linked and the binary follows a single path.

#### 4.1.3 build.sh change (recommended diff)

```diff
 case $DETECTED_PLATFORM in
 linux-x86_64)
-  cmake -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$(pwd)/.." -DCMAKE_INSTALL_LIBDIR="lib" -DPCRE_SOURCE="." -DFAT_RUNTIME=on -DBUILD_SHARED_LIBS=on -DBUILD_AVX2=yes -DBUILD_AVX512=yes -DBUILD_AVX512VBMI=yes .
+  # Baseline-only build: SSE4.2 + POPCNT (Westmere, 2010+). See doc/architecture/linux-x86_64-baseline.en.md
+  cmake -DCMAKE_BUILD_TYPE=Release \
+        -DCMAKE_INSTALL_PREFIX="$(pwd)/.." \
+        -DCMAKE_INSTALL_LIBDIR="lib" \
+        -DPCRE_SOURCE="." \
+        -DFAT_RUNTIME=off \
+        -DBUILD_SHARED_LIBS=on \
+        -DBUILD_AVX2=OFF \
+        -DBUILD_AVX512=OFF \
+        -DBUILD_AVX512VBMI=OFF \
+        -DCMAKE_C_FLAGS="-march=westmere" \
+        -DCMAKE_CXX_FLAGS="-march=westmere" \
+        .
   make -j $THREADS all unit install/strip
   ;;
```

Key points:

1. `FAT_RUNTIME=off` — completely remove FAT runtime dispatch, avoiding the undefined AVX2 symbol problem.
2. `BUILD_AVX2/AVX512/AVX512VBMI` all set to `OFF` — these paths are not compiled into the `.so`.
3. Explicitly append `-DCMAKE_C_FLAGS="-march=westmere" -DCMAKE_CXX_FLAGS="-march=westmere"` — tell CMake to use the baseline instruction set globally.
4. **Note**: vectorscan internally overlays its own `-march=...` compile flags for each runtime variant object, so the final result must be validated by the CI `objdump` check, not just the command line.
5. **glibc fallback**: keep the CentOS 7 container unchanged; the artifact requires glibc 2.17 minimum, remaining compatible with CentOS 7+, RHEL 7+, and equivalent Linux distributions.

#### 4.1.4 Expected artifact size change

| Configuration                                       | libhs.so size (rough order of magnitude) |
| ------------------------------------------ | ------------------------- |
| Current: baseline+AVX2+AVX512+VBMI                | ~30 MB                    |
| After refactoring: baseline only                              | ~6–8 MB                   |
| Reduction                                    | ~70–80%                 |

The size drops significantly because the FAT_RUNTIME duplicate code copies no longer enter the final link.

### 4.2 Alternative plan B: multi-variant layering (**not done this time**, interface reserved)

If in the future we want "old machines run baseline, new machines run high-performance", we can add variants to the CI matrix:

```yaml
- os: linux
  platform: linux-x86_64-baseline
- os: linux
  platform: linux-x86_64-avx2
- os: linux
  platform: linux-x86_64          # default highest, backward compatible
```

The consumer side `hyperscan-java` repository would read `/proc/cpuinfo` at load time to select the jar with the matching classifier. **This plan does not include that**, leaving it as a future extension.

---

## 5. CI pipeline changes

### 5.1 build.yml: new ISA verification step

Insert after the "Build native binaries in centos container" step and before upload-artifact in the linux-x86_64 matrix entry:

```yaml
      - name: Verify baseline ISA (no AVX/AVX2/AVX-512 in native libs)
        if: matrix.os == 'linux' && matrix.platform == 'linux-x86_64'
        run: |
          set -euo pipefail
          find target/staging-deploy -path '*linux-x86_64*' -name '*.so' | while read -r SO; do
            echo "Inspecting $SO"
            # VEX/EVEX encoded SIMD instructions are introduced by AVX/AVX2/AVX-512.
            # Their mnemonics all start with the letter 'v' in AT&T/Intel syntax.
            # We look for instruction lines (skip address/file headers) containing a leading tab + a 'v' prefixed mnemonic.
            if objdump -d "$SO" | grep -E '^[[:space:]]+[0-9a-f]+:[[:space:]]+[0-9a-f ]+[[:space:]]+v' >/dev/null 2>&1; then
              echo "FAIL: $SO contains VEX/EVEX (AVX/AVX2/AVX-512) instructions"
              objdump -d "$SO" | grep -nE '^[[:space:]]+[0-9a-f]+:[[:space:]]+[0-9a-f ]+[[:space:]]+v' | head -10
              exit 1
            fi
          done
          echo "PASS: no AVX/AVX2/AVX-512 instructions detected in linux-x86_64 .so files"
```

> Notes:
> - This check iterates over all `linux-x86_64` `.so` files under `target/staging-deploy` (including `libhs.so`, `libhs_runtime.so`, etc.), not just `libhs.so`.
> - VEX/EVEX instruction mnemonics all start with `v` (e.g. `vmovdqa`, `vpxor`, `vpbroadcastb`). The regex `^[[:space:]]+[0-9a-f]+:` restricts matches to disassembly instruction lines, avoiding false positives on ordinary symbols or strings containing `v`.
> - The check is insensitive to SSE4.2 instructions (`pcmpgtq`, `popcnt`, `crc32`, etc.), so it will not falsely flag baseline-legal instructions.

### 5.2 build.yml: complete linux-x86_64 matrix entry (reference)

```yaml
- os: linux
  runner: ubuntu-24.04
  shell: bash
  platform: linux-x86_64
```

Subsequent steps remain unchanged:

1. checkout
2. Run `./build.sh` inside the centos7 container (already modified per §4.1.3)
3. **New**: ISA verification from §5.1
4. upload-artifact

### 5.3 Local verification (no GitHub Actions required)

On a Linux x86_64 machine with `binutils`, `cmake`, and `gcc` installed:

```bash
./build.sh   # can also run outside the container, but cmake/gcc/make must be available

SO=$(find target/staging-deploy -name 'libhs.so' -path '*linux-x86_64*' | head -1)
[ -n "$SO" ] || { echo "libhs.so not found"; exit 1; }

# (1) Quick size check: significantly smaller
ls -lh "$SO"

# (2) Disassembled .text section must contain no AVX/AVX2/AVX-512 mnemonics
#     VEX/EVEX instruction mnemonics all start with 'v'
objdump -d "$SO" | grep -E '^[[:space:]]+[0-9a-f]+:[[:space:]]+[0-9a-f ]+[[:space:]]+v' | head

# (3) Cross-check with vectorscan's own tool (if unit tests/tools were built)
#     hsvalidplatform returns 0 when the current CPU can run this binary
if command -v hsvalidplatform >/dev/null 2>&1; then
  hsvalidplatform
fi
```

Expected results:

- (1) Size ~6–8 MB.
- (2) Empty output.
- (3) `hsvalidplatform` returns 0 (only true when the current CPU supports the baseline instruction set; build machines usually do).

### 5.4 Regression testing

JUnit tests already exist under `src/test/java`. CI runs `mvn test`, which loads the local `.so` and runs end-to-end cases. After changing the ISA configuration, all tests must pass:

```bash
mvn -B -Dorg.bytedeco.javacpp.platform=linux-x86_64 test
```

Pay special attention to:

- `SmokeTest` / any test that calls `hs_compile` / `hs_scan`: verifies that the baseline path can complete compilation and matching.
- Large-scale `Stream` / `Block` database cases: triggers runtime matching and verifies that the SSE4.2 path works.

---

## 6. Risks and trade-offs

### 6.1 Performance impact

| Operation type            | Impact              | Notes                                  |
| ------------------- | ----------------- | ------------------------------------- |
| Scan throughput            | Drops 30%–60%      | Modern CPUs lose AVX2 acceleration; throughput-sensitive scanning scenarios are most affected |
| Compilation time            | Slightly decreases          | Only one ISA path is compiled, so compilation is slightly faster            |
| `.so` size          | Shrinks 70%+         | See §4.1.4                             |
| Startup / load time     | Slightly decreases          | No longer parses the FAT runtime dispatch table       |

If the customer's workload is sensitive to scan throughput (e.g. high-QPS regex gateway), plan A is unsuitable and plan B (multi-variant) should be used.

### 6.2 Compatibility with upstream release strategy

Upstream `gliwka/hyperscan-java-native` is released as "one variant per platform"; this fork keeps the same convention, so the published name does not change (e.g. `native-5.4.12-2.0.5-linux-x86_64.jar`). Downstream consumers only need to upgrade the version number, not the classifier.

> If plan B is chosen, classifier naming must be coordinated with upstream / downstream `hyperscan-java`; this plan does not cover that.

### 6.3 Residual glibc and OS compatibility risk

The CentOS 7 toolchain artifact is usable on all Linux distributions with glibc 2.17 or newer. If the customer uses an **OS with glibc < 2.17** (CentOS 6, RHEL 6, certain domestic OS), even a correct instruction set will produce `GLIBC_2.xx not found`. This issue **is out of scope** for this plan and requires:

- Repackaging using a `manylinux2014` / `manylinux_2_28` wheel approach; or
- Using `patchelf` on the customer side to redirect `libc.so.6`.

### 6.4 vectorscan upstream behavior change risk

The combination of `FAT_RUNTIME=off` + `-march=westmere` produces a pure baseline binary on vectorscan 5.4.12 (the version used here), **but**:

- vectorscan's build rules may change the inheritance order of `-march` between versions;
- Any future vectorscan upgrade must re-run the validation script from §5.3;
- If a version update introduces a code path that leaks AVX instructions even with AVX disabled, the §5.1 `objdump` check in CI will immediately catch it.

### 6.5 macOS and other platforms are not affected

In `build.sh:83-91`:

- `linux-arm64`: split into `linux-arm64-baseline` (ARMv8.0/NEON, `-march=armv8-a`, `BUILD_SVE=OFF BUILD_SVE2=OFF FAT_RUNTIME=off`) and `linux-arm64` (SVE2, `-march=armv9-a`, `BUILD_SVE=ON BUILD_SVE2=ON FAT_RUNTIME=off`). See `docs/architecture/linux-arm64-multi-variant.en.md`.
- `macosx-x86_64` / `macosx-arm64`: kept unchanged.

---

## 7. Implementation plan

Proceed in the following order; each step can be rolled back independently:

| Step | Content                                                                                  | Estimate  | Validation                           |
| ---- | ------------------------------------------------------------------------------------- | ----- | ------------------------------ |
| 1    | Apply the §4.1.3 build.sh changes on fork branch `feature/linux-baseline`                  | 0.5d  | `git diff` review                 |
| 2    | Run a full `./build.sh` locally (after `docker pull centos7-toolchain` image), output to `target/` | 0.5d  | All §5.3 checks pass               |
| 3    | Run `mvn test` and confirm regression passes                                                            | 0.5d  | JUnit all green                     |
| 4    | Add the §5.1 GitHub Actions ISA verification step on the fork and push to trigger CI                 | 0.5d  | Actions green, with "no AVX..." log visible |
| 5    | Release `5.4.12-2.0.5-baseline` or agree on a version number with upstream                                          | 0.5d  | Customer downloads, installs, and verifies        |
| 6    | Finalize documentation and archive this plan                                                                    | 0.2d  | This doc/PR merged                  |

Total ~3 person-days.

---

## 8. Rollback

If serious problems are found after release, roll back in the following order:

1. **Application-level rollback**: downstream `hyperscan-java` pins the version dependency back to `5.4.12-2.0.4`; no re-release needed.
2. **Repository rollback**: revert the merged commit on the fork and re-trigger the artifact for the v5.4.12-2.0.4 tag (the GitHub Actions workflow will rerun).
3. **Binary rollback**: if already published to Maven Central and cannot be withdrawn, distribute a local copy of `5.4.12-2.0.4.jar` to all internal users (the fork's GitHub Packages or a private Nexus works).

---

## 9. Appendix

### 9.1 Key file locations

- Build script: `build.sh`
- CI workflow: `.github/workflows/build.yml`
- POM / version: `pom.xml:7`
- Release script: `merge-artifacts.sh`
- Release config: `jreleaser.yml`
- This document: `doc/architecture/linux-x86_64-baseline.en.md`

### 9.2 Key command cheat sheet

```bash
# Check whether the CPU supports SSE4.2 / AVX2 / AVX-512
grep -oE 'sse4_2|popcnt|avx2|avx512f|avx512vbmi' /proc/cpuinfo | sort -u

# Check glibc version
ldd --version | head -1

# Check the lowest glibc version actually linked by the .so
objdump -T target/staging-deploy/**/libhs.so | grep GLIBC_ | awk '{print $NF}' | sort -V | uniq

# Disassembly check for AVX/AVX2/AVX-512 (VEX/EVEX mnemonics start with 'v')
objdump -d <libhs.so> | grep -E '^[[:space:]]+[0-9a-f]+:[[:space:]]+[0-9a-f ]+[[:space:]]+v'

# Final cross-check with vectorscan's own tool (provided in 5.4.12)
# hsvalidplatform runs without arguments
./hsvalidplatform
```

### 9.3 References

- vectorscan upstream: https://github.com/VectorCamp/vectorscan (5.4.12 tag)
- hyperscan-java upstream: https://github.com/gliwka/hyperscan-java
- GCC `-march` matrix: https://gcc.gnu.org/onlinedocs/gcc/x86-Options.html
- Intel Intrinsics Guide (SSE4.2 / POPCNT): https://www.intel.com/content/www/us/en/docs/intrinsics-guide/

---

## 10. Open questions

The following items need a decision before starting; otherwise the plan may diverge:

1. **Q1: Is a 30%–60% performance drop acceptable?**
   - If the customer workload is **offline batch / ETL** and throughput-insensitive, plan A is directly usable.
   - If the workload is a **real-time high-QPS gateway**, the throughput drop must be evaluated first; if unacceptable, plan B (multi-variant) is mandatory, costing an extra 1–2 person-days plus integration with `hyperscan-java`.
2. **Q2: Is the customer OS on glibc ≥ 2.17?**
   - Needs customer confirmation; if lower than 2.17, this plan cannot solve it and additional glibc handling is required.
3. **Q3: Version numbering strategy**
   - Recommend `5.4.12-2.0.5`, but confirm before publishing to Maven Central that upstream has not occupied it.
   - Should the fork use a special suffix such as `5.4.12-2.0.5-baseline`?
4. **Q4: Should CI build multi-variant artifacts as a "future enhancement"?**
   - This plan builds only a single baseline variant. If evolutionary capability should be preserved without releasing, add comments or placeholder entries in build.yml.

---

**End of document.** Trigger the implementation plan (§7) only after review approval.
