# Windows 多 Variant 支持方案

| 字段 | 值 |
|------|-----|
| 文档版本 | v1.1 |
| 适用范围 | `hyperscan-java-native`（fork） 未来版本 |
| 仓库根 | `/home/xenoamess/workspace/hyperscan-java-native` |
| 状态 | 计划中 |

---

## 1. 总体策略

采用 **方案 C（调整版）**：

- Linux（x86_64 / arm64）继续使用 **VectorCamp/vectorscan** 源码构建多 variant native 库，方案不变。
- Windows **仅支持 x86_64**，从 **Intel Hyperscan 源码** 自行编译三个 ISA tier 的 native 库，按 variant 打包，与 Linux 的加载/打包逻辑对齐。

原因：
- vectorscan 官方不支持 Windows 原生编译；
- Intel Hyperscan 官方 release **不发布预编译 Windows 二进制**（仅有源码包）；
- 第三方包管理器（Conan / vcpkg）中的 Windows 包多为单一 tier，不满足多 variant 需求；
- Intel Hyperscan 本身 **不支持 Windows arm64**（vcpkg 标记 `!arm`），因此 Windows arm64 放弃；
- JavaCPP 只需要一致的 C 头文件与 `hs_*` 导出符号即可生成绑定，Windows 与 Linux 的 native 库来源可以不同。

---

## 2. 目标平台与 CPU 分级

### 2.1 Windows x86_64

| 等级 | classifier | 最低指令集 | 代表微架构 |
|------|-----------|-----------|-----------|
| 1 | `windows-x86_64-baseline` | SSE4.2 + POPCNT | Intel Westmere / AMD Bulldozer |
| 2 | `windows-x86_64-avx2` | AVX2 + BMI2 | Intel Haswell / AMD Zen |
| 3 | `windows-x86_64` | AVX-512（F/BW/VL）+ VBMI | Intel Skylake-X / Ice Lake |

Windows 无 Linux 的 `ifunc` fat runtime，因此每个 variant 是独立编译的静态/动态库，由 Java 运行时根据 CPU 特性选择。

### 2.2 Windows arm64

**不支持。** Intel Hyperscan 与 vectorscan 均无 Windows arm64 构建支持。未来若上游支持，可再扩展。

---

## 3. JavaCPP Preset 改造

在 `src/main/java/com/gliwka/hyperscan/jni/JavaCppPreset.java` 的 `@Platform` 列表中新增：

```java
"windows-x86_64",
"windows-x86_64-avx2",
"windows-x86_64-baseline",
```

并确保：
- `compiler = "cpp11"` 在 Windows 下仍适用；
- `include` 与 `link` 配置与 Linux 一致（`hs/hs_common.h`、`hs/hs_compile.h`、`hs/hs_runtime.h`、`hs/hs.h`，链接 `hs`、`hs_runtime`）。

---

## 4. 运行时加载器改造

扩展 `HyperscanNativeLoader`：

### 4.1 平台检测

在 `selectPlatform()` 中识别 Windows：

```java
boolean isWindows = os.contains("windows");

if (isWindows && isX86_64) {
    return selectWindowsX86_64Variant();
}
```

### 4.2 Windows x86_64 选择逻辑

使用 JDK 内置方式读取 CPUID 或调用 Windows API：

| CPU 特性 | 选用的 variant |
|----------|---------------|
| `avx512f` + `avx512bw` + `avx512vl` (+ `avx512vbmi`) | `windows-x86_64` |
| `avx2` + `bmi2` | `windows-x86_64-avx2` |
| `sse4_2` + `popcnt` | `windows-x86_64-baseline` |
| 其他 | `windows-x86_64-baseline` |

实现方式候选：
- 使用 JNA 调用 `kernel32.GetNativeSystemInfo` + `IsProcessorFeaturePresent`；
- 若不想引入 JNA，可用 JNI 或解析 Windows 注册表 `HKEY_LOCAL_MACHINE\HARDWARE\DESCRIPTION\System\CentralProcessor\0` 的 `FeatureSet`。

### 4.3 Windows arm64 选择逻辑

**不支持。**

---

## 5. 构建脚本

### 5.1 新增 `build-windows.sh`

职责：
1. 根据 `DETECTED_PLATFORM` 确定目标 tier；
2. 下载 Intel Hyperscan 源码（如 `v5.4.2.tar.gz`）并校验 checksum；
3. 下载 boost 头文件（Hyperscan 编译依赖）；
4. 使用 MSVC（通过 GitHub Actions 的 `windows-latest` 环境）或 MinGW-w64 编译；
5. 将头文件放入 `cppbuild/include/hs/`，DLL/LIB 放入 `cppbuild/lib/`；
6. 运行 `mvn -B -DskipTests -Dorg.bytedeco.javacpp.platform=$DETECTED_PLATFORM`，让 JavaCPP 生成绑定并打包为 classifier jar。

CMake 参数按 tier 区分：

| variant | MSVC arch flag | `BUILD_AVX2` | `BUILD_AVX512` | `BUILD_AVX512VBMI` | `FAT_RUNTIME` |
|---------|---------------|--------------|----------------|--------------------|---------------|
| `windows-x86_64-baseline` | `/arch:AVX` 或默认 x86_64 | OFF | OFF | OFF | OFF |
| `windows-x86_64-avx2` | `/arch:AVX2` | ON | OFF | OFF | OFF |
| `windows-x86_64` | `/arch:AVX512` | ON | ON | ON | OFF |

> MSVC 的 `/arch` 选项无法直接指定 SSE4.2；Hyperscan 的 SSE4.2 代码通过 intrinsic 编译，只要目标 CPU 支持即可。baseline 使用默认 x86_64 或 `/arch:AVX` 编译，运行时仍要求 SSE4.2 + POPCNT。

### 5.2 头文件来源

使用 Intel Hyperscan 源码中的头文件。若与 vectorscan 头文件存在差异（新增/删除 API），需要：
- 在 `JavaCppPreset` 的 `map(InfoMap)` 中做 `Info` 映射或跳过；
- 保证 Linux 与 Windows 公共 API 一致，避免 consumer 代码编译失败。

---

## 6. GitHub Actions Workflow

### 6.1 新增 build-native matrix 条目

```yaml
- os: windows
  runner: windows-latest
  platform: windows-x86_64-baseline
- os: windows
  runner: windows-latest
  platform: windows-x86_64-avx2
- os: windows
  runner: windows-latest
  platform: windows-x86_64
```

### 6.2 Windows 构建步骤

- 使用 `actions/checkout`；
- 使用 `actions/setup-java`（Temurin JDK 21，或按需求 JDK 8/11/17/21）；
- 安装依赖：
  - CMake（Windows runner 通常已带，或 `actions/setup-cmake`）；
  - Python（Hyperscan 构建需要）；
  - MSVC（`windows-latest` 已带 Visual Studio 2022）；
  - boost 头文件；
- 在 Git Bash 或 PowerShell 中运行 `./build-windows.sh`；
- 上传 `target/staging-deploy` artifact。

### 6.3 聚合 job 扩展

将 `package-linux-native` 重命名为 `package-native`：
- 下载 Linux x86_64 / arm64 的 variant artifacts；
- 下载 Windows x86_64 的 variant artifacts；
- 分别合并为：
  - `native-<version>-linux-x86_64.jar`
  - `native-<version>-linux-arm64.jar`
  - `native-<version>-windows-x86_64.jar`
- 所有 classifier jars 放入同一 staging repo。

### 6.4 测试 job

新增：
- `test-windows-x86_64`：在 `windows-latest` runner 上跑 `EndToEndTest`，覆盖 default / baseline / avx2。

### 6.5 publish job

`needs` 增加 Windows 测试 job：

```yaml
needs:
  - package-native
  - test-linux-x86_64
  - test-linux-arm64
  - test-windows-x86_64
```

---

## 7. 产物结构

最终 Maven Central 发布：

```
native-<version>.jar
native-<version>-sources.jar
native-<version>-javadoc.jar
native-<version>-linux-x86_64.jar     # 含 linux-x86_64 / linux-x86_64-avx2 / linux-x86_64-baseline
native-<version>-linux-arm64.jar      # 含 linux-arm64 / linux-arm64-baseline
native-<version>-windows-x86_64.jar   # 含 windows-x86_64 / windows-x86_64-avx2 / windows-x86_64-baseline
```

Windows classifier jar 内部结构示例：

```
com/gliwka/hyperscan/jni/windows-x86_64/hs.dll
com/gliwka/hyperscan/jni/windows-x86_64/hs_runtime.dll
com/gliwka/hyperscan/jni/windows-x86_64/jnihyperscan.dll

com/gliwka/hyperscan/jni/windows-x86_64-avx2/hs.dll
com/gliwka/hyperscan/jni/windows-x86_64-avx2/hs_runtime.dll
com/gliwka/hyperscan/jni/windows-x86_64-avx2/jnihyperscan.dll

com/gliwka/hyperscan/jni/windows-x86_64-baseline/hs.dll
...
```

---

## 8. 风险与依赖

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| Intel Hyperscan 源码与 vectorscan 5.4.12 API 不完全一致 | JavaCPP 生成代码或 consumer 编译失败 | 用统一最小公共头文件子集；必要时 `InfoMap` 跳过差异 API |
| MSVC 编译 Hyperscan 源码复杂度较高 | CI 构建失败 | 先在本地/CI 做 PoC；必要时降级为 MinGW-w64 |
| Windows 无 fat runtime，单个 variant 二进制较大 | 聚合 jar 体积增加 | 与 Linux 方案一致，可接受 |
| Windows CPU 特性检测复杂 | 选错 variant 导致 IllegalInstruction | 优先保守回退 baseline；JNA 或 JNI 实现检测 |
| JavaCPP 在 Windows 上 platform 命名与 loader 交互 | loader 设置 platform 属性后 JavaCPP 找不到资源 | 严格按 JavaCPP 官方 classifier 命名（`windows-x86_64` 等） |
| Windows arm64 不支持 | 无法覆盖 WoA 设备 | 文档明确说明；等待上游支持 |

---

## 9. 实施顺序

1. **PoC**：在 GitHub Actions `windows-latest` 上只编译 `windows-x86_64-baseline`，验证 `EndToEndTest` 能跑通。
2. **扩展 x86_64**：补齐 `windows-x86_64-avx2` / `windows-x86_64`。
3. **加载器**：实现 Windows x86_64 CPU 特性检测。
4. **CI 集成**：更新 workflow、聚合 job、测试 job、publish job。
5. **文档与发布**：更新架构文档，bump 版本，打 tag 发版。

---

## 10. 参考资料

- `docs/architecture/linux-x86_64-multi-variant.md`
- `docs/architecture/linux-arm64-multi-variant.md`
- `src/main/java/com/gliwka/hyperscan/jni/HyperscanNativeLoader.java`
- `src/main/java/com/gliwka/hyperscan/jni/JavaCppPreset.java`
- Intel Hyperscan releases: https://github.com/intel/hyperscan/releases
- Intel Hyperscan build instructions for Windows: https://github.com/intel/hyperscan/blob/v5.4.2/README.md

---

**文档结束。**
