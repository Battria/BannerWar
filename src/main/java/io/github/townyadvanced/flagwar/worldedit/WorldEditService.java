package io.github.townyadvanced.flagwar.worldedit;

import com.fastasyncworldedit.core.util.TaskManager;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.palmergames.bukkit.towny.object.Town;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.*;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.Vector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.session.ClipboardHolder;
import io.github.townyadvanced.flagwar.BannerWarAPI;
import io.github.townyadvanced.flagwar.FlagWar;
import io.github.townyadvanced.flagwar.util.BattleUtil;
import io.github.townyadvanced.flagwar.util.Broadcasts;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This class provides static methods that use
 * FastAsyncWorldEdit (FAWE)
 * for copying a structure from a bounding box, and pasting it using the same bounding box.
 * <p>
 * It also saves the structure as a .schem file onto the "structures" folder in the plugin directory to be pasted later.
 * <p>
 * Because of FAWE's API's similarity to WorldEdit's API, this service is also compatible between the two.
 * It is still strongly recommended to use the correct dependency,
 * and you should remove the async wrapper where needed.
 */
public final class WorldEditService {

    private WorldEditService() {} // only static methods present.

    /** Holds the {@link Plugin} instance. */
    private static final Plugin PLUGIN = FlagWar.getInstance();

    /** Holds the {@link Logger} instance. */
    private static final Logger LOGGER = PLUGIN.getLogger();

    /** Holds the file type of schem files. */
    private static final String SCHEM_EXTENSION = ".schem";

    /** Holds a Vector containing the maximum dimension size of split bounding boxes. */
    private static final Vector LIMITS = new org.bukkit.util.Vector(256, 256, 256);

    /** Holds the directory path of the folder containing the structures to be pasted. */
    private static final String
            STRUCTURE_PATH = Path.of(FlagWar.getInstance().getDataFolder().getPath())
            .resolve("structures").toString();

    /**
     * Copies the blocks at the specified {@link BoundingBox} to a .schem file with the specified name.
     * @param name the name
     * @param box the bounding box
     * @param world the world
     */
    private static void copyToDisk(String name, BoundingBox box, World world) {
        LOGGER.log(Level.INFO, "Copying {0} to disk.", name);

        TaskManager.taskManager().async(() ->  {
            CuboidRegion region = getRegionFrom(box, world);
            BlockArrayClipboard clipboard = new BlockArrayClipboard(region);

            ForwardExtentCopy forwardExtentCopy = new ForwardExtentCopy(
                    BukkitAdapter.adapt(world), region, clipboard, region.getMinimumPoint()
            );

            File schemFile = new File(STRUCTURE_PATH, name + SCHEM_EXTENSION);
            schemFile.getParentFile().mkdirs();

            try (ClipboardWriter writer = BuiltInClipboardFormat.FAST_V3.getWriter(new FileOutputStream(schemFile))) {
                Operations.complete(forwardExtentCopy);
                writer.write(clipboard);

            } catch (IOException | WorldEditException e) {
                String message = String.format("Error copying %s to disk: %s", name, e.getMessage());
                LOGGER.severe(message);
            }
        });
    }

    /**
     * Pastes the blocks in the .schem file with the name back to the world
     * @param name the specified file name
     * @param world the specified world
     * @return a {@link CompletableFuture} of type {@link Void} for chaining.
     */
    @CanIgnoreReturnValue
    private static CompletableFuture<Void> pasteToWorld(String name, World world) {
        LOGGER.log(Level.INFO, "Pasting {0} to world.", name);
        File schemFile = new File(STRUCTURE_PATH, name + SCHEM_EXTENSION);
        Vector origin = WorldEditHelper.extractOriginFromName(name);
        return CompletableFuture.runAsync(() -> {
            ClipboardFormat format = ClipboardFormats.findByFile(schemFile);

            if (format == null) {
                throw new NullPointerException("Could not find schematic for " + name);
            }

            Clipboard clipboard;
            try (ClipboardReader reader = format.getReader(new FileInputStream(schemFile))) {
                clipboard = reader.read();
                clipboard.setOrigin(BlockVector3.at(origin.getX(), origin.getY(), origin.getZ()));
            } catch (IOException e) {
                throw new NullPointerException("Failed to read schematic, " + e.getMessage());
            }

            try (EditSession editSession = WorldEdit.getInstance().newEditSessionBuilder()
                .world(BukkitAdapter.adapt(world))
                .build()) {

                Operation operation = new ClipboardHolder(clipboard)
                        .createPaste(editSession)
                        .to(clipboard.getMinimumPoint())
                        .maskSource(WorldEditHelper.createBlockBlacklistMask(clipboard))
                        .copyEntities(false)
                        //.ignoreAirBlocks(true)
                        .build();

                Operations.complete(operation);

                var chunks = BattleUtil.chunksFrom(BannerWarAPI.getBattle(name).getInitialTownBlocks());
                BoundingBox box = BattleUtil.boundingBoxFrom(chunks);
                unSuffocateNearbyEntities(box, world);
                deleteItems(box, world);
                editSession.flushQueue();
                deleteFile(name);

            } catch (WorldEditException e) {
                e.printStackTrace();
            }
        }, runnable -> TaskManager.taskManager().async(runnable)).exceptionally(e -> {
            e.printStackTrace();
            return null;
        });
    }


    /**
     * Returns a {@link CuboidRegion} from a {@link BoundingBox} and {@link World}.
     * @param box the bounding box
     * @param world the world
     */
    private static CuboidRegion getRegionFrom(BoundingBox box, World world) {
        return new CuboidRegion(
                BukkitAdapter.adapt(world),
                BlockVector3.at(box.getMinX(), box.getMinY(), box.getMinZ()),
                BlockVector3.at(box.getMaxX(), box.getMaxY(), box.getMaxZ())
        );
    }

    private static void deleteFile(String fileName) {
        Path path = Path.of(STRUCTURE_PATH, fileName + SCHEM_EXTENSION);
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,"Could not delete file {}.schem!", fileName);
        }
    }

    /**
     * Gets every {@link LivingEntity} within the bounding box, and teleports it upward
     * if it is suffocating.
     * @param box the bounding box
     * @param world the world where the entities are retrieved
     */
    private static void unSuffocateNearbyEntities(BoundingBox box, World world) {
        Bukkit.getScheduler().runTask(FlagWar.getInstance(), () -> {
            var livingEntities = world.getNearbyEntities(box)
                .stream().filter(LivingEntity.class::isInstance).toList();

            for (var entity : livingEntities) {
                if (WorldEditHelper.checkUnSuffocate(entity) && entity instanceof Player p) {
                    Broadcasts.sendMessage(p,
                        "Teleported you to the top during chunk restoration!", ChatColor.YELLOW);
                }
            }
        });
    }

    /**
     * Deletes all items in the bounding box provided.
     * @param box the bounding box
     * @param world the world where the items are deleted
     */
    private static void deleteItems(BoundingBox box, World world) {
        Bukkit.getScheduler().runTask(FlagWar.getInstance(), () ->
             world.getNearbyEntities(box).stream().filter(Item.class::isInstance).forEach(Entity::remove)
        );
    }

    /**
     * Copies the region of chunks representing this {@link Town} to the disk.
     * @param town the town
     * @param chunks the chunks the town represents
     */
    public static void copyTownToDisk(Town town, Collection<Chunk> chunks) {
        World world = town.getWorld();
        Collection<BoundingBox> boxes = WorldEditHelper.split(BattleUtil.boundingBoxFrom(chunks), LIMITS);
        for (var box : boxes) {
            Vector min = box.getMin().toBlockVector();
            String name = String.format("%s_%d_%d_%d", town.getName(),
                min.getBlockX(), min.getBlockY(), min.getBlockZ()
            );

            copyToDisk(name, box, world);
        }
    }

    /**
     * Pastes the given town's region to the world if the region exists on the disk.
     * @param town the given town
     * @return a {@link CompletableFuture} for when the paste operation is completed.
     */
    public static CompletableFuture<Void> pasteTownToWorld(Town town) {
        File folder = new File(STRUCTURE_PATH);
        if (!folder.exists() || !folder.isDirectory() || folder.list() == null || folder.list().length == 0) {
            LOGGER.log(Level.SEVERE,"Could not find structure directory or it is empty!");
            return CompletableFuture.completedFuture(null);
        }

        String townName = town.getName();
        World world = town.getWorld();
        Collection<String> files =
            Arrays.stream(folder.list()).filter(x -> x.split("_")[0].equals(townName)).toList();

        Collection<CompletableFuture<Void>> futures =
            files.stream().map(f -> pasteToWorld(f, world)).toList();

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }
}
