package club.footlickers.groundtruth.mod;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The in-game GroundTruth map.
 *
 * <p>Drag to pan, scroll to zoom, right-click for options, F for the structure filter, O for colours.
 * Two sources, because they cover different ranges: the prebuilt tile pyramid (<b>one pixel per
 * chunk</b> at its finest) for everything zoomed out, and /api/detail (real per-block colours and
 * heights) once you are close enough that a chunk is more than a pixel - the same split the web
 * viewer uses.
 */
public class GroundTruthScreen extends Screen {

    private static final Identifier MAP_TEX =
            Identifier.fromNamespaceAndPath("groundtruth", "live_map");
    private static final int MARGIN = 96;
    private static final double MIN_BPP = 1.0, MAX_BPP = 512.0, ZOOM_STEP = 1.5;
    private static final double DETAIL_BPP = 2.0;
    private static final int DETAIL_MAX_CHUNKS = 4096;
    private static final int[] PALETTE = {
        0xFFFFFF, 0xFFD25C, 0x8ADF6B, 0x5CC8FF, 0xFF8A5C, 0xD08CFF, 0xFF6BAE, 0x9AA4B2,
    };

    private volatile String status = "connecting\u2026";
    private volatile String world = null;

    private double mapX, mapZ;
    private double bpp = 32;
    private boolean panning = false;
    private double panPx = 0, panPy = 0;
    private boolean dragged = false;

    private volatile NativeImage pending = null;
    private double pendingBpp = 1;
    private boolean pendingIsDetail = false;
    private double pendingOriginX = 0, pendingOriginZ = 0;
    private NativeImage shown = null;
    private int mapW = -1, mapH = -1;
    private double shownBpp = 1;
    private boolean shownIsDetail = false;
    private double shownOriginX = 0, shownOriginZ = 0;
    private boolean registered = false;

    private int refetchIn = -1;
    private int mapGen = 0;

    // right-click menu
    private int[] menuAt = null;
    private double menuX, menuY;

    // waypoint prompt
    private String wpName = null;
    private boolean wpPublic = false;
    private int wpColour = 1;

    // overlays
    private final List<int[]> structures = new ArrayList<>();     // x, z, type index
    private final List<String> structTypes = new ArrayList<>();
    private final Set<Integer> structHidden = new HashSet<>();
    private final List<int[]> structVisible = new ArrayList<>();  // cached per window
    private double cachedWx0 = Double.NaN, cachedWz0, cachedWx1, cachedWz1;
    private volatile Long seed = null;
    private boolean showWaypoints = true, showStructures = true, showSlime = false;
    private int iconColour = 1, slimeColour = 2;

    // panels
    private boolean filterOpen = false;
    private String filterText = "";
    private boolean settingsOpen = false;
    private int[] structPopup = null;                             // x, z, type index

    private String lastDiag = null;

    public GroundTruthScreen() {
        super(Component.literal("GroundTruth"));
    }

    @Override
    protected void init() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            status = "no world";
            return;
        }
        mapX = mc.player.getX();
        mapZ = mc.player.getZ();
        final String env = envOf(mc);
        GroundTruthMod.api.worlds().thenAccept(body -> {
            world = pickWorld(body, env);
            if (world == null) {
                status = "no worlds from server";
                return;
            }
            refetch();
            loadLayers();
        });
    }

    /** Screen position of a block coordinate. */
    private double[] screenOf(double bx, double bz) {
        return new double[]{
            (bx - mapX) / bpp + this.width / 2.0,
            (bz - mapZ) / bpp + this.height / 2.0,
        };
    }

    private double[] blockOf(double sx, double sy) {
        return new double[]{
            mapX + (sx - this.width / 2.0) * bpp,
            mapZ + (sy - this.height / 2.0) * bpp,
        };
    }

    private void refetch() {
        final int w = this.width, h = this.height;
        if (w <= 0 || h <= 0 || world == null) return;
        final int gen = ++mapGen;
        final double cx = mapX, cz = mapZ, at = bpp;

        if (at <= DETAIL_BPP) {
            double blocksW = w * at + 2.0 * MARGIN * at, blocksH = h * at + 2.0 * MARGIN * at;
            final int cx0 = (int) Math.floor((cx - blocksW / 2) / 16), cx1 = (int) Math.ceil((cx + blocksW / 2) / 16);
            final int cz0 = (int) Math.floor((cz - blocksH / 2) / 16), cz1 = (int) Math.ceil((cz + blocksH / 2) / 16);
            if ((long) (cx1 - cx0 + 1) * (cz1 - cz0 + 1) > DETAIL_MAX_CHUNKS) {
                status = "too far out for block detail";
                return;
            }
            status = "loading detail\u2026";
            GroundTruthMod.api.detail(world, cx0, cz0, cx1, cz1).thenAccept(json -> {
                if (gen != mapGen) return;
                NativeImage img = GtDetail.compose(json, cx0, cz0, cx1, cz1);
                if (img == null) {
                    if (gen == mapGen) status = "no detail here yet";
                    return;
                }
                Minecraft.getInstance().execute(() -> {
                    if (gen != mapGen) {
                        img.close();
                        return;
                    }
                    pending = img;
                    pendingBpp = 1;
                    pendingIsDetail = true;
                    // the detail image starts at the first chunk it covers, NOT at the map centre
                    pendingOriginX = cx0 * 16.0;
                    pendingOriginZ = cz0 * 16.0;
                    status = world + " @ " + (int) cx + "," + (int) cz + "  " + fmtBpp(at);
                });
            });
            return;
        }

        int z = (int) Math.round(Math.log(at / 16.0) / Math.log(2));
        z = Math.max(0, Math.min(6, z));
        final int zz = z;
        status = "loading map\u2026";
        GroundTruthMod.api.mapImage(world, (int) cx, (int) cz, zz, w + 2 * MARGIN, h + 2 * MARGIN)
                .thenAccept(bytes -> {
            if (bytes == null) {
                if (gen == mapGen) status = "no map image";
                return;
            }
            final NativeImage img;
            try {
                img = NativeImage.read(bytes);
            } catch (Exception e) {
                if (gen == mapGen) status = "bad map image";
                return;
            }
            Minecraft.getInstance().execute(() -> {
                if (gen != mapGen) {
                    img.close();
                    return;
                }
                pending = img;
                pendingBpp = 16.0 * (1 << zz);
                pendingIsDetail = false;
                status = world + " @ " + (int) cx + "," + (int) cz + "  " + fmtBpp(at);
            });
        });
    }

    private static String fmtBpp(double v) {
        return (v >= 2 ? Math.round(v) : Math.round(v * 100) / 100.0) + " b/px";
    }

    private void refetchSoon() {
        refetchIn = 3;
    }

    @Override
    public void tick() {
        super.tick();
        if (refetchIn > 0 && --refetchIn == 0) refetch();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        Minecraft mc = Minecraft.getInstance();

        if (pending != null) {
            NativeImage img = pending;
            pending = null;
            if (registered) mc.getTextureManager().release(MAP_TEX);
            mc.getTextureManager().register(MAP_TEX, new DynamicTexture(() -> "groundtruth map", img));
            registered = true;
            if (shown != null) shown.close();
            shown = img;
            mapW = img.getWidth();
            mapH = img.getHeight();
            shownBpp = pendingBpp;
            shownIsDetail = pendingIsDetail;
            shownOriginX = pendingOriginX;
            shownOriginZ = pendingOriginZ;
            panPx = 0;
            panPy = 0;
        }

        if (registered && mapW > 0 && mapH > 0) {
            double scale = shownBpp / bpp;
            double dw = mapW * scale, dh = mapH * scale;
            int dx, dy;
            if (shownIsDetail) {
                // position by the image's own origin so overlays land on the right blocks
                double[] s = screenOf(shownOriginX, shownOriginZ);
                dx = (int) Math.round(s[0] + panPx);
                dy = (int) Math.round(s[1] + panPy);
            } else {
                dx = (int) Math.round((this.width - dw) / 2 + panPx);
                dy = (int) Math.round((this.height - dh) / 2 + panPy);
            }
            g.blit(RenderPipelines.GUI_TEXTURED, MAP_TEX, dx, dy, 0f, 0f,
                    (int) Math.round(dw), (int) Math.round(dh), mapW, mapH, mapW, mapH);
        }

        drawLayers(g);
        drawOwnMarker(g);
        drawStructurePopup(g);
        drawMenu(g);
        drawFilterPanel(g);
        drawSettingsPanel(g);
        drawWaypointPrompt(g);

        double[] hover = blockOf(mouseX, mouseY);
        g.text(this.font, "GroundTruth \u00b7 " + status, 12, 12, 0xFFFFFFFF);
        g.text(this.font, GroundTruthMod.api.baseUrl(), 12, 26, 0xFFA0D8FF);
        g.text(this.font, "cursor: " + (int) Math.floor(hover[0]) + ", " + (int) Math.floor(hover[1])
                + "   nether: " + Math.round(hover[0] / 8) + ", " + Math.round(hover[1] / 8),
                12, 40, 0xFFC0FFC0);
        if (mc.player != null) {
            g.text(this.font, "you: " + String.format("%.0f, %.0f, %.0f",
                    mc.player.getX(), mc.player.getY(), mc.player.getZ()), 12, 54, 0xFF9AA4B2);
        }
        g.text(this.font, "drag pan \u00b7 scroll zoom \u00b7 right-click options \u00b7 F structures \u00b7 O colours \u00b7 M closes",
                12, this.height - 16, 0xFF808080);
        g.text(this.font, "[S]lime " + (showSlime ? "on" : "off")
                + "  [T]structures " + (showStructures ? "on" : "off")
                + "  [W]aypoints " + (showWaypoints ? "on" : "off")
                + "  (" + (structTypes.size() - structHidden.size()) + "/" + structTypes.size() + " types shown)",
                12, this.height - 28, 0xFF9AA4B2);

        String diag = "tex=" + mapW + "x" + mapH + " bpp=" + bpp;
        if (!diag.equals(lastDiag)) {
            lastDiag = diag;
            GroundTruthMod.LOGGER.info("[GroundTruth] render: {}", diag);
        }
    }

    // --- overlays ------------------------------------------------------------------------------

    private void loadLayers() {
        GroundTruthMod.api.waypoints().thenAccept(body -> {
            List<Wp> list = new ArrayList<>();
            try {
                JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                for (JsonElement e : root.getAsJsonArray("waypoints")) {
                    JsonObject o = e.getAsJsonObject();
                    Wp w = new Wp();
                    w.name = o.get("name").getAsString();
                    w.x = o.get("x").getAsInt();
                    w.z = o.get("z").getAsInt();
                    JsonElement c = o.get("colour");
                    w.colour = (c == null || c.isJsonNull()) ? 0xFFD25C : parseColour(c.getAsString());
                    list.add(w);
                }
            } catch (Exception ignored) {
            }
            waypoints.clear();
            waypoints.addAll(list);
        });
        GroundTruthMod.api.text("/api/structures", "world", world).thenAccept(body -> {
            List<int[]> list = new ArrayList<>();
            List<String> types = new ArrayList<>();
            try {
                JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                JsonElement s = root.get("seed");
                if (s != null && !s.isJsonNull()) seed = s.getAsLong();
                for (JsonElement e : root.getAsJsonArray("structures")) {
                    JsonObject o = e.getAsJsonObject();
                    String type = o.get("type").getAsString();
                    int ti = types.indexOf(type);
                    if (ti < 0) {
                        types.add(type);
                        ti = types.size() - 1;
                    }
                    list.add(new int[]{o.get("x").getAsInt(), o.get("z").getAsInt(), ti});
                }
            } catch (Exception ignored) {
            }
            structures.clear();
            structures.addAll(list);
            structTypes.clear();
            structTypes.addAll(types);
        });
    }

    private static int parseColour(String s) {
        try {
            return 0xFF000000 | (Integer.parseInt(s.replace("#", ""), 16) & 0xFFFFFF);
        } catch (Exception e) {
            return 0xFFFFD25C;
        }
    }

    /** Structures inside the visible window, recomputed only when the window actually moves. */
    private List<int[]> visibleStructures() {
        double halfW = this.width / 2.0 * bpp, halfH = this.height / 2.0 * bpp;
        double wx0 = mapX - halfW, wx1 = mapX + halfW, wz0 = mapZ - halfH, wz1 = mapZ + halfH;
        if (!structVisible.isEmpty() && wx0 == cachedWx0 && wz0 == cachedWz0 && wx1 == cachedWx1 && wz1 == cachedWz1) {
            return structVisible;
        }
        structVisible.clear();
        for (int[] st : structures) {
            if (st[0] < wx0 - 64 || st[0] > wx1 + 64 || st[1] < wz0 - 64 || st[1] > wz1 + 64) continue;
            if (structHidden.contains(st[2])) continue;
            structVisible.add(st);
        }
        cachedWx0 = wx0;
        cachedWz0 = wz0;
        cachedWx1 = wx1;
        cachedWz1 = wz1;
        return structVisible;
    }

    private void drawLayers(GuiGraphicsExtractor g) {
        double halfW = this.width / 2.0 * bpp, halfH = this.height / 2.0 * bpp;
        double wx0 = mapX - halfW, wx1 = mapX + halfW, wz0 = mapZ - halfH, wz1 = mapZ + halfH;

        if (showSlime && seed != null && bpp <= 16) {
            int cx0 = (int) Math.floor(wx0 / 16), cx1 = (int) Math.ceil(wx1 / 16);
            int cz0 = (int) Math.floor(wz0 / 16), cz1 = (int) Math.ceil(wz1 / 16);
            int sz = (int) Math.max(1, Math.round(16 / bpp));
            int col = 0x55000000 | (PALETTE[slimeColour] & 0xFFFFFF);
            for (int cx = cx0; cx <= cx1; cx++) {
                for (int cz = cz0; cz <= cz1; cz++) {
                    if (!isSlimeChunk(seed, cx, cz)) continue;
                    double[] s = screenOf(cx * 16.0, cz * 16.0);
                    int px = (int) Math.round(s[0]), py = (int) Math.round(s[1]);
                    g.fill(px, py, px + sz, py + sz, col);
                }
            }
        }
        if (showStructures && bpp <= 32) {
            int col = 0xFF000000 | PALETTE[iconColour];
            for (int[] st : visibleStructures()) {
                double[] s = screenOf(st[0], st[1]);
                int px = (int) Math.round(s[0]), py = (int) Math.round(s[1]);
                g.fill(px - 1, py - 1, px + 2, py + 2, col);
            }
        }
        if (showWaypoints) {
            for (Wp w : waypoints) {
                double[] s = screenOf(w.x, w.z);
                if (s[0] < -40 || s[1] < -20 || s[0] > this.width + 40 || s[1] > this.height + 20) continue;
                int px = (int) Math.round(s[0]), py = (int) Math.round(s[1]);
                g.fill(px - 3, py - 3, px + 4, py + 4, 0xFF101010);
                g.fill(px - 2, py - 2, px + 3, py + 3, w.colour);
                g.text(this.font, w.name, px + 6, py - 4, w.colour);
            }
        }
    }

    private static boolean isSlimeChunk(long seed, int cx, int cz) {
        long x = cx, z = cz;
        long s = seed + x * x * 4987142L + x * 5947611L + z * z * 4392871L + z * 2918603L;
        s = (s ^ 0x5DEECE66DL) & ((1L << 48) - 1);
        s = (s * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
        return ((s >> 17) % 10) == 0;
    }

    private void drawOwnMarker(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        double[] s = screenOf(mc.player.getX(), mc.player.getZ());
        int x = (int) Math.round(s[0]), y = (int) Math.round(s[1]);
        g.fill(x - 3, y - 3, x + 4, y + 4, 0xFF101010);
        g.fill(x - 2, y - 2, x + 3, y + 3, 0xFFFFD25C);
        g.text(this.font, "you", x + 6, y - 4, 0xFFFFD25C);
    }

    // --- structure popup / filter ---------------------------------------------------------------

    /** Nearest structure to a screen point, within a small radius, or null. */
    private int[] structureAt(double sx, double sy) {
        int[] best = null;
        double bestD = 8 * 8;
        for (int[] st : visibleStructures()) {
            double[] s = screenOf(st[0], st[1]);
            double d = (s[0] - sx) * (s[0] - sx) + (s[1] - sy) * (s[1] - sy);
            if (d < bestD) {
                bestD = d;
                best = st;
            }
        }
        return best;
    }

    private void drawStructurePopup(GuiGraphicsExtractor g) {
        if (structPopup == null) return;
        String type = structPopup[2] < structTypes.size() ? structTypes.get(structPopup[2]) : "?";
        String line = type + "  @ " + structPopup[0] + ", " + structPopup[1];
        int w = this.font.width(line) + 12;
        int x = Math.min((int) menuX, this.width - w - 4);
        int y = Math.max(4, (int) menuY - 22);
        g.fill(x, y, x + w, y + 16, 0xE0101418);
        g.fill(x, y, x + w, y + 1, 0xFF6A7480);
        g.text(this.font, line, x + 6, y + 4, 0xFFFFFFFF);
    }

    private static final int FILTER_W = 240, FILTER_ROW = 12, FILTER_ROWS = 14;

    private List<Integer> filterMatches() {
        List<Integer> out = new ArrayList<>();
        String q = filterText.toLowerCase();
        for (int i = 0; i < structTypes.size(); i++) {
            String t = structTypes.get(i).split(":").length > 1
                    ? structTypes.get(i).substring(structTypes.get(i).indexOf(':') + 1) : structTypes.get(i);
            if (q.isEmpty() || t.toLowerCase().contains(q)) out.add(i);
            if (out.size() >= FILTER_ROWS) break;
        }
        return out;
    }

    private void drawFilterPanel(GuiGraphicsExtractor g) {
        if (!filterOpen) return;
        int h = FILTER_ROWS * FILTER_ROW + 34;
        int x = this.width - FILTER_W - 8, y = 60;
        g.fill(x, y, x + FILTER_W, y + h, 0xE0101418);
        g.fill(x, y, x + FILTER_W, y + 1, 0xFF6A7480);
        g.text(this.font, "structures (search, click to toggle)", x + 6, y + 5, 0xFFFFFFFF);
        g.fill(x + 6, y + 17, x + FILTER_W - 6, y + 29, 0xFF000000);
        g.text(this.font, filterText + "_", x + 9, y + 20, 0xFFE8EEF5);
        int row = 0;
        for (int ti : filterMatches()) {
            int ry = y + 32 + row * FILTER_ROW;
            boolean hidden = structHidden.contains(ti);
            String t = structTypes.get(ti);
            t = t.contains(":") ? t.substring(t.indexOf(':') + 1) : t;
            g.fill(x + 6, ry + 2, x + 13, ry + 9, hidden ? 0xFF30363C : 0xFF8ADF6B);
            g.text(this.font, t, x + 17, ry + 1, hidden ? 0xFF9AA4B2 : 0xFFE8EEF5);
            row++;
        }
        g.text(this.font, "F closes", x + 6, y + h - 11, 0xFF9AA4B2);
    }

    private void clickFilter(double mx, double my) {
        int h = FILTER_ROWS * FILTER_ROW + 34;
        int x = this.width - FILTER_W - 8, y = 60;
        if (mx < x || mx > x + FILTER_W || my < y || my > y + h) return;
        if (my >= y + 32) {
            int row = (int) ((my - y - 32) / FILTER_ROW);
            List<Integer> matches = filterMatches();
            if (row >= 0 && row < matches.size()) {
                int ti = matches.get(row);
                if (!structHidden.remove(ti)) structHidden.add(ti);
                cachedWx0 = Double.NaN;   // force the visible list to rebuild
            }
        }
    }

    // --- settings panel -------------------------------------------------------------------------

    private static final int SET_W = 190;

    private void drawSettingsPanel(GuiGraphicsExtractor g) {
        if (!settingsOpen) return;
        int h = 62;
        int x = this.width - SET_W - 8, y = 60;
        g.fill(x, y, x + SET_W, y + h, 0xE0101418);
        g.fill(x, y, x + SET_W, y + 1, 0xFF6A7480);
        g.text(this.font, "colours (click to change)", x + 6, y + 5, 0xFFFFFFFF);
        g.fill(x + 6, y + 20, x + 20, y + 30, 0xFF000000 | PALETTE[iconColour]);
        g.text(this.font, "structure icons", x + 26, y + 21, 0xFFE8EEF5);
        g.fill(x + 6, y + 38, x + 20, y + 48, 0xFF000000 | PALETTE[slimeColour]);
        g.text(this.font, "slime chunks", x + 26, y + 39, 0xFFE8EEF5);
        g.text(this.font, "O closes", x + 6, y + h - 11, 0xFF9AA4B2);
    }

    private void clickSettings(double mx, double my) {
        int h = 62;
        int x = this.width - SET_W - 8, y = 60;
        if (mx < x || mx > x + SET_W || my < y || my > y + h) return;
        if (my >= y + 18 && my < y + 32) iconColour = (iconColour + 1) % PALETTE.length;
        else if (my >= y + 36 && my < y + 50) slimeColour = (slimeColour + 1) % PALETTE.length;
    }

    // --- right-click menu -----------------------------------------------------------------------

    private static final String[] MENU_ITEMS = {
        "copy overworld coords", "copy nether coords", "set waypoint", "set public waypoint",
    };
    private static final int MENU_W = 150, MENU_ROW = 12;

    private void drawMenu(GuiGraphicsExtractor g) {
        if (menuAt == null) return;
        int h = MENU_ITEMS.length * MENU_ROW + 6;
        int x = (int) menuX, y = (int) menuY;
        g.fill(x, y, x + MENU_W, y + h, 0xE0101418);
        g.fill(x, y, x + MENU_W, y + 1, 0xFF6A7480);
        g.fill(x, y + h - 1, x + MENU_W, y + h, 0xFF6A7480);
        g.fill(x, y, x + 1, y + h, 0xFF6A7480);
        g.fill(x + MENU_W - 1, y, x + MENU_W, y + h, 0xFF6A7480);
        for (int i = 0; i < MENU_ITEMS.length; i++) {
            g.text(this.font, MENU_ITEMS[i], x + 6, y + 4 + i * MENU_ROW, 0xFFE8EEF5);
        }
        g.text(this.font, "block " + menuAt[0] + ", " + menuAt[1], x + 6, y + h + 3, 0xFF9AA4B2);
    }

    private void drawWaypointPrompt(GuiGraphicsExtractor g) {
        if (wpName == null) return;
        int w = 260, h = 66;
        int x = (this.width - w) / 2, y = (this.height - h) / 2;
        g.fill(x, y, x + w, y + h, 0xF0101418);
        g.fill(x, y, x + w, y + 1, 0xFF6A7480);
        g.fill(x, y + h - 1, x + w, y + h, 0xFF6A7480);
        g.text(this.font, "new waypoint", x + 8, y + 6, 0xFFFFFFFF);
        g.text(this.font, "name: " + wpName + "_", x + 8, y + 22, 0xFFE8EEF5);
        g.fill(x + 8, y + 36, x + 22, y + 46, 0xFF000000 | PALETTE[wpColour]);
        g.text(this.font, "colour (click)", x + 28, y + 37, 0xFF9AA4B2);
        g.text(this.font, wpPublic ? "public" : "private", x + 150, y + 37,
                wpPublic ? 0xFF8ADF6B : 0xFF9AA4B2);
        g.text(this.font, GroundTruthMod.hasCode() ? "login ready \u00b7 enter saves"
                : "asking the server for a login code\u2026", x + 8, y + 52, 0xFF9AA4B2);
    }

    private void menuAction(int index) {
        if (menuAt == null) return;
        int bx = menuAt[0], bz = menuAt[1];
        Minecraft mc = Minecraft.getInstance();
        switch (index) {
            case 0 -> {
                mc.keyboardHandler.setClipboard(bx + " " + bz);
                status = "copied " + bx + " " + bz;
            }
            case 1 -> {
                int nx = Math.round(bx / 8.0f), nz = Math.round(bz / 8.0f);
                mc.keyboardHandler.setClipboard(nx + " " + nz);
                status = "copied nether " + nx + " " + nz;
            }
            case 2 -> openWaypointPrompt(false);
            case 3 -> openWaypointPrompt(true);
            default -> { }
        }
        menuAt = null;
    }

    private void openWaypointPrompt(boolean isPublic) {
        if (menuAt == null) return;
        wpName = "";
        wpPublic = isPublic;
        wpColour = 1;
        if (!GroundTruthMod.hasCode()) {
            status = "requesting a login code\u2026";
            GroundTruthMod.requestLink();
        }
    }

    private void saveWaypoint() {
        if (menuAt == null || wpName == null || wpName.isEmpty()) {
            status = "give the waypoint a name first";
            return;
        }
        if (!GroundTruthMod.hasCode()) {
            status = "still waiting for a login code from the server";
            GroundTruthMod.requestLink();
            return;
        }
        int bx = menuAt[0], bz = menuAt[1];
        String colour = String.format("#%06X", PALETTE[wpColour] & 0xFFFFFF);
        status = "saving waypoint\u2026";
        GroundTruthMod.api.saveWaypoint(GroundTruthMod.code(), wpName, world, bx, bz,
                        wpPublic ? 1 : 0, colour)
                .thenAccept(body -> {
                    boolean ok = body != null && body.contains("\"ok\":true");
                    Minecraft.getInstance().execute(() -> status = ok
                            ? ("saved " + (wpPublic ? "public " : "") + "waypoint " + wpName)
                            : "could not save waypoint");
                });
        wpName = null;
        menuAt = null;
    }

    // --- input ----------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        double mx = e.x(), my = e.y();
        if (wpName != null) {
            int x = (this.width - 260) / 2, y = (this.height - 66) / 2;
            if (my >= y + 34 && my <= y + 48 && mx >= x + 6 && mx <= x + 26) {
                wpColour = (wpColour + 1) % PALETTE.length;
            }
            return true;
        }
        if (filterOpen) {
            clickFilter(mx, my);
            return true;
        }
        if (settingsOpen) {
            clickSettings(mx, my);
            return true;
        }
        if (menuAt != null) {
            int x = (int) menuX, y = (int) menuY;
            if (mx >= x && mx < x + MENU_W && my >= y && my < y + MENU_ITEMS.length * MENU_ROW + 6) {
                menuAction((int) ((my - y - 3) / MENU_ROW));
            } else {
                menuAt = null;
            }
            return true;
        }
        if (e.button() == 0) {
            panning = true;
            dragged = false;
            panPx = 0;
            panPy = 0;
            return true;
        }
        if (e.button() == 1) {
            double[] b = blockOf(mx, my);
            menuAt = new int[]{(int) Math.round(b[0]), (int) Math.round(b[1])};
            menuX = Math.min(mx, this.width - MENU_W - 4);
            menuY = Math.min(my, this.height - MENU_ITEMS.length * MENU_ROW - 20);
            return true;
        }
        return super.mouseClicked(e, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (panning) {
            panPx += dx;
            panPy += dy;
            if (Math.abs(panPx) > 2 || Math.abs(panPy) > 2) dragged = true;
            return true;
        }
        return super.mouseDragged(e, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        if (panning && e.button() == 0) {
            panning = false;
            if (dragged) {
                mapX -= panPx * bpp;
                mapZ -= panPy * bpp;
                refetchSoon();
            } else {
                // a click, not a drag: select a structure under the cursor
                int[] st = structureAt(e.x(), e.y());
                structPopup = st;
                if (st != null) {
                    menuX = e.x();
                    menuY = e.y();
                }
            }
            panPx = 0;
            panPy = 0;
            return true;
        }
        return super.mouseReleased(e);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) return false;
        double next = scrollY > 0 ? bpp / ZOOM_STEP : bpp * ZOOM_STEP;
        next = Math.max(MIN_BPP, Math.min(MAX_BPP, next));
        if (next != bpp) {
            bpp = next;
            cachedWx0 = Double.NaN;
            refetchSoon();
        }
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        char c = (char) e.codepoint();
        if (wpName != null) {
            if (c == '\r' || c == '\n') {           // some keyboards deliver Enter as a character
                saveWaypoint();
                return true;
            }
            if (c >= 32 && c < 127 && wpName.length() < 24) wpName += c;
            return true;
        }
        if (filterOpen) {
            if (c >= 32 && c < 127 && filterText.length() < 20) {
                filterText += c;
                cachedWx0 = Double.NaN;
            }
            return true;
        }
        return super.charTyped(e);
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (wpName != null) {
            if (e.key() == 259) {
                if (!wpName.isEmpty()) wpName = wpName.substring(0, wpName.length() - 1);
                return true;
            }
            if (e.key() == 257 || e.key() == 335) { // enter / numpad enter
                saveWaypoint();
                return true;
            }
            if (e.key() == 258) {
                wpPublic = !wpPublic;
                return true;
            }
            if (e.key() == 256) {
                wpName = null;
                menuAt = null;
                return true;
            }
            return true;
        }
        if (filterOpen) {
            if (e.key() == 259) {
                if (!filterText.isEmpty()) filterText = filterText.substring(0, filterText.length() - 1);
            } else if (e.key() == 70 || e.key() == 256) {
                filterOpen = false;
            }
            return true;
        }
        if (settingsOpen) {
            if (e.key() == 79 || e.key() == 256) settingsOpen = false;
            return true;
        }
        if (e.key() == 77) {                        // M toggles the map closed
            this.onClose();
            return true;
        }
        if (e.key() == 83) {                        // S: slime chunks
            showSlime = !showSlime;
            return true;
        }
        if (e.key() == 84) {                        // T: structures
            showStructures = !showStructures;
            return true;
        }
        if (e.key() == 87) {                        // W: waypoints
            showWaypoints = !showWaypoints;
            return true;
        }
        if (e.key() == 70) {                        // F: structure filter
            filterOpen = !filterOpen;
            settingsOpen = false;
            return true;
        }
        if (e.key() == 79) {                        // O: colours
            settingsOpen = !settingsOpen;
            filterOpen = false;
            return true;
        }
        return super.keyPressed(e);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String envOf(Minecraft mc) {
        String p = mc.level.dimension().identifier().getPath();
        if (p.contains("nether")) return "NETHER";
        if (p.contains("end")) return "THE_END";
        return "NORMAL";
    }

    private static String pickWorld(String json, String env) {
        if (json == null) return null;
        String first = null;
        int i = 0;
        while ((i = json.indexOf("\"world\":", i)) >= 0) {
            int s = json.indexOf('"', i + 8);
            int e = s < 0 ? -1 : json.indexOf('"', s + 1);
            if (s < 0 || e < 0) break;
            String w = json.substring(s + 1, e);
            if (first == null) first = w;
            int end = json.indexOf('}', e);
            int ei = json.indexOf("\"env\":", e);
            if (ei >= 0 && (end < 0 || ei < end)) {
                int es = json.indexOf('"', ei + 6);
                int ee = es < 0 ? -1 : json.indexOf('"', es + 1);
                if (es >= 0 && ee >= 0 && env.equals(json.substring(es + 1, ee))) return w;
            }
            i = e;
        }
        return first;
    }

    private static final class Wp {
        String name;
        int x, z, colour;
    }

    private final List<Wp> waypoints = new ArrayList<>();
}
