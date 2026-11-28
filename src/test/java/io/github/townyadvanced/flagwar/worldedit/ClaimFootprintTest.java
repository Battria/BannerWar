package io.github.townyadvanced.flagwar.worldedit;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

import static io.github.townyadvanced.flagwar.worldedit.ClaimFootprint.Chunk;
import static io.github.townyadvanced.flagwar.worldedit.SnapshotManifest.Bounds;
import static org.junit.jupiter.api.Assertions.*;

class ClaimFootprintTest {
    @Test void filledInteriorUsesLargeBatches() {
        Set<Chunk> claims = new HashSet<>();
        for (int x = -4; x < 0; x++) for (int z = 0; z < 4; z++) claims.add(new Chunk(x, z));
        ClaimFootprint footprint = new ClaimFootprint(claims);
        List<Bounds> batches = footprint.batches(-64, 320, 64);
        assertEquals(6, batches.size());
        assertTrue(batches.stream().allMatch(b -> b.maxX() - b.minX() == 64 && b.maxZ() - b.minZ() == 64));
        assertExactCoverage(footprint, batches, -64, 320, 64);
    }

    @Test void holesSubdivideToChunkEdgesWithoutExpandingClaims() {
        Set<Chunk> claims = new HashSet<>();
        for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++) claims.add(new Chunk(x, z));
        claims.remove(new Chunk(1, 1));
        ClaimFootprint footprint = new ClaimFootprint(claims);
        List<Bounds> batches = footprint.batches(0, 64, 64);
        assertEquals(6, batches.size());
        assertTrue(batches.stream().anyMatch(b -> b.maxX() - b.minX() == 16 && b.maxZ() - b.minZ() == 16));
        assertTrue(batches.stream().anyMatch(b -> b.maxX() - b.minX() == 32 && b.maxZ() - b.minZ() == 32));
        assertFalse(footprint.containsBlock(16, 16));
        assertExactCoverage(footprint, batches, 0, 64, 64);
    }

    @Test void veryDistantIslandsNeverAllocateOrCopyTheirBoundingRectangle() {
        ClaimFootprint footprint = new ClaimFootprint(List.of(new Chunk(0, 0), new Chunk(1_000_000, -1_000_000)));
        List<Bounds> batches = footprint.batches(-64, 320, 64);
        assertEquals(2, batches.size());
        assertExactCoverage(footprint, batches, -64, 320, 64);
    }

    @Test void neighboringChunksCanShareOneFullHeightBatch() {
        ClaimFootprint footprint = new ClaimFootprint(List.of(new Chunk(0, 0), new Chunk(1, 0)));
        List<Bounds> batches = footprint.batches(-64, 320, 64, 262144);
        assertEquals(1, batches.size());
        assertEquals(384, batches.get(0).maxY() - batches.get(0).minY());
        assertExactCoverage(footprint, batches, -64, 320, 64, 262144);
    }

    @Test void oneChunkStaysFullHeightWhenItFitsTheBudget() {
        ClaimFootprint footprint = new ClaimFootprint(List.of(new Chunk(-1, 3)));
        List<Bounds> batches = footprint.batches(-64, 320, 64, 262144);
        assertEquals(List.of(new Bounds(-16, -64, 48, 0, 320, 64)), batches);
        assertExactCoverage(footprint, batches, -64, 320, 64, 262144);
    }

    @Test void roundingFallsBackToChunksInsteadOfCreatingMoreJobs() {
        Set<Chunk> claims = new HashSet<>();
        for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) claims.add(new Chunk(x, z));
        ClaimFootprint footprint = new ClaimFootprint(claims);
        // A 3x3 tile would need ten 42-block slices. Nine full-height chunks fit the same budget.
        List<Bounds> batches = footprint.batches(-64, 320, 48, 98304);
        assertEquals(9, batches.size());
        assertTrue(batches.stream().allMatch(b -> b.maxY() - b.minY() == 384));
        assertExactCoverage(footprint, batches, -64, 320, 48, 98304);
    }

    @Test void smallBudgetsAlsoSubdivideTheHorizontalArea() {
        Set<Chunk> claims = new HashSet<>();
        for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++) claims.add(new Chunk(x, z));
        ClaimFootprint footprint = new ClaimFootprint(claims);
        assertExactCoverage(footprint, footprint.batches(-2, 2, 64, 256), -2, 2, 64, 256);
    }

    @Test void negativeBlockCoordinatesAndExclusiveEdgesSelectTheCorrectChunk() {
        ClaimFootprint footprint = new ClaimFootprint(List.of(new Chunk(-1, -1), new Chunk(2, 3)));
        assertTrue(footprint.containsBlock(-16, -16));
        assertTrue(footprint.containsBlock(-1, -1));
        assertFalse(footprint.containsBlock(0, -1));
        assertFalse(footprint.containsBlock(-17, -1));
        assertTrue(footprint.containsBlock(32, 48));
        assertFalse(footprint.containsBlock(48, 48));
    }

    @Test void arbitraryShapesAndNonPowerOfTwoLimitsRemainExact() {
        Random random = new Random(39124);
        for (int side : new int[]{16, 17, 31, 32, 48, 63, 64, 80, 96, 128}) {
            for (int attempt = 0; attempt < 15; attempt++) {
                Set<Chunk> claims = new HashSet<>();
                for (int i = 0; i < 70; i++) claims.add(new Chunk(random.nextInt(21) - 10, random.nextInt(21) - 10));
                ClaimFootprint footprint = new ClaimFootprint(claims);
                assertExactCoverage(footprint, footprint.batches(-61, 153, side), -61, 153, side);
            }
        }
    }

    @Test void customVolumeBudgetsNeverExceedTheBudgetOrPerChunkJobCount() {
        Set<Chunk> claims = new HashSet<>();
        for (int x = -5; x < 6; x++) for (int z = -4; z < 5; z++) {
            if ((x + z) % 5 != 0) claims.add(new Chunk(x, z));
        }
        ClaimFootprint footprint = new ClaimFootprint(claims);
        for (long budget : new long[]{4096, 10000, 32891, 65536, 98304, 262144, 2097152}) {
            for (int side : new int[]{16, 48, 64, 128})
                assertExactCoverage(footprint, footprint.batches(-61, 322, side, budget), -61, 322, side, budget);
        }
    }

    @Test void actualTermezDumpCoversOnlyIts743ClaimsAcrossBothRegions() throws Exception {
        Set<Chunk> claims = new HashSet<>();
        try (var reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
            getClass().getResourceAsStream("/termez-claims.csv")), StandardCharsets.UTF_8))) {
            reader.readLine();
            reader.lines().forEach(line -> {
                String[] pair = line.split(",");
                claims.add(new Chunk(Integer.parseInt(pair[0]), Integer.parseInt(pair[1])));
            });
        }
        assertEquals(743, claims.size());
        ClaimFootprint footprint = new ClaimFootprint(claims);
        List<Bounds> batches = WorldEditService.batches(footprint, footprint.bounds(-64, 320), 64).stream()
            .map(SnapshotManifest.Batch::bounds).toList();
        assertExactCoverage(footprint, batches, -64, 320, 64);
        assertFalse(footprint.containsBlock(50 * 16, 100 * 16));
        assertTrue(batches.size() < 743);
        assertTrue(batches.stream().anyMatch(b -> b.maxX() - b.minX() == 64 && b.maxZ() - b.minZ() == 64));
        assertEquals(743L * 16 * 16 * 384, volume(batches));
        System.out.println("Termez fixture: 743 claimed chunks -> " + batches.size()
            + " volume-budgeted batches, " + volume(batches) + " blocks; old rectangle: 12768 batches, 3280994304 blocks");
    }

    @Test void allSixteenTownsFromTheDumpStayAtOrBelowPerChunkBatching() throws Exception {
        Map<String, Set<Chunk>> towns = new HashMap<>();
        try (var reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
            getClass().getResourceAsStream("/town-claims.tsv")), StandardCharsets.UTF_8))) {
            reader.readLine();
            reader.lines().forEach(line -> {
                String[] values = line.split("\t");
                towns.computeIfAbsent(values[0] + "/" + values[1], ignored -> new HashSet<>())
                    .add(new Chunk(Integer.parseInt(values[2]), Integer.parseInt(values[3])));
            });
        }
        assertEquals(16, towns.size());
        int totalClaims = 0;
        int totalBatches = 0;
        for (var town : towns.entrySet()) {
            ClaimFootprint footprint = new ClaimFootprint(town.getValue());
            List<Bounds> batches = WorldEditService.batches(footprint, footprint.bounds(-64, 320), 64, 262144)
                .stream().map(SnapshotManifest.Batch::bounds).toList();
            assertExactCoverage(footprint, batches, -64, 320, 64, 262144);
            assertTrue(batches.size() <= town.getValue().size(), town.getKey());
            totalClaims += town.getValue().size();
            totalBatches += batches.size();
        }
        assertEquals(2364, totalClaims);
        System.out.println("All 16 town fixtures: " + totalClaims + " chunks -> " + totalBatches + " volume-budgeted batches");
    }

    @Test void invalidDimensionsFailImmediately() {
        ClaimFootprint footprint = new ClaimFootprint(List.of(new Chunk(0, 0)));
        assertThrows(IllegalArgumentException.class, () -> footprint.batches(0, 64, 0));
        assertThrows(IllegalArgumentException.class, () -> footprint.batches(0, 64, 15));
        assertThrows(IllegalArgumentException.class, () -> footprint.batches(64, 64, 64));
        assertThrows(IllegalArgumentException.class, () -> footprint.batches(0, 64, 64, 255));
        assertThrows(IllegalArgumentException.class, () -> new ClaimFootprint(List.of()));
    }

    private static long volume(Collection<Bounds> batches) {
        return batches.stream().mapToLong(b -> (long) (b.maxX() - b.minX()) * (b.maxY() - b.minY())
            * (b.maxZ() - b.minZ())).sum();
    }

    private static void assertExactCoverage(ClaimFootprint footprint, List<Bounds> batches, int minY, int maxY, int side) {
        assertExactCoverage(footprint, batches, minY, maxY, side, (long) side * side * side);
    }

    private static void assertExactCoverage(ClaimFootprint footprint, List<Bounds> batches, int minY, int maxY, int side, long budget) {
        Map<Chunk, List<Bounds>> intervals = new HashMap<>();
        for (Bounds batch : batches) {
            assertTrue(batch.maxX() - batch.minX() <= side && batch.maxZ() - batch.minZ() <= side);
            assertTrue((long) (batch.maxX() - batch.minX()) * (batch.maxZ() - batch.minZ())
                * (batch.maxY() - batch.minY()) <= budget);
            assertEquals(0, Math.floorMod(batch.minX(), 16));
            assertEquals(0, Math.floorMod(batch.maxX(), 16));
            assertEquals(0, Math.floorMod(batch.minZ(), 16));
            assertEquals(0, Math.floorMod(batch.maxZ(), 16));
            assertTrue(footprint.containsArea(batch), "Batch includes an unclaimed chunk: " + batch);
            for (int x = batch.minX() / 16; x < batch.maxX() / 16; x++) {
                for (int z = batch.minZ() / 16; z < batch.maxZ() / 16; z++)
                    intervals.computeIfAbsent(new Chunk(x, z), ignored -> new ArrayList<>()).add(batch);
            }
        }
        assertEquals(footprint.chunks(), intervals.keySet());
        for (List<Bounds> column : intervals.values()) {
            column.sort(Comparator.comparingInt(Bounds::minY));
            int nextY = minY;
            for (Bounds batch : column) {
                assertEquals(nextY, batch.minY(), "Overlapping or missing vertical slice");
                nextY = batch.maxY();
            }
            assertEquals(maxY, nextY);
        }
        assertEquals((long) footprint.chunks().size() * 16 * 16 * (maxY - minY), volume(batches));
        long chunkSliceHeight = Math.min((long) maxY - minY, budget / 256);
        long chunkBatches = footprint.chunks().size() * (1 + ((long) maxY - minY - 1) / chunkSliceHeight);
        assertTrue(batches.size() <= chunkBatches, "Planner uses more jobs than chunks under the same budget");
    }
}
