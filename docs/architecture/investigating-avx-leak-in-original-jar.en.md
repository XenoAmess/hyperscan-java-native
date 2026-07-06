English | [中文](investigating-avx-leak-in-original-jar.zh.md)

# Investigation Record: Instruction Set Leakage in the Original Jar

| Field | Value |
|------|-----|
| Document version | v1.0 |
| Investigation date | 2026-06-26 |
| Investigation target | `com.gliwka.hyperscan:native:5.4.12-2.0.4:linux-x86_64` (original from Maven Central) |
| Investigator | opencode |
| Conclusion | The original `libhs.so` / `libhs_runtime.so` contain massive AVX-512 instruction leakage; `FAT_RUNTIME=on` cannot protect non-dispatch paths |

---

## 1. Investigation background

Customers reported that using the `5.4.12-2.0.4` / `linux-x86_64` artifact caused `Illegal instruction` (SIGILL) at startup or runtime on some machines.

The original build configuration was `FAT_RUNTIME=on` + `BUILD_AVX2=yes` + `BUILD_AVX512=yes` + `BUILD_AVX512VBMI=yes`. In theory the runtime should select an appropriate path based on CPUID, but customers still hit SIGILL. We therefore suspected that AVX/AVX-512 instructions had leaked into code outside the scope protected by the FAT runtime.

---

## 2. Investigation goals

1. Confirm whether the `.so` files in the original jar contain AVX/AVX2/AVX-512 instructions.
2. Confirm whether these instructions appear only in the FAT runtime dispatch variant implementations, or whether they have leaked into common code.
3. Distinguish the distribution of VEX-encoded (AVX/AVX2) and EVEX-encoded (AVX-512) instructions.
4. Determine whether pure AVX2 CPUs could trigger SIGILL due to these instructions.

---

## 3. Environment and tools

| Item | Description |
|------|------|
| Operating system | Ubuntu x86_64 |
| Disassembler | GNU binutils `objdump` |
| Archive tool | `unzip` |
| Download tool | `curl` |
| Text processing | `grep`, `wc`, `bash` |

---

## 4. Obtaining the original jar

Download the officially published native jar from Maven Central:

```bash
mkdir -p /tmp/opencode/original-check
cd /tmp/opencode/original-check

curl -sL -o original.jar \
  "https://repo1.maven.org/maven2/com/gliwka/hyperscan/native/5.4.12-2.0.4/native-5.4.12-2.0.4-linux-x86_64.jar"
```

Confirm the file exists after download:

```bash
ls -lh original.jar
```

Output:

```
-rw-rw-r-- 6.4M Jun 26 16:48 original.jar
```

---

## 5. Extracting the jar

A native jar is essentially a zip archive containing Java class files and `.so` dynamic libraries under platform directories. Extract it with `unzip`:

```bash
unzip -o original.jar -d original
```

Extracted directory structure:

```
original/
├── META-INF/
│   └── MANIFEST.MF
└── com/gliwka/hyperscan/jni/linux-x86_64/
    ├── libjnihyperscan.so
    ├── libhs.so
    └── libhs_runtime.so
```

List all `.so` files:

```bash
find original -name '*.so' -exec ls -lh {} \;
```

Output:

```
-rwxr-xr-x 5.7M Nov  5  2025 original/com/gliwka/hyperscan/jni/linux-x86_64/libhs_runtime.so
-rwxr-xr-x 9.7M Nov  5  2025 original/com/gliwka/hyperscan/jni/linux-x86_64/libhs.so
-rwxr-xr-x 171K Nov  5  2025 original/com/gliwka/hyperscan/jni/linux-x86_64/libjnihyperscan.so
```

---

## 6. Disassembly check method

### 6.1 Why `objdump -d`

`objdump` is the disassembler provided by GNU binutils. The `-d` option disassembles only **sections expected to contain code** (mainly `.text`), so it does not mistake data sections for instructions. Compared with `-D` (disassemble-all) it is safer and produces fewer false positives.

### 6.2 Encoding characteristics of AVX instructions

x86-64 SIMD instruction encoding prefixes are as follows:

| Encoding prefix | Instruction set | Description |
|---------|--------|------|
| `0f` / `f2` / `f3` + `0f` | SSE / SSE2 / SSE3 / SSSE3 / SSE4.x | 128-bit, legacy format |
| Starts with `c4` / `c5` | VEX | AVX / AVX2, 256-bit |
| Starts with `62` | EVEX | AVX-512, 512-bit |

Example `objdump` disassembly output:

```
  2062d9:  c4 e1 f9 6e cd        vmovq  %rbp,%xmm1
  206309:  c4 e3 f1 22 c0 01     vpinsrq $0x1,%rax,%xmm1,%xmm0
  20630f:  c4 c1 78 11 80 f8 00  vmovups %xmm0,0xf8(%r8)
```

Each line contains:
- Address: `2062d9:`
- Machine-code bytes: `c4 e1 f9 6e cd`
- Mnemonic: `vmovq %rbp,%xmm1`

AVX instruction mnemonics all start with `v`, which can be used as a secondary indicator.

### 6.3 First-round scan script

Goal: count the total VEX/EVEX instructions in each `.so`.

```bash
for SO in original/com/gliwka/hyperscan/jni/linux-x86_64/*.so; do
    echo "=== $(basename $SO) ==="
    HITS=$(objdump -d "$SO" \
        | grep -E '^[[:space:]]+[0-9a-f]+:[[:space:]]+[0-9a-f ]+[[:space:]]+v' \
        | wc -l)
    echo "VEX/EVEX instruction count: $HITS"
    if [ "$HITS" -gt 0 ]; then
        objdump -d "$SO" \
            | grep -E '^[[:space:]]+[0-9a-f]+:[[:space:]]+[0-9a-f ]+[[:space:]]+v' \
            | head -5
    fi
done
```

Script explanation:
- `objdump -d "$SO"`: disassemble the target so.
- `grep -E '^[[:space:]]+[0-9a-f]+:[[:space:]]+[0-9a-f ]+[[:space:]]+v'`: match disassembly instruction lines that start with an address and whose machine-code bytes column is followed by a mnemonic starting with `v`.
- `wc -l`: count matching lines.
- If greater than 0, print the first 5 examples.

First-round output:

```
=== libhs_runtime.so ===
VEX/EVEX instruction count: 57634
  2062d9:  c4 e1 f9 6e cd        vmovq  %rbp,%xmm1
  206309:  c4 e3 f1 22 c0 01     vpinsrq $0x1,%rax,%xmm1,%xmm0
  20630f:  c4 c1 78 11 80 f8 00  vmovups %xmm0,0xf8(%r8)
  20631e:  c5 f9 ef c0           vpxor  %xmm0,%xmm0,%xmm0
  206335:  c4 c1 78 11 80 38 01  vmovups %xmm0,0x138(%r8)

=== libhs.so ===
VEX/EVEX instruction count: 57634
  564de9:  c4 e1 f9 6e cd        vmovq  %rbp,%xmm1
  564e19:  c4 e3 f1 22 c0 01     vpinsrq $0x1,%rax,%xmm1,%xmm0
  564e1f:  c4 c1 78 11 80 f8 00  vmovups %xmm0,0xf8(%r8)
  564e2e:  c5 f9 ef c0           vpxor  %xmm0,%xmm0,%xmm0
  564e45:  c4 c1 78 11 80 38 01  vmovups %xmm0,0x138(%r8)

=== libjnihyperscan.so ===
VEX/EVEX instruction count: 0
```

**Key findings from the first round**:
- `libhs.so` and `libhs_runtime.so` each contain **57,634** VEX/EVEX instructions.
- `libjnihyperscan.so` (the JavaCPP JNI layer) is clean with 0 instructions.
- This shows the AVX instruction leakage is in the vectorscan main library and runtime library, not the JNI layer.

### 6.4 Second-round scan: distinguish AVX/AVX2 from AVX-512

Counting only `v`-prefixed mnemonics is insufficient because it cannot distinguish AVX/AVX2 (VEX) from AVX-512 (EVEX). Further classification by machine-code prefix is needed:

| Prefix | Description |
|------|------|
| `c4` / `c5` | VEX encoding, AVX / AVX2 |
| `62` | EVEX encoding, AVX-512 |

Script:

```bash
for SO in original/com/gliwka/hyperscan/jni/linux-x86_64/*.so; do
    echo "=== $(basename $SO) ==="
    echo "VEX (AVX/AVX2):"
    objdump -d "$SO" \
        | grep -E '^[[:space:]]+[0-9a-f]+:[[:space:]]+c4[[:space:]]+c[0-9a-f]' \
        | wc -l
    echo "EVEX (AVX-512):"
    objdump -d "$SO" \
        | grep -E '^[[:space:]]+[0-9a-f]+:[[:space:]]+62[[:space:]]' \
        | wc -l
done
```

Second-round output:

```
=== libhs_runtime.so ===
VEX (AVX/AVX2):
13716
EVEX (AVX-512):
20327

=== libhs.so ===
VEX (AVX/AVX2):
13716
EVEX (AVX-512):
20326

=== libjnihyperscan.so ===
VEX (AVX/AVX2):
0
EVEX (AVX-512):
0
```

---

## 7. Result summary

| File | VEX (AVX/AVX2) | EVEX (AVX-512) | Conclusion |
|------|---------------|----------------|------|
| `libhs.so` | 13,716 | 20,326 | Contains massive AVX-512 instruction leakage |
| `libhs_runtime.so` | 13,716 | 20,327 | Contains massive AVX-512 instruction leakage |
| `libjnihyperscan.so` | 0 | 0 | Clean |

---

## 8. Conclusion derivation

### 8.1 Why does `FAT_RUNTIME=on` still cause SIGILL?

`FAT_RUNTIME` works by performing CPUID dispatch inside entry functions such as `hs_scan`, forwarding calls to different implementations such as `corei7_*`, `avx2_*`, and `avx512_*`. This mechanism **only protects the dispatch entry points**.

But a library contains a lot of other code:
- Global constructors / initialization code
- Exception handling paths
- Compiler-auto-vectorized loops
- Various helper functions

These code paths were compiled with options such as `-march` or `-msse4.2`/`-mavx2`/`-mavx512`, causing AVX-512 instructions to leak directly into the `.text` section. When the library is loaded or any of these paths execute, they run directly on the CPU without going through dispatch.

### 8.2 Does a pure AVX2 CPU fail?

Yes.

- A pure AVX2 CPU can execute VEX-encoded AVX/AVX2 instructions.
- A pure AVX2 CPU **cannot** execute EVEX-encoded AVX-512 instructions.

The original `libhs.so` / `libhs_runtime.so` each contain **20,000+ EVEX instructions**. Whenever execution enters any code path containing these instructions, SIGILL is triggered.

Therefore, the original jar fails not only on CPUs that do not support AVX2, but also on **CPUs that support only AVX2 but not AVX-512**.

### 8.3 Takeaways

To fundamentally solve this problem, we cannot rely solely on `FAT_RUNTIME`. We must:

1. **Specify a strict `-march` at compile time** to prevent generating instructions beyond the target CPU's capability.
2. **Turn off all unnecessary AVX build switches**.
3. **Use `objdump` in CI to verify every `.so`**, ensuring no disabled instruction set leaks.

This is the core idea of the baseline refactoring plan (`-march=westmere` + CI ISA verification).

---

## 9. Complete command cheat sheet

```bash
# 1. Create working directory
mkdir -p /tmp/opencode/original-check
cd /tmp/opencode/original-check

# 2. Download original jar
curl -sL -o original.jar \
  "https://repo1.maven.org/maven2/com/gliwka/hyperscan/native/5.4.12-2.0.4/native-5.4.12-2.0.4-linux-x86_64.jar"

# 3. Extract
unzip -o original.jar -d original

# 4. List so files
find original -name '*.so' -exec ls -lh {} \;

# 5. Disassemble a single so and inspect
objdump -d original/com/gliwka/hyperscan/jni/linux-x86_64/libhs.so | less

# 6. Count all VEX/EVEX (AVX/AVX2/AVX-512) instructions
for SO in original/com/gliwka/hyperscan/jni/linux-x86_64/*.so; do
    echo "=== $(basename $SO) ==="
    objdump -d "$SO" \
        | grep -E '^[[:space:]]+[0-9a-f]+:[[:space:]]+[0-9a-f ]+[[:space:]]+v' \
        | wc -l
done

# 7. Distinguish VEX (AVX/AVX2) from EVEX (AVX-512)
for SO in original/com/gliwka/hyperscan/jni/linux-x86_64/*.so; do
    echo "=== $(basename $SO) ==="
    echo -n "VEX:  "
    objdump -d "$SO" \
        | grep -E '^[[:space:]]+[0-9a-f]+:[[:space:]]+c4[[:space:]]+c[0-9a-f]' \
        | wc -l
    echo -n "EVEX: "
    objdump -d "$SO" \
        | grep -E '^[[:space:]]+[0-9a-f]+:[[:space:]]+62[[:space:]]' \
        | wc -l
done
```

---

## 10. Pitfalls and notes from the investigation

### 10.1 Do not use `objdump -D`

`-D` disassembles all sections, including `.rodata`, `.data`, and other data sections. Random bytes in data sections may be misidentified as instructions, producing false positives. `-d` disassembles only code sections, giving more reliable results.

### 10.2 Do not rely only on mnemonics

Although AVX mnemonics start with `v`, could some SSE instruction AT&T mnemonics coincidentally contain `v`? In practice, no. But for greater accuracy, directly match the machine-code prefixes `c4`/`c5`/`62`; this is the most reliable criterion.

### 10.3 Distinguish VEX and EVEX

A pure AVX2 CPU can run VEX but not EVEX. Without the second round of classification, one might wrongly assume "as long as there is no AVX-512 dispatch path, it is safe."

### 10.4 Check all `.so` files

Do not check only `libhs.so`. `libhs_runtime.so` can also leak, and JavaCPP loads it together with `libhs.so`. The JNI layer `libjnihyperscan.so` should also be checked; although it was clean this time, different build environments may differ.

### 10.5 Watch the spaces in the regex

In `objdump` output the machine-code column has spaces between bytes. The regex `[0-9a-f ]+` can match these bytes, but make sure it does not cross columns into the mnemonic. Ending with `+v` anchors the mnemonic position.

---

## 11. References

- `docs/architecture/linux-x86_64-baseline.en.md`: baseline refactoring plan
- `docs/architecture/linux-x86_64-multi-variant.en.md`: multi-variant refactoring plan
- `docs/performance/linux-x86_64-baseline-benchmark.en.md`: performance benchmark report
- Intel SDM / AMD APM: x86-64 instruction encoding manuals

---

**End of investigation record.**
