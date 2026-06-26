# Linux x86_64 多 Variant 改造方案

| 字段 | 值 |
|------|-----|
| 文档版本 | v1.0 |
| 适用范围 | `hyperscan-java-native`（fork） 5.4.12-2.0.4+ |
| 仓库根 | `/home/xenoamess/workspace/hyperscan-java-native` |
| 状态 | 待评审 |

---

## 1. 背景

当前单基线版 `linux-x86_64-baseline`（SSE4.2 + POPCNT）解决了老 CPU 的 SIGILL 问题，但性能损失约 **28%–35%**（纯扫描场景）。

为了不损失新 CPU 性能，同时保证老 CPU 可用，需要引入**多 variant**：同一个 Maven artifact 内同时打包多套 native library，运行时根据 CPU 能力自动选择。

---

## 2. 为什么原版 `FAT_RUNTIME=on` 不够

见 `docs/architecture/linux-x86_64-baseline.md` §1.2。核心问题：

- 原版 `libhs.so` / `libhs_runtime.so` 含有 **20,000+ 条 EVEX（AVX-512）指令**泄漏到普通代码。
- `FAT_RUNTIME` 只保护 `hs_scan` 等入口的 dispatch，挡不住这些泄漏。
- 结果连纯 AVX2 CPU 都可能 SIGILL。

因此新版 `linux-x86_64` 不能再是 FAT_RUNTIME 全开，而应该是**精确的 AVX2-only build**。

---

## 3. 目标 CPU 分级

x86-64 上 vectorscan 能用到的 SIMD 只有三个等级：

| 等级 | 最低指令集 | 代表微架构 | 大致年份 | 占比估算 |
|------|-----------|-----------|---------|---------|
| 1 | SSE4.2 + POPCNT | Intel Westmere / AMD Bulldozer | 2010+ | 遗留/国产/低端 |
| 2 | AVX2 + BMI2 | Intel Haswell / AMD Zen | 2013+ | 主流桌面/服务器 |
| 3 | AVX-512（F/BW/CD/DQ/VL）+ VBMI | Intel Skylake-X / Ice Lake / Sapphire Rapids | 2017+ | 高端服务器/云 |

> 注：AVX-only（Sandy Bridge/Ivy Bridge，2011-2012）没有独立价值，因为 vectorscan 没有 AVX-only 实现路径，只能降级到 SSE4.2 baseline。

---

## 4. Variant 策略选择

### 4.1 方案 A：2 variant

| classifier | 指令集 | 构建配置 |
|-----------|--------|---------|
| `linux-x86_64` | AVX2 + BMI2 | `FAT_RUNTIME=off`，`-DBUILD_AVX2=ON`，`-DBUILD_AVX512=OFF`，`-march=haswell` |
| `linux-x86_64-baseline` | SSE4.2 + POPCNT | 当前已实现的 baseline |

**优点**：
- 复杂度低，CI 只增加一个矩阵条目。
- 覆盖 99% 以上的现代 x86-64 CPU。

**缺点**：
- AVX-512 CPU 跑 AVX2 版，损失约 10%（相比 AVX-512 最优路径）。
- 无法发挥 AVX-512 CPU 的最大性能。

### 4.2 方案 B：3 variant（推荐）

| classifier | 指令集 | 构建配置 |
|-----------|--------|---------|
| `linux-x86_64-baseline` | SSE4.2 + POPCNT | `-march=westmere` |
| `linux-x86_64-avx2` | AVX2 + BMI2 | `-march=haswell` |
| `linux-x86_64` | AVX-512 + VBMI | `FAT_RUNTIME=off`，`-DBUILD_AVX512=ON`，`-DBUILD_AVX512VBMI=ON`，`-march=skylake-avx512` |

**优点**：
- 每种 CPU 都跑到最佳路径。
- AVX-512 机器不再因为跑 AVX2 版而损失 10% 性能。

**缺点**：
- CI 增加两个矩阵条目。
- jar 体积增加三套 so。
- `hyperscan-java` 消费端 CPU 检测逻辑更复杂。
- 需要确认 AVX-512 build 在 GCC 9（CentOS7 容器）下不泄漏 AVX-512 到通用代码——如果泄漏，则这个 variant 在 AVX2 CPU 上仍会有 SIGILL 风险。

### 4.3 为什么不推荐 4 variant

x86-64 上不存在第四个有意义的 SIMD 等级。拆 AVX-512F/BW 和 AVX-512VBMI 为两个 jar 收益极小（VBMI 只是 AVX-512 的一个子扩展），但复杂度显著增加。

---

## 5. 推荐方案 B（3 variant）详细设计

### 5.1 产物结构

同一个 jar 内同时包含三套 so：

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

### 5.2 build.sh 改造

`build.sh` 的 `linux-x86_64*` 分支改为根据 `DETECTED_PLATFORM` 判断：

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

> 注：`haswell` 是 GCC 对 AVX2 + BMI2 + POPCNT 的标准微架构名。

### 5.3 GitHub Actions 改造

`build.yml` matrix 增加 baseline 条目：

```yaml
matrix:
  include:
    - os: linux
      runner: ubuntu-24.04
      shell: bash
      platform: linux-x86_64
    - os: linux
      runner: ubuntu-24.04
      shell: bash
      platform: linux-x86_64-baseline
    - os: linux
      runner: ubuntu-24.04-arm
      shell: bash
      platform: linux-arm64
```

ISA 校验步骤扩展为：

```yaml
- name: Verify ISA for selected variant
  if: matrix.os == 'linux'
  run: |
    set -euo pipefail
    if [ "${{ matrix.platform }}" = "linux-x86_64-baseline" ]; then
      # baseline: no VEX/EVEX at all
      for SO in target/staging-deploy/**/linux-x86_64-baseline/*.so; do
        if objdump -d "$SO" | grep -E '^[[:space:]]+[0-9a-f]+:[[:space:]]+[0-9a-f ]+[[:space:]]+v' > /dev/null; then
          echo "FAIL: $SO contains AVX instructions"; exit 1
        fi
      done
    elif [ "${{ matrix.platform }}" = "linux-x86_64" ]; then
      # avx2: allow VEX, forbid EVEX (AVX-512)
      for SO in target/staging-deploy/**/linux-x86_64/*.so; do
        if objdump -d "$SO" | grep -E '^[[:space:]]+[0-9a-f]+:[[:space:]]+62[[:space:]]' > /dev/null; then
          echo "FAIL: $SO contains AVX-512 (EVEX) instructions"; exit 1
        fi
      done
    fi
```

### 5.4 产物合并

当前 `merge-artifacts.sh` 会把多个 classifier 的 staging-deploy 合并。只要 CI 两个 matrix 都跑完，发布到 Maven 的 jar 会自然包含两个目录。

但需要确认 `maven-jar-plugin` 的 `classifier` 不会覆盖。做法：
- 两个 platform 分别跑 `mvn package` 时，`classifier=${DETECTED_PLATFORM}`。
- 最后用一个额外步骤把所有 classifier jar 合并成无 classifier 的聚合 jar，或发布多个 classifier artifact。

推荐**发布单个聚合 jar**（包含所有 classifier 目录），这样消费端依赖最简单。

### 5.5 `hyperscan-java` 消费端改造

在 `hyperscan-java` 仓库里，加载 native 库前检测 CPU：

```java
public class HyperscanLoader {
    private static String selectPlatform() {
        String detected = Loader.getPlatform();
        if (!"linux-x86_64".equals(detected)) {
            return detected; // arm64, macosx, etc.
        }
        try (BufferedReader br = Files.newBufferedReader(Paths.get("/proc/cpuinfo"))) {
            String flags = br.lines()
                .filter(l -> l.startsWith("flags"))
                .findFirst()
                .orElse("");
            if (flags.contains("avx2") && flags.contains("bmi2")) {
                return "linux-x86_64";
            }
        } catch (IOException ignored) {}
        return "linux-x86_64-baseline";
    }

    public static void load() {
        String platform = selectPlatform();
        Loader.load(hyperscan.class, platform);
    }
}
```

或者更简单：提供系统属性覆盖：

```java
String platform = System.getProperty("hyperscan.platform", selectPlatform());
Loader.load(hyperscan.class, platform);
```

---

## 6. 风险与注意事项

### 6.1 AVX2 build 不能泄漏 AVX-512

这是关键。必须：
- `-DBUILD_AVX512=OFF`
- `-DBUILD_AVX512VBMI=OFF`
- `-march=haswell`
- CI 用 EVEX 检测拦截

如果 AVX-512 泄漏到 AVX2 build，纯 AVX2 CPU 仍会 SIGILL。

### 6.2 GCC 9 / CentOS7 兼容性

`-march=haswell` 在 GCC 9 上完全支持，不需要像 `x86-64-v2` 那样 patch。

### 6.3 JavaCPP Loader 行为

需要验证 `Loader.load(cls, "linux-x86_64-baseline")` 是否会正确加载 `com/gliwka/hyperscan/jni/linux-x86_64-baseline/` 下的 so。JavaCPP 1.5.12 支持 classifier 作为 platform 名，但建议先在本地验证。

### 6.4 版本号策略

推荐直接发布 `5.4.12-2.0.5` 或 `5.4.12-2.0.4-x2`，不再带 `-baseline` 后缀，因为 jar 内同时包含两个 variant。

---

## 7. 实施计划

| 步骤 | 内容 | 验证 |
|------|------|------|
| 1 | 改 `build.sh` 支持 `linux-x86_64` 和 `linux-x86_64-baseline` 两个平台 | 本地/CI 构建通过 |
| 2 | 改 `build.yml` matrix + ISA 校验 | CI 绿 |
| 3 | 调整 `merge-artifacts.sh` / `pom.xml` 合并为单 jar | jar 内同时有两个目录 |
| 4 | 改 `hyperscan-java` 的 loader 做 CPU 检测 | 在 AVX2 和老 CPU 上都测试 |
| 5 | 发布新版本 | 客户验证 |

---

## 8. 性能预期

| variant | 相对 baseline 性能 | 覆盖 CPU |
|---------|-------------------|---------|
| `linux-x86_64-baseline` | 100% | SSE4.2+ |
| `linux-x86_64`（AVX2） | +30%–50% | Haswell+/Zen+ |

AVX-512 CPU 跑 AVX2 版预计损失约 10%（相比最优 AVX-512 路径）。如果无法接受这 10%，需采用方案 B（3 variant）。

---

## 9. 参考资料

- `docs/architecture/linux-x86_64-baseline.md`：单基线改造方案
- `docs/performance/linux-x86_64-baseline-benchmark.md`：性能测试报告
- `src/test/java/com/gliwka/hyperscan/jni/BaselineBenchmark.java`：benchmark 源码

---

**文档结束。**
