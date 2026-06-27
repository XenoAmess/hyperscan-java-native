# Windows 多 Variant 支持方案

| 字段 | 值 |
|------|-----|
| 文档版本 | v1.0 |
| 适用范围 | `hyperscan-java-native`（fork） 未来版本 |
| 仓库根 | `/home/xenoamess/workspace/hyperscan-java-native` |
| 状态 | 计划中 |

---

## 1. 总体策略

采用 **方案 C**：

- Linux（x86_64 / arm64）继续使用 **VectorCamp/vectorscan** 源码构建多 variant native 库，方案不变。
- Windows（x86_64 / arm64）使用 **Intel Hyperscan 官方预编译 DLL**，按 ISA 层级拆分为多个 variant，与 Linux 的加载/打包逻辑对齐。

原因：
- vectorscan 官方不支持 Windows 原生编译；
- Intel Hyperscan 提供官方 Windows release，包含预编译 DLL、头文件与导入库；
- JavaCPP 只需要一致的 C 头文件与 `hs_*` 导出符号即可生成绑定，native 库来源可以不同。

---

## 2. 目标平台与 CPU 分级

### 2.1 Windows x86_64

Intel Hyperscan Windows 官方二进制通常按 SIMD 提供多版本。计划对应到以下 classifier：

| 等级 | classifier | 最低指令集 | 代表微架构 |
|------|-----------|-----------|-----------|
| 1 | `windows-x86_64-baseline` | SSE4.2 + POPCNT | Intel Westmere / AMD Bulldozer |
| 2 | `windows-x86_64-avx2` | AVX2 + BMI2 | Intel Haswell / AMD Zen |
| 3 | `windows-x86_64` | AVX-512（F/BW/VL）+ VBMI | Intel Skylake-X / Ice Lake |

> 若 Intel 官方 release 没有严格对应 tier 的 DLL，则通过不同编译选项或不同 release 包自行组织目录；上层 Java 加载逻辑保持不变。

### 2.2 Windows arm64

| 等级 | classifier | 最低指令集 | 代表微架构 |
|------|-----------|-----------|-----------|
| 1 | `windows-arm64-baseline` | ARMv8.0 + NEON/ASIMD | Qualcomm Snapdragon 8cx / Ampere Altra |
| 2 | `windows-arm64` | SVE2 | 未来 Windows on ARM SVE2 设备 |

> 风险：Intel Hyperscan 官方是否提供 Windows arm64 DLL 需要调研确认。若缺失，则 Windows arm64 支持阻塞，需降级为自行用 clang-cl/MSVC 从源码编译，或暂时仅支持 x86_64。

---

## 3. JavaCPP Preset 改造

在 `src/main/java/com/gliwka/hyperscan/jni/JavaCppPreset.java` 的 `@Platform` 列表中新增：

```java
"windows-x86_64",
"windows-x86_64-avx2",
"windows-x86_64-baseline",
"windows-arm64",
"windows-arm64-baseline",
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
if (isWindows && isArm64) {
    return selectWindowsArm64Variant();
}
```

### 4.2 Windows x86_64 选择逻辑

使用 `com.sun.jna.Platform` 或 JDK 内置方式读取 CPUID：

| CPU 特性 | 选用的 variant |
|----------|---------------|
| `avx512f` + `avx512bw` + `avx512vl` (+ `avx512vbmi`) | `windows-x86_64` |
| `avx2` + `bmi2` | `windows-x86_64-avx2` |
| `sse4_2` + `popcnt` | `windows-x86_64-baseline` |
| 其他 | `windows-x86_64-baseline` |

实现方式候选：
- `System.getenv("PROCESSOR_IDENTIFIER")` 仅描述字符串，不够精确；
- 使用 JNA 调用 `kernel32.GetNativeSystemInfo` + `IsProcessorFeaturePresent`；
- 若不想引入 JNA，可用 JNI 或解析 Windows 注册表 `HKEY_LOCAL_MACHINE\HARDWARE\DESCRIPTION\System\CentralProcessor\0` 的 `FeatureSet`。

### 4.3 Windows arm64 选择逻辑

| CPU 特性 | 选用的 variant |
|----------|---------------|
| 包含 `sve2` | `windows-arm64` |
| 其他 | `windows-arm64-baseline` |

实现方式：Windows 上 arm64 CPU 特性没有 `/proc/cpuinfo`，可能需要通过 WMI、`GetSystemInfo` 扩展字段，或暂用固定回退到 baseline。

---

## 5. 构建脚本

### 5.1 新增 `build-windows.sh`

职责：
1. 根据 `DETECTED_PLATFORM` 确定目标 tier；
2. 从 Intel Hyperscan release 下载对应 Windows 二进制包；
3. 校验 checksum；
4. 将头文件放入 `cppbuild/include/hs/`，DLL 放入 `cppbuild/lib/`；
5. 运行 `mvn -B -DskipTests -Dorg.bytedeco.javacpp.platform=$DETECTED_PLATFORM`，让 JavaCPP 生成绑定并打包为 classifier jar。

不同 tier 的 DLL 组织：
- 若官方包内已按目录分好，直接复制；
- 若同一 release 包含多个 DLL（如 `hs.dll`、`hs_avx2.dll`、`hs_avx512.dll`），则按 tier 拆分到不同 platform 目录，分别执行一次 Maven 打包。

### 5.2 头文件来源

优先使用 Intel release 中的头文件。若与 vectorscan 头文件存在差异（新增/删除 API），需要：
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
- os: windows
  runner: windows-latest
  platform: windows-arm64-baseline
- os: windows
  runner: windows-latest
  platform: windows-arm64
```

### 6.2 Windows 构建步骤

- 使用 `actions/checkout`；
- 使用 `actions/setup-java`（Temurin JDK 21，或按需求 JDK 8/11/17/21）；
- 安装 MSYS2 或 Git Bash，运行 `./build-windows.sh`；
- 上传 `target/staging-deploy` artifact。

### 6.3 聚合 job 扩展

将 `package-linux-native` 重命名为 `package-native`：
- 下载 Linux x86_64 / arm64 的 variant artifacts；
- 下载 Windows x86_64 / arm64 的 variant artifacts；
- 分别合并为：
  - `native-<version>-linux-x86_64.jar`
  - `native-<version>-linux-arm64.jar`
  - `native-<version>-windows-x86_64.jar`
  - `native-<version>-windows-arm64.jar`
- 所有 classifier jars 放入同一 staging repo。

### 6.4 测试 job

新增：
- `test-windows-x86_64`：在 `windows-latest` runner 上跑 `EndToEndTest`，覆盖 default / baseline / avx2；
- `test-windows-arm64`：若 GitHub 提供 `windows-latest-arm` 或等效 runner，覆盖 default / baseline。

### 6.5 publish job

`needs` 增加 Windows 测试 job：

```yaml
needs:
  - package-native
  - test-linux-x86_64
  - test-linux-arm64
  - test-windows-x86_64
  - test-windows-arm64
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
native-<version>-windows-arm64.jar    # 含 windows-arm64 / windows-arm64-baseline
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
| Intel 官方不提供 Windows arm64 DLL | Windows arm64 计划阻塞 | 调研确认；若缺失，先支持 x86_64，arm64 后续自建编译 |
| Intel DLL 与 vectorscan 头文件 API 不一致 | JavaCPP 生成代码或 consumer 编译失败 | 用统一最小公共头文件子集，必要时 `InfoMap` 跳过差异 API |
| Windows DLL 运行时依赖 MSVC redist | 部署环境缺少 redist 导致加载失败 | 在文档中说明需安装 MSVC runtime；CI 测试裸机环境验证 |
| Windows CPU 特性检测复杂 | 选错 variant 导致 SIGILL/IllegalInstruction | 优先保守回退 baseline；增加 JVM crash 日志收集 |
| JavaCPP 在 Windows 上 platform 命名与 loader 交互 | loader 设置 platform 属性后 JavaCPP 找不到资源 | 严格按 JavaCPP 官方 classifier 命名（`windows-x86_64` 等） |
| Intel Hyperscan license 限制再分发 | 法律合规风险 | 核对 release 的 LICENSE（通常为 BSD-3），必要时在 POM 中附加 license 信息 |

---

## 9. 实施顺序

1. **调研**：确认 Intel Hyperscan Windows release 的下载地址、版本、架构支持、DLL tier。
2. **PoC**：在本地或 CI 上只做一个 `windows-x86_64-baseline` 的 classifier jar，验证 `EndToEndTest` 能跑通。
3. **扩展 x86_64**：补齐 `windows-x86_64-avx2` / `windows-x86_64`。
4. **扩展 arm64**：调研 / 尝试 Windows arm64 DLL；若可行则补齐，否则文档标注不支持。
5. **CI 集成**：更新 workflow、聚合 job、测试 job、publish job。
6. **文档与发布**：更新架构文档，bump 版本，打 tag 发版。

---

## 10. 参考资料

- `docs/architecture/linux-x86_64-multi-variant.md`
- `docs/architecture/linux-arm64-multi-variant.md`
- `src/main/java/com/gliwka/hyperscan/jni/HyperscanNativeLoader.java`
- `src/main/java/com/gliwka/hyperscan/jni/JavaCppPreset.java`
- Intel Hyperscan releases: https://github.com/intel/hyperscan/releases

---

**文档结束。**
