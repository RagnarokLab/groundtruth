package club.footlickers.groundtruth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * GroundTruth's own web API, served by the plugin itself.
 *
 * <p>This is the migration away from the separate Python process: the plugin already owns the data
 * (it writes both databases and knows who is online), so it can answer the HTTP queries directly.
 * The JDK ships an HTTP server, so there is no dependency to add, and nothing here ever touches the
 * main thread - requests are handled on their own pool, and anything that needs Bukkit state reads a
 * snapshot the plugin refreshes on the main thread.
 *
 * <p>URLs deliberately match the old Python service, so the web app and any consumers (Sassy) keep
 * working unchanged.
 */
public final class WebServer {

    private final GroundTruthPlugin plugin;
    private final Storage storage;
    private final LogDb logDb;
    private final Auth auth;
    private final String serviceKey;
    private final int port;
    private final int threads;
    private final MapData map;
    private final Path staticDir;
    private final Path tilesDir;
    private final String proxyTo;   // "" disables the fallback
    private final boolean renderOnlyVisited;
    private final int visitedRadius;

    private HttpServer server;
    /** Snapshot of online players, refreshed on the main thread (Bukkit state must not be read here). */
    private volatile String playersJson = "[]";

    public WebServer(GroundTruthPlugin plugin, Storage storage, LogDb logDb, Auth auth,
                     String serviceKey, int port, int threads,
                     String staticDir, String tilesDir, String proxyTo,
                     boolean renderOnlyVisited, int visitedRadius,
                     java.io.File dataFolder, MapColours colours) {
        this.plugin = plugin;
        this.storage = storage;
        this.logDb = logDb;
        this.auth = auth;
        this.serviceKey = serviceKey == null ? "" : serviceKey;
        this.port = port;
        this.threads = Math.max(1, threads);
        this.map = new MapData(dataFolder, Paths.get(tilesDir).toFile(), colours);
        this.staticDir = Paths.get(staticDir).toAbsolutePath().normalize();
        this.tilesDir = Paths.get(tilesDir).toAbsolutePath().normalize();
        this.proxyTo = proxyTo == null ? "" : proxyTo.trim();
        this.renderOnlyVisited = renderOnlyVisited;
        this.visitedRadius = visitedRadius;
    }

    public void start() throws IOException {
        // A plugin reload can leave the old instance's socket in TIME_WAIT, so retry the bind a few
        // times rather than coming up with no web server at all.
        IOException last = null;
        for (int attempt = 0; attempt < 6 && server == null; attempt++) {
            try {
                server = HttpServer.create(new InetSocketAddress(port), 64);
            } catch (IOException e) {
                last = e;
                plugin.getLogger().warning("[GroundTruth] web bind to port " + port + " failed (attempt "
                        + (attempt + 1) + "): " + e.getMessage());
                try { Thread.sleep(1500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
        if (server == null) throw last != null ? last : new IOException("could not bind port " + port);
        server.setExecutor(Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "GroundTruth-web");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", this::dispatch);
        map.warmup();   // open the read connection now so the first request doesn't pay the lazy-init cost
        server.start();
        plugin.getLogger().info("[GroundTruth] web API listening on port " + port);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            // HttpServer.stop() does NOT shut down a user-supplied executor, so without this the pool's
            // threads leaked on every /groundtruth reload.
            java.util.concurrent.Executor ex = server.getExecutor();
            if (ex instanceof java.util.concurrent.ExecutorService) {
                ((java.util.concurrent.ExecutorService) ex).shutdownNow();
            }
            server = null;
        }
    }

    /** Called from the main thread on a timer; the HTTP threads only ever read this string. */
    public void refreshPlayers() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"name\":").append(LogListener.Json.str(p.getName()))
              .append(",\"uuid\":\"").append(p.getUniqueId()).append('"')
              .append(",\"x\":").append(round(p.getLocation().getX()))
              .append(",\"y\":").append(round(p.getLocation().getY()))
              .append(",\"z\":").append(round(p.getLocation().getZ()))
              .append(",\"world\":").append(LogListener.Json.str(p.getWorld().getName()))
              .append(",\"health\":").append(round(p.getHealth()))
              .append(",\"food\":").append(p.getFoodLevel())
              .append('}');
        }
        playersJson = sb.append(']').toString();
    }

    private static String round(double d) {
        return String.valueOf(Math.round(d * 10.0) / 10.0);
    }

    // --- request handling ------------------------------------------------------------------------

    private void dispatch(HttpExchange ex) {
        try {
            String path = ex.getRequestURI().getPath();
            String rawQuery = ex.getRequestURI().getRawQuery();
            Map<String, String> q = query(rawQuery);
            String method = ex.getRequestMethod();
            if (!"GET".equals(method) && !"POST".equals(method)) {
                send(ex, 405, "{\"error\":\"GET or POST only\"}");
                return;
            }

            switch (path) {
                case "/healthz":
                    send(ex, 200, health());
                    return;
                case "/api/worlds":
                    send(ex, 200, worlds());
                    return;
                case "/api/players":
                    if (!authorised(ex, q)) { send(ex, 401, "{\"error\":\"login required\"}"); return; }
                    send(ex, 200, "{\"players\":" + playersJson + "}");
                    return;
                case "/api/chat":
                    if (!authorised(ex, q)) { send(ex, 401, "{\"error\":\"login required\"}"); return; }
                    send(ex, 200, chat(q));
                    return;
                case "/api/spawns":
                    if (!authorised(ex, q)) { send(ex, 401, "{\"error\":\"login required\"}"); return; }
                    send(ex, 200, spawns(q));
                    return;
                case "/api/last-attack":
                    if (!authorised(ex, q)) { send(ex, 401, "{\"error\":\"login required\"}"); return; }
                    send(ex, 200, lastAttack(q));
                    return;
                case "/api/player/now":
                    if (!authorised(ex, q)) { send(ex, 401, "{\"error\":\"login required\"}"); return; }
                    send(ex, 200, playerNow(q));
                    return;
                case "/api/structures":
                    send(ex, 200, structures(q));
                    return;
                case "/api/terrain":
                    send(ex, 200, terrain(q));
                    return;
                case "/api/pixels":
                    send(ex, 200, pixels(q));
                    return;
                case "/api/voxels":
                    send(ex, 200, voxels(q));
                    return;
                case "/api/detail":
                    send(ex, 200, map.detail(q.getOrDefault("world", "world"),
                            intOf(q, "cx0", 0), intOf(q, "cz0", 0),
                            intOf(q, "cx1", 0), intOf(q, "cz1", 0)));
                    return;
                case "/api/map/image":
                    // A top-down map PNG around a block position, composed from the tile pyramid.
                    // Public (plain map data). ?world=&x=&z=&zoom=&w=&h=&layer=terrain|biome
                    sendBytes(ex, "image/png", map.mapImage(
                            q.getOrDefault("world", "world"),
                            q.getOrDefault("layer", "terrain"),
                            intOf(q, "x", 0), intOf(q, "z", 0),
                            intOf(q, "zoom", 1), intOf(q, "w", 512), intOf(q, "h", 512)));
                    return;
                case "/api/player/inventory":
                    if (!authorised(ex, q)) { send(ex, 401, "{\"error\":\"login required\"}"); return; }
                    send(ex, 200, inventory(q));
                    return;
                case "/api/container":
                    if (!authorised(ex, q)) { send(ex, 401, "{\"error\":\"login required\"}"); return; }
                    send(ex, 200, container(q));
                    return;
                case "/api/around":
                    if (!authorised(ex, q)) { send(ex, 401, "{\"error\":\"login required\"}"); return; }
                    send(ex, 200, around(q));
                    return;
                default:
                    // Not (yet) handled inside the plugin. Serve the web app / tiles from disk (GET
                    // only), and for anything still living on the old service proxy it through - POST
                    // included, so the admin panel and waypoint saving keep working during the move.
                    if ("GET".equals(method)) {
                        String rel = path.startsWith("/") ? path.substring(1) : path;
                        if (serveFile(ex, staticDir, rel.isEmpty() ? "index.html" : rel)) return;
                        if (serveFile(ex, tilesDir, rel)) return;
                    }
                    proxy(ex, rawQuery == null ? path : path + "?" + rawQuery, method, ex.getRequestBody());
            }
        } catch (Exception e) {
            // log it: a 500 with no trace was impossible to diagnose after the fact
            plugin.getLogger().warning("[GroundTruth] web " + ex.getRequestURI() + " failed: " + e);
            try { send(ex, 500, "{\"error\":" + LogListener.Json.str(String.valueOf(e.getMessage())) + "}"); }
            catch (Exception ignored) { /* client gone */ }
        } finally {
            ex.close();
        }
    }

    /** Service key (for a local assistant) or any valid signed login code. */
    private boolean authorised(HttpExchange ex, Map<String, String> q) {
        String key = q.get("key");
        if (!serviceKey.isEmpty() && serviceKey.equals(key)) return true;
        String code = ex.getRequestHeaders().getFirst("X-GT-Code");
        if (code == null) code = q.get("code");
        return auth.verify(code) != null;
    }

    private void send(HttpExchange ex, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    /** Send raw bytes (e.g. a generated PNG). */
    private void sendBytes(HttpExchange ex, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    /** Serve a file from a root directory, refusing anything that escapes it. */
    private boolean serveFile(HttpExchange ex, Path root, String rel) throws IOException {
        if (rel.isEmpty()) return false;
        Path p = root.resolve(rel).normalize();
        if (!p.startsWith(root) || !Files.isRegularFile(p)) return false;
        byte[] body = Files.readAllBytes(p);
        String name = p.getFileName().toString();
        String ct = name.endsWith(".html") ? "text/html"
                : name.endsWith(".js") ? "application/javascript"
                : name.endsWith(".css") ? "text/css"
                : name.endsWith(".json") ? "application/json"
                : name.endsWith(".png") ? "image/png"
                : "application/octet-stream";
        ex.getResponseHeaders().add("Content-Type", ct);
        // HTML/JS must revalidate or the browser serves a stale app after a deploy; images can sit.
        ex.getResponseHeaders().add("Cache-Control",
                ct.startsWith("text/") || ct.endsWith("javascript") ? "no-cache, must-revalidate" : "max-age=3600");
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
        return true;
    }

    /** Hand a request to the old service while its endpoints are still being ported. */
    private void proxy(HttpExchange ex, String pathAndQuery, String method, InputStream body)
            throws IOException {
        if (proxyTo.isEmpty()) {
            send(ex, 404, "{\"error\":\"not handled by the plugin yet\",\"path\":"
                    + LogListener.Json.str(pathAndQuery) + "}");
            return;
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(proxyTo + pathAndQuery).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(120000);
            c.setRequestMethod(method);
            c.setDoInput(true);
            if ("POST".equals(method)) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type",
                        ex.getRequestHeaders().getFirst("Content-Type") == null
                                ? "application/x-www-form-urlencoded"
                                : ex.getRequestHeaders().getFirst("Content-Type"));
                byte[] payload = readFully(body);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(payload);
                }
            }
            String codeHeader = ex.getRequestHeaders().getFirst("X-GT-Code");
            if (codeHeader != null) c.setRequestProperty("X-GT-Code", codeHeader);
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] resp = in == null ? new byte[0] : readFully(in);
            String ct = c.getContentType();
            ex.getResponseHeaders().add("Content-Type", ct == null ? "application/octet-stream" : ct);
            ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            ex.sendResponseHeaders(code, resp.length == 0 ? -1 : resp.length);
            if (resp.length > 0) {
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(resp);
                }
            }
        } catch (Exception e) {
            send(ex, 502, "{\"error\":\"upstream unavailable\",\"detail\":"
                    + LogListener.Json.str(String.valueOf(e.getMessage())) + "}");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static byte[] readFully(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(65536);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new java.util.HashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            try {
                if (eq < 0) out.put(dec(pair), "");
                else out.put(dec(pair.substring(0, eq)), dec(pair.substring(eq + 1)));
            } catch (Exception ignored) { /* skip malformed pair */ }
        }
        return out;
    }

    private static String dec(String s) {
        return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static int intOf(Map<String, String> q, String k, int def) {
        try { return q.containsKey(k) ? Integer.parseInt(q.get(k)) : def; }
        catch (NumberFormatException e) { return def; }
    }

    // --- endpoints -------------------------------------------------------------------------------

    private String health() {
        return "{\"ok\":true,\"service\":\"GroundTruth plugin\",\"version\":"
                + LogListener.Json.str(plugin.getDescription().getVersion())
                + ",\"playersOnline\":" + countPlayers()
                + ",\"logEvents\":" + (logDb == null ? 0 : logDb.eventCount())
                + ",\"logQueue\":" + (logDb == null ? 0 : logDb.queueDepth()) + "}";
    }

    private int countPlayers() {
        String pj = playersJson;
        int n = 0;
        for (int i = 0; i < pj.length(); i++) if (pj.charAt(i) == '{') n++;
        return n;
    }

    private String worlds() {
        StringBuilder sb = new StringBuilder("{\"worlds\":[");
        boolean first = true;
        for (Storage.WorldRow w : storage.worlds()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"world\":").append(LogListener.Json.str(w.world))
              .append(",\"chunks\":").append(w.chunks)
              .append(",\"structures\":").append(w.structures)
              .append('}');
        }
        return sb.append("]}").toString();
    }

    private String structures(Map<String, String> q) {
        String world = q.getOrDefault("world", "world");
        String type = q.get("type");
        int x = intOf(q, "x", 0), z = intOf(q, "z", 0), limit = Math.min(500, intOf(q, "limit", 50));
        List<Storage.StructureHit> hits = storage.findNearest(world, type, x, z, limit);
        StringBuilder sb = new StringBuilder("{\"world\":").append(LogListener.Json.str(world))
                .append(",\"count\":").append(hits.size()).append(",\"structures\":[");
        boolean first = true;
        for (Storage.StructureHit s : hits) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"type\":").append(LogListener.Json.str(s.type))
              .append(",\"x\":").append(s.x).append(",\"y\":").append(s.y).append(",\"z\":").append(s.z)
              .append('}');
        }
        return sb.append("]}").toString();
    }

    /** /api/terrain - the same JSON envelope the Python service returned. */
    private String terrain(Map<String, String> q) throws Exception {
        String world = q.getOrDefault("world", "world");
        boolean all = q.containsKey("all") || q.containsKey("world_view");
        boolean deflated = "1".equals(q.get("deflate")) || "true".equals(q.get("deflate"));
        int cx0 = intOf(q, "cx0", 0), cz0 = intOf(q, "cz0", 0);
        int cx1 = intOf(q, "cx1", 0), cz1 = intOf(q, "cz1", 0);
        int step = Math.max(1, intOf(q, "step", 1));
        if (all) {
            int[] e = map.worldExtent(world);
            if (e == null) return "{\"error\":\"no surface data for this world yet\"}";
            cx0 = e[0]; cx1 = e[1]; cz0 = e[2]; cz1 = e[3];
        }
        int[] meta = new int[6];
        byte[] data = map.terrain(world, cx0, cz0, cx1, cz1, step, all, deflated, meta);
        StringBuilder sb = new StringBuilder(data.length / 3 + 256);
        sb.append("{\"world\":").append(LogListener.Json.str(world))
          .append(",\"cx0\":").append(cx0).append(",\"cz0\":").append(cz0)
          .append(",\"count\":").append(meta[0]).append(",\"step\":").append(step);
        if (deflated) sb.append(",\"deflated\":true");
        if (all && meta[5] > 0) {
            sb.append(",\"bounds\":{\"minCx\":").append(meta[1]).append(",\"maxCx\":").append(meta[2])
              .append(",\"minCz\":").append(meta[3]).append(",\"maxCz\":").append(meta[4])
              .append(",\"chunks\":").append(meta[5]).append('}');
        }
        String b64 = java.util.Base64.getEncoder().encodeToString(data);
        return sb.append(",\"data\":\"").append(b64).append("\"}").toString();
    }

    /** /api/pixels - per-block grid, deflated and base64'd exactly like the old service. */
    private String pixels(Map<String, String> q) throws Exception {
        String world = q.getOrDefault("world", "world");
        boolean deflated = "1".equals(q.get("deflate")) || "true".equals(q.get("deflate"));
        byte[] data = map.pixels(world, intOf(q, "cx0", 0), intOf(q, "cz0", 0),
                intOf(q, "cx1", 0), intOf(q, "cz1", 0), Math.max(1, intOf(q, "px", 1)), deflated);
        String b64 = java.util.Base64.getEncoder().encodeToString(data);
        return "{\"world\":" + LogListener.Json.str(world) + ",\"px\":" + Math.max(1, intOf(q, "px", 1))
                + (deflated ? ",\"deflated\":true" : "") + ",\"data\":\"" + b64 + "\"}";
    }

    /** /api/voxels - real blocks, or the assembled virtual chunks for a coarser LOD level. */
    private String voxels(Map<String, String> q) throws Exception {
        String world = q.getOrDefault("world", "world");
        int lod = Math.max(0, Math.min(6, intOf(q, "lod", 0)));
        int[] vmeta = new int[5];
        List<Object[]> rows = map.voxels(world, intOf(q, "cx0", 0), intOf(q, "cz0", 0),
                intOf(q, "cx1", 0), intOf(q, "cz1", 0), lod, vmeta);
        int bc0 = intOf(q, "cx0", 0), bz0 = intOf(q, "cz0", 0),
            bc1 = intOf(q, "cx1", 0), bz1 = intOf(q, "cz1", 0);
        int k = 1 << lod;
        // Attach a biome to every chunk so the 3D view can tint grass/foliage/water. lod 0 returns real
        // chunks; coarser levels are merged virtual chunks, so each takes the biome of the real chunk at
        // its centre (falling back to the first non-null inside the block it covers). Without this the
        // coarse levels came back with no biome at all and rendered untinted.
        java.util.Map<Long, String> biomes = map.biomes(world, bc0 * k, bz0 * k,
                bc1 * k + k - 1, bz1 * k + k - 1);
        // "only show what we've seen": drop anything outside the visited+radius scope, using the same
        // rule the 2D renderer uses so the whole map agrees. null scope = filtering off / nothing seen yet.
        java.util.Set<Long> scope = renderOnlyVisited ? map.visitedScope(world, visitedRadius) : null;
        StringBuilder sb = new StringBuilder(rows.size() * 400 + 128);
        sb.append("{\"world\":").append(LogListener.Json.str(world)).append(",\"lod\":").append(lod);
        if (lod > 0) sb.append(",\"k\":").append(vmeta[0]);
        sb.append(",\"cx0\":").append(vmeta[1]).append(",\"cz0\":").append(vmeta[2])
          .append(",\"cx1\":").append(vmeta[3]).append(",\"cz1\":").append(vmeta[4])
          .append(",\"chunks\":[");
        boolean first = true;
        for (Object[] r : rows) {
            if (scope != null && !inScope(scope, lod, (Integer) r[0], (Integer) r[1])) continue;
            if (!first) sb.append(',');
            first = false;
            String biome;
            if (lod == 0) {
                biome = biomes.get(((long) (Integer) r[0] << 32) ^ ((Integer) r[1] & 0xffffffffL));
            } else {
                int rcx = (Integer) r[0] * k, rcz = (Integer) r[1] * k;
                biome = biomes.get(((long) (rcx + (k >> 1)) << 32) ^ ((rcz + (k >> 1)) & 0xffffffffL));
                if (biome == null) {
                    for (int dx = 0; dx < k && biome == null; dx++) {
                        for (int dz = 0; dz < k && biome == null; dz++) {
                            biome = biomes.get(((long) (rcx + dx) << 32) ^ ((rcz + dz) & 0xffffffffL));
                        }
                    }
                }
            }
            sb.append("{\"cx\":").append((Integer) r[0]).append(",\"cz\":").append((Integer) r[1])
              .append(",\"biome\":").append(biome == null ? "null" : LogListener.Json.str(biome))
              .append(",\"data\":\"")
              .append(java.util.Base64.getEncoder().encodeToString((byte[]) r[2])).append("\"}");
        }
        return sb.append("]}").toString();
    }

    /**
     * True if a chunk (lod 0) or virtual chunk (lod &gt; 0, covering 2^lod real chunks each way) is
     * inside the visited+radius scope.
     */
    private static boolean inScope(java.util.Set<Long> scope, int lod, int cx, int cz) {
        if (lod == 0) {
            return scope.contains(((long) cx << 32) ^ (cz & 0xffffffffL));
        }
        int k = 1 << lod;
        for (int dx = 0; dx < k; dx++) {
            for (int dz = 0; dz < k; dz++) {
                if (scope.contains(((long) (cx * k + dx) << 32) ^ ((cz * k + dz) & 0xffffffffL))) return true;
            }
        }
        return false;
    }

    /** Latest inventory snapshot for a player (periodic/death/logout snapshots). */
    private String inventory(Map<String, String> q) {
        if (logDb == null) return "{\"error\":\"logging disabled\"}";
        String name = strip(q.getOrDefault("player", ""));
        if (name == null || name.isEmpty()) return "{\"error\":\"player required\"}";
        String uuid = null;
        boolean online = false;
        String entry = playerEntry(name);
        if (entry != null) { online = true; uuid = strField(entry, "uuid"); }
        if (uuid == null) uuid = logDb.uuidFor(name);
        if (uuid == null) return "{\"player\":" + LogListener.Json.str(name) + ",\"error\":\"unknown player\"}";
        String[] inv = logDb.latestInventory(uuid);
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"player\":").append(LogListener.Json.str(name))
          .append(",\"uuid\":\"").append(uuid).append("\",\"online\":").append(online);
        if (inv == null) {
            sb.append(",\"items\":[],\"count\":0,\"note\":\"no snapshot recorded yet\"");
        } else {
            long ts = Long.parseLong(inv[0]);
            String items = (inv[2] == null || inv[2].isEmpty()) ? "[]" : inv[2];
            sb.append(",\"ts\":").append(ts).append(",\"ago\":").append(LogListener.Json.str(ago(ts)))
              .append(",\"reason\":").append(LogListener.Json.str(inv[1]))
              .append(",\"count\":").append(countOf(items, "\"id\":"))
              .append(",\"items\":").append(items);
        }
        return sb.append('}').toString();
    }

    /** Last-known contents of a logged container (chest/barrel/shulker/...) at a position. */
    private String container(Map<String, String> q) {
        if (logDb == null) return "{\"error\":\"logging disabled\"}";
        String world = q.getOrDefault("world", "world");
        int x = intOf(q, "x", 0), y = intOf(q, "y", 0), z = intOf(q, "z", 0);
        String[] c = logDb.containerAt(world, x, y, z);
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"world\":").append(LogListener.Json.str(world))
          .append(",\"x\":").append(x).append(",\"y\":").append(y).append(",\"z\":").append(z);
        if (c == null) {
            sb.append(",\"found\":false");
        } else {
            long ts = Long.parseLong(c[2]);
            String items = (c[1] == null || c[1].isEmpty()) ? "[]" : c[1];
            sb.append(",\"found\":true,\"kind\":").append(LogListener.Json.str(c[0]))
              .append(",\"ts\":").append(ts).append(",\"ago\":").append(LogListener.Json.str(ago(ts)))
              .append(",\"count\":").append(countOf(items, "\"id\":"))
              .append(",\"items\":").append(items);
        }
        return sb.append('}').toString();
    }

    /** One call for "what's around me": position, biome, surface, nearby structures/players/events. */
    private String around(Map<String, String> q) throws Exception {
        String name = strip(q.getOrDefault("player", ""));
        int radius = Math.max(1, Math.min(512, intOf(q, "radius", 64)));
        int minutes = Math.max(1, intOf(q, "minutes", 10));
        String world = q.get("world");
        int x, y, z;
        boolean online = false;
        if (q.get("x") != null && q.get("z") != null) {
            x = intOf(q, "x", 0); z = intOf(q, "z", 0); y = intOf(q, "y", 64);
            if (world == null) world = "world";
        } else {
            if (name == null || name.isEmpty()) return "{\"error\":\"player or x/z required\"}";
            String entry = playerEntry(name);
            if (entry != null) {
                online = true;
                x = (int) numField(entry, "x"); y = (int) numField(entry, "y"); z = (int) numField(entry, "z");
                String w = strField(entry, "world");
                if (w != null) world = w;
            } else {
                if (logDb == null) return "{\"error\":\"logging disabled\"}";
                String uuid = logDb.uuidFor(name);
                String[] pos = uuid != null ? logDb.lastPosition(uuid) : null;
                if (pos == null) return "{\"player\":" + LogListener.Json.str(name) + ",\"error\":\"unknown player\"}";
                world = pos[0]; x = Integer.parseInt(pos[1]); y = Integer.parseInt(pos[2]); z = Integer.parseInt(pos[3]);
            }
        }
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"world\":").append(LogListener.Json.str(world));
        if (name != null && !name.isEmpty()) {
            sb.append(",\"player\":").append(LogListener.Json.str(name)).append(",\"online\":").append(online);
        }
        sb.append(",\"x\":").append(x).append(",\"y\":").append(y).append(",\"z\":").append(z);
        String[] surf = map.chunkSurface(world, x >> 4, z >> 4);
        if (surf != null) {
            sb.append(",\"biome\":").append(surf[0] == null ? "null" : LogListener.Json.str(surf[0]))
              .append(",\"surface_block\":").append(surf[1] == null ? "null" : LogListener.Json.str(surf[1]))
              .append(",\"surface_y\":").append(surf[2]);
        }
        sb.append(",\"nearby_structures\":[");
        boolean f = true;
        int shown = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Storage.StructureHit h : storage.findNearest(world, null, x, z, 20)) {
            if (shown >= 5) break;
            if (!seen.add(h.type + "|" + h.x + "|" + h.z)) continue;   // one row per structure
            if (!f) sb.append(',');
            f = false;
            shown++;
            sb.append("{\"type\":").append(LogListener.Json.str(h.type))
              .append(",\"x\":").append(h.x).append(",\"y\":").append(h.y).append(",\"z\":").append(h.z)
              .append(",\"dist\":").append(round(Math.hypot(h.x - x, h.z - z))).append('}');
        }
        sb.append("],\"nearby_players\":").append(nearbyPlayers(x, z, radius, name));
        if (logDb != null) {
            long since = System.currentTimeMillis() - minutes * 60_000L;
            sb.append(",\"recent\":[");
            boolean f2 = true;
            for (LogDb.Hit h : logDb.lookup(world, null, x, z, radius, since, null, 15)) {
                if (!f2) sb.append(',');
                f2 = false;
                sb.append("{\"ago\":").append(LogListener.Json.str(ago(h.ts)))
                  .append(",\"action\":").append(LogListener.Json.str(h.action))
                  .append(",\"actor\":").append(LogListener.Json.str(h.actorName))
                  .append(",\"x\":").append(h.x).append(",\"y\":").append(h.y).append(",\"z\":").append(h.z).append('}');
            }
            sb.append(']');
        }
        return sb.append('}').toString();
    }

    /** The JSON object for an online player from the snapshot, or null. */
    private String playerEntry(String name) {
        String j = playersJson;
        int i = 0;
        while ((i = j.indexOf("\"name\":", i)) >= 0) {
            int qs = j.indexOf('"', i + 7), qe = j.indexOf('"', qs + 1);
            if (qs < 0 || qe < 0) return null;
            if (j.substring(qs + 1, qe).equals(name)) {
                int start = j.lastIndexOf('{', i), end = j.indexOf('}', qe);
                return (start >= 0 && end > start) ? j.substring(start, end + 1) : null;
            }
            i = qe;
        }
        return null;
    }

    private static String strField(String json, String key) {
        int i = json.indexOf("\"" + key + "\":\"");
        if (i < 0) return null;
        int s = i + key.length() + 4, e = json.indexOf('"', s);
        return e < 0 ? null : json.substring(s, e);
    }

    private static double numField(String json, String key) {
        int i = json.indexOf("\"" + key + "\":");
        if (i < 0) return 0;
        int s = i + key.length() + 3, e = s;
        while (e < json.length() && "-+.eE0123456789".indexOf(json.charAt(e)) >= 0) e++;
        try { return Double.parseDouble(json.substring(s, e)); } catch (Exception ex) { return 0; }
    }

    private static int countOf(String hay, String needle) {
        int n = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    /** Online players within radius blocks of (x,z), as a JSON array. */
    private String nearbyPlayers(int x, int z, int radius, String exclude) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        String j = playersJson;
        int i = 0;
        while ((i = j.indexOf("\"name\":", i)) >= 0) {
            int qs = j.indexOf('"', i + 7), qe = j.indexOf('"', qs + 1);
            if (qs < 0 || qe < 0) break;
            String n = j.substring(qs + 1, qe);
            int start = j.lastIndexOf('{', i), end = j.indexOf('}', qe);
            String seg = (start >= 0 && end > start) ? j.substring(start, end + 1) : "";
            double d = Math.hypot(numField(seg, "x") - x, numField(seg, "z") - z);
            if (d <= radius && !n.equals(exclude)) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"name\":").append(LogListener.Json.str(n)).append(",\"dist\":").append(round(d)).append('}');
            }
            i = qe;
        }
        return sb.append(']').toString();
    }

    private String chat(Map<String, String> q) {
        int minutes = Math.max(1, intOf(q, "minutes", 60));
        int limit = Math.min(1000, Math.max(1, intOf(q, "limit", 100)));
        String player = q.get("player");
        long since = System.currentTimeMillis() - minutes * 60_000L;
        List<LogDb.Event> rows = logDb.recent("chat", player, null, since, limit);
        StringBuilder sb = new StringBuilder("{\"window_minutes\":").append(minutes)
                .append(",\"player\":").append(LogListener.Json.str(player))
                .append(",\"count\":").append(rows.size()).append(",\"messages\":[");
        boolean first = true;
        for (LogDb.Event e : rows) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"ts\":").append(e.ts)
              .append(",\"ago\":").append(LogListener.Json.str(ago(e.ts)))
              .append(",\"player\":").append(LogListener.Json.str(strip(e.actorName)))
              .append(",\"message\":").append(metaField(e.meta, "message"))
              .append(",\"world\":").append(LogListener.Json.str(e.world))
              .append(",\"x\":").append(e.x).append(",\"y\":").append(e.y).append(",\"z\":").append(e.z)
              .append('}');
        }
        sb.append(']');
        if (!rows.isEmpty()) {
            sb.append(",\"summary\":").append(LogListener.Json.str(
                    "Last " + rows.size() + " message(s). Most recent - " + strip(rows.get(0).actorName)
                            + ": " + metaRaw(rows.get(0).meta, "message")));
        }
        return sb.append('}').toString();
    }

    private String spawns(Map<String, String> q) {
        int minutes = Math.max(1, intOf(q, "minutes", 30));
        int limit = Math.min(200, Math.max(1, intOf(q, "limit", 20)));
        long since = System.currentTimeMillis() - minutes * 60_000L;
        List<LogDb.Event> rows = logDb.recent("mob-spawn", null, null, since, limit);
        StringBuilder sb = new StringBuilder("{\"window_minutes\":").append(minutes)
                .append(",\"count\":").append(rows.size()).append(",\"spawns\":[");
        boolean first = true;
        for (LogDb.Event e : rows) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"ts\":").append(e.ts)
              .append(",\"ago\":").append(LogListener.Json.str(ago(e.ts)))
              .append(",\"mob\":").append(LogListener.Json.str(e.actorName))
              .append(",\"reason\":").append(LogListener.Json.str(e.causeId))
              .append(",\"world\":").append(LogListener.Json.str(e.world))
              .append(",\"x\":").append(e.x).append(",\"y\":").append(e.y).append(",\"z\":").append(e.z)
              .append(",\"light\":").append(metaNum(e.meta, "light"))
              .append(",\"block_below\":").append(metaField(e.meta, "blockBelow"))
              .append(",\"biome\":").append(metaField(e.meta, "biome"))
              .append(",\"nearest_player\":").append(metaField(e.meta, "nearestPlayer"))
              .append(",\"nearest_player_dist\":").append(metaNum(e.meta, "nearestPlayerDist"))
              .append('}');
        }
        sb.append(']');
        if (!rows.isEmpty()) {
            LogDb.Event e = rows.get(0);
            sb.append(",\"summary\":").append(LogListener.Json.str(
                    e.actorName + " spawned " + ago(e.ts) + " - " + e.causeId
                            + ", light " + metaRaw(e.meta, "light") + ", on " + metaRaw(e.meta, "blockBelow")
                            + " at " + e.x + "," + e.y + "," + e.z));
        }
        return sb.append('}').toString();
    }

    private String lastAttack(Map<String, String> q) {
        int minutes = Math.max(1, intOf(q, "minutes", 10));
        long since = System.currentTimeMillis() - minutes * 60_000L;
        List<LogDb.Event> rows = logDb.recent("damage", null, null, since, 40);
        for (LogDb.Event e : rows) {
            String victim = strip(e.target);
            if (!playersJson.contains("\"name\":\"" + victim + "\"")) continue;
            return "{\"player\":" + LogListener.Json.str(victim) + ",\"online\":true"
                    + ",\"ago\":" + LogListener.Json.str(ago(e.ts)) + ",\"ts\":" + e.ts
                    + ",\"attacker\":" + LogListener.Json.str(e.actorName)
                    + ",\"attacker_kind\":" + LogListener.Json.str(e.actorKind)
                    + ",\"cause\":" + LogListener.Json.str(e.causeId)
                    + ",\"weapon\":" + metaField(e.meta, "weapon")
                    + ",\"damage\":" + metaNum(e.meta, "amount")
                    + ",\"health_after\":" + metaNum(e.meta, "healthAfter")
                    + ",\"world\":" + LogListener.Json.str(e.world)
                    + ",\"x\":" + e.x + ",\"y\":" + e.y + ",\"z\":" + e.z
                    + ",\"summary\":" + LogListener.Json.str(e.actorName + " hit " + victim + " for "
                        + metaRaw(e.meta, "amount") + " (" + e.causeId + ") " + ago(e.ts)
                        + " at " + e.x + "," + e.y + "," + e.z) + "}";
        }
        return "{\"player\":null,\"online\":false,\"attack\":null,\"summary\":"
                + LogListener.Json.str("Nothing has hit anyone online in the last " + minutes + " minutes.") + "}";
    }

    private String playerNow(Map<String, String> q) {
        String name = strip(q.getOrDefault("player", ""));
        if (name.isEmpty()) return "{\"error\":\"player required\"}";
        StringBuilder sb = new StringBuilder();
        boolean found = false;
        for (String part : playersJson.replace("[", "").replace("]", "").split("\\},\\{")) {
            if (!part.contains("\"name\":\"" + name + "\"")) continue;
            found = true;
            sb.append("{\"player\":").append(LogListener.Json.str(name)).append(",\"online\":true,")
              .append(part.replace("{", "").replace("}", "").replace("\"name\":\"" + name + "\",", ""));
            break;
        }
        if (!found) return "{\"player\":" + LogListener.Json.str(name) + ",\"online\":false}";
        long since = System.currentTimeMillis() - 24 * 3600_000L;
        for (LogDb.Event e : logDb.recent("damage", null, name, since, 1)) {
            sb.append(",\"last_damage\":{\"ago\":").append(LogListener.Json.str(ago(e.ts)))
              .append(",\"ts\":").append(e.ts)
              .append(",\"attacker\":").append(LogListener.Json.str(e.actorName))
              .append(",\"attacker_kind\":").append(LogListener.Json.str(e.actorKind))
              .append(",\"cause\":").append(LogListener.Json.str(e.causeId))
              .append(",\"damage\":").append(metaNum(e.meta, "amount")).append('}');
        }
        return sb.append('}').toString();
    }

    // --- small helpers ---------------------------------------------------------------------------

    private static String strip(String s) {
        if (s == null) return null;
        return s.startsWith(".") ? s.substring(1) : s;
    }

    static String ago(long ts) {
        long secs = Math.max(0, (System.currentTimeMillis() - ts) / 1000);
        if (secs < 60) return secs + "s ago";
        if (secs < 3600) return (secs / 60) + "m ago";
        if (secs < 86400) return (secs / 3600) + "h " + ((secs % 3600) / 60) + "m ago";
        return (secs / 86400) + "d ago";
    }

    /** Read one flat field out of a small JSON object we wrote ourselves (no JSON library needed). */
    static String rawField(String json, String key) {
        if (json == null) return null;
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) return null;
        i = json.indexOf(':', i + needle.length());
        if (i < 0) return null;
        i++;
        while (i < json.length() && json.charAt(i) == ' ') i++;
        if (i >= json.length()) return null;
        if (json.charAt(i) == '"') {
            StringBuilder sb = new StringBuilder();
            for (int k = i + 1; k < json.length(); k++) {
                char c = json.charAt(k);
                if (c == '\\' && k + 1 < json.length()) { sb.append(json.charAt(++k)); continue; }
                if (c == '"') break;
                sb.append(c);
            }
            return sb.toString();
        }
        int k = i;
        while (k < json.length() && ",}".indexOf(json.charAt(k)) < 0) k++;
        return json.substring(i, k).trim();
    }

    /** A meta field, quoted as JSON (null-safe). */
    static String metaField(String meta, String key) {
        String v = rawField(meta, key);
        return v == null ? "null" : LogListener.Json.str(v);
    }

    /** A meta field, unquoted (numbers read as-is). */
    static String metaRaw(String meta, String key) {
        String v = rawField(meta, key);
        return v == null ? "?" : v;
    }

    /** A meta field as a JSON number, or null when absent. */
    static String metaNum(String meta, String key) {
        String v = rawField(meta, key);
        return v == null ? "null" : v;
    }

    /** Unused today, kept for the map endpoints that move across next. */
    static List<String> emptyList() {
        return new ArrayList<>();
    }
}
