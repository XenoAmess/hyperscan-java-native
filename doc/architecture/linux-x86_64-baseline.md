# Linux x86_64 最低指令集兼容化改造方案

| 字段       | 值                                          |
| ---------- | ------------------------------------------- |
| 文档版本   | v1.1                                        |
| 适用范围   | `hyperscan-java-native`（fork） 5.4.12-2.0.4+ |
| 仓库根     | `/home/xenoamess/workspace/hyperscan-java-native` |
| 状态       | 待评审                                      |

---

## 1. 背景

客户现场反馈基于 5.4.12-2.0.4 / `linux-x86_64` 制品在部分机器上启动失败或运行期报 `Illegal instruction`（SIGILL），属于指令集兼容性问题。

### 1.1 当前制品的指令集现状

`build.sh:78-82` 中 `linux-x86_64` 分支：

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

含义：

- `FAT_RUNTIME=on`：二进制内同时保留多套 ISA 实现，运行时通过 CPUID 选择。
- `BUILD_AVX2=yes`：`AVX2` 路径进入链接产物。
- `BUILD_AVX512=yes`：`AVX-512 F/BW/CD/DQ/VL` 路径进入链接产物。
- `BUILD_AVX512VBMI=yes`：`AVX-512 VBMI`（IceLake 特性）路径进入链接产物。
- 构建在 `ghcr.io/gliwka/centos7-toolchain:main`（CentOS 7，gcc 4.8.5，glibc 2.17）中完成。

### 1.2 客户现场常见的失败模式

| 模式                                              | 触发条件                                                                | 报错特征                             |
| ------------------------------------------------- | ----------------------------------------------------------------------- | ------------------------------------ |
| 客户 CPU 无 AVX2                                  | 2013 年以前消费级 CPU、AMD K10 之前、某些国产 x86                        | `Illegal instruction`（部分路径）    |
| 客户 CPU 无 AVX-512                                | 大多数云主机默认屏蔽 AVX-512；旧至强 E5 v3/v4；虚拟化穿透关闭           | `Illegal instruction`（AVX-512 路径）|
| 内核/虚机屏蔽 AVX-512                              | 旧内核（< 4.x）、某些 Xen/KVM 配置、BIOS "Memory Encryption" 干扰       | `Illegal instruction`（AVX-512 路径）|
| glibc < 2.17                                       | CentOS 6、某些国产化 OS                                                 | `GLIBC_2.14 not found`（ABI 问题）   |
| 客户机根本不是 x86-64                              | ARM 服务器、LoongArch 等错装 linux-x86_64 制品                          | `cannot execute binary file` 或 SIGILL |

本方案主要解决**前三类**（指令集问题）；glibc 与指令集无关，单独处理。

---

## 2. 目标与非目标

### 2.1 目标

1. **G1**：`linux-x86_64` 制品能在**只支持 SSE4.2 + POPCNT** 的 CPU 上正常运行（覆盖 Westmere 2010 / Clarkdale / AMD Bulldozer 之后的所有 x86-64 CPU）。
2. **G2**：制品二进制文件中**不包含** AVX / AVX2 / AVX-512 / AVX-512 VBMI 指令序列（可由 `objdump` 客观验证）。
3. **G3**：流水线改造最小化侵入现有发布流程，CI 仍能在 GitHub Actions 中跑通。
4. **G4**：保留向更高 ISA 演进的可扩展性，未来需要时不必推翻本方案。

### 2.2 非目标

- 不解决 glibc 版本问题（与 ISA 无关）。
- 不解决 `linux-arm64` 制品的兼容性问题（arm64 端已通过 `BUILD_SVE=on -DBUILD_SVE2=on` 单独处理，与本方案正交）。
- 不修改 vectorscan 上游源码。
- 不在 JavaCPP 层做运行时多 variant 选择（详见 §4.2 备选方案，本次不做）。

---

## 3. 指令集基线分析

### 3.1 vectorscan 硬性最低要求

vectorscan（hyperscan fork）在源码中强制要求基线 **SSE4.2 + POPCNT**：

- SSE4.2：`PCMPGTQ`、`CRC32` 等指令用于字符比较。
- POPCNT：用于字符分类加速。
- 无任何 SSE4.2 之前的实现路径。

所以"最老最垃圾的 CPU"在 x86 语境下实际下界为：

| 厂商 | 最低微架构                       | 大致年份 | 代表型号                          |
| ---- | -------------------------------- | -------- | --------------------------------- |
| Intel | Westmere / Clarkdale（第一代 Core i 系列） | 2010     | Xeon 5600、Core i5/i7 1xxx（桌面）、Core i5/i7 Mobile 1xxx |
| AMD   | Bulldozer / Piledriver            | 2011     | FX-8xxx、Opteron 4200/6200        |

再老的 CPU（Core 2、AMD K10、K8）即使支持 x86-64 也无法运行 vectorscan。这是项目硬限制，无法在不改 vectorscan 的前提下突破。

### 3.2 本方案锁定的基线

```
-march=westmere     # GCC 等价于 -msse4.2 -msse4.1 -mssse3 -msse3 -mpopcnt -mcx16 -msahf
```

`westmere` 是 GCC 对 Intel Westmere 微架构的标准 `-march` 值，等价于 `-msse4.2 -mpopcnt` 的最小超集。这覆盖所有 2010+ 的 Intel 与 AMD CPU。

> **重要提示**：
> - `-march=westmere` 等价 flag 中包含 `CMPXCHG16B`（`-mcx16`）和 `SAHF/LAHF`（`-msahf`）。这两个扩展在 x86-64 上极为普遍，Westmere 之后的 CPU 都已支持，但非常老的 K8 类 CPU 不支持。由于 vectorscan 硬需要 SSE4.2 + POPCNT，真正"最老最垃圾"可运行它的 CPU 必然也满足这两个扩展，所以不会额外限制兼容性。
> - 本方案**不开启 `-mtune=native`**，避免编译机 CPU 的 tuning 选项泄漏到目标二进制。

---

## 4. 方案设计

### 4.1 推荐方案 A：单 variant、最低基线版

**核心思想**：把现有 `linux-x86_64` 制品从"多 ISA FAT_RUNTIME + AVX2/AVX512/VBMI"改为"单基线 SSE4.2 + POPCNT、无运行时分派"，发布的 `linux-x86_64` jar 在任何满足向 vectorscan 最低要求的 CPU 上都能跑。

#### 4.1.1 改动范围

| 文件                                 | 改动                                            |
| ------------------------------------ | ----------------------------------------------- |
| `build.sh`                           | linux-x86_64 分支的 cmake 行：关闭 FAT_RUNTIME 与 3 个 AVX 开关，显式 `-march=westmere` |
| `.github/workflows/build.yml`       | 增加 ISA 验证步骤（`objdump` 反汇编检测）       |
| `doc/architecture/linux-x86_64-baseline.md` | 本文档                                          |

#### 4.1.2 为什么必须 `FAT_RUNTIME=off`

`src/dispatcher.c` 的 dispatch 逻辑为：

```c
if (check_avx512vbmi()) { ... }
else if (check_avx512()) { ... }
else if (check_avx2()) { ... }
else if (check_sse42() && check_popcnt()) { ... }
else if (check_ssse3()) { ... }
else { error }
```

该文件只支持通过 `DISABLE_AVX512_DISPATCH` / `DISABLE_AVX512VBMI_DISPATCH` 宏关闭 AVX-512 分派，**没有 `DISABLE_AVX2_DISPATCH` 宏**。如果保留 `FAT_RUNTIME=on` 而仅把 `BUILD_AVX2=no`：

- `avx2_*` 实现不会被编译进对象文件；
- 但 dispatcher 仍会在 AVX2 CPU 上把函数指针解析到未定义的 `avx2_*` 符号；
- 结果在 AVX2 机器上运行时触发 **undefined symbol / 段错误**。

因此必须同时关闭 `FAT_RUNTIME`，让 dispatcher 不再参与链接，彻底走单一路径。

#### 4.1.3 build.sh 改动（推荐 diff）

```diff
 case $DETECTED_PLATFORM in
 linux-x86_64)
-  cmake -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$(pwd)/.." -DCMAKE_INSTALL_LIBDIR="lib" -DPCRE_SOURCE="." -DFAT_RUNTIME=on -DBUILD_SHARED_LIBS=on -DBUILD_AVX2=yes -DBUILD_AVX512=yes -DBUILD_AVX512VBMI=yes .
+  # Baseline-only build: SSE4.2 + POPCNT (Westmere, 2010+). 见 doc/architecture/linux-x86_64-baseline.md
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

要点：

1. `FAT_RUNTIME=off` —— 彻底取消 FAT runtime dispatch，避免未定义 AVX2 符号问题。
2. `BUILD_AVX2/AVX512/AVX512VBMI` 全部置 `OFF` —— 这些路径不会编译进 `.so`。
3. 显式追加 `-DCMAKE_C_FLAGS="-march=westmere" -DCMAKE_CXX_FLAGS="-march=westmere"` —— 告诉 CMake 全局使用基线指令集。
4. **注意**：vectorscan 内部每个 runtime variant 对象会再叠加自己的 `-march=...` compile flags，因此最终仍需以 CI 的 `objdump` 检查为准，不能只看命令行。
5. **glibc 兜底**：保留 CentOS 7 容器不动，产物最低要求 glibc 2.17，仍兼容 CentOS 7+、RHEL 7+ 及同等版本的其他 Linux 发行版。

#### 4.1.4 预期产物大小变化

| 配置                                       | libhs.so 体积（粗略量级） |
| ------------------------------------------ | ------------------------- |
| 当前：基线+AVX2+AVX512+VBMI                | ~30 MB                    |
| 改造后：仅基线                              | ~6–8 MB                   |
| 缩小幅度                                    | 约 70–80%                 |

体积显著减小（不再有 FAT_RUNTIME 多份代码副本进入最终链接）。

### 4.2 备选方案 B：多 variant 分层（**本次不做**，仅留接口）

如果未来希望"老机器跑基线版，新机器跑高性能版"，可在 CI 矩阵里加 variant：

```yaml
- os: linux
  platform: linux-x86_64-baseline
- os: linux
  platform: linux-x86_64-avx2
- os: linux
  platform: linux-x86_64          # 默认最高，向后兼容
```

由消费侧 `hyperscan-java` 仓库在加载时读 `/proc/cpuinfo` 选择对应 classifier 的 jar。**本方案不包含此项**，作为未来扩展。

---

## 5. CI 流水线改造

### 5.1 build.yml：新增 ISA 校验步骤

在 linux-x86_64 matrix 条目的 "Build native binaries in centos container" 之后、upload-artifact 之前，插入：

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

> 说明：
> - 该检查遍历 `target/staging-deploy` 下所有 `linux-x86_64` 的 `.so`（包括 `libhs.so`、`libhs_runtime.so` 等），而不是只查 `libhs.so`。
> - VEX/EVEX 指令助记符统一以 `v` 开头（如 `vmovdqa`、`vpxor`、`vpbroadcastb` 等），用 `^[[:space:]]+[0-9a-f]+:` 限定为反汇编指令行，避免误伤包含 `v` 的普通符号或字符串。
> - 该检查对 SSE4.2 指令（`pcmpgtq`、`popcnt`、`crc32` 等）无感，因此不会误报基线合法指令。

### 5.2 build.yml：完整 linux-x86_64 matrix 条目（参考）

```yaml
- os: linux
  runner: ubuntu-24.04
  shell: bash
  platform: linux-x86_64
```

后续步骤保持不变：

1. checkout
2. 在 centos7 容器里跑 `./build.sh`（已按 §4.1.3 改过）
3. **新增**：§5.1 的 ISA 校验
4. upload-artifact

### 5.3 本地验证（不依赖 GitHub Actions）

在装好 `binutils` / `cmake` / `gcc` 的 Linux x86_64 机器上：

```bash
./build.sh   # 容器外也行，但要确保 cmake/gcc/make 可用

SO=$(find target/staging-deploy -name 'libhs.so' -path '*linux-x86_64*' | head -1)
[ -n "$SO" ] || { echo "libhs.so not found"; exit 1; }

# (1) 体积快速判断：明显变小
ls -lh "$SO"

# (2) 反汇编 .text 段不含 AVX/AVX2/AVX-512 助记符
#     VEX/EVEX 指令的助记符都以 'v' 开头
objdump -d "$SO" | grep -E '^[[:space:]]+[0-9a-f]+:[[:space:]]+[0-9a-f ]+[[:space:]]+v' | head

# (3) 用 vectorscan 自带工具做交叉验证（若编译了 unit tests/tools）
#     hsvalidplatform 不带参数运行，返回 0 表示当前 CPU 可以运行该二进制
if command -v hsvalidplatform >/dev/null 2>&1; then
  hsvalidplatform
fi
```

预期：

- (1) 体积 ~6–8 MB。
- (2) 输出为空。
- (3) `hsvalidplatform` 返回 0（仅在当前 CPU 支持基线指令集时成立，注意编译机通常都支持）。

### 5.4 回归测试

`src/test/java` 下已有 JUnit 测试。CI 上 `mvn test` 会加载本地 `.so` 跑端到端用例。在改了 ISA 配置后必须确认全部通过：

```bash
mvn -B -Dorg.bytedeco.javacpp.platform=linux-x86_64 test
```

特别关注：

- `SmokeTest` / 任何调用 `hs_compile` / `hs_scan` 的用例：验证基线路径能完成编译与匹配。
- 大规模 `Stream` / `Block` 数据库用例：触发运行时匹配，验证 SSE4.2 路径工作。

---

## 6. 风险与权衡

### 6.1 性能影响

| 操作类型            | 影响              | 备注                                  |
| ------------------- | ----------------- | ------------------------------------- |
| 扫描吞吐            | 下降 30%–60%      | 现代 CPU 失去 AVX2 加速；扫描吞吐敏感场景影响最大 |
| 编译耗时            | 略有下降          | 只编一个 ISA 路径，编译略快            |
| `.so` 体积          | 缩小 70%+         | 见 §4.1.4                             |
| 启动 / 加载时间     | 略有下降          | 不再解析 FAT runtime dispatch 表       |

如果客户业务对扫描吞吐敏感（例如高 QPS 的正则网关），方案 A 不适合，需走方案 B（多 variant）。

### 6.2 与上游发布策略的兼容性

上游 `gliwka/hyperscan-java-native` 是按"每平台单 variant"发布的；本 fork 仍沿用这一约定，发布名不变（例如 `native-5.4.12-2.0.5-linux-x86_64.jar`）。下游消费者只需升级版本号，不需要改 classifier。

> 如果选择方案 B，则需要和上游 / 下游 `hyperscan-java` 协商 classifier 命名，本方案不涉及。

### 6.3 glibc 与 OS 兼容性的遗留风险

CentOS 7 工具链产物对 glibc 2.17 起的所有 Linux 发行版可用。如果客户用 **glibc < 2.17 的 OS**（CentOS 6、RHEL 6、某些国产化 OS），即便指令集对了也会报 `GLIBC_2.xx not found`。该问题**不属于本方案范围**，需要：

- 改用 `manylinux2014` / `manylinux_2_28` wheel 的方式重新打包；或
- 在客户现场用 `patchelf` 替换 `libc.so.6` 指向。

### 6.4 vectorscan 上游行为变更风险

`FAT_RUNTIME=off` + `-march=westmere` 的组合在 vectorscan 5.4.12（本次使用的版本）上可生成纯基线二进制，**但**：

- vectorscan 的编译规则可能在不同版本间调整 `-march` 的继承顺序；
- 任何未来 vectorscan 升级都需要重新跑 §5.3 的验证脚本；
- 若某次版本更新引入"即使关闭 AVX 也会泄漏 AVX 指令"的代码路径，CI 中 §5.1 的 `objdump` 检查会立刻拦截。

### 6.5 macOS 与其他平台不受影响

`build.sh:83-91` 中：

- `linux-arm64`：保持 `BUILD_SVE=on -DBUILD_SVE2=on` 不变。
- `macosx-x86_64` / `macosx-arm64`：保持原配置不变。

---

## 7. 实施计划

按下列顺序推进，每步都可独立回滚：

| 步骤 | 内容                                                                                  | 估时  | 验证                           |
| ---- | ------------------------------------------------------------------------------------- | ----- | ------------------------------ |
| 1    | 在 fork 分支 `feature/linux-baseline` 上应用 §4.1.3 的 build.sh 改动                  | 0.5d  | `git diff` 审查                 |
| 2    | 在本地（docker pull centos7-toolchain 镜像后）跑一次完整 `./build.sh`，产物落 `target/` | 0.5d  | §5.3 检查全部通过               |
| 3    | 跑 `mvn test`，确认回归通过                                                            | 0.5d  | JUnit 全绿                     |
| 4    | 在 fork 上加 §5.1 的 GitHub Actions ISA 校验步骤，推送到 fork 触发 CI                 | 0.5d  | Actions 绿，且能看到"no AVX..."日志 |
| 5    | 发布 `5.4.12-2.0.5-baseline` 或与上游商定版本号                                          | 0.5d  | 客户下载、装机、复现验证        |
| 6    | 文档定稿、本方案归档                                                                    | 0.2d  | 本 doc/PR 合入                  |

合计 ~3 人天。

---

## 8. 回滚

如发布后发现严重问题，按下列顺序回滚：

1. **应用层回滚**：下游 `hyperscan-java` 把版本依赖 pin 回 `5.4.12-2.0.4`，无需重发。
2. **仓库回滚**：在 fork 上 revert 合并的 commit，重新触发 v5.4.12-2.0.4 tag 的产物（GitHub Actions 的 workflow 会重跑）。
3. **二进制回滚**：若已发到 Maven Central 而无法撤回，向所有内部用户发放 `5.4.12-2.0.4.jar` 的本地副本（fork 仓库的 GitHub Packages 或私有 Nexus 均可）。

---

## 9. 附录

### 9.1 关键文件定位

- 构建脚本：`build.sh`
- CI 工作流：`.github/workflows/build.yml`
- POM / 版本：`pom.xml:7`
- 发布脚本：`merge-artifacts.sh`
- 发布配置：`jreleaser.yml`
- 本文档：`doc/architecture/linux-x86_64-baseline.md`

### 9.2 关键命令速查

```bash
# 看 CPU 是否支持 SSE4.2 / AVX2 / AVX-512
grep -oE 'sse4_2|popcnt|avx2|avx512f|avx512vbmi' /proc/cpuinfo | sort -u

# 看 glibc 版本
ldd --version | head -1

# 看 .so 实际链接的 glibc 最低版本
objdump -T target/staging-deploy/**/libhs.so | grep GLIBC_ | awk '{print $NF}' | sort -V | uniq

# 反汇编检查 AVX/AVX2/AVX-512（VEX/EVEX 指令助记符以 'v' 开头）
objdump -d <libhs.so> | grep -E '^[[:space:]]+[0-9a-f]+:[[:space:]]+[0-9a-f ]+[[:space:]]+v'

# 用 vectorscan 自带工具做最终交叉验证（5.4.12 提供）
# hsvalidplatform 不带参数运行
./hsvalidplatform
```

### 9.3 参考资料

- vectorscan upstream：<https://github.com/VectorCamp/vectorscan>（5.4.12 tag）
- hyperscan-java 上游：<https://github.com/gliwka/hyperscan-java>
- GCC `-march` 矩阵：<https://gcc.gnu.org/onlinedocs/gcc/x86-Options.html>
- Intel Intrinsics Guide（SSE4.2 / POPCNT）：<https://www.intel.com/content/www/us/en/docs/intrinsics-guide/>

---

## 10. 待确认问题

下列事项在动手前需要决策，否则方案会有偏差：

1. **Q1：是否接受性能下降 30%–60%？**
   - 若客户业务是**离线批处理 / ETL** 这类吞吐不敏感场景，方案 A 直接可用。
   - 若客户业务是**实时高 QPS 网关**，需要先评估吞吐下降是否可接受；不可接受则必须走方案 B（多 variant），但要多 1–2 人天 + 与 `hyperscan-java` 联调。
2. **Q2：客户 OS 是否在 glibc ≥ 2.17 的系统上？**
   - 需要客户确认；若低于 2.17，本方案解决不了，需要额外 glibc 处理。
3. **Q3：版本号策略**
   - 推荐 `5.4.12-2.0.5`，但发布到 Maven Central 前需要确认不被上游占用。
   - 是否在 fork 内改为 `5.4.12-2.0.5-baseline` 之类的特殊后缀？
4. **Q4：CI 中是否需要构建多 variant 留作"未来增强"？**
   - 本方案只构建单一基线 variant。如果想保留演进能力但暂不发布，建议在 build.yml 加注释或占位条目。

---

**文档结束。** 评审通过后再触发实施计划（§7）。
