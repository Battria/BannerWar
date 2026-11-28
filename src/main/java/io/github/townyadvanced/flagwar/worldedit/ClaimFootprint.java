package io.github.townyadvanced.flagwar.worldedit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static io.github.townyadvanced.flagwar.worldedit.SnapshotManifest.Bounds;

/** Immutable union of the Minecraft chunks selected before combat, including disconnected islands. */
final class ClaimFootprint {
    static final int CHUNK_SIZE = 16;
    record Chunk(int x, int z) {}

    private final Set<Chunk> chunks;

    ClaimFootprint(Collection<Chunk> chunks) {
        this.chunks = Set.copyOf(chunks);
        if (this.chunks.isEmpty()) throw new IllegalArgumentException("Snapshot contains no claimed chunks");
    }

    Set<Chunk> chunks() {
        return chunks;
    }

    boolean containsBlock(int x, int z) {
        return chunks.contains(new Chunk(Math.floorDiv(x, CHUNK_SIZE), Math.floorDiv(z, CHUNK_SIZE)));
    }

    boolean intersects(Bounds bounds) {
        return chunks.stream().anyMatch(chunk -> chunk.x() * CHUNK_SIZE < bounds.maxX()
            && (chunk.x() + 1) * CHUNK_SIZE > bounds.minX() && chunk.z() * CHUNK_SIZE < bounds.maxZ()
            && (chunk.z() + 1) * CHUNK_SIZE > bounds.minZ());
    }

    boolean containsArea(Bounds bounds) {
        for (int x = Math.floorDiv(bounds.minX(), CHUNK_SIZE); x <= Math.floorDiv(bounds.maxX() - 1, CHUNK_SIZE); x++) {
            for (int z = Math.floorDiv(bounds.minZ(), CHUNK_SIZE); z <= Math.floorDiv(bounds.maxZ() - 1, CHUNK_SIZE); z++) {
                if (!chunks.contains(new Chunk(x, z))) return false;
            }
        }
        return true;
    }

    /** Restricts old rectangular snapshots to original claims they actually cover. */
    ClaimFootprint within(Bounds bounds) {
        return new ClaimFootprint(chunks.stream().filter(chunk -> chunk.x() * CHUNK_SIZE >= bounds.minX()
            && (chunk.x() + 1) * CHUNK_SIZE <= bounds.maxX() && chunk.z() * CHUNK_SIZE >= bounds.minZ()
            && (chunk.z() + 1) * CHUNK_SIZE <= bounds.maxZ()).toList());
    }

    Bounds bounds(int minY, int maxY) {
        int minX = chunks.stream().mapToInt(Chunk::x).min().orElseThrow() * CHUNK_SIZE;
        int minZ = chunks.stream().mapToInt(Chunk::z).min().orElseThrow() * CHUNK_SIZE;
        int maxX = (chunks.stream().mapToInt(Chunk::x).max().orElseThrow() + 1) * CHUNK_SIZE;
        int maxZ = (chunks.stream().mapToInt(Chunk::z).max().orElseThrow() + 1) * CHUNK_SIZE;
        return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Uses the old cube's volume as a default budget, while allowing narrow batches to be taller. */
    List<Bounds> batches(int minY, int maxY, int maxSide) {
        return batches(minY, maxY, maxSide, (long) maxSide * maxSide * maxSide);
    }

    /** Sparse subdivision keeps claims exact; volume, rather than a fixed side, limits vertical slices. */
    List<Bounds> batches(int minY, int maxY, int maxSide, long maxBlocks) {
        if (maxSide < CHUNK_SIZE || maxY <= minY || maxBlocks < CHUNK_SIZE * CHUNK_SIZE)
            throw new IllegalArgumentException("Invalid claim batch dimensions");
        int tileSide = maxSide / CHUNK_SIZE;
        Map<Chunk, List<Chunk>> tiles = new TreeMap<>(Comparator.comparingInt(Chunk::x).thenComparingInt(Chunk::z));
        for (Chunk chunk : chunks) {
            Chunk tile = new Chunk(Math.floorDiv(chunk.x(), tileSide) * tileSide,
                Math.floorDiv(chunk.z(), tileSide) * tileSide);
            tiles.computeIfAbsent(tile, ignored -> new ArrayList<>()).add(chunk);
        }
        List<Bounds> horizontal = new ArrayList<>();
        tiles.forEach((tile, claims) -> subdivide(tile.x(), tile.z(), tileSide, tileSide, claims,
            maxBlocks / (CHUNK_SIZE * CHUNK_SIZE), horizontal));
        List<Bounds> result = new ArrayList<>();
        for (Bounds tile : horizontal) {
            long area = (long) (tile.maxX() - tile.minX()) * (tile.maxZ() - tile.minZ());
            long height = (long) maxY - minY;
            long sliceHeight = Math.min(height, maxBlocks / area);
            long chunkSliceHeight = Math.min(height, maxBlocks / (CHUNK_SIZE * CHUNK_SIZE));
            long perChunkCount = (area / (CHUNK_SIZE * CHUNK_SIZE)) * ceilDiv(height, chunkSliceHeight);
            if (ceilDiv(height, sliceHeight) > perChunkCount) {
                // Integer rounding can waste the budget on some wide tiles. Never use more jobs than
                // individual chunks would need under the same budget.
                for (int x = tile.minX(); x < tile.maxX(); x += CHUNK_SIZE) {
                    for (int z = tile.minZ(); z < tile.maxZ(); z += CHUNK_SIZE)
                        addSlices(new Bounds(x, 0, z, x + CHUNK_SIZE, 1, z + CHUNK_SIZE),
                            minY, maxY, chunkSliceHeight, result);
                }
            } else {
                addSlices(tile, minY, maxY, sliceHeight, result);
            }
        }
        return result;
    }

    private static long ceilDiv(long value, long divisor) {
        return 1 + (value - 1) / divisor;
    }

    private static void addSlices(Bounds tile, int minY, int maxY, long sliceHeight, List<Bounds> result) {
        for (long y = minY; y < maxY; y += sliceHeight)
            result.add(new Bounds(tile.minX(), (int) y, tile.minZ(), tile.maxX(),
                (int) Math.min(y + sliceHeight, maxY), tile.maxZ()));
    }

    private static void subdivide(int x, int z, int width, int depth, List<Chunk> claims,
                                  long maxFootprintChunks, List<Bounds> result) {
        int minX = claims.stream().mapToInt(Chunk::x).min().orElseThrow();
        int minZ = claims.stream().mapToInt(Chunk::z).min().orElseThrow();
        int maxX = claims.stream().mapToInt(Chunk::x).max().orElseThrow() + 1;
        int maxZ = claims.stream().mapToInt(Chunk::z).max().orElseThrow() + 1;
        // A partially occupied tile may still contain a solid rectangle, such as two neighboring chunks.
        if (claims.size() == (long) (maxX - minX) * (maxZ - minZ) && claims.size() <= maxFootprintChunks) {
            result.add(new Bounds(minX * CHUNK_SIZE, 0, minZ * CHUNK_SIZE, maxX * CHUNK_SIZE, 1, maxZ * CHUNK_SIZE));
            return;
        }
        int left = (width + 1) / 2;
        int top = (depth + 1) / 2;
        Map<Integer, List<Chunk>> quadrants = new HashMap<>();
        for (Chunk chunk : claims) {
            int quadrant = (chunk.x() >= x + left ? 1 : 0) + (chunk.z() >= z + top ? 2 : 0);
            quadrants.computeIfAbsent(quadrant, ignored -> new ArrayList<>()).add(chunk);
        }
        for (int quadrant = 0; quadrant < 4; quadrant++) {
            List<Chunk> selected = quadrants.get(quadrant);
            if (selected == null) continue;
            boolean right = (quadrant & 1) != 0;
            boolean bottom = (quadrant & 2) != 0;
            subdivide(right ? x + left : x, bottom ? z + top : z,
                right ? width - left : left, bottom ? depth - top : top, selected, maxFootprintChunks, result);
        }
    }
}
