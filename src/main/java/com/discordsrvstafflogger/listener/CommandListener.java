package com.discordsrvstafflogger.listener;

import com.discordsrvstafflogger.DiscordNotifier;
import com.discordsrvstafflogger.DiscordSRVStaffLogger;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Detects three command-based staff actions:
 * <ul>
 *   <li>CoreProtect rollback ({@code /co rollback ...})</li>
 *   <li>Enchanting ({@code /enchant ...})</li>
 *   <li>Giving/clearing items on <em>another</em> player ({@code /give}, {@code /clear}, ...)</li>
 * </ul>
 *
 * <p>Runs at {@link EventPriority#MONITOR} with {@code ignoreCancelled = true}, so this
 * listener never influences command handling.
 *
 * <p>Because {@link PlayerCommandPreprocessEvent} fires <em>before</em> the command's own
 * permission check, we gate logging on an explicit, configurable set of permission nodes per
 * action (covering both the vanilla {@code minecraft.command.*} node and the plugin node).
 * Operators satisfy any node; a player lacking all of them (whose command would fail) is not
 * logged. Console and command blocks are always permitted.
 */
public final class CommandListener implements Listener {

    // give-style bases use the "give" permission set; everything else in the give/clear list
    // (clear, clearinventory, ci, invclear, enderclear, ...) uses the "clear" set.
    private static final Set<String> GIVE_LIKE = Set.of("give", "invgive", "endergive");

    private final DiscordNotifier notifier;

    private final boolean rollbacksEnabled;
    private final boolean enchantsEnabled;
    private final boolean giveClearEnabled;
    private final boolean requirePermission;
    private final boolean ignoreOps;

    private final Set<String> rollbackBases;
    private final Set<String> rollbackSubs;
    private final Set<String> enchantBases;
    private final Set<String> enchantSubcommandBases;
    private final Set<String> enchantSubcommands;
    private final Set<String> giveClearBases;

    private final Set<String> rollbackPerms;
    private final Set<String> enchantPerms;
    private final Set<String> givePerms;
    private final Set<String> clearPerms;

    // Extra, owner-defined commands to log generically (base command -> how to log it).
    private final Map<String, WatchEntry> watched;

    /** A custom watched command: the embed title to use and the permission nodes that gate it. */
    private record WatchEntry(String title, Set<String> permissions) {
    }

    public CommandListener(DiscordSRVStaffLogger plugin, DiscordNotifier notifier) {
        this.notifier = notifier;

        this.rollbacksEnabled = plugin.getConfig().getBoolean("log.rollbacks", true);
        this.enchantsEnabled = plugin.getConfig().getBoolean("log.enchants", true);
        this.giveClearEnabled = plugin.getConfig().getBoolean("log.inventory-edits", true);
        this.requirePermission = plugin.getConfig().getBoolean("commands.require-permission", true);
        this.ignoreOps = plugin.getConfig().getBoolean("ignore-ops", false);

        this.rollbackBases = plugin.lowerSet("commands.rollback.bases", List.of("co", "core", "coreprotect"));
        this.rollbackSubs = plugin.lowerSet("commands.rollback.subcommands", List.of("rollback", "rb", "undo"));
        this.enchantBases = plugin.lowerSet("commands.enchant.bases", List.of("enchant", "enchantment"));
        // Base commands that only count with a tracked subcommand, e.g. Enchants Squared's
        // /es enchant|give|remove.
        this.enchantSubcommandBases = plugin.lowerSet("commands.enchant.subcommand-bases", List.of("es"));
        this.enchantSubcommands = plugin.lowerSet("commands.enchant.subcommands", List.of("enchant", "give", "remove"));
        this.giveClearBases = plugin.lowerSet("commands.give-clear.bases", List.of(
                "give", "clear", "clearinventory", "clearinvent", "ci",
                "invgive", "invclear", "endergive", "enderclear"));

        this.rollbackPerms = plugin.lowerSet("commands.permissions.rollback",
                List.of("coreprotect.rollback"));
        this.enchantPerms = plugin.lowerSet("commands.permissions.enchant",
                List.of("minecraft.command.enchant", "essentials.enchant"));
        this.givePerms = plugin.lowerSet("commands.permissions.give",
                List.of("minecraft.command.give", "essentials.give"));
        this.clearPerms = plugin.lowerSet("commands.permissions.clear",
                List.of("minecraft.command.clear", "essentials.clearinventory"));

        this.watched = parseWatched(plugin);
    }

    /**
     * Parses the {@code commands.watch} config into base command -> {@link WatchEntry}.
     * Supports two forms:
     * <ul>
     *   <li>a simple list of names: {@code watch: [gamemode, op]}</li>
     *   <li>a map with a custom title/permissions per command:
     *       {@code watch: {gamemode: {title: "...", permissions: [...]}}}</li>
     * </ul>
     * If permissions are omitted, they default to {@code minecraft.command.<name>}.
     */
    private static Map<String, WatchEntry> parseWatched(DiscordSRVStaffLogger plugin) {
        Map<String, WatchEntry> result = new HashMap<>();
        Object raw = plugin.getConfig().get("commands.watch");

        if (raw instanceof List<?> list) { // simple form: [gamemode, op, ...]
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                addWatch(result, item.toString(), null, List.of());
            }
        } else if (raw instanceof ConfigurationSection section) { // advanced map form
            for (String key : section.getKeys(false)) {
                ConfigurationSection entry = section.getConfigurationSection(key);
                if (entry != null) {
                    addWatch(result, key, entry.getString("title"), entry.getStringList("permissions"));
                } else {
                    // bare "name: Some Title" (value is the title) or "name:" (null)
                    Object value = section.get(key);
                    String title = (value instanceof String s && !s.isBlank()) ? s : null;
                    addWatch(result, key, title, List.of());
                }
            }
        }
        return result;
    }

    private static void addWatch(Map<String, WatchEntry> map, String name, String title, List<String> perms) {
        String base = name.trim().toLowerCase(Locale.ROOT);
        if (base.isEmpty()) {
            return;
        }
        Set<String> nodes = new LinkedHashSet<>();
        for (String perm : perms) {
            nodes.add(perm.toLowerCase(Locale.ROOT));
        }
        if (nodes.isEmpty()) {
            nodes.add("minecraft.command." + base); // sensible default gate
        }
        String label = (title == null || title.isBlank()) ? "Command: /" + base : title;
        map.put(base, new WatchEntry(label, nodes));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        // getMessage() includes the leading slash.
        handle(event.getPlayer(), event.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        // getCommand() has no leading slash. Executor is console or a command block.
        handle(event.getSender(), event.getCommand());
    }

    private void handle(CommandSender sender, String raw) {
        if (raw == null) {
            return;
        }
        if (ignoreOps && sender instanceof Player player && player.isOp()) {
            return; // op actions are exempt from logging
        }
        String message = raw.startsWith("/") ? raw.substring(1) : raw;
        String[] parts = message.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return;
        }

        String base = parts[0].toLowerCase(Locale.ROOT);
        int colon = base.indexOf(':'); // strip namespace, e.g. minecraft:give -> give
        if (colon >= 0) {
            base = base.substring(colon + 1);
        }

        String executor = senderName(sender);
        String fullCommand = "/" + message;

        if (rollbacksEnabled && rollbackBases.contains(base) && parts.length >= 2) {
            String sub = parts[1].toLowerCase(Locale.ROOT);
            if (rollbackSubs.contains(sub)) {
                if (permitted(sender, rollbackPerms)) {
                    String action = (sub.equals("rollback") || sub.equals("rb")) ? "Rollback" : capitalize(sub);
                    notifier.send("CoreProtect " + action, DiscordNotifier.ROLLBACK_COLOR, DiscordSRVStaffLogger.fields(
                            "Staff", executor,
                            "Command", fullCommand));
                }
                return;
            }
        }

        boolean enchantMatch = enchantBases.contains(base)
                || (enchantSubcommandBases.contains(base)
                        && parts.length >= 2 && enchantSubcommands.contains(parts[1].toLowerCase(Locale.ROOT)));
        if (enchantsEnabled && enchantMatch) {
            if (permitted(sender, enchantPerms)) {
                notifier.send("Item Enchanted", DiscordNotifier.ENCHANT_COLOR, DiscordSRVStaffLogger.fields(
                        "Staff", executor,
                        "Command", fullCommand));
            }
            return;
        }

        if (giveClearEnabled && giveClearBases.contains(base)) {
            Set<String> nodes = GIVE_LIKE.contains(base) ? givePerms : clearPerms;
            String target = parts.length >= 2 ? parts[1] : null;
            if (targetsAnotherPlayer(sender, target) && permitted(sender, nodes)) {
                notifier.send("Inventory Changed (command)", DiscordNotifier.INVENTORY_COLOR, DiscordSRVStaffLogger.fields(
                        "Staff", executor,
                        "Target", target,
                        "Command", fullCommand));
            }
            return;
        }

        // Owner-defined extra commands (logged generically as "who ran what").
        WatchEntry entry = watched.get(base);
        if (entry != null && permitted(sender, entry.permissions())) {
            notifier.send(entry.title(), DiscordNotifier.WATCH_COLOR, DiscordSRVStaffLogger.fields(
                    "Staff", executor,
                    "Command", fullCommand));
        }
    }

    /**
     * True if the sender is allowed to run the command, so a no-permission attempt (which would
     * fail) is not logged. Console/command-block senders always pass; operators satisfy any
     * node. If no nodes are configured for an action, we don't suppress.
     */
    private boolean permitted(CommandSender sender, Collection<String> nodes) {
        if (!requirePermission || !(sender instanceof Player)) {
            return true;
        }
        if (nodes.isEmpty()) {
            return true;
        }
        for (String node : nodes) {
            if (sender.hasPermission(node)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the give/clear command affects a player other than the executor.
     * A {@code null} target (e.g. bare {@code /clear}) or a self-reference is treated as self.
     * Console/command-block senders are never a target player, so they always count as "another".
     */
    private boolean targetsAnotherPlayer(CommandSender sender, String target) {
        if (target == null || target.isBlank()) {
            return false; // no target argument -> acts on self
        }
        if (target.startsWith("@")) {
            // @s = self; any other selector (@a/@p/@r/@e) affects others.
            return !target.equalsIgnoreCase("@s");
        }
        if (sender instanceof Player player) {
            return !player.getName().equalsIgnoreCase(target);
        }
        return true; // console / command block acting on a named player
    }

    private static String capitalize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private String senderName(CommandSender sender) {
        if (sender instanceof Player player) {
            return player.getName();
        }
        if (sender instanceof ConsoleCommandSender) {
            return "Console";
        }
        if (sender instanceof BlockCommandSender block) {
            return "CommandBlock @ " + block.getBlock().getLocation().toVector();
        }
        return sender.getName();
    }
}
