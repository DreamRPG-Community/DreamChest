package cn.mythicland.dreamchest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DreamChestParticleGeometryTest {

    @Test
    void keepsParticlesNearTheChestHeight() {
        List<DreamChestParticleGeometry.ParticlePoint> points = DreamChestParticleGeometry.randomAroundChest(
                10,
                64,
                20,
                12,
                new SplittableRandom(0L)
        );

        assertEquals(12, points.size());
        assertTrue(points.stream().allMatch(point ->
                point.x() >= 9.0D && point.x() < 12.0D
                        && point.y() >= 64.3D && point.y() < 65.3D
                        && point.z() >= 19.0D && point.z() < 22.0D
        ));
    }

    @Test
    void usesTheConfiguredParticleCount() {
        List<DreamChestParticleGeometry.ParticlePoint> points = DreamChestParticleGeometry.randomAroundChest(
                10,
                64,
                20,
                3,
                new SplittableRandom(0L)
        );

        assertEquals(3, points.size());
    }
}
