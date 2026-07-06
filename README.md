# hyperscan-java-native

本仓库是 [gliwka/hyperscan-java-native](https://github.com/gliwka/hyperscan-java-native) 的维护分支，为 [hyperscan-java](https://github.com/gliwka/hyperscan-java) 提供 native 二进制依赖。

- 维护者：XenoAmess
- 当前版本：`5.4.12-2.0.4-x9`
- Maven 坐标：`com.xenoamess.hyperscan:native:5.4.12-2.0.4-x9`
- 上游 JavaCPP 版本：`1.5.11`

## 主要改进

### 多 ISA 变体（multi-variant）构建与运行时自动选择

原版 `FAT_RUNTIME=on` 仅在 `hs_scan` 等入口做运行时派发，通用代码中仍会泄漏 AVX/AVX-512 指令，导致旧 CPU 或屏蔽了 AVX-512 的虚拟化环境启动时报 `Illegal instruction`（SIGILL）。本 fork 改为：

- 每个 ISA tier **独立编译**（`FAT_RUNTIME=off`），并用 `-march` 严格锁定指令集；
- 同一 classifier jar 内打包多个变体；
- 启动时通过 `HyperscanNativeLoader` 读取 CPU 特性，自动选择可用变体。

#### Linux x86_64（三变体）

| 变体 | 最低指令集 | 说明 |
|------|-----------|------|
| `linux-x86_64-baseline` | SSE4.2 + POPCNT | 兼容 2010 年后 Westmere / Bulldozer 起的主流 x86-64 CPU |
| `linux-x86_64-avx2` | AVX2 + BMI2 | Haswell / Zen 起，扫描吞吐比 baseline 高约 30%–50% |
| `linux-x86_64` | AVX-512（F/BW/VL）+ VBMI | Skylake-X / Ice Lake / Sapphire Rapids 等 |

> **注意**：`5.4.12-2.0.4-x9` 起，即使 CPU 宣称支持 AVX-512，默认也回退到 `linux-x86_64-avx2`。原因是多数虚拟化/容器环境暴露 AVX-512 flag 却无法可靠执行 AVX-512 指令。如确认宿主支持，可强制指定 `-Dorg.bytedeco.javacpp.platform=linux-x86_64`。

#### Linux arm64（两变体）

| 变体 | 最低指令集 | 说明 |
|------|-----------|------|
| `linux-arm64-baseline` | ARMv8.0 + NEON/ASIMD | Graviton2 / Ampere Altra / 主流 ARMv8 服务器 |
| `linux-arm64` | ARMv9 + SVE2 | Graviton4 / Cobalt 100 / Apple M4+ |

#### Windows x86_64（两变体）

| 变体 | 最低指令集 | 说明 |
|------|-----------|------|
| `windows-x86_64-baseline` | SSE4.2 + POPCNT | 兼容主流 x86-64 CPU |
| `windows-x86_64` | AVX2 + BMI2 | Haswell / Zen 起 |

Windows 从 Intel Hyperscan 源码自行构建（vectorscan 官方不支持 Windows）。由于 Intel Hyperscan 5.4.2 的 MSVC 路径未设置 `SKYLAKE_FLAG`，**Windows 暂不提供 AVX-512 变体**。

#### macOS

`macosx-x86_64` / `macosx-arm64` 保持原构建策略不变。

### 运行时加载器 `HyperscanNativeLoader`

`src/main/java/com/gliwka/hyperscan/jni/HyperscanNativeLoader.java` 负责：

1. 读取 `/proc/cpuinfo`（Linux）或 `PROCESSOR_IDENTIFIER`（Windows）检测 CPU 特性；
2. 在首次调用 JavaCPP `Loader.load()` 前，设置 `org.bytedeco.javacpp.platform` 系统属性；
3. 让 JavaCPP 加载正确的变体库。

使用方式：

```java
import com.gliwka.hyperscan.jni.HyperscanNativeLoader;

HyperscanNativeLoader.load();
// 之后即可使用 hyperscan-java API
```

如需强制指定变体：

```bash
-Dorg.bytedeco.javacpp.platform=linux-x86_64-avx2
```

生成代码 `hyperscan.java` 的静态初始化块已被 `maven-antrun-plugin` 后处理为 `static { HyperscanNativeLoader.load(); }`，因此 consumer 通常无需手动调用。

### CI 与 ISA 校验

`.github/workflows/build.yml` 为每个 platform 矩阵条目构建独立变体，并在 `package-native` job 中合并为统一 classifier jar：

- `native-<version>-linux-x86_64.jar`：内含 baseline / avx2 / avx512 三套 `.so`
- `native-<version>-linux-arm64.jar`：内含 baseline / sve2 两套 `.so`
- `native-<version>-windows-x86_64.jar`：内含 baseline / avx2 两套 `.dll`

CI 对每个 x86_64 变体执行 `objdump` 校验：

- baseline：禁止任何 `v` 开头助记符（VEX/EVEX）；
- avx2：允许 VEX，禁止 EVEX（`62` 前缀，即 AVX-512）；
- avx512：不做额外限制。

发布通过 [JReleaser](https://jreleaser.org/) 推送到 Maven Central。

## 快速开始

```xml
<dependency>
    <groupId>com.xenoamess.hyperscan</groupId>
    <artifactId>native</artifactId>
    <version>5.4.12-2.0.4-x9</version>
    <classifier>${os.detected.classifier}</classifier>
</dependency>
```

或配合 [os-maven-plugin](https://github.com/trustin/os-maven-plugin) 自动选择 classifier。

## 构建

### Linux

```bash
# 默认构建当前平台
./build.sh

# 指定变体
DETECTED_PLATFORM=linux-x86_64-avx2 ./build.sh
```

### Windows

在 `windows-latest` GitHub Actions runner（或本地 Git Bash + MSYS2 + MSVC）中：

```bash
DETECTED_PLATFORM=windows-x86_64 ./build-windows.sh
```

## 架构文档

- [Linux x86_64 多变体方案](docs/architecture/linux-x86_64-multi-variant.md)
- [Linux x86_64 baseline 方案](docs/architecture/linux-x86_64-baseline.md)
- [Linux arm64 多变体方案](docs/architecture/linux-arm64-multi-variant.md)
- [Windows 多变体方案](docs/architecture/windows-multi-variant.md)
- [原版 jar AVX 指令泄漏排查](docs/architecture/investigating-avx-leak-in-original-jar.md)
- [Linux x86_64 baseline 性能基准](docs/performance/linux-x86_64-baseline-benchmark.md)

## 许可证

3-Clause BSD License
