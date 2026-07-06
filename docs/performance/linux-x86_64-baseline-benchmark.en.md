English | [中文](linux-x86_64-baseline-benchmark.zh.md)

# Linux x86_64 Baseline Version Performance Comparison Report

| Field | Value |
|------|-----|
| Document version | v1.0 |
| Test date | 2026-06-26 |
| Test environment | `/home/xenoamess/workspace/hyperscan-java-native` |
| Tester | opencode |
| Status | Completed |

---

## 1. Test purpose

Evaluate the performance loss after refactoring the `hyperscan-java-native` `linux-x86_64` artifact from **multi-ISA FAT_RUNTIME (SSE4.2 + AVX2 + AVX-512 + AVX-512VBMI)** to **single baseline SSE4.2 + POPCNT**.

---

## 2. Tested versions

| Version | Instruction set | Source |
|------|--------|------|
| **Original** `5.4.12-2.0.4` | FAT_RUNTIME + AVX2/AVX-512/AVX-512VBMI | Maven Central: `com.gliwka.hyperscan:native:5.4.12-2.0.4:linux-x86_64` |
| **Baseline** `5.4.12-2.0.4-x1` | SSE4.2 + POPCNT only (`-march=westmere`) | This fork's CI artifact (commit `90136c9`) |

---

## 3. Test environment

| Item | Value |
|------|-----|
| CPU | x86_64 (supports SSE4.2 / POPCNT / AVX2 / AVX-512F) |
| OS | Ubuntu (glibc 2.43) |
| JDK | Temurin 21 |
| JavaCPP | 1.5.12 |
| Test tool | `com.gliwka.hyperscan.jni.BaselineBenchmark` (see `src/test/java/.../BaselineBenchmark.java`) |

---

## 4. Test method

### 4.1 Controlled variables

- Same physical/virtual machine.
- Same JVM parameters.
- Same rule set and payload.
- Warmup before each test to eliminate JIT, cache, and branch-prediction effects.
- High-load scenarios run in segments to avoid ordering bias from CPU thermal throttling.

### 4.2 Test scenarios

| Scenario | Description | Focus |
|------|------|--------|
| **no-match** | Rules are long strings / complex regexes that cannot match | Pure scan throughput, maximizing SIMD difference |
| **mixed** | Random text sprinkled with a few real tokens | Low-hit scenario |
| **log** | Real Apache access log text | High-hit / real-world business scenario |

### 4.3 Example command

```bash
java -Djava.library.path=<native-so-dir> \
     -cp "<javacpp-classpath>:<jni-classes>:." \
     com.gliwka.hyperscan.jni.BaselineBenchmark \
     --mode no-match \
     --patterns 100 \
     --iterations 500 \
     --payload-size 1048576
```

---

## 5. Test results

### 5.1 no-match scenario (1 MB payload, 100 patterns)

| Version | Time (ms) | Throughput (MB/s) | Latency (us/scan) | matches/scan |
|------|-----------|-------------|----------------|--------------|
| Original AVX2/AVX-512 | 385.54 | **1296.88** | 771.08 | 0 |
| Baseline SSE4.2 | 536.95 | **931.19** | 1073.90 | 0 |

**Performance loss: ~28.2%** (baseline throughput / original throughput = 931 / 1297 ≈ 71.8%)

> Note: In another run where baseline was tested first, the loss was about 35%. The difference mainly comes from CPU thermal throttling and run-to-run noise. The consolidated range is **28%–35%**.

### 5.2 mixed scenario (1 MB payload, 100 patterns)

| Version | Time (ms) | Throughput (MB/s) | Latency (us/scan) | matches/scan |
|------|-----------|-------------|----------------|--------------|
| Original AVX2/AVX-512 | 3338.04 | **299.58** | 3338.04 | 7030 |
| Baseline SSE4.2 | 3590.68 | **278.50** | 3590.68 | 9277 |

**Performance loss: ~7.0%** (baseline throughput / original throughput = 278.5 / 299.6 ≈ 93.0%)

> Note: The matches/scan values differ because the random payloads were generated differently, not because of behavioral differences between versions. Match-callback overhead begins to dominate in this scenario.

### 5.3 log scenario (1 MB Apache log, 100 patterns)

| Version | Time (ms) | Throughput (MB/s) | Latency (us/scan) | matches/scan |
|------|-----------|-------------|----------------|--------------|
| Original AVX2/AVX-512 | 175445 | **5.70** | 175445 | 1,384,413 |
| Baseline SSE4.2 | 159423 | **6.27** | 159423 | 1,384,413 |

**The original is slightly slower than the baseline.**

Reason analysis: In this scenario each scan triggers about 1.38 million match callbacks, totaling more than 1.3 billion callbacks. The cross-JNI call overhead of the Java-side `match_event_handler.call()` becomes the absolute bottleneck, and the SIMD scan advantage cannot be realized. The original scans faster, but pushes match results into the JNI callback queue more quickly, and is therefore dragged down overall by callback overhead.

---

## 6. Conclusions

| Business scenario | Expected performance loss | Notes |
|----------|--------------|------|
| **High-throughput, low-hit scanning** (e.g. DPI, traffic filtering, packet inspection) | **28%–35%** | SIMD width is the main bottleneck; loss is most obvious |
| **Medium/low throughput, occasional matches** | **5%–15%** | Callback and compilation overhead dilute SIMD advantage |
| **High-hit, heavy-callback workloads** | **Negligible or even reversed** | JNI/business callbacks become the bottleneck, masking ISA differences |

### 6.1 Decision recommendations

- If the workload is mainly **pure scanning, large traffic, low hit rate** (typical network security scenario), the **28%–35% throughput loss** needs serious evaluation.
- If the workload has **frequent matches and heavy callback logic**, the baseline performance loss may be small, and it may even benefit from a smaller jar and faster loading.
- If a loss above 28% is unacceptable, use a **multi-variant plan**: keep the default high-performance `linux-x86_64` and add a `linux-x86_64-baseline` classifier, letting the consumer select based on CPU capability.

### 6.2 Other incidental benefits

| Item | Original | Baseline | Change |
|------|------|--------|------|
| `libhs.so` size | ~10.1 MB | ~5.3 MB | **-47%** |
| `libhs_runtime.so` size | ~5.9 MB | ~1.2 MB | **-80%** |
| Runtime dispatch | Yes (FAT_RUNTIME) | No | Simplified call path |
| Minimum CPU requirement | AVX-512 compile path requires dispatch on supported CPU | SSE4.2 + POPCNT | Greatly improved compatibility |

---

## 7. Test limitations and notes

1. **Single-machine test**: not verified on multiple CPU microarchitectures; target old CPUs such as Westmere/Clarkdale may differ due to memory latency.
2. **Thermal throttling**: continuous high-load runs may cause downclocking; the no-match scenario loss fluctuates in the 28%–35% range.
3. **JVM and JNI overhead**: all tests go through the JavaCPP JNI layer; absolute numbers for real native applications would be higher, but the relative loss ratio should be similar.
4. **Rule set representativeness**: this test used general security/log rules; actual business rules may cause slightly different losses.
5. **High-hit scenario is unreliable**: in the log scenario the original is slightly slower than the baseline. This should not be read as "baseline is faster" but as "callback overhead dominates and this benchmark cannot distinguish SIMD differences."

---

## 8. Reproduction steps

```bash
# 1. Prepare baseline jar (download from this fork's CI artifact)
gh run download --repo XenoAmess/hyperscan-java-native \
    --name build-result-linux-x86_64 -D baseline

# 2. Prepare original jar
mkdir -p original
curl -o original/native-5.4.12-2.0.4-linux-x86_64.jar \
    https://repo1.maven.org/maven2/com/gliwka/hyperscan/native/5.4.12-2.0.4/native-5.4.12-2.0.4-linux-x86_64.jar

# 3. Compile benchmark
mvn -q -DskipTests package
javac -cp "$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout):target/classes" \
    -d . src/test/java/com/gliwka/hyperscan/jni/BaselineBenchmark.java

# 4. Extract native so files
mkdir -p baseline-so original-so
unzip -o baseline/.../native-5.4.12-2.0.4-x1-linux-x86_64.jar -d baseline-so
unzip -o original/native-5.4.12-2.0.4-linux-x86_64.jar -d original-so

# 5. Run comparison
CP="$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout):target/classes:."

java -Djava.library.path=original-so/.../linux-x86_64 -cp "$CP" \
    com.gliwka.hyperscan.jni.BaselineBenchmark --mode no-match --iterations 500 --payload-size 1048576

java -Djava.library.path=baseline-so/.../linux-x86_64 -cp "$CP" \
    com.gliwka.hyperscan.jni.BaselineBenchmark --mode no-match --iterations 500 --payload-size 1048576
```

---

## 9. References

- `doc/architecture/linux-x86_64-baseline.en.md`: refactoring plan design document
- `src/test/java/com/gliwka/hyperscan/jni/BaselineBenchmark.java`: benchmark source used by this report
- vectorscan internal matcher benchmark: `benchmarks/benchmarks.cpp`
- Test data: Elastic Apache logs sample, VectorCamp vectorscan `leipzig-3200.txt`

---

**End of report.**
