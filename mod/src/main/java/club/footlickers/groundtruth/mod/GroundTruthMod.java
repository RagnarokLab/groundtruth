package club.footlickers.groundtruth.mod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
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

    /** Map URL the server advertised on join, if any. Authoritative when present. */
    public static volatile String advertisedApi = null;

    /**
     * Where the map API lives. The server states its own public map URL on join, because the address
     * a player joins on and the address the map is served on can differ - and that wins. Failing that,
     * assume the map is on the same host as the game, on the plugin's web port.
     */
    public static String apiBase(Minecraft mc) {
        String adv = advertisedApi;
        if (adv != null && !adv.isBlank()) return adv;
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
        // The server tells us its map URL on join, so no client-side configuration is needed.
        PayloadTypeRegistry.clientboundPlay().register(GtApiPayload.ID, GtApiPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(GtApiPayload.ID, (payload, context) -> {
            advertisedApi = payload.url();
            LOGGER.info("[GroundTruth] server map API: {}", advertisedApi);
        });
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
