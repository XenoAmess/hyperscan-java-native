[English](investigating-avx-leak-in-original-jar.en.md) | 中文

# 排查原版 jar 指令集泄漏过程记录

| 字段 | 值 |
|------|-----|
| 文档版本 | v1.0 |
| 排查日期 | 2026-06-26 |
| 排查对象 | `com.gliwka.hyperscan:native:5.4.12-2.0.4:linux-x86_64`（Maven Central 原版） |
| 排查者 | opencode |
| 结论 | 原版 `libhs.so` / `libhs_runtime.so` 存在大量 AVX-512 指令泄漏，`FAT_RUNTIME=on` 无法保护非 dispatch 路径 |

---

## 1. 排查背景

客户反馈使用 `5.4.12-2.0.4` / `linux-x86_64` 制品在部分机器上启动或运行时报 `Illegal instruction`（SIGILL）。

原版构建配置为 `FAT_RUNTIME=on` + `BUILD_AVX2=yes` + `BUILD_AVX512=yes` + `BUILD_AVX512VBMI=yes`。理论上运行时应该根据 CPUID 选择合适路径，但客户仍出现 SIGILL。因此怀疑 AVX/AVX-512 指令泄漏到了 FAT runtime 保护范围之外的代码中。

---

## 2. 排查目标

1. 确认原版 jar 中的 `.so` 文件是否含有 AVX/AVX2/AVX-512 指令。
2. 确认这些指令是否只是出现在 FAT runtime dispatch 的 variant 实现中，还是泄漏到了通用代码。
3. 区分 VEX 编码（AVX/AVX2）和 EVEX 编码（AVX-512）的分布。
4. 判断纯 AVX2 CPU 是否可能因这些指令触发 SIGILL。

---

## 3. 环境与工具

| 项目 | 说明 |
|------|------|
| 操作系统 | Ubuntu x86_64 |
| 反汇编工具 | GNU binutils `objdump` |
| 压缩工具 | `unzip` |
| 下载工具 | `curl` |
| 文本处理 | `grep`、`wc`、`bash` |

---

## 4. 获取原版 jar

从 Maven Central 下载官方发布的 native jar：

```bash
mkdir -p /tmp/opencode/original-check
cd /tmp/opencode/original-check

curl -sL -o original.jar \
  "https://repo1.maven.org/maven2/com/gliwka/hyperscan/native/5.4.12-2.0.4/native-5.4.12-2.0.4-linux-x86_64.jar"
```

下载后确认文件存在：

```bash
ls -lh original.jar
```

输出：

```
-rw-rw-r-- 6.4M Jun 26 16:48 original.jar
```

---

## 5. 解压 jar

native jar 本质上是一个 zip 包，里面包含 Java class 文件和 platform 目录下的 `.so` 动态库。使用 `unzip` 解压：

```bash
unzip -o original.jar -d original
```

解压后的目录结构：

```
original/
├── META-INF/
│   └── MANIFEST.MF
└── com/gliwka/hyperscan/jni/linux-x86_64/
    ├── libjnihyperscan.so
    ├── libhs.so
    └── libhs_runtime.so
```

列出所有 `.so` 文件：

```bash
find original -name '*.so' -exec ls -lh {} \;
```

输出：

```
-rwxr-xr-x 5.7M Nov  5  2025 original/com/gliwka/hyperscan/jni/linux-x86_64/libhs_runtime.so
-rwxr-xr-x 9.7M Nov  5  2025 original/com/gliwka/hyperscan/jni/linux-x86_64/libhs.so
-rwxr-xr-x 171K Nov  5  2025 original/com/gliwka/hyperscan/jni/linux-x86_64/libjnihyperscan.so
```

---

## 6. 反汇编检查方法

### 6.1 为什么选择 `objdump -d`

`objdump` 是 GNU binutils 提供的反汇编工具。`-d` 选项只反汇编**预期包含代码的 section**（主要是 `.text`），不会把数据段误判为指令。相比 `-D`（disassemble-all）更安全，假阳性更少。

### 6.2 AVX 指令的编码特征

x86-64 的 SIMD 指令编码前缀如下：

| 编码前缀 | 指令集 | 说明 |
|---------|--------|------|
| `0f` / `f2` / `f3` + `0f` | SSE / SSE2 / SSE3 / SSSE3 / SSE4.x | 128-bit，老格式 |
| `c4` / `c5` 开头 | VEX | AVX / AVX2，256-bit |
| `62` 开头 | EVEX | AVX-512，512-bit |

`objdump` 反汇编输出格式示例：

```
  2062d9:  c4 e1 f9 6e cd        vmovq  %rbp,%xmm1
  206309:  c4 e3 f1 22 c0 01     vpinsrq $0x1,%rax,%xmm1,%xmm0
  20630f:  c4 c1 78 11 80 f8 00  vmovups %xmm0,0xf8(%r8)
```

每行结构：
- 地址：`2062d9:`
- 机器码字节：`c4 e1 f9 6e cd`
- 助记符：`vmovq %rbp,%xmm1`

AVX 指令助记符统一以 `v` 开头，可作为辅助判断。

### 6.3 第一轮扫描脚本

目标：统计每个 `.so` 中 VEX/EVEX 指令总数。

```bash
for SO in original/com/gliwka/hyperscan/jni/linux-x86_64/*.so; do
    echo "=== $(basename $SO) ==="
    HITS=$(objdump -d "$SO" \
        | grep -E '^[[:space:]]+[0-9a-f]+:[[:space:]]+[0-9a-f ]+[[:space:]]+v' \
        | wc -l)
    echo "VEX/EVEX instruction count: $HITS"
    if [ "$HITS" -gt 0 ]; then
        objdump -d "$SO" \
            | grep -E '^[[:space:]]+[0-9a-f]+:[[:space:]]+[0-9a-f ]+[[:space:]]+v' \
            | head -5
    fi
done
```

脚本解释：
- `objdump -d "$SO"`：反汇编目标 so。
- `grep -E '^[[:space:]]+[0-9a-f]+:[[:space:]]+[0-9a-f ]+[[:space:]]+v'`：匹配反汇编指令行，要求以地址开头、机器码字节列之后跟着以 `v` 开头的助记符。
- `wc -l`：统计匹配行数。
- 如果大于 0，再打印前 5 条示例。

第一轮输出：

```
=== libhs_runtime.so ===
VEX/EVEX instruction count: 57634
  2062d9:  c4 e1 f9 6e cd        vmovq  %rbp,%xmm1
  206309:  c4 e3 f1 22 c0 01     vpinsrq $0x1,%rax,%xmm1,%xmm0
  20630f:  c4 c1 78 11 80 f8 00  vmovups %xmm0,0xf8(%r8)
  20631e:  c5 f9 ef c0           vpxor  %xmm0,%xmm0,%xmm0
  206335:  c4 c1 78 11 80 38 01  vmovups %xmm0,0x138(%r8)

=== libhs.so ===
VEX/EVEX instruction count: 57634
  564de9:  c4 e1 f9 6e cd        vmovq  %rbp,%xmm1
  564e19:  c4 e3 f1 22 c0 01     vpinsrq $0x1,%rax,%xmm1,%xmm0
  564e1f:  c4 c1 78 11 80 f8 00  vmovups %xmm0,0xf8(%r8)
  564e2e:  c5 f9 ef c0           vpxor  %xmm0,%xmm0,%xmm0
  564e45:  c4 c1 78 11 80 38 01  vmovups %xmm0,0x138(%r8)

=== libjnihyperscan.so ===
VEX/EVEX instruction count: 0
```

**第一轮关键发现**：
- `libhs.so` 和 `libhs_runtime.so` 各有 **57,634** 条 VEX/EVEX 指令。
- `libjnihyperscan.so`（JavaCPP JNI 层）是干净的，0 条。
- 说明 AVX 指令泄漏在 vectorscan 主库和 runtime 库里，不在 JNI 层。

### 6.4 第二轮扫描：区分 AVX/AVX2 与 AVX-512

仅统计 `v` 开头的助记符不够，因为无法区分 AVX/AVX2（VEX）和 AVX-512（EVEX）。需要按机器码前缀进一步分类：

| 前缀 | 说明 |
|------|------|
| `c4` / `c5` | VEX 编码，AVX / AVX2 |
| `62` | EVEX 编码，AVX-512 |

脚本：

```bash
for SO in original/com/gliwka/hyperscan/jni/linux-x86_64/*.so; do
    echo "=== $(basename $SO) ==="
    echo "VEX (AVX/AVX2):"
    objdump -d "$SO" \
        | grep -E '^[[:space:]]+[0-9a-f]+:[[:space:]]+c4[[:space:]]+c[0-9a-f]' \
        | wc -l
    echo "EVEX (AVX-512):"
    objdump -d "$SO" \
        | grep -E '^[[:space:]]+[0-9a-f]+:[[:space:]]+62[[:space:]]' \
        | wc -l
done
```

第二轮输出：

```
=== libhs_runtime.so ===
VEX (AVX/AVX2):
13716
EVEX (AVX-512):
20327

=== libhs.so ===
VEX (AVX/AVX2):
13716
EVEX (AVX-512):
20326

=== libjnihyperscan.so ===
VEX (AVX/AVX2):
0
EVEX (AVX-512):
0
```

---

## 7. 结果汇总

| 文件 | VEX (AVX/AVX2) | EVEX (AVX-512) | 结论 |
|------|---------------|----------------|------|
| `libhs.so` | 13,716 | 20,326 | 含有大量 AVX-512 指令泄漏 |
| `libhs_runtime.so` | 13,716 | 20,327 | 含有大量 AVX-512 指令泄漏 |
| `libjnihyperscan.so` | 0 | 0 | 干净 |

---

## 8. 结论推导

### 8.1 为什么 `FAT_RUNTIME=on` 仍会导致 SIGILL？

`FAT_RUNTIME` 的机制是在 `hs_scan` 等入口函数内做 CPUID 分派，把调用转发到 `corei7_*`、`avx2_*`、`avx512_*` 等不同实现。这个机制**只保护 dispatch 入口**。

但库文件里还有大量其他代码：
- 全局构造函数 / 初始化代码
- 异常处理路径
- 被编译器自动 vectorize 的循环
- 各种辅助函数

这些代码在编译时使用了 `-march` 或 `-msse4.2`/`-mavx2`/`-mavx512` 等选项，导致 AVX-512 指令直接泄漏到 `.text` section。当库被加载或执行到这些路径时，不依赖 dispatch，直接在 CPU 上执行 AVX-512 指令。

### 8.2 纯 AVX2 CPU 会出问题吗？

会。

- 纯 AVX2 CPU 可以执行 VEX 编码的 AVX/AVX2 指令。
- 纯 AVX2 CPU **不能**执行 EVEX 编码的 AVX-512 指令。

原版 `libhs.so` / `libhs_runtime.so` 中各有 **20,000+ 条 EVEX 指令**。只要执行流进入任何包含这些指令的代码路径，就会触发 SIGILL。

因此，原版 jar 不仅在不支持 AVX2 的 CPU 上会失败，在**只支持 AVX2 而不支持 AVX-512 的 CPU 上也会失败**。

### 8.3 给我们的启示

要从根本上解决这个问题，不能只依赖 `FAT_RUNTIME`，必须：

1. **编译时指定严格的 `-march`**，禁止生成超出目标 CPU 能力的指令。
2. **关闭所有不需要的 AVX 构建开关**。
3. **用 `objdump` 对所有 `.so` 做 CI 校验**，确保没有禁用指令集泄漏。

这正是基线版改造方案（`-march=westmere` + CI ISA 校验）的核心思路。

---

## 9. 完整命令速查

```bash
# 1. 创建工作目录
mkdir -p /tmp/opencode/original-check
cd /tmp/opencode/original-check

# 2. 下载原版 jar
curl -sL -o original.jar \
  "https://repo1.maven.org/maven2/com/gliwka/hyperscan/native/5.4.12-2.0.4/native-5.4.12-2.0.4-linux-x86_64.jar"

# 3. 解压
unzip -o original.jar -d original

# 4. 列出 so
find original -name '*.so' -exec ls -lh {} \;

# 5. 反汇编单个 so 并查看
objdump -d original/com/gliwka/hyperscan/jni/linux-x86_64/libhs.so | less

# 6. 统计所有 VEX/EVEX（AVX/AVX2/AVX-512）指令
for SO in original/com/gliwka/hyperscan/jni/linux-x86_64/*.so; do
    echo "=== $(basename $SO) ==="
    objdump -d "$SO" \
        | grep -E '^[[:space:]]+[0-9a-f]+:[[:space:]]+[0-9a-f ]+[[:space:]]+v' \
        | wc -l
done

# 7. 区分 VEX (AVX/AVX2) 和 EVEX (AVX-512)
for SO in original/com/gliwka/hyperscan/jni/linux-x86_64/*.so; do
    echo "=== $(basename $SO) ==="
    echo -n "VEX:  "
    objdump -d "$SO" \
        | grep -E '^[[:space:]]+[0-9a-f]+:[[:space:]]+c4[[:space:]]+c[0-9a-f]' \
        | wc -l
    echo -n "EVEX: "
    objdump -d "$SO" \
        | grep -E '^[[:space:]]+[0-9a-f]+:[[:space:]]+62[[:space:]]' \
        | wc -l
done
```

---

## 10. 排查中踩过的坑与注意事项

### 10.1 不要用 `objdump -D`

`-D` 会反汇编所有 section，包括 `.rodata`、`.data` 等数据段。数据段里的随机字节可能被误识别为指令，产生假阳性。`-d` 只反汇编代码 section，结果更可靠。

### 10.2 不要只看助记符

虽然 AVX 助记符都以 `v` 开头，但某些 SSE 指令的 AT&T 助记符也可能巧合包含 `v`？实际上不会。但为了更准确，应该直接匹配机器码前缀 `c4`/`c5`/`62`，这是最可靠的判断。

### 10.3 需要区分 VEX 和 EVEX

纯 AVX2 CPU 可以跑 VEX，不能跑 EVEX。如果不做第二轮区分，会误以为"只要没有 AVX-512 dispatch 路径就安全"。

### 10.4 检查所有 `.so`

不能只检查 `libhs.so`。`libhs_runtime.so` 同样可能泄漏，并且 JavaCPP 会同时加载它。JNI 层 `libjnihyperscan.so` 也需要检查，虽然这次它是干净的，但不同构建环境可能不同。

### 10.5 注意正则中的空格

`objdump` 输出的机器码列中，字节之间用空格分隔。正则 `[0-9a-f ]+` 可以匹配这些字节，但要确保不会跨列匹配到助记符。使用 `+v` 结尾可以限定助记符位置。

---

## 11. 参考资料

- `docs/architecture/linux-x86_64-baseline.zh.md`：基线改造方案
- `docs/architecture/linux-x86_64-multi-variant.zh.md`：多 variant 改造方案
- `docs/performance/linux-x86_64-baseline-benchmark.zh.md`：性能测试报告
- Intel SDM / AMD APM：x86-64 指令编码手册

---

**排查记录结束。**
