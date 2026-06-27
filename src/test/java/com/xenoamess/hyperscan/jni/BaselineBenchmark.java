package com.xenoamess.hyperscan.jni;

import org.bytedeco.javacpp.*;
import org.bytedeco.javacpp.annotation.Cast;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ThreadLocalRandom;

import static com.xenoamess.hyperscan.jni.hyperscan.*;

/**
 * Benchmark to compare baseline (SSE4.2) vs advanced ISA (AVX2/AVX-512) hyperscan builds.
 *
 * The key insight is that match-callback overhead often dominates, so this benchmark
 * offers two scenarios:
 *
 * 1. no-match: patterns that never match -> measures pure scan throughput.
 * 2. mixed:  patterns that match occasionally -> measures realistic throughput.
 *
 * Usage:
 *   java -cp ... com.xenoamess.hyperscan.jni.BaselineBenchmark [options]
 *
 * Options:
 *   --patterns N          number of patterns to compile (default 100)
 *   --iterations N        number of scan calls to measure (default 1000)
 *   --payload-size SIZE   target payload size in bytes (default 1048576)
 *   --warmup N            warmup scans (default max(iterations/10, 50))
 *   --mode no-match|mixed|log|text
 *   --file PATH           for log/text mode: read payload from file
 */
public class BaselineBenchmark {

    // Patterns unlikely to match random text -> pure scan throughput
    private static final String[] NO_MATCH_PATTERNS = {
        "ZZZZZZZZZZZZZZZZZZZZ",
        "this-string-should-not-appear-in-random-data",
        "quantumphishing12345@protonmail.com",
        "https://internal-only.example.com/secret/path",
        "BEGIN TOP SECRET DOCUMENT",
        "token=[a-f0-9]{128}",
        "CVE-[0-9]{4}-[0-9]{8}",
        "sha512:[a-f0-9]{128}",
        "\\{\\{\\{ESCAPE_SEQUENCE_\\d\\}\\}\\}",
        "MATCH_ME_IF_YOU_CAN_42",
        "rare_literal_that_is_very_unlikely_to_match",
        "___XyZ_12345_qWeRtY___"
    };

    // Patterns that match occasionally in realistic text
    private static final String[] MIXED_PATTERNS = {
        "GET /presentations/",
        "POST /api/v[0-9]+/",
        "Mozilla/[0-9]+\\.[0-9]+",
        "Chrome/[0-9]+\\.[0-9]+",
        "[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}",
        "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}",
        "https?://[a-zA-Z0-9./?=_-]+",
        "error|ERROR|exception|Exception",
        "password|passwd|secret|token",
        "TODO|FIXME|XXX",
        "\\b[a-f0-9]{32}\\b"
    };

    public static void main(String[] args) throws Exception {
        int patternCount = intArg(args, "--patterns", 100);
        int iterations = intArg(args, "--iterations", 1000);
        int payloadSize = intArg(args, "--payload-size", 1024 * 1024);
        int warmupIterations = intArg(args, "--warmup", Math.max(iterations / 10, 50));
        String mode = stringArg(args, "--mode", "no-match");
        String filePath = stringArg(args, "--file", null);

        System.out.println("=== hyperscan baseline benchmark ===");
        System.out.println("patterns:        " + patternCount);
        System.out.println("payload size:    " + payloadSize + " bytes");
        System.out.println("warmup scans:    " + warmupIterations);
        System.out.println("measured scans:  " + iterations);
        System.out.println("mode:            " + mode);

        String[] patterns = generatePatterns(patternCount, mode);
        String payload = buildPayload(mode, filePath, payloadSize);

        hs_database_t database = compileDatabase(patterns);
        hs_scratch_t scratch = allocScratch(database);

        long warmupMatches = runScans(database, scratch, payload, warmupIterations);
        System.out.println("warmup matches:  " + warmupMatches);

        long start = System.nanoTime();
        long matches = runScans(database, scratch, payload, iterations);
        long elapsedNs = System.nanoTime() - start;

        double elapsedMs = elapsedNs / 1_000_000.0;
        double totalMb = (iterations * (double) payload.length()) / (1024.0 * 1024.0);
        double throughputMbs = totalMb / (elapsedMs / 1000.0);
        double latencyUs = elapsedMs * 1000.0 / iterations;

        System.out.println();
        System.out.println("results:");
        System.out.printf("  elapsed:        %.2f ms%n", elapsedMs);
        System.out.printf("  throughput:     %.2f MB/s%n", throughputMbs);
        System.out.printf("  avg latency:    %.3f us/scan%n", latencyUs);
        System.out.printf("  total matches:  %d%n", matches);
        System.out.printf("  matches/scan:   %.2f%n", matches / (double) iterations);

        hs_free_scratch(scratch);
        hs_free_database(database);
    }

    private static hs_database_t compileDatabase(String[] patterns) throws Exception {
        PointerPointer<BytePointer> expressions = new PointerPointer<>(patterns);
        IntPointer ids = new IntPointer(patterns.length);
        IntPointer flags = new IntPointer(patterns.length);
        for (int i = 0; i < patterns.length; i++) {
            ids.put(i, i + 1);
            flags.put(i, 0);
        }

        PointerPointer<hs_database_t> dbPtr = new PointerPointer<>(1);
        PointerPointer<hs_compile_error_t> errPtr = new PointerPointer<>(1);

        int rc = hs_compile_multi(expressions, flags, ids, patterns.length, HS_MODE_BLOCK, null, dbPtr, errPtr);
        if (rc != 0) {
            hs_compile_error_t err = new hs_compile_error_t(errPtr.get(0));
            throw new RuntimeException("compile failed: " + err.message().getString());
        }
        return new hs_database_t(dbPtr.get(0));
    }

    private static hs_scratch_t allocScratch(hs_database_t database) {
        hs_scratch_t scratch = new hs_scratch_t();
        int rc = hs_alloc_scratch(database, scratch);
        if (rc != 0) {
            throw new RuntimeException("alloc scratch failed: " + rc);
        }
        return scratch;
    }

    private static long runScans(hs_database_t database, hs_scratch_t scratch, String payload, int iterations) {
        MatchCounter counter = new MatchCounter();
        for (int i = 0; i < iterations; i++) {
            hs_scan(database, payload, payload.length(), 0, scratch, counter, null);
        }
        return counter.count;
    }

    private static String[] generatePatterns(int count, String mode) {
        String[] source = "no-match".equals(mode) ? NO_MATCH_PATTERNS : MIXED_PATTERNS;
        String[] patterns = new String[count];
        for (int i = 0; i < count; i++) {
            patterns[i] = source[i % source.length];
        }
        return patterns;
    }

    private static String buildPayload(String mode, String filePath, int size) throws IOException {
        switch (mode) {
            case "log":
                return loadPayload(filePath != null ? filePath : "/tmp/opencode/bench/apache_logs.txt", size);
            case "text":
                return loadPayload(filePath != null ? filePath : "/tmp/opencode/bench/leipzig-3200.txt", size);
            case "mixed":
                return generateMixedPayload(size);
            case "no-match":
            default:
                return generateRandomPayload(size);
        }
    }

    private static String generateRandomPayload(int size) {
        StringBuilder sb = new StringBuilder(size);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < size; i++) {
            sb.append((char) (0x20 + rnd.nextInt(0x5E)));
        }
        return sb.toString();
    }

    private static String generateMixedPayload(int size) {
        // Mostly random, with a few realistic tokens sprinkled in
        StringBuilder sb = new StringBuilder(size);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        String[] tokens = {
            "GET /presentations/logstash-monitorama-2013/images/kibana-search.png HTTP/1.1",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
            "error: connection refused",
            "password=supersecret123",
            "https://github.com/example/repo/pull/123",
            "contact@example.com"
        };
        int pos = 0;
        while (pos < size) {
            if (rnd.nextDouble() < 0.0001) {
                String token = tokens[rnd.nextInt(tokens.length)];
                if (pos + token.length() > size) break;
                sb.append(token);
                pos += token.length();
            } else {
                sb.append((char) (0x20 + rnd.nextInt(0x5E)));
                pos++;
            }
        }
        while (pos++ < size) sb.append(' ');
        return sb.toString();
    }

    private static String loadPayload(String filePath, int size) throws IOException {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            throw new IOException("data file not found: " + path);
        }
        byte[] bytes = Files.readAllBytes(path);
        StringBuilder sb = new StringBuilder(size);
        while (sb.length() < size) {
            int remaining = size - sb.length();
            int len = Math.min(bytes.length, remaining);
            sb.append(new String(bytes, 0, len, java.nio.charset.StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static int intArg(String[] args, String name, int defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) return Integer.parseInt(args[i + 1]);
        }
        return defaultValue;
    }

    private static String stringArg(String[] args, String name, String defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) return args[i + 1];
        }
        return defaultValue;
    }

    public static class MatchCounter extends match_event_handler {
        long count = 0;

        @Override
        public int call(@Cast("unsigned int") int id,
                        @Cast("unsigned long long") long from,
                        @Cast("unsigned long long") long to,
                        @Cast("unsigned int") int flags, Pointer context) {
            count++;
            return 0;
        }
    }
}
