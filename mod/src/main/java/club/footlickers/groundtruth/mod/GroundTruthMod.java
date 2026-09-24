package club.footlickers.groundtruth.mod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class GroundTruthMod implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("groundtruth");

    /** Where the server publishes its map URL, as a file inside its resource pack. */
    private static final Identifier API_RESOURCE =
            Identifier.fromNamespaceAndPath("groundtruth", "api.txt");

    public static volatile GtApi api = new GtApi("http://127.0.0.1:8095", "");

    /** The player's login code, captured from chat. Never typed or pasted by hand. */
    private static volatile String loginCode = null;

    public static boolean hasCode() {
        return loginCode != null;
    }

    public static String code() {
        return loginCode;
    }

    /**
     * Where the map API lives.
     *
     * <p>The server publishes its own public map URL as a small file inside the resource pack it
     * already sends every client on join, so the mod needs no per-user configuration and the address
     * a player joins on does not have to be the address the map is served on. A plugin message would
     * be the obvious alternative, but Paper only delivers those on channels the client has declared,
     * and Fabric announces its channels in a way a Bukkit server does not read.
     *
     * <p>Failing that, assume the map is on the same host as the game, on the plugin's web port.
     */
    public static String apiBase(Minecraft mc) {
        String published = packApi(mc);
        if (published != null && !published.isBlank()) return published;
        ServerData server = mc.getCurrentServer();
        if (server != null && server.ip != null && !server.ip.isBlank()) {
            String host = server.ip;
            int colon = host.indexOf(':');
            if (colon > 0) host = host.substring(0, colon);   // the API has its own port
            return "http://" + host + ":8095";
        }
        return "http://127.0.0.1:8095";
    }

    private static String packApi(Minecraft mc) {
        try {
            List<Resource> stack = mc.getResourceManager().getResourceStack(API_RESOURCE);
            for (Resource r : stack) {
                try (InputStream in = r.open()) {
                    String url = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                    if (!url.isEmpty()) return url;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[GroundTruth] could not read {}: {}", API_RESOURCE, e.toString());
        }
        return null;
    }

    /**
     * Ask the server for a login code. It answers in chat and {@link #captureCode} picks it up, so
     * nothing has to be copied out of the game - you cannot copy Minecraft chat.
     */
    public static void requestLink() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.getConnection() != null) {
            mc.getConnection().sendCommand("groundtruth link");
        }
    }

    /**
     * A login code is a base64url payload and signature joined by a dot, and nothing else in chat
     * looks like that, so it can be recognised without matching any of the surrounding wording.
     */
    private static void captureCode(String text) {
        if (text == null) return;
        for (String tok : text.split("[\\s,;]+")) {
            int dot = tok.indexOf('.');
            if (tok.startsWith("eyJ") && dot > 20 && dot < tok.length() - 10) {
                loginCode = tok;
                LOGGER.info("[GroundTruth] captured a login code from chat");
                return;
            }
        }
    }

    private KeyMapping openKey;

    @Override
    public void onInitializeClient() {
        // The server states the map URL in its resource pack; the login code arrives in chat.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> captureCode(message.getString()));
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
