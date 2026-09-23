package club.footlickers.groundtruth.mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The in-game GroundTruth view.
 *
 * <p>Foundation stage: proves the mod loads, the keybind opens it, and it can reach the plugin's API.
 * The map / 3D tile rendering builds on top of this (the tiles are fetched by {@link GtApi#meshTile}).
 */
public class GroundTruthScreen extends Screen {

    private volatile String status = "connecting\u2026";
    private volatile int worlds = -1;

    public GroundTruthScreen() {
        super(Component.literal("GroundTruth"));
    }

    @Override
    protected void init() {
        GroundTruthMod.api.worlds().thenAccept(body -> {
            if (body == null) {
                status = "no server (set the API url)";
            } else {
                status = "connected";
                worlds = countOf(body, "\"name\"");
            }
        });
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.text(this.font, "GroundTruth", 12, 12, 0xFFFFFFFF);
        g.text(this.font, "api: " + GroundTruthMod.api.baseUrl(), 12, 28, 0xFFA0D8FF);
        g.text(this.font, "status: " + status + (worlds >= 0 ? "  (" + worlds + " world(s))" : ""),
                12, 44, 0xFFFFFFFF);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            g.text(this.font, String.format("you: %.0f, %.0f, %.0f",
                    mc.player.getX(), mc.player.getY(), mc.player.getZ()), 12, 60, 0xFFC0FFC0);
        }
        g.text(this.font, "M closes \u00b7 foundation build", 12, this.height - 16, 0xFF808080);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static int countOf(String hay, String needle) {
        int n = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
