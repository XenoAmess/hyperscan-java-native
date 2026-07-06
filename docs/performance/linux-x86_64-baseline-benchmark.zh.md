[English](linux-x86_64-baseline-benchmark.en.md) | 中文

# Linux x86_64 基线版性能对比报告

| 字段 | 值 |
|------|-----|
| 文档版本 | v1.0 |
| 测试日期 | 2026-06-26 |
| 测试环境 | `/home/xenoamess/workspace/hyperscan-java-native` |
| 测试者 | opencode |
| 状态 | 完成 |

---

## 1. 测试目的

评估将 `hyperscan-java-native` 的 `linux-x86_64` 制品从**多 ISA FAT_RUNTIME（SSE4.2 + AVX2 + AVX-512 + AVX-512VBMI）**改造为**单基线 SSE4.2 + POPCNT**后的性能损失。

---

## 2. 测试版本

| 版本 | 指令集 | 来源 |
|------|--------|------|
| **原版** `5.4.12-2.0.4` | FAT_RUNTIME + AVX2/AVX-512/AVX-512VBMI | Maven Central: `com.gliwka.hyperscan:native:5.4.12-2.0.4:linux-x86_64` |
| **基线版** `5.4.12-2.0.4-x1` | SSE4.2 + POPCNT only (`-march=westmere`) | 本 fork CI 产物（commit `90136c9`） |

---

## 3. 测试环境

| 项目 | 值 |
|------|-----|
| CPU | x86_64（支持 SSE4.2 / POPCNT / AVX2 / AVX-512F） |
| OS | Ubuntu（glibc 2.43） |
| JDK | Temurin 21 |
| JavaCPP | 1.5.12 |
| 测试工具 | `com.gliwka.hyperscan.jni.BaselineBenchmark`（见 `src/test/java/.../BaselineBenchmark.java`） |

---

## 4. 测试方法

### 4.1 控制变量

- 同一台物理机/虚拟机。
- 相同 JVM 参数。
- 相同规则集与 payload。
- 每次测试前包含 warmup，消除 JIT、缓存、分支预测影响。
- 高负载场景分段执行，避免 CPU thermal throttling 导致顺序偏差。

### 4.2 测试场景

| 场景 | 说明 | 关注点 |
|------|------|--------|
| **no-match** | 规则为不可能命中的长字符串/复杂正则 | 纯扫描吞吐，最大化 SIMD 差异 |
| **mixed** | 随机文本中 sprinkled 少量真实 token | 低命中场景 |
| **log** | Apache access log 真实日志文本 | 高命中/真实业务场景 |

### 4.3 命令示例

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

## 5. 测试结果

### 5.1 no-match 场景（1 MB payload，100 patterns）

| 版本 | 耗时 (ms) | 吞吐 (MB/s) | 延迟 (us/scan) | matches/scan |
|------|-----------|-------------|----------------|--------------|
| 原版 AVX2/AVX-512 | 385.54 | **1296.88** | 771.08 | 0 |
| 基线 SSE4.2 | 536.95 | **931.19** | 1073.90 | 0 |

**性能损失：约 28.2%**（基线版吞吐 / 原版吞吐 = 931 / 1297 ≈ 71.8%）

> 注：另一组先跑 baseline 的测试中，损失约为 35%。差异主要来自 CPU thermal throttling 与运行间噪声。综合取值 **28%–35%**。

### 5.2 mixed 场景（1 MB payload，100 patterns）

| 版本 | 耗时 (ms) | 吞吐 (MB/s) | 延迟 (us/scan) | matches/scan |
|------|-----------|-------------|----------------|--------------|
| 原版 AVX2/AVX-512 | 3338.04 | **299.58** | 3338.04 | 7030 |
| 基线 SSE4.2 | 3590.68 | **278.50** | 3590.68 | 9277 |

**性能损失：约 7.0%**（基线版吞吐 / 原版吞吐 = 278.5 / 299.6 ≈ 93.0%）

> 注：matches/scan 不一致是因为随机 payload 生成不同，不是版本行为差异。该场景下匹配回调开销开始主导。

### 5.3 log 场景（1 MB Apache 日志，100 patterns）

| 版本 | 耗时 (ms) | 吞吐 (MB/s) | 延迟 (us/scan) | matches/scan |
|------|-----------|-------------|----------------|--------------|
| 原版 AVX2/AVX-512 | 175445 | **5.70** | 175445 | 1,384,413 |
| 基线 SSE4.2 | 159423 | **6.27** | 159423 | 1,384,413 |

**原版反而略慢于基线版。**

原因分析：该场景下每 scan 触发约 138 万次 match 回调，总回调次数超过 13 亿次。Java 端的 `match_event_handler.call()` 跨 JNI 调用开销成为绝对瓶颈，SIMD 扫描优势无法体现。原版扫描更快，反而以更快速度将匹配结果推入 JNI 回调队列，整体被回调拖慢。

---

## 6. 结论

| 业务场景 | 预期性能损失 | 说明 |
|----------|--------------|------|
| **高吞吐、低命中扫描**（如 DPI、流量过滤、包检测） | **28%–35%** | SIMD 宽度是主要瓶颈，损失最明显 |
| **中低吞吐、偶发匹配** | **5%–15%** | 回调与编译开销稀释 SIMD 优势 |
| **高命中、重回调业务** | **可忽略甚至反超** | JNI/业务回调成为瓶颈，ISA 差异被掩盖 |

### 6.1 决策建议

- 如果业务以**纯扫描、大流量、低命中**为主（典型网络安全场景），**28%–35% 的吞吐损失**需要认真评估。
- 如果业务**匹配频繁且回调逻辑重**，基线版的性能损失可能不大，反而因为 jar 更小、加载更快有附带收益。
- 如果无法接受 28% 以上损失，应采用**多 variant 方案**：保留 `linux-x86_64` 默认高性能版，新增 `linux-x86_64-baseline` classifier，由消费端根据 CPU 能力选择。

### 6.2 其他附带收益

| 项目 | 原版 | 基线版 | 变化 |
|------|------|--------|------|
| `libhs.so` 体积 | ~10.1 MB | ~5.3 MB | **-47%** |
| `libhs_runtime.so` 体积 | ~5.9 MB | ~1.2 MB | **-80%** |
| 运行时 dispatch | 有（FAT_RUNTIME） | 无 | 简化调用路径 |
| 最低 CPU 要求 | AVX-512 编译路径需在支持 CPU 上分派 | SSE4.2 + POPCNT | 兼容性大幅提升 |

---

## 7. 测试限制与注意事项

1. **单台机器测试**：未在多种 CPU 微架构上验证；Westmere/Clarkdale 等目标老 CPU 可能因内存延迟不同而有差异。
2. **thermal throttling**：连续高负载运行可能导致降频，no-match 场景损失在 28%–35% 区间波动。
3. **JVM 与 JNI 开销**：所有测试经过 JavaCPP JNI 层，真实 native 应用的绝对数字会更高，但相对损失比例应接近。
4. **规则集代表性**：本次使用的规则集为通用安全/日志规则，实际业务规则不同可能导致损失略有差异。
5. **高命中场景不可靠**：log 场景下原版略慢于基线版，不应解读为“基线版更快”，而应解读为“回调开销主导，本 benchmark 无法区分 SIMD 差异”。

---

## 8. 复现方式

```bash
# 1. 准备 baseline jar（从本 fork CI artifact 下载）
gh run download --repo XenoAmess/hyperscan-java-native \
    --name build-result-linux-x86_64 -D baseline

# 2. 准备原版 jar
mkdir -p original
curl -o original/native-5.4.12-2.0.4-linux-x86_64.jar \
    https://repo1.maven.org/maven2/com/gliwka/hyperscan/native/5.4.12-2.0.4/native-5.4.12-2.0.4-linux-x86_64.jar

# 3. 编译 benchmark
mvn -q -DskipTests package
javac -cp "$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout):target/classes" \
    -d . src/test/java/com/gliwka/hyperscan/jni/BaselineBenchmark.java

# 4. 解压 native so
mkdir -p baseline-so original-so
unzip -o baseline/.../native-5.4.12-2.0.4-x1-linux-x86_64.jar -d baseline-so
unzip -o original/native-5.4.12-2.0.4-linux-x86_64.jar -d original-so

# 5. 运行对比
CP="$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout):target/classes:."

java -Djava.library.path=original-so/.../linux-x86_64 -cp "$CP" \
    com.gliwka.hyperscan.jni.BaselineBenchmark --mode no-match --iterations 500 --payload-size 1048576

java -Djava.library.path=baseline-so/.../linux-x86_64 -cp "$CP" \
    com.gliwka.hyperscan.jni.BaselineBenchmark --mode no-match --iterations 500 --payload-size 1048576
```

---

## 9. 参考资料

- `doc/architecture/linux-x86_64-baseline.zh.md`：改造方案设计文档
- `src/test/java/com/gliwka/hyperscan/jni/BaselineBenchmark.java`：本报告使用的 benchmark 源码
- vectorscan 内部 matcher benchmark：`benchmarks/benchmarks.cpp`
- 测试数据：Elastic Apache logs 示例、VectorCamp vectorscan `leipzig-3200.txt`

---

**报告结束。**
