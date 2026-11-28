package io.github.townyadvanced.flagwar;

import com.palmergames.bukkit.towny.object.TownBlock;
import com.palmergames.bukkit.towny.object.WorldCoord;
import com.palmergames.bukkit.towny.TownySettings;
import io.github.townyadvanced.flagwar.managers.BattleManager;
import io.github.townyadvanced.flagwar.objects.Battle;
import io.github.townyadvanced.flagwar.objects.BattleStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BannerWarExplosionTest {
    @BeforeAll static void initializeTownyCoordinates() throws Exception {
        try (var settings = mockStatic(TownySettings.class)) {
            settings.when(TownySettings::getTownBlockSize).thenReturn(16);
            Class.forName(WorldCoord.class.getName());
        }
    }

    private final WorldCoord claim = new WorldCoord("battle_world", new java.util.UUID(0, 1), -12, 34);

    @Test void lookupUsesOriginalCoordinatesWithoutLookingUpCurrentOwnership() {
        Battle battle = mock(Battle.class);
        when(battle.getInitialTownBlocksAsWorldCoords()).thenReturn(List.of(claim));
        TownBlock captured = mock(TownBlock.class);
        when(captured.getWorldCoord()).thenReturn(claim);
        try (var manager = mockStatic(BattleManager.class)) {
            manager.when(BattleManager::getActiveBattles).thenReturn(List.of(battle));
            assertSame(battle, BannerWarAPI.getBattleAt(claim));
            assertSame(battle, BannerWarAPI.getBattleAt(captured));
            assertNull(BannerWarAPI.getBattleAt(new WorldCoord("other_world", new java.util.UUID(0, 2), -12, 34)));
            assertNull(BannerWarAPI.getBattleAt(new WorldCoord("battle_world", new java.util.UUID(0, 1), -11, 34)));
            assertNull(BannerWarAPI.getBattleAt((WorldCoord) null));
            assertNull(BannerWarAPI.getBattleAt((TownBlock) null));
        }
        verify(captured, never()).getTownOrNull();
        verify(battle, never()).getInitialTownBlocks();
    }

    @Test void explosionLookupChecksAllMatchingBattlesAndNeverExpandsTheFootprint() {
        Battle dormant = mock(Battle.class);
        Battle active = mock(Battle.class);
        when(dormant.getInitialTownBlocksAsWorldCoords()).thenReturn(List.of(claim));
        when(active.getInitialTownBlocksAsWorldCoords()).thenReturn(List.of(claim));
        when(active.isAllowingExplosions()).thenReturn(true);
        try (var manager = mockStatic(BattleManager.class)) {
            manager.when(BattleManager::getActiveBattles).thenReturn(List.of(dormant, active));
            assertTrue(BannerWarAPI.isExplosionAllowedAt(claim));
            assertFalse(BannerWarAPI.isExplosionAllowedAt(new WorldCoord("other_world", new java.util.UUID(0, 2), -12, 34)));
            assertFalse(BannerWarAPI.isExplosionAllowedAt(new WorldCoord("battle_world", new java.util.UUID(0, 1), -11, 34)));
            assertFalse(BannerWarAPI.isExplosionAllowedAt(null));
            when(active.isAllowingExplosions()).thenReturn(false);
            assertFalse(BannerWarAPI.isExplosionAllowedAt(claim));
        }
    }

    @Test void everyStageRequiresSuccessfulSnapshotValidation() throws Exception {
        Battle battle = mock(Battle.class);
        when(battle.isAllowingExplosions()).thenCallRealMethod();
        var snapshot = Battle.class.getDeclaredField("snapshotReady");
        snapshot.setAccessible(true);
        for (BattleStage stage : BattleStage.values()) {
            when(battle.getCurrentStage()).thenReturn(stage);
            boolean combatStage = stage == BattleStage.FLAG || stage == BattleStage.RUINED;
            snapshot.set(battle, CompletableFuture.completedFuture(null));
            assertEquals(combatStage, battle.isAllowingExplosions(), stage.name());
            snapshot.set(battle, new CompletableFuture<Void>());
            assertFalse(battle.isAllowingExplosions(), "Pending snapshot in " + stage);
            snapshot.set(battle, CompletableFuture.failedFuture(new IllegalStateException("Invalid snapshot")));
            assertFalse(battle.isAllowingExplosions(), "Failed snapshot in " + stage);
            var cancelled = new CompletableFuture<Void>();
            cancelled.cancel(false);
            snapshot.set(battle, cancelled);
            assertFalse(battle.isAllowingExplosions(), "Cancelled snapshot in " + stage);
            snapshot.set(battle, null);
            assertFalse(battle.isAllowingExplosions(), "Missing snapshot in " + stage);
        }
    }
}
