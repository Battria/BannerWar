package io.github.townyadvanced.flagwar.worldedit;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.function.mask.BlockTypeMask;
import com.sk89q.worldedit.function.mask.Mask;
import io.github.townyadvanced.flagwar.config.BannerWarConfig;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;

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
     * Adds every material in {@link BannerWarConfig#getBlacklistedMaterials()} to a BlockTypeMask
     * and returns its negation so that the materials are excluded.
     * @param extent the {@link Extent} to have its black listed materials masked
     */
    static Mask createBlockBlacklistMask(Extent extent) {
        BlockTypeMask blacklistMask = new BlockTypeMask(extent);

        for (Material material : BannerWarConfig.getBlacklistedMaterials()) {
            blacklistMask.add(BukkitAdapter.asBlockType(material));
        }
        return blacklistMask.inverse();
    }

    static Vector extractOriginFromName(String name) {
        String[] parts = name.split("_");
        return new Vector(Double.parseDouble(parts[1]), Double.parseDouble(parts[2]), Double.parseDouble(parts[3]));
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
