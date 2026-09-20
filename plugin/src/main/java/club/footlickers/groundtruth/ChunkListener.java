package club.footlickers.groundtruth;

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

    public ChunkListener(ChunkIndexer indexer) {
        this.indexer = indexer;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (event.isNewChunk()) {
            indexer.indexChunk(event.getChunk());
        }
    }
}
