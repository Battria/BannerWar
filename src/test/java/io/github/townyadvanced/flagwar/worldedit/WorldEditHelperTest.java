package io.github.townyadvanced.flagwar.worldedit;

import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Random;

import static io.github.townyadvanced.flagwar.worldedit.SnapshotManifest.Bounds;
import static org.junit.jupiter.api.Assertions.*;

class WorldEditHelperTest {
    @Test void partitionsCoverExactlyTheRequestedBlocksWithNoOverlap() {
        Random random = new Random(12345);
        for (int attempt = 0; attempt < 100; attempt++) {
            int x = random.nextInt(200) - 100;
            int y = random.nextInt(200) - 100;
            int z = random.nextInt(200) - 100;
            Bounds original = new Bounds(x, y, z, x + random.nextInt(100) + 1,
                y + random.nextInt(100) + 1, z + random.nextInt(100) + 1);
            int side = 16 + random.nextInt(49);
            var boxes = new ArrayList<>(WorldEditHelper.split(WorldEditHelper.box(original), new Vector(side, side, side)));
            long volume = 0;
            for (int i = 0; i < boxes.size(); i++) {
                var region = WorldEditHelper.region(WorldEditHelper.bounds(boxes.get(i)));
                assertTrue(region.getWidth() <= side);
                assertTrue(region.getHeight() <= side);
                assertTrue(region.getLength() <= side);
                volume += (long) region.getWidth() * region.getHeight() * region.getLength();
                for (int j = 0; j < i; j++) assertFalse(boxes.get(i).overlaps(boxes.get(j)));
            }
            assertEquals((long) (original.maxX() - original.minX()) * (original.maxY() - original.minY())
                * (original.maxZ() - original.minZ()), volume);
        }
    }

    @Test void exactBatchBoundaryDoesNotCopyAnExtraLayer() {
        var region = WorldEditHelper.region(new Bounds(-64, -64, 320, 0, 0, 384));
        assertEquals(64, region.getWidth());
        assertEquals(64, region.getHeight());
        assertEquals(64, region.getLength());
        assertEquals(-1, region.getMaximumPoint().x());
        assertEquals(383, region.getMaximumPoint().z());
    }

    @Test void legacyNamesPreserveUnderscoresNegativeCoordinatesAndOneExtension() {
        assertEquals(Path.of("structures", "New_Termez_-16_-64_320.schem"),
            WorldEditHelper.legacyFile(Path.of("structures"), "New_Termez", new BoundingBox(-16, -64, 320, 0, 192, 336)));
        assertThrows(IllegalArgumentException.class, () -> WorldEditHelper.legacyFile(Path.of("structures"),
            "../other", new BoundingBox(0, 0, 0, 1, 1, 1)));
    }

    @Test void invalidBatchSizeFailsInsteadOfLoopingForever() {
        assertThrows(IllegalArgumentException.class,
            () -> WorldEditHelper.split(new BoundingBox(0, 0, 0, 16, 16, 16), new Vector(0, 64, 64)));
    }
}
