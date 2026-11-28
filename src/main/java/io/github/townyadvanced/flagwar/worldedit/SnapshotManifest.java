package io.github.townyadvanced.flagwar.worldedit;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.Comparator;

/** Durable inventory and recovery marker for one battle's snapshot. */
final class SnapshotManifest {
    static final String FILE_NAME = "manifest.properties";

    enum State { COPYING, COPY_FAILED, READY, RESTORING, RESTORE_FAILED, RESTORED }

    /** All maxima are exclusive, including the world's maximum build height. */
    record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        Bounds {
            if (maxX <= minX || maxY <= minY || maxZ <= minZ)
                throw new IllegalArgumentException("Snapshot bounds must have positive volume");
        }

        String serialize() {
            return minX + "," + minY + "," + minZ + "," + maxX + "," + maxY + "," + maxZ;
        }

        static Bounds parse(String value) {
            String[] parts = value.split(",");
            if (parts.length != 6) throw new IllegalArgumentException("Invalid snapshot bounds");
            return new Bounds(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                Integer.parseInt(parts[3]), Integer.parseInt(parts[4]), Integer.parseInt(parts[5]));
        }
    }

    record Batch(String file, Bounds bounds, long size, int offsetX, int offsetY, int offsetZ) {
        Batch(String file, Bounds bounds, long size) {
            this(file, bounds, size, 0, 0, 0);
        }

        Batch {
            if (!file.matches("batch-[0-9]+\\.schem") || size < 0 || offsetX < 0 || offsetY < 0 || offsetZ < 0)
                throw new IllegalArgumentException("Invalid snapshot batch: " + file);
        }

        Batch withSize(long size) {
            return new Batch(file, bounds, size, offsetX, offsetY, offsetZ);
        }
    }

    final UUID snapshotId;
    final UUID townId;
    final UUID worldId;
    final Bounds bounds;
    final List<Batch> batches;
    final ClaimFootprint footprint;
    State state;

    SnapshotManifest(UUID snapshotId, UUID townId, UUID worldId, Bounds bounds, List<Batch> batches, State state) {
        this(snapshotId, townId, worldId, bounds, batches, state, null);
    }

    SnapshotManifest(UUID snapshotId, UUID townId, UUID worldId, Bounds bounds, List<Batch> batches, State state,
                     ClaimFootprint footprint) {
        this.snapshotId = snapshotId;
        this.townId = townId;
        this.worldId = worldId;
        this.bounds = bounds;
        this.batches = new ArrayList<>(batches);
        this.state = state;
        this.footprint = footprint;
        if (batches.isEmpty()) throw new IllegalArgumentException("Snapshot contains no batches");
        if (batches.stream().map(Batch::file).distinct().count() != batches.size())
            throw new IllegalArgumentException("Snapshot contains duplicate batch files");
    }

    SnapshotManifest withFootprint(ClaimFootprint originalClaims) {
        ClaimFootprint selected = originalClaims.within(bounds);
        List<Batch> selectedBatches = batches.stream().filter(batch -> selected.intersects(batch.bounds())).toList();
        return new SnapshotManifest(snapshotId, townId, worldId, bounds, selectedBatches, state, selected);
    }

    void save(Path directory) throws IOException {
        Files.createDirectories(directory);
        Properties properties = new Properties();
        properties.setProperty("version", footprint == null ? "1" : "2");
        properties.setProperty("snapshot", snapshotId.toString());
        properties.setProperty("town", townId.toString());
        properties.setProperty("world", worldId.toString());
        properties.setProperty("bounds", bounds.serialize());
        properties.setProperty("state", state.name());
        properties.setProperty("batches", Integer.toString(batches.size()));
        if (footprint != null) {
            var claims = footprint.chunks().stream()
                .sorted(Comparator.comparingInt(ClaimFootprint.Chunk::x).thenComparingInt(ClaimFootprint.Chunk::z)).toList();
            properties.setProperty("claims", Integer.toString(claims.size()));
            for (int i = 0; i < claims.size(); i++)
                properties.setProperty("claim." + i, claims.get(i).x() + "," + claims.get(i).z());
        }
        for (int i = 0; i < batches.size(); i++) {
            Batch batch = batches.get(i);
            String prefix = "batch." + i + ".";
            properties.setProperty(prefix + "file", batch.file());
            properties.setProperty(prefix + "bounds", batch.bounds().serialize());
            properties.setProperty(prefix + "size", Long.toString(batch.size()));
            properties.setProperty(prefix + "offsetX", Integer.toString(batch.offsetX()));
            properties.setProperty(prefix + "offsetY", Integer.toString(batch.offsetY()));
            properties.setProperty(prefix + "offsetZ", Integer.toString(batch.offsetZ()));
        }
        Path temporary = directory.resolve(FILE_NAME + ".tmp");
        try (OutputStream output = Files.newOutputStream(temporary)) {
            properties.store(output, "BannerWar snapshot; do not edit while the server is running");
        }
        publish(temporary, directory.resolve(FILE_NAME));
    }

    static SnapshotManifest load(Path directory, UUID snapshotId, UUID townId, UUID worldId) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(directory.resolve(FILE_NAME))) {
            properties.load(input);
        }
        try {
            String version = properties.getProperty("version");
            if (!("1".equals(version) || "2".equals(version))
                || !snapshotId.toString().equals(properties.getProperty("snapshot"))
                || !townId.toString().equals(properties.getProperty("town"))
                || !worldId.toString().equals(properties.getProperty("world")))
                throw new IllegalArgumentException("Snapshot identity or format does not match this battle");
            int count = Integer.parseInt(properties.getProperty("batches"));
            List<Batch> batches = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String prefix = "batch." + i + ".";
                batches.add(new Batch(properties.getProperty(prefix + "file"),
                    Bounds.parse(properties.getProperty(prefix + "bounds")),
                    Long.parseLong(properties.getProperty(prefix + "size")),
                    Integer.parseInt(properties.getProperty(prefix + "offsetX", "0")),
                    Integer.parseInt(properties.getProperty(prefix + "offsetY", "0")),
                    Integer.parseInt(properties.getProperty(prefix + "offsetZ", "0"))));
            }
            ClaimFootprint footprint = null;
            if ("2".equals(version)) {
                int claimCount = Integer.parseInt(properties.getProperty("claims"));
                List<ClaimFootprint.Chunk> claims = new ArrayList<>();
                for (int i = 0; i < claimCount; i++) {
                    String[] coordinates = properties.getProperty("claim." + i).split(",");
                    if (coordinates.length != 2) throw new IllegalArgumentException("Invalid snapshot claim");
                    claims.add(new ClaimFootprint.Chunk(Integer.parseInt(coordinates[0]), Integer.parseInt(coordinates[1])));
                }
                footprint = new ClaimFootprint(claims);
                if (footprint.chunks().size() != claimCount) throw new IllegalArgumentException("Duplicate snapshot claim");
            }
            return new SnapshotManifest(snapshotId, townId, worldId,
                Bounds.parse(properties.getProperty("bounds")), batches,
                State.valueOf(properties.getProperty("state")), footprint);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException("Invalid snapshot manifest in " + directory, e);
        }
    }

    void requireCompleteFiles(Path directory) throws IOException {
        if (state == State.COPYING || state == State.COPY_FAILED)
            throw new IOException("The pre-war snapshot did not finish copying; preserve its files and recover from backup");
        if (state == State.RESTORED) return;
        for (Batch batch : batches) {
            Path file = directory.resolve(batch.file());
            if (batch.size() == 0 || !Files.isRegularFile(file) || Files.size(file) != batch.size())
                throw new IOException("Missing or incomplete snapshot batch: " + file);
        }
    }

    static void publish(Path temporary, Path destination) throws IOException {
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
