package club.footlickers.groundtruth.mod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GroundTruthMod implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("groundtruth");

    /** The plugin web API for the server currently connected to; set when the map is opened. */
    public static volatile GtApi api = new GtApi("http://127.0.0.1:8095", "");

    /**
     * The API host for the server the player is on: the game address, with the game port replaced by
     * the plugin's web port. A dedicated server's map lives on that server, not on the client, so
     * 127.0.0.1 is only right for a local test server. Override with -Dgroundtruth.api=...
     */
    public static String apiBase(Minecraft mc) {
        String override = System.getProperty("groundtruth.api");
        if (override != null && !override.isBlank()) return override;
        ServerData server = mc.getCurrentServer();
        if (server != null && server.ip != null && !server.ip.isBlank()) {
            String host = server.ip;
            int colon = host.indexOf(':');
            if (colon > 0) host = host.substring(0, colon);   // the API has its own port
            return "http://" + host + ":8095";
        }
        return "http://127.0.0.1:8095";
    }

    private KeyMapping openKey;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = new KeyMapping.Category(
                Identifier.fromNamespaceAndPath("groundtruth", "map"));
        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.groundtruth.open", 77, category));   // 77 = GLFW_KEY_M
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) {
                api = new GtApi(apiBase(client), "");
                client.setScreenAndShow(new GroundTruthScreen());
            }
        });
        LOGGER.info("[GroundTruth] map mod initialised");
    }
}
