package io.github.townyadvanced.flagwar.worldedit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static io.github.townyadvanced.flagwar.worldedit.SnapshotManifest.*;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotManifestTest {
    @TempDir Path directory;
    private final UUID snapshot = UUID.randomUUID();
    private final UUID town = UUID.randomUUID();
    private final UUID world = UUID.randomUUID();
    private final Bounds bounds = new Bounds(-64, -64, 320, 0, 320, 384);

    private SnapshotManifest manifest(State state) {
        return new SnapshotManifest(snapshot, town, world, bounds,
            List.of(new Batch("batch-0.schem", bounds, 3, 1, 2, 3)), state);
    }

    @Test void inventoryAndStateSurviveRestart() throws Exception {
        SnapshotManifest original = manifest(State.RESTORING);
        Files.write(directory.resolve("batch-0.schem"), new byte[]{1, 2, 3});
        original.save(directory);
        SnapshotManifest restored = SnapshotManifest.load(directory, snapshot, town, world);
        assertEquals(original.bounds, restored.bounds);
        assertEquals(original.batches, restored.batches);
        assertEquals(State.RESTORING, restored.state);
        restored.requireCompleteFiles(directory);
        assertFalse(Files.exists(directory.resolve(FILE_NAME + ".tmp")));
    }

    @Test void refusesAnotherBattleTownOrWorld() throws Exception {
        manifest(State.READY).save(directory);
        assertThrows(IOException.class, () -> SnapshotManifest.load(directory, UUID.randomUUID(), town, world));
        assertThrows(IOException.class, () -> SnapshotManifest.load(directory, snapshot, UUID.randomUUID(), world));
        assertThrows(IOException.class, () -> SnapshotManifest.load(directory, snapshot, town, UUID.randomUUID()));
    }

    @Test void refusesMissingTruncatedOrUnpublishedBatches() throws Exception {
        SnapshotManifest ready = manifest(State.READY);
        assertThrows(IOException.class, () -> ready.requireCompleteFiles(directory));
        Files.write(directory.resolve("batch-0.schem"), new byte[]{1, 2});
        assertThrows(IOException.class, () -> ready.requireCompleteFiles(directory));
        Files.write(directory.resolve("batch-0.schem"), new byte[]{1, 2, 3});
        ready.requireCompleteFiles(directory);
        assertThrows(IOException.class, () -> manifest(State.COPYING).requireCompleteFiles(directory));
        assertThrows(IOException.class, () -> manifest(State.COPY_FAILED).requireCompleteFiles(directory));
    }

    @Test void interruptedAndFailedRestoresKeepAllFilesForRetry() throws Exception {
        Files.write(directory.resolve("batch-0.schem"), new byte[]{1, 2, 3});
        for (State state : List.of(State.RESTORING, State.RESTORE_FAILED)) {
            manifest(state).save(directory);
            SnapshotManifest loaded = SnapshotManifest.load(directory, snapshot, town, world);
            loaded.requireCompleteFiles(directory);
            assertEquals(3, Files.size(directory.resolve("batch-0.schem")));
        }
    }

    @Test void completedMarkerSurvivesRetiredFiles() throws Exception {
        manifest(State.RESTORED).save(directory);
        SnapshotManifest restored = SnapshotManifest.load(directory, snapshot, town, world);
        assertEquals(State.RESTORED, restored.state);
        restored.requireCompleteFiles(directory);
    }

    @Test void rejectsCorruptManifestAndUnsafeInventory() throws Exception {
        Files.writeString(directory.resolve(FILE_NAME), "version=999\n");
        assertThrows(IOException.class, () -> SnapshotManifest.load(directory, snapshot, town, world));
        assertThrows(IllegalArgumentException.class, () -> new Batch("../other.schem", bounds, 1));
        assertThrows(IllegalArgumentException.class, () -> new SnapshotManifest(snapshot, town, world, bounds,
            List.of(new Batch("batch-0.schem", bounds, 1), new Batch("batch-0.schem", bounds, 1)), State.READY));
    }

    @Test void exactFootprintSurvivesRestartWithoutDependingOnCurrentClaims() throws Exception {
        ClaimFootprint selected = new ClaimFootprint(List.of(new ClaimFootprint.Chunk(-4, 20), new ClaimFootprint.Chunk(-1, 23)));
        SnapshotManifest original = manifest(State.READY).withFootprint(selected);
        original.save(directory);
        SnapshotManifest loaded = SnapshotManifest.load(directory, snapshot, town, world);
        assertEquals(selected.chunks(), loaded.footprint.chunks());
        assertTrue(loaded.footprint.containsBlock(-64, 320));
        assertFalse(loaded.footprint.containsBlock(-48, 320));
    }

    @Test void oldRectangularInventoryDropsEmptyBatchesAndPersistsTheClaimMask() throws Exception {
        Bounds envelope = new Bounds(0, -64, 0, 144, 320, 16);
        SnapshotManifest old = new SnapshotManifest(snapshot, town, world, envelope, List.of(
            new Batch("batch-0.schem", new Bounds(0, -64, 0, 64, 320, 16), 3),
            new Batch("batch-1.schem", new Bounds(64, -64, 0, 128, 320, 16), 3),
            new Batch("batch-2.schem", new Bounds(128, -64, 0, 144, 320, 16), 3)), State.READY);
        old.save(directory);
        assertNull(SnapshotManifest.load(directory, snapshot, town, world).footprint);
        ClaimFootprint selected = new ClaimFootprint(List.of(new ClaimFootprint.Chunk(0, 0), new ClaimFootprint.Chunk(8, 0)));
        SnapshotManifest migrated = old.withFootprint(selected);
        assertEquals(List.of("batch-0.schem", "batch-2.schem"), migrated.batches.stream().map(Batch::file).toList());
        Files.write(directory.resolve("batch-0.schem"), new byte[]{1, 2, 3});
        Files.write(directory.resolve("batch-2.schem"), new byte[]{1, 2, 3});
        migrated.save(directory);
        SnapshotManifest loaded = SnapshotManifest.load(directory, snapshot, town, world);
        loaded.requireCompleteFiles(directory); // The empty gap's file is deliberately absent.
        assertEquals(selected.chunks(), loaded.footprint.chunks());
        assertFalse(loaded.footprint.containsArea(loaded.batches.get(0).bounds()));
    }
}
