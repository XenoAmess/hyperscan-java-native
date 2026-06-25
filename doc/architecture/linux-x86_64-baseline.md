# Linux x86_64 最低指令集兼容化改造方案

| 字段       | 值                                          |
| ---------- | ------------------------------------------- |
| 文档版本   | v1.0                                        |
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
- 未显式限制 `-march`，vectorscan 的 CMake 在无 AVX flag 时回退到基线 `SSE4.2 + POPCNT`。

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

---

## 4. 方案设计

### 4.1 推荐方案 A：单 variant、最低基线版

**核心思想**：把现有 `linux-x86_64` 制品的 cmake 配置从"基线 + AVX2 + AVX512 + AVX512VBMI"简化为"仅基线 + 运行时分派壳"，发布的 `linux-x86_64` jar 在任何 SSE4.2 CPU 上都能跑。

#### 4.1.1 改动范围

| 文件                                 | 改动                                            |
| ------------------------------------ | ----------------------------------------------- |
| `build.sh`                           | linux-x86_64 分支的 cmake 行：关闭 3 个 AVX 开关 |
| `.github/workflows/build.yml`       | 增加 ISA 验证步骤（`objdump` 反汇编检测）       |
| `doc/architecture/linux-x86_64-baseline.md` | 本文档                                          |

#### 4.1.2 build.sh 改动（推荐 diff）

```diff
 case $DETECTED_PLATFORM in
 linux-x86_64)
-  cmake -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$(pwd)/.." -DCMAKE_INSTALL_LIBDIR="lib" -DPCRE_SOURCE="." -DFAT_RUNTIME=on -DBUILD_SHARED_LIBS=on -DBUILD_AVX2=yes -DBUILD_AVX512=yes -DBUILD_AVX512VBMI=yes .
+  # Baseline-only build: SSE4.2 + POPCNT (Westmere, 2010+). 见 doc/architecture/linux-x86_64-baseline.md
+  cmake -DCMAKE_BUILD_TYPE=Release \
+        -DCMAKE_INSTALL_PREFIX="$(pwd)/.." \
+        -DCMAKE_INSTALL_LIBDIR="lib" \
+        -DPCRE_SOURCE="." \
+        -DFAT_RUNTIME=on \
+        -DBUILD_SHARED_LIBS=on \
+        -DBUILD_AVX2=no \
+        -DBUILD_AVX512=no \
+        -DBUILD_AVX512VBMI=no \
+        -DCMAKE_CXX_FLAGS_RELEASE="-march=westmere" \
+        -DCMAKE_C_FLAGS_RELEASE="-march=westmere" \
+        .
   make -j $THREADS all unit install/strip
   ;;
```

要点：

1. `BUILD_AVX2/AVX512/AVX512VBMI` 全部置 `no` —— 这些路径根本不会被编译进 `.so`。
2. 显式追加 `-march=westmere` —— 双重保险。即使将来 cmake 工具链默认值变化，也保证基线指令集不被放大。
3. 保留 `FAT_RUNTIME=on` —— 保留 runtime dispatch 壳，未来如需"渐进增强"无需再改 build.sh，只需打开对应开关。
4. **glibc 兜底**：保留 CentOS 7 容器不动，glibc 2.17 ABI 不变，仍兼容 CentOS 6+、RHEL 6+。

#### 4.1.3 预期产物大小变化

| 配置                                       | libhs.so 体积（粗略量级） |
| ------------------------------------------ | ------------------------- |
| 当前：基线+AVX2+AVX512+VBMI                | ~30 MB                    |
| 改造后：仅基线                              | ~6–8 MB                   |
| 缩小幅度                                    | 约 70–80%                 |

体积显著减小（FAT_RUNTIME 模式下多份代码副本不会进入最终链接）。

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
      - name: Verify baseline ISA (no AVX/AVX2/AVX-512 in libhs.so)
        if: matrix.os == 'linux' && matrix.platform == 'linux-x86_64'
        run: |
          set -e
          SO=$(find target/staging-deploy -name 'libhs.so' -path '*linux-x86_64*' | head -1)
          if [ -z "$SO" ]; then
            echo "libhs.so not found under target/staging-deploy"
            exit 1
          fi
          echo "Inspecting $SO"
          # 允许 zmm 出现在 plt/got/vtables 的字符串常量里，但要求 .text 中没有 avx* 指令
          AVX_HITS=$(objdump -d "$SO" \
            | awk '/<.*>:/ {sec=$0} /Disassembly/ {sec=""} {print sec" "$0}' \
            | awk '$0 ~ /\.text/ || $0 ~ /\.text\./' \
            | grep -E '\b(v[mu]mov|vp[abd]|vperm|vextract|vinsert|valign|vpternlog|vcompress|vexpand|vpbroadcast|vpmultishift|vgf2|vpclmulq|vzeroupper)\b' \
            | wc -l)
          if [ "$AVX_HITS" -gt 0 ]; then
            echo "FAIL: $SO contains $AVX_HITS AVX/AVX2/AVX-512 instructions in .text"
            objdump -d "$SO" | grep -nE '\b(v[mu]mov|vp[abd]|vperm|vextract|vinsert|valign|vpternlog|vcompress|vexpand|vpbroadcast|vpmultishift|vgf2|vpclmulq|vzeroupper)\b' | head -10
            exit 1
          fi
          echo "PASS: no AVX/AVX2/AVX-512 instructions detected in $SO"
```

### 5.2 build.yml：完整 linux-x86_64 matrix 条目（参考）

```yaml
- os: linux
  runner: ubuntu-24.04
  shell: bash
  platform: linux-x86_64
```

后续步骤保持不变：

1. checkout
2. 在 centos7 容器里跑 `./build.sh`（已按 §4.1.2 改过）
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

# (2) 符号表里不应出现 avx/avx2/avx512 相关实现符号
nm -D "$SO" 2>/dev/null | grep -iE 'avx2|avx512|vbmi' | head

# (3) 反汇编 .text 段不含 AVX/AVX2/AVX-512 助记符
objdump -d "$SO" | grep -E '\b(v[mu]mov|vp[abd]|vperm|vextract|vinsert|valign|vpternlog|vcompress|vexpand|vpbroadcast|vpmultishift|vgf2|vpclmulq|vzeroupper)\b' | head

# (4) 用 vectorscan 自带的 hsvalidplatform 做交叉验证（若有）
"$SO"  # 不会成功执行（它是 .so），这里只是示例
```

预期：

- (1) 体积 ~6–8 MB。
- (2) 仅 `fat_`-前缀的 dispatch 壳符号，无具体 AVX 实现函数。
- (3) 输出为空。

### 5.4 回归测试

`src/test/java` 下已有 JUnit 测试。CI 上 `mvn test` 会加载本地 `.so` 跑端到端用例。在改了 ISA 配置后必须确认全部通过：

```bash
mvn -B -Dorg.bytedeco.javacpp.platform=linux-x86_64 test
```

特别关注：

- `HyperscanTest` / 任何编译 `Pattern` 的用例：编译期会触发 vectorscan 内部 codegen，间接验证运行时分派。
- 大规模 `Stream` / `Block` 数据库用例：触发运行时匹配，验证 SSE4.2 路径工作。

---

## 6. 风险与权衡

### 6.1 性能影响

| 操作类型            | 影响              | 备注                                  |
| ------------------- | ----------------- | ------------------------------------- |
| 扫描吞吐            | 下降 30%–60%      | 现代 CPU 失去 AVX2 加速；扫描吞吐敏感场景影响最大 |
| 编译耗时            | 略有下降          | 只编一个 ISA 路径，编译略快            |
| `.so` 体积          | 缩小 70%+         | 见 §4.1.3                             |
| 启动 / 加载时间     | 略有下降          | dispatch 表更小                        |

如果客户业务对扫描吞吐敏感（QPS > 10w 的正则网关），方案 A 不适合，需走方案 B（多 variant）。

### 6.2 与上游发布策略的兼容性

上游 `gliwka/hyperscan-java-native` 是按"每平台单 variant"发布的；本 fork 仍沿用这一约定，发布名不变（仍是 `native-5.4.12-2.0.5-linux-x86_64.jar` 一类）。下游消费者只需升级版本号，不需要改 classifier。

> 如果选择方案 B，则需要和上游 / 下游 `hyperscan-java` 协商 classifier 命名，本方案不涉及。

### 6.3 glibc 与 OS 兼容性的遗留风险

CentOS 7 工具链产物对 glibc 2.17 起的所有 Linux 发行版可用。如果客户用 **glibc < 2.17 的 OS**（CentOS 6、RHEL 6、某些国产化 OS），即便指令集对了也会报 `GLIBC_2.xx not found`。该问题**不属于本方案范围**，需要：

- 改用 `manylinux2014` / `manylinux_2_28` wheel 的方式重新打包；或
- 在客户现场用 `patchelf` 替换 `libc.so.6` 指向。

### 6.4 vectorscan 上游行为变更风险

`FAT_RUNTIME=on` + 全部 AVX 关闭的配置，vectorscan 上游 CMake 的处理是：只编出"host"路径（即 `-march=westmere` 指定的基线），同时仍然生成 dispatch 入口函数。vectorscan 5.4.12（本次使用的版本）已稳定支持该组合，**但**：

- 任何未来 vectorscan 升级需要重新跑 §5.3 的验证脚本。
- 若某次 vectorscan 版本更新引入"即使关闭 AVX 也会引用 intrinsics 的代码路径"，CI 中 §5.1 的 `objdump` 检查会立刻拦截。

### 6.5 macOS 与其他平台不受影响

`build.sh:83-91` 中：

- `linux-arm64`：保持 `BUILD_SVE=on -DBUILD_SVE2=on` 不变。
- `macosx-x86_64` / `macosx-arm64`：保持原配置不变。

---

## 7. 实施计划

按下列顺序推进，每步都可独立回滚：

| 步骤 | 内容                                                                                  | 估时  | 验证                           |
| ---- | ------------------------------------------------------------------------------------- | ----- | ------------------------------ |
| 1    | 在 fork 分支 `feature/linux-baseline` 上应用 §4.1.2 的 build.sh 改动                  | 0.5d  | `git diff` 审查                 |
| 2    | 在本地（docker pull centos7-toolchain 镜像后）跑一次完整 `./build.sh`，产物落 `target/` | 0.5d  | §5.3 三步检查全部通过           |
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

# 反汇编检查 AVX/AVX2/AVX-512
objdump -d <libhs.so> | grep -E '\b(v[mu]mov|vp[abd]|vperm|vextract|vinsert|valign|vpternlog|vcompress|vexpand|vpbroadcast|vpmultishift|vgf2|vpclmulq|vzeroupper)\b'

# 用 vectorscan 自带工具做最终交叉验证（5.4.12 提供）
./hsvalidplatform <libhs.so>
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
