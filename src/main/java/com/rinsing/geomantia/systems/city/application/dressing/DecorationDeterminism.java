package com.rinsing.geomantia.systems.city.application.dressing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class DecorationDeterminism {
    private DecorationDeterminism() {
    }

    public static String slotId(String programId, String patternType, long... coordinates) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(programId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(patternType.getBytes(StandardCharsets.UTF_8));
            for (long coordinate : coordinates) {
                for (int shift = 56; shift >= 0; shift -= 8) {
                    digest.update((byte) (coordinate >>> shift));
                }
            }
            return programId + ":" + HexFormat.of().formatHex(digest.digest(), 0, 8);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", ex);
        }
    }

    public static long worldHash(long seed, String programId, long... coordinates) {
        long hash = mix64(seed ^ stableStringHash(programId));
        for (long coordinate : coordinates) {
            hash = mix64(hash ^ mix64(coordinate));
        }
        return hash;
    }

    public static int boundedInt(long hash, int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be > 0");
        }
        return (int) Math.floorMod(hash, bound);
    }

    private static long stableStringHash(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
