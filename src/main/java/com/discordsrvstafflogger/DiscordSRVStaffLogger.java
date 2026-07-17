package com.discordsrvstafflogger;

import com.discordsrvstafflogger.listener.BanListener;
import com.discordsrvstafflogger.listener.CommandListener;
import com.discordsrvstafflogger.listener.InventoryEditListener;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Watches four sensitive staff actions and posts a record of each to a Discord channel
 * (via DiscordSRV). No log file and no database - Discord is the only sink.
 *
 * <ol>
 *   <li>Bans (via the LiteBans API)</li>
 *   <li>CoreProtect rollbacks (command)</li>
 *   <li>Enchanting items with /enchant (command)</li>
 *   <li>Adding/removing items from another player's inventory (give/clear commands + InvSee++ GUI)</li>
 * </ol>
 */
public final class DiscordSRVStaffLogger extends JavaPlugin {

    private DiscordNotifier notifier;
    private BanListener banListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration config = getConfig();

        String channelName = config.getString("discord.channel-name", "staff-log");
        this.notifier = new DiscordNotifier(this, channelName);

        PluginManager pm = getServer().getPluginManager();

        // 2, 3, 4a - command-based actions (only needs Bukkit events).
        if (config.getBoolean("log.rollbacks", true)
                || config.getBoolean("log.enchants", true)
                || config.getBoolean("log.inventory-edits", true)) {
            pm.registerEvents(new CommandListener(this, notifier), this);
        }

        // 4b - InvSee++ GUI edits.
        if (config.getBoolean("log.inventory-edits", true)) {
            if (pm.getPlugin("InvSeePlusPlus") != null) {
                pm.registerEvents(new InventoryEditListener(this, notifier), this);
            } else {
                getLogger().info("InvSeePlusPlus not found - GUI inventory-edit logging disabled "
                        + "(give/clear command logging still active).");
            }
        }

        // 1 - bans and unbans via LiteBans.
        boolean logBans = config.getBoolean("log.bans", true);
        boolean logUnbans = config.getBoolean("log.unbans", true);
        if (logBans || logUnbans) {
            if (pm.getPlugin("LiteBans") != null) {
                banListener = new BanListener(this, notifier, logBans, logUnbans);
                banListener.register();
                getLogger().info("Hooked into LiteBans - ban/unban logging active.");
            } else {
                getLogger().warning("LiteBans not found - ban/unban logging disabled.");
            }
        }

        getLogger().info("DiscordSRVStaffLogger enabled. Posting to DiscordSRV channel \"" + channelName + "\".");
    }

    @Override
    public void onDisable() {
        if (banListener != null) {
            banListener.unregister();
            banListener = null;
        }
    }

    // ---- config helpers ----

    /** Reads a string list from config and returns it lower-cased as a set (for case-insensitive matching). */
    public Set<String> lowerSet(String path, List<String> defaults) {
        List<String> values = getConfig().isList(path) ? getConfig().getStringList(path) : defaults;
        return values.stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    /** Builds an insertion-ordered field map from alternating key/value pairs. */
    public static LinkedHashMap<String, String> fields(String... keyValues) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
