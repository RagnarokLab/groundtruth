package club.footlickers.groundtruth;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Marks a chunk "dirty" whenever a block actually changes, so the map reflects player builds and
 * natural changes instead of freezing at whatever was there when the chunk first generated.
 *
 * <p>ChunkListener only indexes {@code isNewChunk()}, so without this a placed block, a broken
 * block, a grown tree or a piston move never updates the map. This listener watches the real block
 * events (the same ones the event log already captures) and queues the affected chunks; the plugin
 * drains the queue on a gentle timer and re-runs {@link ChunkIndexer#indexChunk} for each.
 */
public class BlockChangeListener implements Listener {

    private final ChunkIndexer indexer;
    private final Plugin plugin;
    private final Set<String> dirty = ConcurrentHashMap.newKeySet();

    public BlockChangeListener(ChunkIndexer indexer, Plugin plugin) {
        this.indexer = indexer;
        this.plugin = plugin;
    }

    private static String key(World w, int cx, int cz) {
        return w.getName() + '\u0000' + cx + '\u0000' + cz;
    }

    private void mark(World w, int cx, int cz) {
        dirty.add(key(w, cx, cz));
    }

    private void mark(Block b) {
        if (b != null) mark(b.getWorld(), b.getX() >> 4, b.getZ() >> 4);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) { mark(e.getBlockPlaced()); }

    @EventHandler
    public void onBreak(BlockBreakEvent e) { mark(e.getBlock()); }

    @EventHandler
    public void onBurn(BlockBurnEvent e) { mark(e.getBlock()); }

    @EventHandler
    public void onExplode(BlockExplodeEvent e) {
        for (Block b : e.blockList()) mark(b);
    }

    @EventHandler
    public void onEntityChange(EntityChangeBlockEvent e) { mark(e.getBlock()); }

    @EventHandler
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) mark(b);
        mark(e.getBlock());
    }

    @EventHandler
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) mark(b);
        mark(e.getBlock());
    }

    @EventHandler
    public void onGrow(BlockGrowEvent e) { mark(e.getBlock()); }

    @EventHandler
    public void onSpread(BlockSpreadEvent e) { mark(e.getBlock()); }

    @EventHandler
    public void onForm(BlockFormEvent e) { mark(e.getBlock()); }

    @EventHandler
    public void onFertilize(BlockFertilizeEvent e) { mark(e.getBlock()); }

    @EventHandler
    public void onStructureGrow(StructureGrowEvent e) {
        for (BlockState s : e.getBlocks()) {
            if (s != null) mark(s.getBlock());
        }
    }

    /** Number of chunks still queued. */
    public int pending() {
        return dirty.size();
    }

    /**
     * Re-index up to {@code limit} dirty chunks. Must be called on the main thread: re-indexing takes
     * a fresh ChunkSnapshot, and the heavy DB writes are handed off to Storage asynchronously.
     */
    public int drain(int limit) {
        java.util.Iterator<String> it = dirty.iterator();
        int done = 0;
        while (it.hasNext() && done < limit) {
            String k = it.next();
            it.remove();
            String[] parts = k.split("\u0000", 3);
            if (parts.length != 3) continue;
            World w = plugin.getServer().getWorld(parts[0]);
            if (w == null) continue;
            int cx, cz;
            try {
                cx = Integer.parseInt(parts[1]);
                cz = Integer.parseInt(parts[2]);
            } catch (NumberFormatException ex) {
                continue;
            }
            try {
                indexer.indexChunk(w.getChunkAt(cx, cz));
                done++;
            } catch (Exception ex) {
                plugin.getLogger().warning("[GroundTruth] re-index failed for " + parts[0]
                        + " " + cx + "," + cz + ": " + ex.getMessage());
            }
        }
        return dirty.size();
    }
}
