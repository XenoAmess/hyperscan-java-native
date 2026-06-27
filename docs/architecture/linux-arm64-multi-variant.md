# Linux ARM64 多 Variant 改造方案

| 字段 | 值 |
|------|-----|
| 文档版本 | v1.0 |
| 适用范围 | `hyperscan-java-native`（fork） 5.4.12-2.0.4-x3+ |
| 仓库根 | `/home/xenoamess/workspace/hyperscan-java-native` |
| 状态 | 已实施 |

---

## 1. 背景

与 x86_64 类似，vectorscan 在 ARM64 上也会通过 `FAT_RUNTIME` 构建多个 SIMD runtime variant（NEON、SVE、SVE2）。当构建机支持 SVE/SVE2 且没有显式限制 `-march` 时，通用代码可能泄漏 SVE/SVE2 指令，导致在仅支持 ARMv8.0/NEON 的 CPU 上触发 `SIGILL`。

原版 `linux-arm64` 配置使用：

```bash
CC="clang" CXX="clang++" cmake ... -DFAT_RUNTIME=on -DBUILD_SVE=on -DBUILD_SVE2=on .
```

且未显式指定 `-march`，实际兼容层级由构建机决定，存在指令泄漏风险。

---

## 2. 目标 CPU 分级

ARM64 上 vectorscan 实际有意义的 SIMD 层级：

| 等级 | classifier | 最低指令集 | 代表微架构 | 大致年份 |
|------|-----------|-----------|-----------|---------|
| 1 | `linux-arm64-baseline` | ARMv8.0 + NEON/ASIMD | Cortex-A53 / A72 / Ampere Altra / AWS Graviton2 | 2016+ |
| 2 | `linux-arm64` | ARMv9 / SVE2 | AWS Graviton4 / Azure Cobalt 100 / Apple M4+ | 2023+ |

> 独立的 SVE-only（ARMv8.2+，如 Fujitsu A64FX、AWS Graviton3）在本方案中归到 `linux-arm64-baseline`，因为 SVE2 不向后兼容 SVE-only CPU；未来如需专门优化可再拆分 `linux-arm64-sve`。

---

## 3. 产物结构

发布时 Maven Central 上的 `native-<version>-linux-arm64.jar` 是一个**聚合 jar**，内部同时包含两套 so：

```
com/gliwka/hyperscan/jni/linux-arm64/libhs.so              # SVE2 build
com/gliwka/hyperscan/jni/linux-arm64/libhs_runtime.so
com/gliwka/hyperscan/jni/linux-arm64/libjnihyperscan.so

com/gliwka/hyperscan/jni/linux-arm64-baseline/libhs.so     # ARMv8.0/NEON build
com/gliwka/hyperscan/jni/linux-arm64-baseline/libhs_runtime.so
com/gliwka/hyperscan/jni/linux-arm64-baseline/libjnihyperscan.so
```

---

## 4. build.sh 实现

`build.sh` 的 `linux-arm64*` 分支根据 `DETECTED_PLATFORM` 选择 `-march` 与 SVE/SVE2 开关：

```bash
linux-arm64|linux-arm64-baseline)
  case $DETECTED_PLATFORM in
    linux-arm64-baseline)
      MARCH="armv8-a"
      BUILD_SVE=OFF
      BUILD_SVE2=OFF
      FAT_RUNTIME=off
      ;;
    linux-arm64)
      MARCH="armv9-a"
      BUILD_SVE=ON
      BUILD_SVE2=ON
      FAT_RUNTIME=off
      ;;
  esac

  CC="clang" CXX="clang++" cmake \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$(pwd)/.." \
        -DCMAKE_INSTALL_LIBDIR="lib" \
        -DPCRE_SOURCE="." \
        -DFAT_RUNTIME=$FAT_RUNTIME \
        -DBUILD_SHARED_LIBS=on \
        -DBUILD_SVE=$BUILD_SVE \
        -DBUILD_SVE2=$BUILD_SVE2 \
        -DCMAKE_C_FLAGS="-march=$MARCH" \
        -DCMAKE_CXX_FLAGS="-march=$MARCH" \
        -DBUILD_BENCHMARKS=false \
        -DBUILD_EXAMPLES=false \
        .
  make -j $THREADS install/strip
  ;;
```

关键点：
- 所有 ARM64 variant 都使用 `FAT_RUNTIME=off`，并用严格的 `-march` 锁定指令集。
- baseline 显式关闭 `BUILD_SVE` 和 `BUILD_SVE2`，只保留 NEON。
- SVE2 variant 使用 `-march=armv9-a`（clang 下等价于启用 SVE2）。

---

## 5. Java 运行时加载与 CPU 检测

`HyperscanNativeLoader` 在 ARM64 Linux 上读取 `/proc/cpuinfo` 的 `Features` 字段：

| CPU features | 选用的 variant |
|--------------|---------------|
| 包含 `sve2` | `linux-arm64` |
| 其他 | `linux-arm64-baseline`（安全回退） |

ARM64 的 `/proc/cpuinfo` 中特性字段名为 `Features`（x86 为 `flags`），因此 `readLinuxCpuFlags()` 同时支持两种字段名。

---

## 6. GitHub Actions 实现

### 6.1 build-native matrix

新增 ARM64 baseline 条目：

```yaml
matrix:
  include:
    ...
    - os: linux
      runner: ubuntu-24.04-arm
      platform: linux-arm64-baseline
    - os: linux
      runner: ubuntu-24.04-arm
      platform: linux-arm64
```

### 6.2 聚合 package 任务

`package-linux-native` job 在合并 x86_64 三个 variant 之后，继续合并两个 arm64 variant：

1. 下载 `linux-arm64-baseline` 与 `linux-arm64` 的 staging artifact。
2. 把 baseline 的 native 目录叠加到 SVE2 jar 中。
3. 生成统一的 `native-<version>-linux-arm64.jar`。

### 6.3 测试任务

`test-linux-arm64` job：

- 在 `ubuntu-24.04-arm` runner 上运行。
- 默认 platform 测试：runner 支持 SVE2，会选中 `linux-arm64`。
- 强制 `linux-arm64-baseline` 测试：验证聚合 jar 中的 baseline variant 可正常运行。

---

## 7. 风险与注意事项

### 7.1 SVE2 build 不能作为默认路径

JavaCPP 自动检测会把普通 ARM64 Linux 识别为 `linux-arm64`，必须由 `HyperscanNativeLoader` 在加载前把 platform 属性改写为正确的 variant。

### 7.2 消费端使用方式

`hyperscan-java` 或其他消费者应确保首次使用 hyperscan API 前调用：

```java
HyperscanNativeLoader.load();
```

或者通过 JavaCPP 系统属性强制指定：

```bash
-Dorg.bytedeco.javacpp.platform=linux-arm64-baseline
```

### 7.3 版本号

本方案随 `5.4.12-2.0.4-x3` 发布。

---

## 8. 参考资料

- `docs/architecture/linux-x86_64-multi-variant.md`：x86_64 三 variant 改造方案
- `docs/architecture/linux-x86_64-baseline.md`：基线版改造方案
- `src/main/java/com/gliwka/hyperscan/jni/HyperscanNativeLoader.java`：运行时加载器
- `src/main/java/com/gliwka/hyperscan/jni/JavaCppPreset.java`：JavaCPP preset

---

**文档结束。**
