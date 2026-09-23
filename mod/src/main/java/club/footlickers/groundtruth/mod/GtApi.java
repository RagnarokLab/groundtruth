package club.footlickers.groundtruth.mod;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Thin client for the GroundTruth plugin's web API (the same endpoints the web map uses).
 * Everything returns a CompletableFuture so the game thread is never blocked on the network.
 */
public final class GtApi {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private final String baseUrl;
    private final String key;

    public GtApi(String baseUrl, String key) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.key = key == null ? "" : key;
    }

    public String baseUrl() {
        return baseUrl;
    }

    private URI uri(String path, String... query) {
        StringBuilder sb = new StringBuilder(baseUrl).append(path).append('?');
        if (!key.isEmpty()) sb.append("key=").append(key).append('&');
        for (int i = 0; i + 1 < query.length; i += 2) {
            sb.append(query[i]).append('=').append(query[i + 1]).append('&');
        }
        return URI.create(sb.toString());
    }

    /** Raw bytes (map PNG, mesh tile, ...). Null on any non-200 / failure. */
    public CompletableFuture<byte[]> bytes(String path, String... query) {
        HttpRequest req = HttpRequest.newBuilder(uri(path, query))
                .timeout(Duration.ofSeconds(30)).GET().build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(r -> r.statusCode() == 200 ? r.body() : null)
                .exceptionally(e -> null);
    }

    /** JSON/text body. Null on any non-200 / failure. */
    public CompletableFuture<String> text(String path, String... query) {
        HttpRequest req = HttpRequest.newBuilder(uri(path, query))
                .timeout(Duration.ofSeconds(15)).GET().build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> r.statusCode() == 200 ? r.body() : null)
                .exceptionally(e -> null);
    }

    /** A top-down map PNG centred on a block position (zoom 0 = 1px per chunk / 16 blocks). */
    public CompletableFuture<byte[]> mapImage(String world, int blockX, int blockZ, int zoom, int w, int h) {
        return bytes("/api/map/image",
                "world", world, "x", Integer.toString(blockX), "z", Integer.toString(blockZ),
                "zoom", Integer.toString(zoom), "w", Integer.toString(w), "h", Integer.toString(h));
    }

    /** A prerendered 3D mesh tile (10x10-chunk grid): <tiles>/<world>/mesh/lod0/<tx>_<tz>.gtmesh */
    public CompletableFuture<byte[]> meshTile(String world, int tx, int tz) {
        return bytes("/tiles/" + world + "/mesh/lod0/" + tx + "_" + tz + ".gtmesh");
    }

    /** Everyone online (name, uuid, x/y/z, world, health, food). */
    public CompletableFuture<String> players() {
        return text("/api/players");
    }

    /** World list + dimension + chunk counts. */
    public CompletableFuture<String> worlds() {
        return text("/api/worlds");
    }

    /** Waypoints (public to all, private to their owner). */
    public CompletableFuture<String> waypoints() {
        return text("/api/waypoints");
    }
}
