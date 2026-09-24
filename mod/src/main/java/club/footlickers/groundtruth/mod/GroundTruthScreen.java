package club.footlickers.groundtruth.mod;

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
import java.util.List;

/**
 * The in-game GroundTruth map.
 *
 * <p>Drag to pan, scroll to zoom, right-click for options. Two sources, because they cover different
 * ranges: the prebuilt tile pyramid (<b>one pixel per chunk</b> at its finest) for everything zoomed
 * out, and /api/detail (real per-block colours and heights) once you are close enough that a chunk is
 * more than a pixel - the same split the web viewer uses.
 */
public class GroundTruthScreen extends Screen {

    private static final Identifier MAP_TEX =
            Identifier.fromNamespaceAndPath("groundtruth", "live_map");
    private static final int MARGIN = 96;
    private static final double MIN_BPP = 1.0, MAX_BPP = 512.0, ZOOM_STEP = 1.5;
    private static final double DETAIL_BPP = 2.0;
    private static final int DETAIL_MAX_CHUNKS = 4096;
    private static final int[] WAYPOINT_COLOURS = {
        0xFFFFFF, 0xFFD25C, 0x8ADF6B, 0x5CC8FF, 0xFF8A5C, 0xD08CFF, 0xFF6BAE, 0x9AA4B2,
    };

    private volatile String status = "connecting\u2026";
    private volatile String world = null;

    private double mapX, mapZ;
    private double bpp = 32;
    private boolean panning = false;
    private double panPx = 0, panPy = 0;

    private volatile NativeImage pending = null;
    private double pendingBpp = 1;
    private NativeImage shown = null;
    private int mapW = -1, mapH = -1;
    private double shownBpp = 1;
    private boolean registered = false;

    private int refetchIn = -1;
    private int mapGen = 0;

    /** Right-click menu: null when closed, otherwise the block coords it was opened over. */
    private int[] menuAt = null;
    private double menuX, menuY;

    /** Waypoint prompt: non-null while asking for a name. */
    private String wpName = null;
    private boolean wpPublic = false;
    private int wpColour = 1;

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
        });
    }

    /** Blocks covered by one GUI pixel. */
    private double bpp() {
        return bpp;
    }

    /** Screen position of a block coordinate. */
    private double[] screenOf(double bx, double bz) {
        return new double[]{
            (bx - mapX) / bpp + this.width / 2.0,
            (bz - mapZ) / bpp + this.height / 2.0,
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
            panPx = 0;
            panPy = 0;
        }

        if (registered && mapW > 0 && mapH > 0) {
            // the texture is one pixel per shownBpp blocks; dest size is independent of source size,
            // which is what makes zooming work. The pixel-rect overload is the one that draws reliably.
            double scale = shownBpp / bpp;
            double dw = mapW * scale, dh = mapH * scale;
            int dx = (int) Math.round((this.width - dw) / 2 + panPx);
            int dy = (int) Math.round((this.height - dh) / 2 + panPy);
            g.blit(RenderPipelines.GUI_TEXTURED, MAP_TEX, dx, dy, 0f, 0f,
                    (int) Math.round(dw), (int) Math.round(dh), mapW, mapH, mapW, mapH);
        }

        drawOwnMarker(g);
        drawMenu(g);
        drawWaypointPrompt(g);

        g.text(this.font, "GroundTruth \u00b7 " + status, 12, 12, 0xFFFFFFFF);
        g.text(this.font, GroundTruthMod.api.baseUrl(), 12, 26, 0xFFA0D8FF);
        if (mc.player != null) {
            g.text(this.font, String.format("%.0f, %.0f, %.0f",
                    mc.player.getX(), mc.player.getY(), mc.player.getZ()), 12, 40, 0xFFC0FFC0);
        }
        g.text(this.font, "drag pan \u00b7 scroll zoom \u00b7 right-click options \u00b7 M closes",
                12, this.height - 16, 0xFF808080);

        String diag = "tex=" + mapW + "x" + mapH + " shownBpp=" + shownBpp + " bpp=" + bpp;
        if (!diag.equals(lastDiag)) {
            lastDiag = diag;
            GroundTruthMod.LOGGER.info("[GroundTruth] render: {}", diag);
        }
    }

    /** Your own position, so the map is anchored to something. */
    private void drawOwnMarker(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        double[] s = screenOf(mc.player.getX(), mc.player.getZ());
        int x = (int) Math.round(s[0]), y = (int) Math.round(s[1]);
        g.fill(x - 3, y - 3, 7, 7, 0xFF101010);
        g.fill(x - 2, y - 2, 5, 5, 0xFFFFD25C);
        g.text(this.font, "you", x + 6, y - 4, 0xFFFFD25C);
    }

    private static final String[] MENU_ITEMS = {
        "copy overworld coords", "copy nether coords", "set waypoint", "set public waypoint",
    };
    private static final int MENU_W = 150, MENU_ROW = 12;

    private void drawMenu(GuiGraphicsExtractor g) {
        if (menuAt == null) return;
        int h = MENU_ITEMS.length * MENU_ROW + 6;
        int x = (int) menuX, y = (int) menuY;
        g.fill(x, y, MENU_W, h, 0xE0101418);
        g.fill(x, y, MENU_W, 1, 0xFF6A7480);
        g.fill(x, y + h - 1, MENU_W, 1, 0xFF6A7480);
        g.fill(x, y, 1, h, 0xFF6A7480);
        g.fill(x + MENU_W - 1, y, 1, h, 0xFF6A7480);
        for (int i = 0; i < MENU_ITEMS.length; i++) {
            g.text(this.font, MENU_ITEMS[i], x + 6, y + 4 + i * MENU_ROW, 0xFFE8EEF5);
        }
        g.text(this.font, "block " + menuAt[0] + ", " + menuAt[1],
                x + 6, y + h + 3, 0xFF9AA4B2);
    }

    private void drawWaypointPrompt(GuiGraphicsExtractor g) {
        if (wpName == null) return;
        int w = 260, h = 66;
        int x = (this.width - w) / 2, y = (this.height - h) / 2;
        g.fill(x, y, w, h, 0xF0101418);
        g.fill(x, y, w, 1, 0xFF6A7480);
        g.fill(x, y + h - 1, w, 1, 0xFF6A7480);
        g.text(this.font, "new waypoint", x + 8, y + 6, 0xFFFFFFFF);
        g.text(this.font, "name: " + wpName + "_", x + 8, y + 22, 0xFFE8EEF5);
        g.fill(x + 8, y + 36, 14, 10, 0xFF000000 | WAYPOINT_COLOURS[wpColour]);
        g.text(this.font, "colour (click)", x + 28, y + 37, 0xFF9AA4B2);
        g.text(this.font, wpPublic ? "public" : "private", x + 150, y + 37,
                wpPublic ? 0xFF8ADF6B : 0xFF9AA4B2);
        g.text(this.font, "enter save \u00b7 tab public/private \u00b7 esc cancel", x + 8, y + 52, 0xFF9AA4B2);
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
            // no stored login: ask the server for one. It answers in chat and the mod reads it there,
            // so nothing has to be copied out of the game.
            status = "requesting a login code\u2026";
            GroundTruthMod.requestLink();
        }
    }

    private void saveWaypoint() {
        if (menuAt == null || wpName == null || wpName.isEmpty()) return;
        if (!GroundTruthMod.hasCode()) {
            status = "still waiting for a login code";
            return;
        }
        int bx = menuAt[0], bz = menuAt[1];
        String colour = String.format("#%06X", WAYPOINT_COLOURS[wpColour] & 0xFFFFFF);
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

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        double mx = e.x(), my = e.y();
        if (wpName != null) {                       // the prompt swallows clicks
            if (my >= (this.height - 66) / 2.0 + 36 && my <= (this.height - 66) / 2.0 + 48) {
                wpColour = (wpColour + 1) % WAYPOINT_COLOURS.length;
            }
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
            panPx = 0;
            panPy = 0;
            return true;
        }
        if (e.button() == 1) {
            double[] world2 = new double[]{
                mapX + (mx - this.width / 2.0) * bpp,
                mapZ + (my - this.height / 2.0) * bpp,
            };
            menuAt = new int[]{(int) Math.round(world2[0]), (int) Math.round(world2[1])};
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
            return true;
        }
        return super.mouseDragged(e, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        if (panning && e.button() == 0) {
            panning = false;
            // Move the centre by what was dragged, but KEEP the offset until the new image lands -
            // clearing it here made the map jump back, then jump again.
            mapX -= panPx * bpp;
            mapZ -= panPy * bpp;
            refetchSoon();
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
            refetchSoon();
        }
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        if (wpName != null) {
            char c = (char) e.codepoint();
            if (c >= 32 && c < 127 && wpName.length() < 24) wpName += c;
            return true;
        }
        return super.charTyped(e);
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (wpName != null) {
            if (e.key() == 259) {                   // backspace
                if (!wpName.isEmpty()) wpName = wpName.substring(0, wpName.length() - 1);
                return true;
            }
            if (e.key() == 257 || e.key() == 335) { // enter / numpad enter
                saveWaypoint();
                return true;
            }
            if (e.key() == 258) {                   // tab: public <-> private
                wpPublic = !wpPublic;
                return true;
            }
            if (e.key() == 256) {                   // esc cancels the prompt, not the map
                wpName = null;
                menuAt = null;
                return true;
            }
            return true;
        }
        if (e.key() == 77) {                        // GLFW_KEY_M toggles the map closed
            this.onClose();
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

    /**
     * Find the server world whose environment matches, from /api/worlds. Deliberately a scan rather
     * than a JSON library: the shape is one flat object per world.
     */
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
}
