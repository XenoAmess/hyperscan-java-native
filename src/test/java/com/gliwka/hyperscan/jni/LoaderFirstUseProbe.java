package com.gliwka.hyperscan.jni;

import org.bytedeco.javacpp.Loader;

import java.io.File;
import java.util.Map;

/** Runs in an isolated JVM to verify loading after JavaCPP cached its default platform. */
public final class LoaderFirstUseProbe {

    private LoaderFirstUseProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("expected <platform> <first-generated-class>");
        }

        String expectedPlatform = args[0];
        String firstGeneratedClass = args[1];

        System.clearProperty("org.bytedeco.javacpp.platform");
        String cachedDefault = Loader.getPlatform();
        System.setProperty("org.bytedeco.javacpp.platform", expectedPlatform);

        Class.forName(firstGeneratedClass);
        if (!expectedPlatform.equals(HyperscanNativeLoader.getLoadedPlatform())) {
            throw new AssertionError("expected loaded platform " + expectedPlatform
                    + " but got " + HyperscanNativeLoader.getLoadedPlatform()
                    + " after Loader cached " + cachedDefault);
        }

        String expectedPathPart = "/" + expectedPlatform + "/";
        Map<String, String> loadedLibraries = Loader.getLoadedLibraries();
        boolean loadedExpectedLibrary = false;
        for (String path : loadedLibraries.values()) {
            if (path != null && path.replace(File.separatorChar, '/').contains(expectedPathPart)) {
                loadedExpectedLibrary = true;
                break;
            }
        }
        if (!loadedExpectedLibrary) {
            throw new AssertionError("no library loaded from " + expectedPlatform + ": " + loadedLibraries);
        }

        String version = hyperscan.hs_version().getString();
        if (version == null || version.isEmpty()) {
            throw new AssertionError("hs_version returned no value");
        }
        System.out.println("PASS: cached=" + cachedDefault + ", loaded=" + expectedPlatform
                + ", first=" + firstGeneratedClass + ", version=" + version);
    }
}
