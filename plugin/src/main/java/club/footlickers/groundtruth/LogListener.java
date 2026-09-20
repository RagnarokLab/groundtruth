package club.footlickers.groundtruth;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * M-Log L1 capture. Every handler only ENQUEUES a row ({@link LogDb} writes it off-thread), and all
 * use {@code MONITOR} + {@code ignoreCancelled} so we record what actually happened, after other
 * plugins have had their say.
 *
 * <p>Attribution is the point: explosions record who primed the TNT (or which mob), and
 * {@link EntityChangeBlockEvent} captures mob griefing (endermen, withers, ravagers, silverfish,
 * sheep, villagers, falling sand, zombie doors).
 */
public class LogListener implements Listener {

    private final LogDb log;

    public LogListener(LogDb log) {
        this.log = log;
    }

    // --- blocks ---------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Player p = e.getPlayer();
        event(p, b, "block-break", b.getBlockData().getAsString(), "minecraft:air",
                itemJson(p.getInventory().getItemInMainHand()), null, null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (e instanceof BlockMultiPlaceEvent) {
            for (BlockState replaced : ((BlockMultiPlaceEvent) e).getReplacedBlockStates()) {
                Block b = replaced.getBlock();
                event(p, b, "block-place", replaced.getBlockData().getAsString(),
                        b.getBlockData().getAsString(), itemJson(p.getInventory().getItemInMainHand()), null, null);
            }
            return;
        }
        Block b = e.getBlockPlaced();
        event(p, b, "block-place", e.getBlockReplacedState().getBlockData().getAsString(),
                b.getBlockData().getAsString(), itemJson(p.getInventory().getItemInMainHand()), null, null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        Entity src = e.getEntity();
        String kind = src instanceof Player ? "player" : "entity";
        String id = src instanceof Player ? ((Player) src).getUniqueId().toString() : src.getType().getKey().toString();
        String name = src.getName();
        // TNT remembers who lit it - attribute the destruction to the player, not the TNT entity
        if (src instanceof TNTPrimed && ((TNTPrimed) src).getSource() instanceof LivingEntity) {
            LivingEntity s = (LivingEntity) ((TNTPrimed) src).getSource();
            if (s instanceof Player) { kind = "player"; id = ((Player) s).getUniqueId().toString(); }
            else { kind = "entity"; id = s.getType().getKey().toString(); }
            name = s.getName() + " (tnt)";
        }
        Location l = src.getLocation();
        log.logEvent(now(), l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(),
                "entity-explode", kind, id, name, null, null, null,
                src.getType().getKey().toString(), null, null, "{\"blocks\":" + e.blockList().size() + "}", 0);
        for (Block b : e.blockList()) {
            String before = b.getBlockData().getAsString();
            log.logEvent(now(), b.getWorld().getName(), b.getX(), b.getY(), b.getZ(),
                    "block-break", kind, id, name, null, null, null,
                    before, before, "minecraft:air", "{\"via\":\"explosion\"}", 0);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        Block s = e.getBlock();
        for (Block b : e.blockList()) {
            log.logEvent(now(), b.getWorld().getName(), b.getX(), b.getY(), b.getZ(),
                    "block-break", "block", s.getType().getKey().toString(), null, null, null, null,
                    b.getBlockData().getAsString(), b.getBlockData().getAsString(), "minecraft:air",
                    "{\"via\":\"explosion\"}", 0);
        }
    }

    /** Mob griefing: endermen, withers, ravagers, silverfish, sheep, villagers, falling sand, doors. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        Entity src = e.getEntity();
        Block b = e.getBlock();
        String after = e.getTo() == null ? "minecraft:air" : e.getTo().getKey().toString();
        log.logEvent(now(), b.getWorld().getName(), b.getX(), b.getY(), b.getZ(),
                "entity-change-block", "entity", src.getType().getKey().toString(), src.getName(),
                null, null, null, b.getBlockData().getAsString(), b.getBlockData().getAsString(), after, null, 0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Block b = e.getClickedBlock();
        event(e.getPlayer(), b, "interact", b.getBlockData().getAsString(), null,
                itemJson(e.getItem()), null, null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        Block b = e.getBlock();
        event(e.getPlayer(), b, "fluid-place", null, e.getBucket().getKey().toString(),
                itemJson(e.getItemStack()), null, null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        Block b = e.getBlock();
        event(e.getPlayer(), b, "fluid-pickup", b.getBlockData().getAsString(), null,
                itemJson(e.getItemStack()), null, null);
    }

    // --- containers -----------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        InventoryType t = e.getInventory().getType();
        if (!isContainer(t)) return;
        Player p = (Player) e.getWhoClicked();
        ItemStack current = e.getCurrentItem();
        ItemStack cursor = e.getCursor();
        Location l = e.getInventory().getLocation();
        int x = l != null ? l.getBlockX() : p.getLocation().getBlockX();
        int y = l != null ? l.getBlockY() : p.getLocation().getBlockY();
        int z = l != null ? l.getBlockZ() : p.getLocation().getBlockZ();
        String world = l != null ? l.getWorld().getName() : p.getWorld().getName();
        String action = e.isShiftClick() ? "container-shift" : "container-click";
        log.logEvent(now(), world, x, y, z, action, "player", p.getUniqueId().toString(), p.getName(),
                null, null, null, itemId(current), itemJson(current), itemJson(cursor),
                "{\"container\":\"" + t.name() + "\",\"slot\":" + e.getSlot() + "}", 0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMoveItem(InventoryMoveItemEvent e) {
        Location l = e.getSource().getLocation();
        if (l == null) return;
        log.logEvent(now(), l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(),
                "container-move", "block", e.getSource().getType().name(), null,
                null, null, null,
                itemId(e.getItem()), itemJson(e.getItem()), null,
                "{\"to\":\"" + e.getDestination().getType().name() + "\"}", 0);
    }

    // --- deaths ---------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        Entity killer = p.getKiller();
        String cause = p.getLastDamageCause() != null ? p.getLastDamageCause().getCause().name() : "UNKNOWN";
        Location l = p.getLocation();
        log.logEvent(now(), l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(),
                "player-death", "player", p.getUniqueId().toString(), p.getName(),
                killer != null ? "player" : "environment", killer != null ? killer.getUniqueId().toString() : null,
                killer != null ? killer.getName() : null, null, null, null,
                "{\"cause\":\"" + cause + "\",\"drops\":" + e.getDrops().size() + "}", 0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent e) {
        Player killer = e.getEntity().getKiller();
        if (killer == null) return;
        Entity v = e.getEntity();
        Location l = v.getLocation();
        log.logEvent(now(), l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(),
                "entity-kill", "player", killer.getUniqueId().toString(), killer.getName(),
                null, null, null, v.getType().getKey().toString(), null, null,
                "{\"drops\":" + e.getDrops().size() + "}", 0);
    }

    // --- players --------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        String ip = p.getAddress() != null && p.getAddress().getAddress() != null
                ? p.getAddress().getAddress().getHostAddress() : null;
        log.playerSeen(p.getUniqueId().toString(), p.getName(), ip, now());
        log.sessionStart(p.getUniqueId().toString(), p.getName(), ip, p.getWorld().getName(), now());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Location l = p.getLocation();
        log.sessionEnd(p.getUniqueId().toString(), now(), l.getWorld().getName(),
                l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        Location l = p.getLocation();
        String cmd = e.getMessage();
        log.logEvent(now(), l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(),
                "command", "player", p.getUniqueId().toString(), p.getName(),
                null, null, null, null, null, null,
                "{\"cmd\":" + Json.str(cmd) + "}", 0);
    }

    // --- helpers --------------------------------------------------------------------------------

    private static boolean isContainer(InventoryType t) {
        switch (t) {
            case CHEST: case BARREL: case SHULKER_BOX: case HOPPER: case DISPENSER: case DROPPER:
            case FURNACE: case BLAST_FURNACE: case SMOKER: case BREWING: case ENDER_CHEST:
            case LECTERN: case CRAFTER:
                return true;
            default:
                return false;
        }
    }

    private static String itemId(ItemStack it) {
        return it == null || it.getType() == Material.AIR ? null : it.getType().getKey().toString();
    }

    private static String itemJson(ItemStack it) {
        if (it == null || it.getType() == Material.AIR) return null;
        return "{\"id\":" + Json.str(it.getType().getKey().toString()) + ",\"n\":" + it.getAmount() + "}";
    }

    private void event(Player actor, Block b, String action, String before, String after,
                       String itemJson, String causeId, String causeName) {
        Location l = b.getLocation();
        log.logEvent(now(), l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(),
                action, "player", actor.getUniqueId().toString(), actor.getName(),
                null, causeId, causeName, before, before, after,
                itemJson != null ? "{\"item\":" + itemJson + "}" : null, 0);
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    /** Tiny JSON string helper so we never have to pull in a JSON library. */
    static final class Json {
        static String str(String s) {
            if (s == null) return "null";
            StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                }
            }
            return sb.append('"').toString();
        }
    }
}
