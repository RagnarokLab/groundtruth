package club.footlickers.groundtruth;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Tells a joining client where the map API is served.
 *
 * <p>The address a player joins on and the address the map is served on are not always the same host:
 * a server can be reached on one name and port while its map is served on another, or behind a proxy.
 * Rather than every client being configured, the server states its own public map URL once on join.
 * Nothing is sent when no URL is configured, and a client without the mod simply ignores it.
 */
public final class MapAdvertListener implements Listener {

    /** Plugin-message channel the map mod listens on; must match the mod's payload id. */
    public static final String CHANNEL = "groundtruth:api";

    private final Plugin plugin;
    private final String url;

    public MapAdvertListener(Plugin plugin, String url) {
        this.plugin = plugin;
        this.url = url == null ? "" : url.trim();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (url.isEmpty()) return;
        Player p = event.getPlayer();
        try {
            p.sendPluginMessage(plugin, CHANNEL, encode(url));
        } catch (Exception e) {
            // a client that cannot be told is not worth failing a join over
            plugin.getLogger().warning("[GroundTruth] could not advertise the map URL to "
                    + p.getName() + ": " + e.getMessage());
        }
    }

    /** A Minecraft string: a varint byte length then the UTF-8 bytes, matching the mod's codec. */
    private static byte[] encode(String s) {
        byte[] utf = s.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(utf.length + 5);
        int v = utf.length;
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
        out.write(utf, 0, utf.length);
        return out.toByteArray();
    }
}
