package com.gliwka.hyperscan.jni;

import org.bytedeco.javacpp.Loader;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class HyperscanNativeLoader {

    private static final Set<String> LINUX_X86_64_BASELINE_FLAGS = new HashSet<>(
            Arrays.asList("sse4_2", "popcnt")
    );
    private static final Set<String> LINUX_X86_64_AVX2_FLAGS = new HashSet<>(
            Arrays.asList("avx2", "bmi2")
    );
    private static final Set<String> LINUX_ARM64_SVE2_FLAGS = new HashSet<>(
            Arrays.asList("sve2")
    );

    private static volatile boolean loaded = false;

    private HyperscanNativeLoader() {
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }

        if (System.getProperty("org.bytedeco.javacpp.platform") == null) {
            String platform = selectPlatform();
            if (platform != null) {
                System.setProperty("org.bytedeco.javacpp.platform", platform);
            }
        }

        Loader.load(JavaCppPreset.class);
        loaded = true;
    }

    public static String selectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();

        boolean isLinux = os.contains("linux");
        boolean isWindows = os.contains("windows");
        boolean isX86_64 = arch.equals("amd64") || arch.equals("x86_64");
        boolean isArm64 = arch.equals("aarch64") || arch.equals("arm64");

        if (isLinux && isX86_64) {
            return selectLinuxX86_64Variant();
        }

        if (isLinux && isArm64) {
            return selectLinuxArm64Variant();
        }

        if (isWindows && isX86_64) {
            return selectWindowsX86_64Variant();
        }

        return null;
    }

    private static String selectLinuxX86_64Variant() {
        Set<String> flags = readLinuxCpuFlags();

        // Default to AVX2 even when AVX-512 is advertised: many virtualized or
        // containerized environments expose AVX-512 flags but do not reliably
        // execute AVX-512 instructions. Users who are sure their host supports
        // it can force the AVX-512 build via -Dorg.bytedeco.javacpp.platform=linux-x86_64.
        if (flags.containsAll(LINUX_X86_64_AVX2_FLAGS)) {
            return "linux-x86_64-avx2";
        }
        if (flags.containsAll(LINUX_X86_64_BASELINE_FLAGS)) {
            return "linux-x86_64-baseline";
        }

        return "linux-x86_64-baseline";
    }

    private static String selectLinuxArm64Variant() {
        Set<String> flags = readLinuxCpuFlags();

        if (flags.containsAll(LINUX_ARM64_SVE2_FLAGS)) {
            return "linux-arm64";
        }

        return "linux-arm64-baseline";
    }

    private static String selectWindowsX86_64Variant() {
        Set<String> flags = readWindowsCpuFlags();

        // Intel Hyperscan 5.4.2 does not provide a working AVX-512 MSVC build,
        // so we ship only baseline (SSE4.2-class) and AVX2 tiers. Both AVX2 and
        // AVX-512 capable hosts use the AVX2 build published as windows-x86_64.
        if (flags.containsAll(LINUX_X86_64_AVX2_FLAGS)) {
            return "windows-x86_64";
        }

        return "windows-x86_64-baseline";
    }

    private static Set<String> readWindowsCpuFlags() {
        Set<String> flags = new HashSet<>();

        String cpuIdentifier = System.getenv("PROCESSOR_IDENTIFIER");
        if (cpuIdentifier != null) {
            String lower = cpuIdentifier.toLowerCase();
            if (lower.contains("avx512vbmi")) {
                flags.add("avx512f");
                flags.add("avx512bw");
                flags.add("avx512vl");
                flags.add("avx512vbmi");
            } else if (lower.contains("avx512")) {
                flags.add("avx512f");
                flags.add("avx512bw");
                flags.add("avx512vl");
            } else if (lower.contains("avx2")) {
                flags.add("avx2");
                flags.add("bmi2");
            }
        }

        return flags;
    }

    private static Set<String> readLinuxCpuFlags() {
        Set<String> flags = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.toLowerCase().startsWith("flags") || line.startsWith("Features")) {
                    int idx = line.indexOf(':');
                    if (idx >= 0) {
                        String[] parts = line.substring(idx + 1).trim().split("\\s+");
                        flags.addAll(Arrays.asList(parts));
                    }
                    break;
                }
            }
        } catch (IOException e) {
            return flags;
        }
        return flags;
    }
}
