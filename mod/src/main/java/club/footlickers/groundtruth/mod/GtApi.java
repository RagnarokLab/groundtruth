package club.footlickers.groundtruth.mod;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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

    /**
     * Identify ourselves rather than let the JDK send an anonymous Java client string. A nameless
     * client is the kind of thing a CDN will challenge, and a challenge page is not JSON - the map
     * would just fail to load with no clue why.
     */
    private static final String USER_AGENT =
            "GroundTruthMod/0.1 (+https://github.com/RagnarokLab/groundtruth)";

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
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT)
                .GET().build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(r -> r.statusCode() == 200 ? r.body() : null)
                .exceptionally(e -> null);
    }

    /** JSON/text body. Null on any non-200 / failure. */
    public CompletableFuture<String> text(String path, String... query) {
        HttpRequest req = HttpRequest.newBuilder(uri(path, query))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", USER_AGENT)
                .GET().build();
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

    /**
     * Per-block detail for a chunk range: the true block colour and height of every block, 16x16 per
     * chunk, deflated and base64'd. This is what gives the close-in view real block detail - the tile
     * pyramid bottoms out at one pixel per chunk.
     */
    public CompletableFuture<String> detail(String world, int cx0, int cz0, int cx1, int cz1) {
        return text("/api/detail",
                "world", world, "cx0", Integer.toString(cx0), "cz0", Integer.toString(cz0),
                "cx1", Integer.toString(cx1), "cz1", Integer.toString(cz1));
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

    /**
     * Waypoints: public ones for everyone, plus the caller's own private ones when their login code
     * is supplied. Without the code the server cannot tell who is asking, so only public ones come
     * back.
     */
    public CompletableFuture<String> waypoints(String code) {
        if (code == null || code.isEmpty()) return text("/api/waypoints");
        return text("/api/waypoints", "code", code);
    }

    /**
     * Create or update one of the caller's waypoints. Needs their login code, which the mod captures
     * from chat rather than asking for.
     */
    public CompletableFuture<String> saveWaypoint(String code, String name, String world,
                                                 int x, int z, int isPublic, String colour) {
        String query = form("code", code, "name", name, "world", world,
                "x", Integer.toString(x), "y", "64", "z", Integer.toString(z),
                "public", Integer.toString(isPublic), "colour", colour);
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/waypoints?" + query))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> r.statusCode() == 200 ? r.body() : null)
                .exceptionally(e -> null);
    }

    /** The caller's own recent position samples, oldest first: {"points":[[x,y,z,ts],...]}. */
    public CompletableFuture<String> track(String code, String world, int hours) {
        return text("/api/track", "code", code, "world", world, "hours", Integer.toString(hours));
    }

    /** Delete one of the caller's own waypoints by name. */
    public CompletableFuture<String> deleteWaypoint(String code, String name) {
        String query = form("code", code, "name", name);
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/waypoints/delete?" + query))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> r.statusCode() == 200 ? r.body() : null)
                .exceptionally(e -> null);
    }

    private static String form(String... kv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (i > 0) sb.append('&');
            sb.append(URLEncoder.encode(kv[i], StandardCharsets.UTF_8)).append('=')
              .append(URLEncoder.encode(kv[i + 1] == null ? "" : kv[i + 1], StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
