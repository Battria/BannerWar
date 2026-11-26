package io.github.townyadvanced.flagwar.worldedit;

import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.object.TownBlock;
import com.palmergames.bukkit.towny.object.WorldCoord;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.function.mask.MaskIntersection;
import com.sk89q.worldedit.function.mask.RegionMask;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.session.ClipboardHolder;
import io.github.townyadvanced.flagwar.FlagWar;
import io.github.townyadvanced.flagwar.config.BannerWarConfig;
import io.github.townyadvanced.flagwar.util.Broadcasts;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import static io.github.townyadvanced.flagwar.worldedit.SnapshotManifest.Batch;
import static io.github.townyadvanced.flagwar.worldedit.SnapshotManifest.Bounds;
import static io.github.townyadvanced.flagwar.worldedit.SnapshotManifest.State;

/** Serial FAWE snapshot jobs. Bukkit/Towny context is captured on the server thread. */
public final class WorldEditService {
    private static final SnapshotWorker WORKER = new SnapshotWorker();

    private WorldEditService() {}

    private record Context(UUID snapshotId, UUID townId, UUID worldId, String townName, World bukkitWorld,
                           com.sk89q.worldedit.world.World world, Bounds bounds, Path directory,
                           Path structures, List<org.bukkit.Material> blacklist, ClaimFootprint footprint) {}

    /** Creates a new generation; incomplete generations are never recaptured from the changed world. */
    public static CompletableFuture<Void> copyTownToDisk(Town town, Collection<TownBlock> townBlocks, UUID snapshotId) {
        try {
            Context context = context(town, townBlocks, List.of(), snapshotId, true);
            int side = BannerWarConfig.getWorldEditBatchSize();
            long maxBlocks = BannerWarConfig.getWorldEditMaxBlocksPerBatch();
            return submit(() -> {
                List<Batch> batches = batches(context.footprint(), context.bounds(), side, maxBlocks);
                SnapshotManifest manifest = new SnapshotManifest(snapshotId, context.townId(), context.worldId(),
                    context.bounds(), batches, State.COPYING, context.footprint());
                if (Files.exists(context.directory().resolve(SnapshotManifest.FILE_NAME)))
                    throw new IOException("Refusing to overwrite existing snapshot " + snapshotId);
                manifest.save(context.directory());
                FlagWar.getInstance().getLogger().info("Snapshot footprint for " + context.townName() + ": "
                    + context.footprint().chunks().size() + " claimed chunks, " + batches.size()
                    + " volume-budgeted batches (up to " + maxBlocks + " blocks each)");
                try {
                    for (int i = 0; i < batches.size(); i++) {
                        ensureRunning();
                        Batch batch = batches.get(i);
                        Path destination = context.directory().resolve(batch.file());
                        copy(context, batch.bounds(), destination);
                        manifest.batches.set(i, batch.withSize(Files.size(destination)));
                        logProgress("Copied", context, i + 1, batches.size());
                    }
                    ensureRunning();
                    manifest.state = State.READY;
                    manifest.save(context.directory());
                } catch (Exception e) {
                    recordFailure(manifest, context, State.COPY_FAILED, e);
                    throw e;
                }
                return null;
            });
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** Loads a snapshot, importing old files only for battles without a persisted snapshot ID. */
    public static CompletableFuture<Void> prepareExistingSnapshot(Town town, Collection<WorldCoord> initialClaims,
                                                                   UUID snapshotId, boolean allowLegacy) {
        try {
            Context context = context(town, List.of(), initialClaims, snapshotId, false);
            return submit(() -> {
                load(context, allowLegacy).requireCompleteFiles(context.directory());
                return null;
            });
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** Completes only after all sessions close, entity cleanup finishes, and RESTORED is persisted. */
    public static CompletableFuture<Boolean> pasteTownToWorld(Town town, Collection<WorldCoord> initialClaims, UUID snapshotId) {
        try {
            Context context = context(town, List.of(), initialClaims, snapshotId, false);
            return submit(() -> {
                SnapshotManifest manifest = load(context, false);
                if (manifest.state == State.RESTORED) return null;
                try {
                    manifest.requireCompleteFiles(context.directory());
                    // Validate every file before changing the world, without retaining multiple clipboards.
                    for (Batch batch : manifest.batches) {
                        ensureRunning();
                        try (Clipboard clipboard = read(context.directory().resolve(batch.file()))) {
                            validateDimensions(clipboard, batch);
                        }
                    }
                    manifest.state = State.RESTORING;
                    manifest.save(context.directory());
                    for (int i = 0; i < manifest.batches.size(); i++) {
                        ensureRunning();
                        paste(context, manifest.batches.get(i), manifest.footprint);
                        logProgress("Restored", context, i + 1, manifest.batches.size());
                    }
                } catch (Exception e) {
                    if (manifest.state != State.COPYING && manifest.state != State.COPY_FAILED)
                        recordFailure(manifest, context, State.RESTORE_FAILED, e);
                    throw e;
                }
                return manifest;
            }).thenCompose(manifest -> {
                if (manifest == null) return CompletableFuture.completedFuture(false);
                return cleanupEntities(context, manifest).thenCompose(ignored -> submit(() -> {
                    ensureRunning();
                    manifest.state = State.RESTORED;
                    manifest.save(context.directory());
                    // Keep the completion marker for restart recovery; retire files only after success.
                    for (Batch batch : manifest.batches) {
                        try {
                            Files.deleteIfExists(context.directory().resolve(batch.file()));
                        } catch (IOException e) {
                            FlagWar.getInstance().getLogger().log(Level.WARNING, "Could not retire snapshot batch", e);
                        }
                    }
                    return true;
                }));
            });
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static Context context(Town town, Collection<TownBlock> townBlocks, Collection<WorldCoord> initialClaims,
                                   UUID snapshotId, boolean copying) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Capture snapshot context on the server thread");
        try {
            Class.forName("com.fastasyncworldedit.core.Fawe", false, WorldEditService.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Town snapshots require FastAsyncWorldEdit", e);
        }
        World world = town.getWorld();
        if (world == null) throw new IllegalStateException("The battle world is not loaded");
        Bounds bounds = null;
        ClaimFootprint footprint = null;
        try {
            footprint = copying ? WorldEditHelper.footprintFrom(townBlocks, world)
                : WorldEditHelper.footprintFromCoords(initialClaims, world);
            bounds = footprint.bounds(world.getMinHeight(), world.getMaxHeight());
        } catch (IllegalArgumentException e) {
            // Existing manifests own their bounds. Claims may have been removed during the cooldown.
            if (copying) throw e;
        }
        Path structures = FlagWar.getInstance().getDataFolder().toPath().resolve("structures");
        Path directory = structures.resolve(town.getUUID().toString()).resolve(world.getUID().toString())
            .resolve(snapshotId.toString());
        return new Context(snapshotId, town.getUUID(), world.getUID(), town.getName(), world, BukkitAdapter.adapt(world),
            bounds, directory, structures, List.copyOf(BannerWarConfig.getBlacklistedMaterials()), footprint);
    }

    static List<Batch> batches(ClaimFootprint footprint, Bounds bounds, int side) {
        return batches(footprint, bounds, side, (long) side * side * side);
    }

    static List<Batch> batches(ClaimFootprint footprint, Bounds bounds, int side, long maxBlocks) {
        List<Batch> batches = new ArrayList<>();
        for (Bounds selected : footprint.batches(bounds.minY(), bounds.maxY(), side, maxBlocks))
            batches.add(new Batch("batch-" + batches.size() + ".schem", selected, 0));
        return batches;
    }

    private static void copy(Context context, Bounds bounds, Path destination) throws IOException, WorldEditException {
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
        CuboidRegion region = WorldEditHelper.region(bounds);
        try (EditSession source = WorldEdit.getInstance().newEditSessionBuilder().world(context.world()).build();
             Clipboard clipboard = new BlockArrayClipboard(region);
             ClipboardWriter writer = BuiltInClipboardFormat.FAST_V3.getWriter(Files.newOutputStream(temporary))) {
            clipboard.setOrigin(region.getMinimumPoint());
            ForwardExtentCopy operation = new ForwardExtentCopy(source, region, clipboard, region.getMinimumPoint());
            operation.setCopyingEntities(false);
            Operations.complete(operation);
            writer.write(clipboard);
        }
        SnapshotManifest.publish(temporary, destination);
    }

    private static Clipboard read(Path file) throws IOException {
        ClipboardFormat format = ClipboardFormats.findByFile(file.toFile());
        if (format == null) throw new IOException("Unrecognized schematic format: " + file);
        try (ClipboardReader reader = format.getReader(Files.newInputStream(file))) {
            return reader.read();
        }
    }

    private static void validateDimensions(Clipboard clipboard, Batch batch) throws IOException {
        Bounds bounds = batch.bounds();
        var dimensions = clipboard.getDimensions();
        if (dimensions.x() < batch.offsetX() + bounds.maxX() - bounds.minX()
            || dimensions.y() < batch.offsetY() + bounds.maxY() - bounds.minY()
            || dimensions.z() < batch.offsetZ() + bounds.maxZ() - bounds.minZ())
            throw new IOException("Schematic is smaller than its recorded batch bounds");
    }

    private static void paste(Context context, Batch batch, ClaimFootprint footprint) throws IOException, WorldEditException {
        try (Clipboard clipboard = read(context.directory().resolve(batch.file()));
             EditSession session = WorldEdit.getInstance().newEditSessionBuilder().world(context.world()).build()) {
            validateDimensions(clipboard, batch);
            BlockVector3 min = clipboard.getMinimumPoint().add(batch.offsetX(), batch.offsetY(), batch.offsetZ());
            Bounds bounds = batch.bounds();
            CuboidRegion source = new CuboidRegion(min, min.add(bounds.maxX() - bounds.minX() - 1,
                bounds.maxY() - bounds.minY() - 1, bounds.maxZ() - bounds.minZ() - 1));
            // Normalize the source origin and explicitly position it; never parse coordinates from a filename.
            clipboard.setOrigin(min);
            var destination = BlockVector3.at(bounds.minX(), bounds.minY(), bounds.minZ());
            var mask = MaskIntersection.of(WorldEditHelper.createBlockBlacklistMask(clipboard, context.blacklist()),
                new RegionMask(source));
            // New batches are entirely claimed. Old rectangles additionally need a source-to-world mask.
            if (!footprint.containsArea(bounds))
                mask = MaskIntersection.of(mask, new ClaimSourceMask(footprint, min, destination));
            Operations.complete(new ClipboardHolder(clipboard).createPaste(session)
                .to(destination).maskSource(mask)
                .copyEntities(false).build());
        }
    }

    private static SnapshotManifest load(Context context, boolean allowLegacy) throws IOException {
        if (!Files.exists(context.directory().resolve(SnapshotManifest.FILE_NAME))) {
            if (!allowLegacy) throw new IOException("Missing snapshot manifest for " + context.townName());
            importLegacy(context);
        }
        SnapshotManifest manifest = SnapshotManifest.load(context.directory(), context.snapshotId(), context.townId(), context.worldId());
        if (manifest.footprint == null && manifest.state != State.RESTORED) {
            if (context.footprint() == null) throw new IOException("Cannot recover the original snapshot claims");
            manifest = manifest.withFootprint(context.footprint());
            manifest.save(context.directory());
            FlagWar.getInstance().getLogger().info("Restricted old snapshot for " + context.townName() + " to "
                + manifest.footprint.chunks().size() + " original claimed chunks and " + manifest.batches.size() + " relevant batches");
        }
        return manifest;
    }

    private static void importLegacy(Context context) throws IOException {
        if (context.bounds() == null)
            throw new IOException("Cannot determine the initial claims for this legacy snapshot; recover from backup");
        List<Path> files = new ArrayList<>();
        List<Batch> batches = new ArrayList<>();
        Path single = context.structures().resolve(context.townId() + ".schem");
        Pattern splitName = Pattern.compile(Pattern.quote(context.townName()) + "_-?[0-9]+_-?[0-9]+_-?[0-9]+\\.schem");
        boolean hasSplitFiles;
        if (!Files.isDirectory(context.structures())) throw new IOException("The structures directory is missing");
        try (var entries = Files.list(context.structures())) {
            hasSplitFiles = entries.anyMatch(file -> splitName.matcher(file.getFileName().toString()).matches());
        }
        if (hasSplitFiles && Files.isRegularFile(single))
            throw new IOException("Both UUID and town-named legacy snapshots exist for " + context.townName()
                + "; move the unrelated generation aside before retrying restoration");
        if (Files.isRegularFile(single)) {
            try (Clipboard clipboard = read(single)) {
                BlockVector3 min = clipboard.getMinimumPoint();
                BlockVector3 max = clipboard.getMaximumPoint();
                Bounds bounds = new Bounds(min.x(), min.y(), min.z(), max.x() + 1, max.y() + 1, max.z() + 1);
                requireCoverage(bounds, context.bounds());
                batches.add(legacyBatch("batch-0.schem", bounds, context.bounds()));
                files.add(single);
            }
        } else {
            // The batching branch used fixed height -64..319 and 256-block exclusive split boxes.
            Bounds original = context.bounds();
            if (original.minY() < -64 || original.maxY() > 320)
                throw new IOException("Legacy split snapshots do not cover the current world's build height");
            BoundingBox legacy = new BoundingBox(original.minX(), -64, original.minZ(), original.maxX(), 319, original.maxZ());
            for (BoundingBox box : WorldEditHelper.split(legacy, new Vector(256, 256, 256))) {
                int maxY = box.getMaxY() == 319 ? 320 : (int) box.getMaxY();
                if (maxY <= original.minY() || box.getMinY() >= original.maxY()) continue;
                Bounds bounds = new Bounds((int) box.getMinX(), Math.max((int) box.getMinY(), original.minY()), (int) box.getMinZ(),
                    (int) box.getMaxX(), maxY, (int) box.getMaxZ());
                if (!context.footprint().intersects(bounds)) continue;
                Path file = WorldEditHelper.legacyFile(context.structures(), context.townName(), box);
                if (!Files.isRegularFile(file)) throw new IOException("Missing legacy snapshot batch: " + file);
                batches.add(legacyBatch("batch-" + batches.size() + ".schem", bounds, original));
                files.add(file);
            }
        }
        SnapshotManifest manifest = new SnapshotManifest(context.snapshotId(), context.townId(), context.worldId(),
            context.bounds(), batches, State.COPYING, context.footprint());
        manifest.save(context.directory());
        try {
            for (int i = 0; i < files.size(); i++) {
                ensureRunning();
                Path destination = context.directory().resolve(batches.get(i).file());
                Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
                Files.copy(files.get(i), temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                SnapshotManifest.publish(temporary, destination);
                manifest.batches.set(i, batches.get(i).withSize(Files.size(destination)));
            }
            manifest.state = State.READY;
            manifest.save(context.directory());
            FlagWar.getInstance().getLogger().warning("Imported legacy snapshot for " + context.townName()
                + ". Original legacy files have been retained; their generation cannot be verified.");
        } catch (Exception e) {
            recordFailure(manifest, context, State.COPY_FAILED, e);
            throw e;
        }
    }

    private static Batch legacyBatch(String file, Bounds source, Bounds intended) {
        Bounds clipped = WorldEditHelper.intersect(source, intended);
        return new Batch(file, clipped, 0, clipped.minX() - source.minX(),
            clipped.minY() - source.minY(), clipped.minZ() - source.minZ());
    }

    private static void requireCoverage(Bounds source, Bounds intended) throws IOException {
        if (source.minX() > intended.minX() || source.minY() > intended.minY() || source.minZ() > intended.minZ()
            || source.maxX() < intended.maxX() || source.maxY() < intended.maxY() || source.maxZ() < intended.maxZ())
            throw new IOException("Legacy schematic does not cover the initial town bounds");
    }

    private static CompletableFuture<Void> cleanupEntities(Context context, SnapshotManifest manifest) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            ensureRunning();
            Bukkit.getScheduler().runTask(FlagWar.getInstance(), () -> {
                try {
                    // Scan loaded entities once, without walking the enormous gap between disconnected claims.
                    for (var entity : context.bukkitWorld().getEntities()) {
                        var location = entity.getLocation();
                        if (location.getY() < manifest.bounds.minY() || location.getY() >= manifest.bounds.maxY()
                            || !manifest.footprint.containsBlock(location.getBlockX(), location.getBlockZ())) continue;
                        if (entity instanceof Item) entity.remove();
                        else if (entity instanceof LivingEntity && WorldEditHelper.checkUnSuffocate(entity)
                            && entity instanceof Player player)
                            Broadcasts.sendMessage(player, "Teleported you to the top during chunk restoration!", ChatColor.YELLOW);
                    }
                    result.complete(null);
                } catch (Exception e) {
                    result.completeExceptionally(e);
                }
            });
        } catch (Exception e) {
            result.completeExceptionally(e);
        }
        return result;
    }

    private static void recordFailure(SnapshotManifest manifest, Context context, State state, Exception failure) {
        manifest.state = state;
        try {
            manifest.save(context.directory());
        } catch (IOException e) {
            failure.addSuppressed(e);
        }
    }

    private static void logProgress(String action, Context context, int completed, int total) {
        if (completed == 1 || completed == total || completed % 10 == 0)
            FlagWar.getInstance().getLogger().info(action + " snapshot batches for " + context.townName()
                + ": " + completed + "/" + total);
    }

    private static void ensureRunning() {
        WORKER.ensureRunning();
    }

    private static <T> CompletableFuture<T> submit(Callable<T> job) {
        return WORKER.submit(job);
    }

    /** Never wait here: a FAWE operation can need the server thread to finish. */
    public static void shutdown() {
        WORKER.close();
    }
}
