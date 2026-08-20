package com.gliwka.hyperscan.jni;

import org.bytedeco.javacpp.Loader;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;

public final class HyperscanNativeLoader {

    private static final Set<String> LINUX_X86_64_BASELINE_FLAGS = new HashSet<>(
            Arrays.asList("sse4_2", "popcnt")
    );
    private static final Set<String> LINUX_X86_64_AVX2_FLAGS = new HashSet<>(
            Arrays.asList(
                    "sse4_2", "popcnt", "avx", "avx2", "bmi1", "bmi2",
                    "f16c", "fma", "movbe", "xsave"
            )
    );
    private static final Set<String> LINUX_ARM64_SVE2_FLAGS = new HashSet<>(
            Arrays.asList("sve2")
    );

    private static volatile boolean loaded = false;
    private static volatile String loadedPlatform;

    private HyperscanNativeLoader() {
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }

        String platform = System.getProperty("org.bytedeco.javacpp.platform");
        if (platform == null || platform.isEmpty()) {
            platform = selectPlatform();
            if (platform != null) {
                System.setProperty("org.bytedeco.javacpp.platform", platform);
            }
        }

        if (platform != null) {
            // Loader caches its default platform during class initialization. Passing
            // explicit properties keeps tier selection correct even if another
            // JavaCPP Pointer class initialized Loader before Hyperscan.
            Properties properties = Loader.loadProperties(platform, null);
            Loader.load(JavaCppPreset.class, properties, false);
            loadedPlatform = platform;
        } else {
            Loader.load(JavaCppPreset.class);
            loadedPlatform = Loader.getPlatform();
        }
        loaded = true;
    }

    public static String getLoadedPlatform() {
        return loadedPlatform;
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
        if (flags.containsAll(LINUX_X86_64_AVX2_FLAGS)
                && (flags.contains("abm") || flags.contains("lzcnt"))) {
            return "linux-x86_64-avx2";
        }
        if (flags.containsAll(LINUX_X86_64_BASELINE_FLAGS)) {
            return "linux-x86_64-baseline";
        }

        throw new UnsatisfiedLinkError(
                "Hyperscan baseline requires SSE4.2 and POPCNT on Linux x86_64"
        );
    }

    private static String selectLinuxArm64Variant() {
        Set<String> flags = readLinuxCpuFlags();

        if (flags.containsAll(LINUX_ARM64_SVE2_FLAGS)) {
            return "linux-arm64";
        }

        return "linux-arm64-baseline";
    }

    static String selectWindowsX86_64Variant() {
        // Intel Hyperscan 5.4.2 does not provide a working AVX-512 MSVC build,
        // so we ship only baseline (SSE4.2-class) and AVX2 tiers. Both AVX2 and
        // AVX-512 capable hosts use the AVX2 build published as windows-x86_64.
        // HotSpot derives UseAVX from CPUID plus OS XSAVE support, unlike
        // PROCESSOR_IDENTIFIER, which normally contains no ISA feature names.
        int useAvx = readHotSpotVmOption("UseAVX");
        if (useAvx >= 2) {
            return "windows-x86_64";
        }

        int useSse = readHotSpotVmOption("UseSSE");
        if (useSse >= 0 && useSse < 4) {
            throw new UnsatisfiedLinkError(
                    "Hyperscan baseline requires SSE4.2 on Windows x86_64"
            );
        }

        // Non-HotSpot VMs may not expose these diagnostic options. Baseline is
        // the conservative fallback; callers can explicitly select a tier.
        return "windows-x86_64-baseline";
    }

    static int readHotSpotVmOption(String option) {
        try {
            Object value = ManagementFactory.getPlatformMBeanServer().invoke(
                    new ObjectName("com.sun.management:type=HotSpotDiagnostic"),
                    "getVMOption",
                    new Object[]{option},
                    new String[]{String.class.getName()}
            );
            if (value instanceof CompositeData) {
                Object optionValue = ((CompositeData) value).get("value");
                if (optionValue != null) {
                    return Integer.parseInt(optionValue.toString());
                }
            }
        } catch (Exception ignored) {
            // The diagnostic bean is HotSpot-specific; callers use baseline.
        }
        return -1;
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
