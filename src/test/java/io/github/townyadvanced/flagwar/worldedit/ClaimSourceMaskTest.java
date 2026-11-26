package io.github.townyadvanced.flagwar.worldedit;

import com.sk89q.worldedit.math.BlockVector3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClaimSourceMaskTest {
    @Test void croppedLegacyClipboardMapsBackToWorldClaimsRatherThanLocalCoordinates() {
        ClaimFootprint footprint = new ClaimFootprint(List.of(new ClaimFootprint.Chunk(-2, 3),
            new ClaimFootprint.Chunk(10, 20)));
        ClaimSourceMask mask = new ClaimSourceMask(footprint, BlockVector3.at(20, 70, -30), BlockVector3.at(-32, -64, 48));
        assertTrue(mask.test(BlockVector3.at(20, 70, -30)));
        assertTrue(mask.test(BlockVector3.at(35, 200, -15)));
        assertFalse(mask.test(BlockVector3.at(36, 70, -30)));
        assertFalse(mask.test(BlockVector3.at(20, 70, -14)));
        assertFalse(mask.test(BlockVector3.at(19, 70, -30)));
        assertSame(mask, mask.copy());
    }

    @Test void holesAndTheGapBetweenDisconnectedRegionsStayExcluded() {
        ClaimFootprint footprint = new ClaimFootprint(List.of(new ClaimFootprint.Chunk(0, 0), new ClaimFootprint.Chunk(2, 0),
            new ClaimFootprint.Chunk(120, 200)));
        ClaimSourceMask mask = new ClaimSourceMask(footprint, BlockVector3.ZERO, BlockVector3.ZERO);
        assertTrue(mask.test(BlockVector3.at(0, 64, 0)));
        assertFalse(mask.test(BlockVector3.at(16, 64, 0)));
        assertTrue(mask.test(BlockVector3.at(32, 64, 0)));
        assertFalse(mask.test(BlockVector3.at(50 * 16, 64, 100 * 16)));
        assertTrue(mask.test(BlockVector3.at(120 * 16, 64, 200 * 16)));
    }
}
