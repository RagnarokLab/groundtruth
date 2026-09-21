package club.footlickers.groundtruth;

import org.bukkit.plugin.Plugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

/**
 * Event-driven capture only - no polling or scanning of the world folder while the server
 * is running. isNewChunk() is true exactly once, the moment a chunk is freshly generated,
 * so this only ever does work when there's genuinely new ground truth to record.
 */
public class ChunkListener implements Listener {

    private final ChunkIndexer indexer;
    private final Plugin plugin;

    public ChunkListener(ChunkIndexer indexer, Plugin plugin) {
        this.indexer = indexer;
        this.plugin = plugin;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        // "debug chunk events: true" in config.yml logs every load with its new/generated flag -
        // the quickest way to see whether live indexing is actually being triggered.
        if (plugin.getConfig().getBoolean("debug-chunk-events", false)) {
            plugin.getLogger().info("[GroundTruth] chunk load " + event.getChunk().getX() + ","
                    + event.getChunk().getZ() + " world=" + event.getWorld().getName()
                    + " new=" + event.isNewChunk());
        }
        if (event.isNewChunk()) {
            try {
                indexer.indexChunk(event.getChunk());
            } catch (Exception e) {
                // log once per failing chunk and carry on; never take the tick down over indexing
                plugin.getLogger().warning("[GroundTruth] index failed for chunk "
                        + event.getChunk().getX() + "," + event.getChunk().getZ() + ": " + e.getMessage());
            }
        }
    }
}
