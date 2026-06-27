# Linux 多 Variant 改造方案

| 字段 | 值 |
|------|-----|
| 文档版本 | v2.1 |
| 适用范围 | `hyperscan-java-native`（fork） 5.4.12-2.0.4-x3+ |
| 仓库根 | `/home/xenoamess/workspace/hyperscan-java-native` |
| 状态 | 已实施 |

---

## 1. 背景

单基线版 `linux-x86_64-baseline`（SSE4.2 + POPCNT）解决了老 CPU 的 SIGILL 问题，但纯扫描场景性能损失约 **28%–35%**。

为了在 SSE4.2 老 CPU 上可用、在 AVX2 主流 CPU 上满速、在 AVX-512 高端 CPU 上发挥最大性能，本仓库实施了三 variant 方案：同一个 Maven classifier jar 内同时打包三套 native library，运行时根据 CPU 能力自动选择。

ARM64 端采用同样的思路拆分为 `linux-arm64-baseline`（ARMv8.0/NEON）与 `linux-arm64`（SVE2），详见 `docs/architecture/linux-arm64-multi-variant.md`。

---

## 2. 为什么原版 `FAT_RUNTIME=on` 不够

见 `docs/architecture/linux-x86_64-baseline.md` 与 `docs/architecture/investigating-avx-leak-in-original-jar.md`。核心问题：

- 原版 `libhs.so` / `libhs_runtime.so` 含有 **20,000+ 条 EVEX（AVX-512）指令**泄漏到普通代码。
- `FAT_RUNTIME` 只保护 `hs_scan` 等入口的 dispatch，挡不住这些泄漏。
- 结果连纯 AVX2 CPU 都可能 SIGILL。

因此新版每个 variant 都使用 `FAT_RUNTIME=off`，并用严格的 `-march` 把对应 variant 的指令集限制死。

---

## 3. 目标 CPU 分级

x86-64 上 vectorscan 能用到的 SIMD 只有三个等级：

| 等级 | classifier | 最低指令集 | 代表微架构 | 大致年份 |
|------|-----------|-----------|-----------|---------|
| 1 | `linux-x86_64-baseline` | SSE4.2 + POPCNT | Intel Westmere / AMD Bulldozer | 2010+ |
| 2 | `linux-x86_64-avx2` | AVX2 + BMI2 | Intel Haswell / AMD Zen | 2013+ |
| 3 | `linux-x86_64` | AVX-512（F/BW/VL）+ VBMI | Intel Skylake-X / Ice Lake / Sapphire Rapids | 2017+ |

> AVX-only（Sandy Bridge/Ivy Bridge，2011-2012）没有独立价值，vectorscan 没有 AVX-only 实现路径，只能降级到 SSE4.2 baseline。

---

## 4. 产物结构

发布时 Maven Central 上的 `native-<version>-linux-x86_64.jar` 是一个**聚合 jar**，内部同时包含三套 so：

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

`linux-arm64` 也按同样思路拆分为 `linux-arm64-baseline` 与 `linux-arm64`，统一打包进 `native-<version>-linux-arm64.jar`，详见 `docs/architecture/linux-arm64-multi-variant.md`。

---

## 5. build.sh 实现

`build.sh` 的 `linux-x86_64*` 分支根据 `DETECTED_PLATFORM` 选择 `-march` 与 AVX 开关：

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

GCC 9 兼容性：
- 仅在 baseline variant 需要将 vectorscan 内部的 `x86-64-v2` 别名替换为 `westmere`。
- 所有 variant 都删除 GCC 9 不支持的 `-Wno-stringop-overread`。

---

## 6. Java 运行时加载与 CPU 检测

新增 `HyperscanNativeLoader`：

```java
public final class HyperscanNativeLoader {
    public static synchronized void load() {
        if (loaded) return;

        // 尊重外部已设置的 platform 属性（例如 CI 单 variant 构建时）。
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

Linux x86_64 选择逻辑：

| CPU flags | 选用的 variant |
|-----------|---------------|
| `avx512f` + `avx512bw` + `avx512vl` (+ `avx512vbmi`) | `linux-x86_64` |
| `avx2` + `bmi2` | `linux-x86_64-avx2` |
| `sse4_2` + `popcnt` | `linux-x86_64-baseline` |
| 其他 | `linux-x86_64-baseline`（安全回退） |

为了让任何引用 `hyperscan` 类的地方都自动走 CPU 检测，生成的 `hyperscan.java` 静态块被后处理为：

```java
static { HyperscanNativeLoader.load(); }
```

后处理由 `maven-antrun-plugin` 在 `generate-sources` 阶段完成：

```xml
<replaceregexp
    file="${project.build.directory}/generated-sources/com/gliwka/hyperscan/jni/hyperscan.java"
    match="static\s*\{[^}]*Loader\.load\([^)]*\)\s*;[^}]*\}"
    replace="static { HyperscanNativeLoader.load(); }"
    byline="false"
    flags="g"
/>
```

`JavaCppPreset` 不再包含 `static { Loader.load(); }`，仅作为 `@Properties` 注解的持有者。

---

## 7. GitHub Actions 实现

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

每个 matrix 条目在 CentOS 7 容器里跑 `./build.sh`，`DETECTED_PLATFORM` 设为对应值。

x86_64 与 arm64 的全部 variant 在 `package-linux-native` 中分别聚合成统一的 classifier jar，最终发布到 Maven Central 的 artifact：

- `native-<version>.jar`（Java 类，含 `HyperscanNativeLoader`）
- `native-<version>-linux-x86_64.jar`（聚合三套 x86_64 so）
- `native-<version>-linux-arm64.jar`（聚合两套 arm64 so）
- sources / javadoc

### 7.2 ISA 校验

按 variant 分别校验：

| variant | 允许 | 禁止 |
|---------|------|------|
| `linux-x86_64-baseline` | SSE 系列 | 任何 `v` 开头助记符（VEX/EVEX） |
| `linux-x86_64-avx2` | VEX（AVX/AVX2） | EVEX（`62` 前缀，AVX-512） |
| `linux-x86_64` | VEX / EVEX | 无 |

### 7.3 聚合 package 任务

新增 `package-linux-native` job：

1. 下载三个 x86_64 variant 与两个 arm64 variant 的 staging artifact。
2. 提取各自的 classifier jar。
3. 把 x86_64 的 `linux-x86_64-avx2` 与 `linux-x86_64-baseline` 的 native 目录叠加到 `linux-x86_64` jar 中。
4. 把 arm64 的 `linux-arm64-baseline` 的 native 目录叠加到 `linux-arm64` jar 中。
5. 生成统一的 `native-<version>-linux-x86_64.jar` 与 `native-<version>-linux-arm64.jar`。

最终发布到 Maven Central 的 artifact：

- `native-<version>.jar`（Java 类，含 `HyperscanNativeLoader`）
- `native-<version>-linux-x86_64.jar`（聚合三套 x86_64 so）
- `native-<version>-linux-arm64.jar`（聚合两套 arm64 so）
- sources / javadoc

---

## 8. 风险与注意事项

### 8.1 AVX2 build 不能泄漏 AVX-512

CI 通过扫描 `62` 前缀（EVEX）拦截。若泄漏，纯 AVX2 CPU 仍会 SIGILL。

### 8.2 AVX-512 build 不能作为默认路径

由于 JavaCPP 自动检测会把普通 x86_64 Linux 识别为 `linux-x86_64`，必须由 `HyperscanNativeLoader` 在加载前把 platform 属性改写为正确的 variant。任何绕过 loader、直接引用 `hyperscan` 类前的 `Loader.load()` 都可能选错 variant。

### 8.3 消费端使用方式

`hyperscan-java` 或其他消费者应确保首次使用 hyperscan API 前调用：

```java
HyperscanNativeLoader.load();
```

或者通过 JavaCPP 系统属性强制指定：

```bash
-Dorg.bytedeco.javacpp.platform=linux-x86_64-avx2
```

### 8.4 版本号

本方案随 `5.4.12-2.0.4-x3` 发布。

---

## 9. 性能预期

| variant | 相对 baseline 性能 | 覆盖 CPU |
|---------|-------------------|---------|
| `linux-x86_64-baseline` | 100% | SSE4.2+ |
| `linux-x86_64-avx2` | +30%–50% | Haswell+/Zen+ |
| `linux-x86_64` | +40%–60%（相对 baseline，视 AVX-512 场景） | Skylake-X+/Ice Lake+ |

具体数据见 `docs/performance/linux-x86_64-baseline-benchmark.md`。

---

## 10. 参考资料

- `docs/architecture/linux-x86_64-baseline.md`：单基线改造方案
- `docs/architecture/linux-arm64-multi-variant.md`：ARM64 多 variant 改造方案
- `docs/architecture/investigating-avx-leak-in-original-jar.md`：原版 jar 指令泄漏排查
- `docs/performance/linux-x86_64-baseline-benchmark.md`：性能测试报告
- `src/main/java/com/gliwka/hyperscan/jni/HyperscanNativeLoader.java`：运行时加载器
- `src/main/java/com/gliwka/hyperscan/jni/JavaCppPreset.java`：JavaCPP preset

---

**文档结束。**
