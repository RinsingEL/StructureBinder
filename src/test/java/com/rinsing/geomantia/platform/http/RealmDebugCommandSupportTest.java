package com.rinsing.geomantia.platform.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RealmDebugCommandSupportTest {
    @Test
    void normalizesSlashPrefixedCommand() {
        assertEquals("tp Rinsing 100 120 -300",
                RealmDebugCommandSupport.normalizeCommand(" /tp Rinsing 100 120 -300 "));
        assertEquals("/tp Rinsing 100 120 -300",
                RealmDebugCommandSupport.prefixedCommand("tp Rinsing 100 120 -300"));
    }

    @Test
    void rejectsBlankOrMultilineCommand() {
        assertThrows(IllegalArgumentException.class, () -> RealmDebugCommandSupport.normalizeCommand(" / "));
        assertThrows(IllegalArgumentException.class,
                () -> RealmDebugCommandSupport.normalizeCommand("tp Rinsing 0 100 0\nweather clear"));
    }

    @Test
    void normalizesSourceMode() {
        assertEquals("auto", RealmDebugCommandSupport.normalizeSourceMode(""));
        assertEquals("player", RealmDebugCommandSupport.normalizeSourceMode("PLAYER"));
        assertThrows(IllegalArgumentException.class, () -> RealmDebugCommandSupport.normalizeSourceMode("keyboard"));
    }

    @Test
    void unsafeManagementCommandsRequireExplicitOverride() {
        assertThrows(IllegalArgumentException.class,
                () -> RealmDebugCommandSupport.requireSafeOrExplicit("stop", false));
        RealmDebugCommandSupport.requireSafeOrExplicit("stop", true);
        RealmDebugCommandSupport.requireSafeOrExplicit("tp Rinsing 100 120 -300", false);
    }
}
