package io.github.townyadvanced.flagwar.worldedit;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.function.mask.BlockTypeMask;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.palmergames.bukkit.towny.object.TownBlock;
import com.palmergames.bukkit.towny.object.WorldCoord;
import com.palmergames.bukkit.towny.TownyAPI;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.nio.file.Path;

import static io.github.townyadvanced.flagwar.worldedit.SnapshotManifest.Bounds;

/**
 * This class provides static functions that are used solely by the {@link WorldEditService} to keep both classes tidy.
 */
final class WorldEditHelper {

    private WorldEditHelper() {} // shan't be constructed

    /**
     * Checks if an entity's eye level is in a block (it is likely suffocating in said block),
     * and teleports it to the highest point of its X-Z coordinate if so.
     * @param e the entity
     * @return whether the entity had to be teleported upward
     */
    static boolean checkUnSuffocate(Entity e) {
        World world = e.getWorld();
        Location topOfHead = e.getLocation().clone().add(0, e.getHeight(), 0);

        if (topOfHead.getBlock().getType().isSolid()) {
            int highestY = world.getHighestBlockYAt(topOfHead.getBlockX(), topOfHead.getBlockZ()) + 1;
            e.teleport(
                new Location(
                    world,
                    topOfHead.getX(),
                    highestY,
                    topOfHead.getZ(),
                    topOfHead.getYaw(),
                    topOfHead.getPitch()
                )
            );
            return true;
        }
        return false;
    }

    /**
     * Adds the materials captured on the server thread to a BlockTypeMask
     * and returns its negation so that the materials are excluded.
     * @param extent the {@link Extent} to have its black listed materials masked
     */
    static Mask createBlockBlacklistMask(Extent extent, Collection<Material> materials) {
        BlockTypeMask blacklistMask = new BlockTypeMask(extent);

        for (Material material : materials) {
            var blockType = BukkitAdapter.asBlockType(material);
            if (blockType != null) blacklistMask.add(blockType);
        }
        return blacklistMask.inverse();
    }

    static ClaimFootprint footprintFrom(Collection<TownBlock> townBlocks, World world) {
        Collection<ClaimFootprint.Chunk> claims = new ArrayList<>();
        for (TownBlock block : townBlocks) {
            if (block == null || block.isOutpost()) continue;
            var coordinate = block.getWorldCoord();
            if (!world.equals(coordinate.getBukkitWorld()))
                throw new IllegalArgumentException("Snapshot claims span multiple worlds");
            claims.add(new ClaimFootprint.Chunk(coordinate.getX(), coordinate.getZ()));
        }
        return new ClaimFootprint(claims);
    }

    /** Used only to upgrade old snapshots; persisted v2 footprints never consult current ownership. */
    static ClaimFootprint footprintFromCoords(Collection<WorldCoord> coordinates, World world) {
        Collection<ClaimFootprint.Chunk> claims = new ArrayList<>();
        for (WorldCoord coordinate : coordinates) {
            if (!world.equals(coordinate.getBukkitWorld()))
                throw new IllegalArgumentException("Snapshot claims span multiple worlds");
            TownBlock block = TownyAPI.getInstance().getTownBlock(coordinate);
            if (block != null && block.isOutpost()) continue;
            claims.add(new ClaimFootprint.Chunk(coordinate.getX(), coordinate.getZ()));
        }
        return new ClaimFootprint(claims);
    }

    static Bounds bounds(BoundingBox box) {
        return new Bounds((int) box.getMinX(), (int) box.getMinY(), (int) box.getMinZ(),
            (int) box.getMaxX(), (int) box.getMaxY(), (int) box.getMaxZ());
    }

    static BoundingBox box(Bounds bounds) {
        return new BoundingBox(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ());
    }

    static CuboidRegion region(Bounds bounds) {
        return new CuboidRegion(BlockVector3.at(bounds.minX(), bounds.minY(), bounds.minZ()),
            BlockVector3.at(bounds.maxX() - 1, bounds.maxY() - 1, bounds.maxZ() - 1));
    }

    static Bounds intersect(Bounds a, Bounds b) {
        return new Bounds(Math.max(a.minX(), b.minX()), Math.max(a.minY(), b.minY()), Math.max(a.minZ(), b.minZ()),
            Math.min(a.maxX(), b.maxX()), Math.min(a.maxY(), b.maxY()), Math.min(a.maxZ(), b.maxZ()));
    }

    static Path legacyFile(Path structures, String townName, BoundingBox box) {
        String file = townName + "_" + (int) box.getMinX() + "_" + (int) box.getMinY() + "_"
            + (int) box.getMinZ() + ".schem";
        Path result = structures.resolve(file).normalize();
        if (!structures.normalize().equals(result.getParent()))
            throw new IllegalArgumentException("Invalid legacy schematic name");
        return result;
    }

    /**
     * Splits a given box into a {@link Collection} of bounding boxes.
     * @param box the given box
     * @param limits the maximum size of dimensions in each box.
     */
    static Collection<BoundingBox> split(BoundingBox box, Vector limits) {
        Collection<BoundingBox> boxes = new ArrayList<>();

        int minX = (int) Math.floor(box.getMinX());
        int minY = (int) Math.floor(box.getMinY());
        int minZ = (int) Math.floor(box.getMinZ());

        int maxX = (int) Math.ceil(box.getMaxX());
        int maxY = (int) Math.ceil(box.getMaxY());
        int maxZ = (int) Math.ceil(box.getMaxZ());

        int gridStepX = limits.getBlockX();
        int gridStepY = limits.getBlockY();
        int gridStepZ = limits.getBlockZ();

        if (gridStepX <= 0 || gridStepY <= 0 || gridStepZ <= 0)
            throw new IllegalArgumentException("Batch dimensions must be positive");

        for (int x = minX; x < maxX; x += gridStepX) {
            int upperX = Math.min(x + gridStepX, maxX);

            for (int y = minY; y < maxY; y += gridStepY) {
                int upperY = Math.min(y + gridStepY, maxY);

                for (int z = minZ; z < maxZ; z += gridStepZ) {
                    int upperZ = Math.min(z + gridStepZ, maxZ);

                    boxes.add(new BoundingBox(x, y, z, upperX, upperY, upperZ));
                }
            }
        }

        return boxes;
    }

}
