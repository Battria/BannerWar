# WorldEdit batching and recovery testing

This implementation requires FastAsyncWorldEdit. New battles use one global worker and a default
volume budget of 262,144 blocks per clipboard. Configure `worldedit.max_blocks_per_batch` from 4,096 to
2,097,152 blocks. `worldedit.batch_size` limits the horizontal side length (16..128, default 64), while
batch height is chosen from the available volume. Configurations without an explicit volume budget
default to `batch_size` cubed for compatibility. Existing snapshots retain their recorded batch bounds.
Snapshots cover the exact union of the initial non-outpost chunks, including disconnected regions.
Filled interior tiles and rectangular edge strips stay together; partial tiles subdivide down to
individual 16-by-16 chunks where necessary.
Empty tiles are skipped. Holes, unclaimed edges, and the space between disconnected regions are excluded
from copying, pasting, and entity cleanup. A single edge chunk can span the full world height when it
fits the volume budget. Larger footprints use shorter vertical slices, with no batch exceeding the
configured block count. If rounding would create extra jobs, the planner falls back to individual
chunks under the same budget. When a full-height chunk fits, the resulting job count never exceeds
one batch per claimed chunk; smaller budgets may require vertical slices even for individual chunks.
The exact chunk footprint is stored in the manifest and survives restarts and ownership changes.

For the supplied 743-claim Termez dump, the default budget and -64..320 build height produce
306 volume-budgeted batches covering 73,039,872 blocks. The previous bounding rectangle produced 12,768 batches
covering 3,280,994,304 blocks. Both regions are included; the empty space between them is excluded.
Across all 16 towns in the supplied dump, using that same height and budget, the planner produces
976 batches versus 2,364 full-height chunk batches and 3,546 batches with the earlier fixed-height
subdivision. These are planner job counts, not measured live FAWE timings.
Towny's ownership restoration continues to use the recorded pre-war townblocks, independently of the
WorldEdit subdivision; it does not claim the bounding rectangle.

## Installing the test build

1. Stop the test server and back up the world and the plugin data folder, including
   `BannerWarDatabase.db` and `structures`. The build adds a nullable `SnapshotId` database column.
2. Replace the plugin jar with `target/FlagWar-0.9.0.jar`. Keep FAWE and all the existing plugin dependencies.
3. Start the server normally. Use a full restart when replacing jars; plugin hot reload is unsupported.

## Existing Termez battle

Existing battles without a SnapshotId import their legacy files on startup. Both UUID-named schematics
and the batching branch's `<TownName>_<x>_<y>_<z>.schem` files are supported, including town names with
underscores and negative coordinates. Original legacy files remain untouched. If both formats exist
for the same town, import stops rather than guessing which generation belongs to the battle.

Version 1 rectangular manifests are also supported. On first load, their footprint is recovered from
the battle's recorded initial coordinates and written as a version 2 manifest. Files covering only gaps
are dropped from the active inventory. A coordinate-aware source mask excludes unclaimed chunks inside
the remaining mixed files. Files dropped from the old inventory are retained on disk, and are not read
or pasted. Version 2 manifests always use their persisted footprint rather than current town ownership.

If Termez is already DORMANT from the earlier command exception, startup attempts restoration
automatically. Otherwise, `/pa Termez` advances normally once snapshot validation completes. Watch for
`Imported legacy snapshot`, `Restored snapshot batches`, and `Town restoration complete for Termez`.
While restoration is running, repeated `/pa Termez` must keep the battle DORMANT. After restoration
has completed, a further `/pa Termez` may end and remove the battle as usual.

Legacy files can contain much larger clipboards than the new default. They run sequentially, but are
not automatically subdivided during import. Test a fresh battle as well to measure the smaller batches.

## Fresh battle checks

1. Start a battle in a small town, then run `/pa <Town>` immediately. If the snapshot is still copying,
   the command should report that preparation is pending, with no exception or phase change.
2. Wait for the final `Copied snapshot batches` progress line. Advance to FLAG, destroy some blocks,
   and add blocks in space that was originally air. Advance with the defender winning.
3. Check that blocks and air return to their pre-war state, blacklisted source materials remain
   excluded, dropped items are removed, and players in solid blocks are moved after the edits finish.
4. Repeat with `/pa <Town> false` from FLAG, then advance RUINED to DORMANT. Check the same restoration.
5. Repeat with disconnected regions, an unclaimed hole inside a region, negative coordinates, and
   claims crossing 64-block boundaries. Place markers and dropped items in the hole, just outside the
   claim edges, and between the regions after copying. They must survive restoration unchanged. Confirm
   both owned regions restore and that Towny's claim count does not expand into the gaps.
6. Start two battles together. Their copy/paste progress must proceed sequentially, with no concurrent
   snapshot jobs. Check server tick time and heap use while copying and restoring.

## Failure and restart checks

New snapshots live under
`structures/<town UUID>/<world UUID>/<snapshot UUID>/`. Each contains `manifest.properties` and its
expected batch files. Temporary files are published only after the writer closes.

On a test copy, stop the server after a READY snapshot, move one batch aside, and restart. Phase
advancement must be blocked. Put the file back and restart, then verify restoration succeeds. For a
failed DORMANT restoration, restore the missing file and use `/pa <Town>` to retry without deleting
the battle. No completion event should fire on the failed attempt.

Restart during a long restoration. The manifest remains RESTORING or RESTORE_FAILED, all batches are
retained, and the DORMANT battle retries on startup. Restoration replays all batches, so repeated pastes
must produce the same result. After success, RESTORED is persisted before batch files are retired;
subsequent restarts recognize the completion marker without pasting or firing a second completion event.

An interrupted or failed initial copy is different: the snapshot is incomplete and cannot safely be
recaptured after the world may have changed. The plugin retains its files, blocks progression, and logs
that backup recovery is required. It never creates a new pre-war snapshot from the damaged town.

## Automated checks and building

`mvn test` covers volume-budgeted subdivision, full-height edge chunks, rectangular groups, rounding
fallbacks, small budgets, disconnected regions, holes, negative coordinates, odd batch sizes, coordinate-only
fixtures for all 16 supplied towns, legacy source-coordinate masks, manifest footprint migration, identity
and file integrity, restart states, and serial worker failure/shutdown.
Live FAWE edits, Towny events, player cleanup, and tick/heap measurements still need the server checks above.

If compilation reports a missing Wayfinder `setWaypointColor` method, refresh the local dependency
from the bundled API before building:

```text
mvn org.apache.maven.plugins:maven-install-plugin:3.1.2:install-file -Dfile=libs/WayfinderAPI-1.0.jar -DgroupId=town.sheepy -DartifactId=WayFinderAPI -Dversion=1.0 -Dpackaging=jar -DgeneratePom=false
mvn package
```
