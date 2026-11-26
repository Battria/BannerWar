package io.github.townyadvanced.flagwar.worldedit;

import com.sk89q.worldedit.function.mask.AbstractMask;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.math.BlockVector3;

/** Maps schematic-local positions to the persisted claim footprint when reading old rectangular files. */
final class ClaimSourceMask extends AbstractMask {
    private final ClaimFootprint footprint;
    private final BlockVector3 source;
    private final BlockVector3 destination;

    ClaimSourceMask(ClaimFootprint footprint, BlockVector3 source, BlockVector3 destination) {
        this.footprint = footprint;
        this.source = source;
        this.destination = destination;
    }

    @Override
    public boolean test(BlockVector3 position) {
        return footprint.containsBlock(position.x() - source.x() + destination.x(),
            position.z() - source.z() + destination.z());
    }

    @Override
    public Mask copy() {
        return this; // Immutable and safe for FAWE's parallel readers.
    }
}
