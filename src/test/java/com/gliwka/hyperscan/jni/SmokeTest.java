package com.gliwka.hyperscan.jni;

import org.bytedeco.javacpp.*;
import org.bytedeco.javacpp.annotation.Cast;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static com.gliwka.hyperscan.jni.hyperscan.*;
import static org.assertj.core.api.Assertions.assertThat;

class SmokeTest {
    @Test
    void smokeTest() {
        assertThat(hs_valid_platform()).isEqualTo(0);

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
            assertThat(compileResult).isEqualTo(0);

            hs_database_t database = new hs_database_t(databasePointer.get(0));
            hs_scratch_t scratch = new hs_scratch_t();
            try {
                assertThat(hs_alloc_scratch(database, scratch)).isEqualTo(0);
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
                        assertThat(hs_scan(database, inputPointer, input.length, 0, scratch, handler, null)).isEqualTo(0);
                    }
                }
                assertThat(matches).containsExactly(new long[]{2, 4, 7}, new long[]{3, 9, 12});
            } finally {
                hs_free_scratch(scratch);
                scratch.close();
                hs_free_database(database);
                database.close();
            }
        }
    }
}
