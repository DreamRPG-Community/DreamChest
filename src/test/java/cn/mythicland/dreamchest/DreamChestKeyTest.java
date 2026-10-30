package cn.mythicland.dreamchest;

import cn.mythicland.dreamchest.api.DreamChestKey;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the world-coordinate storage key format.
 */
class DreamChestKeyTest {

    @Test
    void encodedKeyRoundTripsNegativeCoordinates() {
        DreamChestKey key = new DreamChestKey(UUID.fromString("00000000-0000-0000-0000-000000000001"), -4, 70, -8);

        assertEquals(key, DreamChestKey.parse(key.encoded()));
    }

    @Test
    void malformedKeyIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> DreamChestKey.parse("not-a-key"));
    }
}
