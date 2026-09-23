package club.footlickers.groundtruth.mod;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The in-game GroundTruth map.
 *
 * <p>Draws the same top-down map the web viewer shows: the plugin composes a PNG around a block
 * position, this uploads it as a texture and blits it over the screen. The world is chosen from the
 * server's own world list by matching the dimension the player is in, so nothing here assumes a
 * world name. The 3D tile view builds on the same API ({@link GtApi#meshTile}).
 */
public class GroundTruthScreen extends Screen {

    private static final Identifier MAP_TEX =
            Identifier.fromNamespaceAndPath("groundtruth", "live_map");

    private volatile String status = "connecting\u2026";
    private volatile String world = null;
    private int centreX, centreZ;
    private int mapW = -1, mapH = -1;     // size the current texture was fetched for
    private boolean registered = false;

    public GroundTruthScreen() {
        super(Component.literal("GroundTruth"));
    }

    @Override
    protected void init() {
        loadWorldThenMap();
    }

    /** Pick the server world matching the dimension we're in, then fetch the map around the player. */
    private void loadWorldThenMap() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            status = "no world";
            return;
        }
        centreX = (int) mc.player.getX();
        centreZ = (int) mc.player.getZ();
        final String env = envOf(mc);
        GroundTruthMod.api.worlds().thenAccept(body -> {
            world = pickWorld(body, env);
            if (world == null) {
                status = "no worlds from server";
                return;
            }
            loadMap();
        });
    }

    private void loadMap() {
        Minecraft mc = Minecraft.getInstance();
        final int w = this.width, h = this.height;
        if (w <= 0 || h <= 0 || world == null) return;
        status = "loading map\u2026";
        GroundTruthMod.api.mapImage(world, centreX, centreZ, 1, w, h).thenAccept(bytes -> {
            if (bytes == null) {
                status = "no map image";
                return;
            }
            final NativeImage img;
            try {
                img = NativeImage.read(bytes);
            } catch (Exception e) {
                status = "bad map image";
                return;
            }
            mc.execute(() -> {
                if (registered) mc.getTextureManager().release(MAP_TEX);   // frees the old image too
                mc.getTextureManager().register(MAP_TEX,
                        new DynamicTexture(() -> "groundtruth map", img));
                registered = true;
                mapW = w;
                mapH = h;
                status = world + " @ " + centreX + "," + centreZ;
            });
        });
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        // only blit when the texture matches the current screen size, so a resize does not stretch it
        if (registered && mapW == this.width && mapH == this.height) {
            g.blit(MAP_TEX, 0, 0, this.width, this.height, 0f, 0f, 1f, 1f);
        }
        g.text(this.font, "GroundTruth \u00b7 " + status, 12, 12, 0xFFFFFFFF);
        g.text(this.font, GroundTruthMod.api.baseUrl(), 12, 26, 0xFFA0D8FF);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            g.text(this.font, String.format("%.0f, %.0f, %.0f",
                    mc.player.getX(), mc.player.getY(), mc.player.getZ()), 12, 40, 0xFFC0FFC0);
        }
        g.text(this.font, "M closes", 12, this.height - 16, 0xFF808080);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** The plugin's environment names, from the dimension id the client is in. */
    private static String envOf(Minecraft mc) {
        String p = mc.level.dimension().identifier().getPath();
        if (p.contains("nether")) return "NETHER";
        if (p.contains("end")) return "THE_END";
        return "NORMAL";
    }

    /**
     * Find the server world whose environment matches, from /api/worlds. Deliberately a scan rather
     * than a JSON library: the mod ships no parser and the shape is one flat object per world.
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
