package com.gliwka.hyperscan.jni;

/** Verifies Windows tier selection without loading a native library. */
public final class WindowsPlatformSelectionProbe {

    private WindowsPlatformSelectionProbe() {
    }

    public static void main(String[] args) {
        String selected = HyperscanNativeLoader.selectWindowsX86_64Variant();
        if (args.length > 1) {
            throw new IllegalArgumentException("expected at most one platform argument");
        }
        if (args.length == 1 && !args[0].equals(selected)) {
            throw new AssertionError("expected " + args[0] + " but selected " + selected
                    + " (UseAVX=" + HyperscanNativeLoader.readHotSpotVmOption("UseAVX")
                    + ", UseSSE=" + HyperscanNativeLoader.readHotSpotVmOption("UseSSE") + ")");
        }
        System.out.println(selected);
    }
}
