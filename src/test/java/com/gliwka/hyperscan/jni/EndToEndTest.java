package com.gliwka.hyperscan.jni;

import org.bytedeco.javacpp.*;
import org.bytedeco.javacpp.annotation.Cast;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static com.gliwka.hyperscan.jni.hyperscan.*;

/**
 * Standalone end-to-end smoke test that can be run with {@code java -cp ...}.
 *
 * It exercises the same code path as {@link SmokeTest} but does not depend on
 * JUnit, making it easy to run against pre-built native artifacts in CI.
 */
public class EndToEndTest {
    public static void main(String[] args) throws Exception {
        String explicit = System.getProperty("org.bytedeco.javacpp.platform");
        String selected = HyperscanNativeLoader.selectPlatform();
        System.out.println("explicit platform: " + explicit);
        System.out.println("selected platform: " + selected);

        HyperscanNativeLoader.load();

        String actual = System.getProperty("org.bytedeco.javacpp.platform");
        String loaded = HyperscanNativeLoader.getLoadedPlatform();
        System.out.println("actual platform: " + actual);
        System.out.println("loaded platform: " + loaded);
        if (actual != null && !actual.equals(loaded)) {
            throw new RuntimeException("expected loaded platform " + actual + " but got " + loaded);
        }

        int valid = hs_valid_platform();
        if (valid != 0) {
            throw new RuntimeException("hs_valid_platform returned " + valid);
        }

        try (BytePointer first = new BytePointer("abc1", StandardCharsets.UTF_8);
             BytePointer second = new BytePointer("asa", StandardCharsets.UTF_8);
             BytePointer third = new BytePointer("dab", StandardCharsets.UTF_8);
             PointerPointer<BytePointer> expressionsPointer = new PointerPointer<>(first, second, third);
             IntPointer patternIds = new IntPointer(1, 2, 3);
             IntPointer compileFlags = new IntPointer(HS_FLAG_SOM_LEFTMOST, HS_FLAG_SOM_LEFTMOST, HS_FLAG_SOM_LEFTMOST);
             PointerPointer<hs_database_t> databasePointer = new PointerPointer<>(1);
             PointerPointer<hs_compile_error_t> compileErrorPointer = new PointerPointer<>(1)) {
            int compileResult = hs_compile_multi(expressionsPointer, compileFlags, patternIds, 3, HS_MODE_BLOCK,
                    null, databasePointer, compileErrorPointer);
            if (compileResult != 0) {
                throw new RuntimeException("hs_compile_multi failed: " + compileResult);
            }

            hs_database_t database = new hs_database_t(databasePointer.get(0));
            hs_scratch_t scratch = new hs_scratch_t();
            try {
                int allocResult = hs_alloc_scratch(database, scratch);
                if (allocResult != 0) {
                    throw new RuntimeException("hs_alloc_scratch failed: " + allocResult);
                }

                List<long[]> matches = new ArrayList<>();
                try (match_event_handler handler = new match_event_handler() {
                    @Override
                    public int call(@Cast("unsigned int") int id,
                                    @Cast("unsigned long long") long from,
                                    @Cast("unsigned long long") long to,
                                    @Cast("unsigned int") int flags, Pointer context) {
                        matches.add(new long[]{id, from, to});
                        return 0;
                    }
                }) {
                    byte[] input = "-21dasaaadabcaaa".getBytes(StandardCharsets.UTF_8);
                    try (BytePointer inputPointer = new BytePointer(input)) {
                        int scanResult = hs_scan(database, inputPointer, input.length, 0, scratch, handler, null);
                        if (scanResult != 0) {
                            throw new RuntimeException("hs_scan failed: " + scanResult);
                        }
                    }
                }

                if (matches.size() != 2 || matches.get(0)[0] != 2 || matches.get(1)[0] != 3) {
                    throw new RuntimeException("unexpected matches: " + matches);
                }
                System.out.println("PASS: platform=" + actual + ", matches=" + matches.size());
            } finally {
                hs_free_scratch(scratch);
                scratch.close();
                hs_free_database(database);
                database.close();
            }
        }
    }
}
