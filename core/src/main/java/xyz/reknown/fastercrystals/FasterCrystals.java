package xyz.reknown.fastercrystals;

import com.github.retrooper.packetevents.PacketEvents;
import dev.jorel.commandapi.CommandAPI;
import dev.jorel.commandapi.CommandAPIBukkitConfig;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import io.github.retrooper.packetevents.util.folia.FoliaScheduler;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.reknown.fastercrystals.bstats.Metrics;
import xyz.reknown.fastercrystals.commands.impl.FastercrystalsCommand;
import xyz.reknown.fastercrystals.listeners.bukkit.*;
import xyz.reknown.fastercrystals.listeners.packet.AnimationListener;
import xyz.reknown.fastercrystals.listeners.packet.InteractEntityListener;
import xyz.reknown.fastercrystals.listeners.packet.LastPacketListener;
import xyz.reknown.fastercrystals.papi.FasterCrystalsExpansion;
import xyz.reknown.fastercrystals.user.Users;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Getter
public class FasterCrystals extends JavaPlugin {
    private Users users;
    private Map<Integer, EnderCrystal> crystalIds;
    private static final Set<Material> AIR_TYPES = Set.of(Material.AIR, Material.CAVE_AIR, Material.VOID_AIR);
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>(); // Player placement cooldowns

    @Override
    public void onLoad() {
        // Initialize PacketEvents API
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().getSettings()
                .checkForUpdates(false)
                .reEncodeByDefault(false);
        PacketEvents.getAPI().load();

        // CommandAPI configuration
        CommandAPI.onLoad(new CommandAPIBukkitConfig(this)
                .missingExecutorImplementationMessage("Only players can run this command."));
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Use thread-safe collection for crystal IDs
        this.crystalIds = new ConcurrentHashMap<>();
        this.users = new Users();

        CommandAPI.onEnable();
        new FastercrystalsCommand().register();

        // Register Bukkit event listeners
        getServer().getPluginManager().registerEvents(new EntityRemoveFromWorldListener(), this);
        getServer().getPluginManager().registerEvents(new EntitySpawnListener(), this);
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(), this);
        getServer().getPluginManager().registerEvents(new PlayerQuitListener(), this);
        getServer().getPluginManager().registerEvents(new WorldUnloadListener(), this);

        // Register PacketEvents listeners
        PacketEvents.getAPI().getEventManager().registerListener(new AnimationListener());
        PacketEvents.getAPI().getEventManager().registerListener(new InteractEntityListener());
        PacketEvents.getAPI().getEventManager().registerListener(new LastPacketListener());
        PacketEvents.getAPI().init();

        // Register PlaceholderAPI expansions
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new FasterCrystalsExpansion().register();
        }

        // Metrics setup for plugin analytics
        int pluginId = 22397;
        new Metrics(this, pluginId);

        getLogger().info("FasterCrystals plugin enabled successfully!");
    }

    @Override
    public void onDisable() {
        PacketEvents.getAPI().terminate();
        CommandAPI.onDisable();
        getLogger().info("FasterCrystals plugin disabled.");
    }

    public void spawnCrystal(Location loc, Player player, ItemStack item) {
        UUID playerId = player.getUniqueId();

        // Handle cooldown for crystal placement
        long currentTime = System.currentTimeMillis();
        if (cooldowns.containsKey(playerId)) {
            long lastPlaced = cooldowns.get(playerId);
            long cooldownTime = getConfig().getLong("crystal-placement-cooldown", 50L); // Default 50ms
            if (currentTime - lastPlaced < cooldownTime) {
                return; // Player is still in cooldown
            }
        }
        cooldowns.put(playerId, currentTime);

        // Adjust location for proper placement
        Location clonedLoc = loc.clone().subtract(0.5, 0.0, 0.5);
        if (!AIR_TYPES.contains(clonedLoc.getBlock().getType())) return;

        clonedLoc.add(0.5, 1.0, 0.5);
        List<Entity> nearbyEntities = new ArrayList<>(clonedLoc.getWorld().getNearbyEntities(clonedLoc, 0.5, 1, 0.5,
                entity -> !(entity instanceof Player p) || p.getGameMode() != GameMode.SPECTATOR));

        if (nearbyEntities.isEmpty()) {
            // Use PacketEvents for faster crystal spawning
            loc.getWorld().spawn(clonedLoc.subtract(0.0, 1.0, 0.0), EnderCrystal.class, entity -> entity.setShowingBottom(false));

            // Remove one crystal from inventory for survival players
            if (player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR) {
                item.setAmount(item.getAmount() - 1);
            }
        }
    }
}
