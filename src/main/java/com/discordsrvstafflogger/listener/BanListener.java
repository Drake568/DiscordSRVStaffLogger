package com.discordsrvstafflogger.listener;

import com.discordsrvstafflogger.DiscordNotifier;
import com.discordsrvstafflogger.DiscordSRVStaffLogger;
import litebans.api.Entry;
import litebans.api.Events;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Logs bans and unbans by subscribing to the LiteBans API. {@code entryAdded} fires once,
 * after a ban is committed to LiteBans' database, and {@code entryRemoved} fires when a ban
 * is lifted (an unban). Both carry structured data (executor, target, reason, duration). This
 * is the single source of truth for bans - see the README for the requirement that LiteBans
 * owns the ban commands so every ban/unban flows through it.
 *
 * <p>LiteBans fires these events on an async thread. {@link DiscordNotifier#send} is
 * async-safe, and {@link Bukkit#getOfflinePlayer(UUID)} is a local cache lookup, so no
 * main-thread hop is required here.
 */
public final class BanListener {

    private final DiscordSRVStaffLogger plugin;
    private final DiscordNotifier notifier;
    private final boolean logBans;
    private final boolean logUnbans;
    private final boolean ignoreOps;
    private final boolean ignoreConsole;
    private Events.Listener listener;

    public BanListener(DiscordSRVStaffLogger plugin, DiscordNotifier notifier, boolean logBans, boolean logUnbans) {
        this.plugin = plugin;
        this.notifier = notifier;
        this.logBans = logBans;
        this.logUnbans = logUnbans;
        this.ignoreOps = plugin.getConfig().getBoolean("ignore-ops", false);
        this.ignoreConsole = plugin.getConfig().getBoolean("ignore-console", false);
    }

    public void register() {
        listener = new Events.Listener() {
            @Override
            public void entryAdded(Entry entry) {
                if (!logBans || !"ban".equals(entry.getType())) {
                    return; // ignore mutes/warns/kicks (and unwanted bans)
                }
                try {
                    handleBan(entry);
                } catch (Throwable t) {
                    plugin.getLogger().warning("Failed to log LiteBans ban: " + t);
                }
            }

            @Override
            public void entryRemoved(Entry entry) {
                if (!logUnbans || !"ban".equals(entry.getType())) {
                    return; // only bans being lifted (not unmutes etc.)
                }
                try {
                    handleUnban(entry);
                } catch (Throwable t) {
                    plugin.getLogger().warning("Failed to log LiteBans unban: " + t);
                }
            }
        };
        Events.get().register(listener);
    }

    public void unregister() {
        if (listener != null) {
            Events.get().unregister(listener);
            listener = null;
        }
    }

    private void handleBan(Entry entry) {
        if (ignoreConsole && isConsoleExecutor(entry)) {
            return; // ban issued from console - exempt
        }
        if (ignoreOps && isOpByUuid(safeExecutorUuid(entry))) {
            return; // ban issued by an op - exempt
        }
        String staff = entry.getExecutorName();
        if (staff == null || staff.isBlank()) {
            staff = "Console";
        }

        String target = resolveTarget(entry);

        String reason = entry.getReason();
        if (reason == null || reason.isBlank()) {
            reason = "No reason provided";
        }

        String duration;
        try {
            duration = entry.isPermanent() ? "Permanent" : entry.getDurationString();
        } catch (Throwable t) {
            duration = "Unknown";
        }

        notifier.send("Player Banned", DiscordNotifier.BAN_COLOR, DiscordSRVStaffLogger.fields(
                "Staff", staff,
                "Player", target,
                "Reason", reason,
                "Duration", duration,
                "Type", safeIsIpBan(entry) ? "IP ban" : "Ban"));
    }

    private void handleUnban(Entry entry) {
        String staff = safeRemovedByName(entry);
        if (ignoreConsole && isConsoleName(staff)) {
            return; // unban issued from console - exempt
        }
        if (ignoreOps && isOpByName(staff)) {
            return; // unban issued by an op - exempt (online remover only; see isOpByName)
        }
        if (staff == null || staff.isBlank()) {
            staff = "Console";
        }

        notifier.send("Player Unbanned", DiscordNotifier.UNBAN_COLOR, DiscordSRVStaffLogger.fields(
                "Staff", staff,
                "Player", resolveTarget(entry),
                "Type", safeIsIpBan(entry) ? "IP ban" : "Ban"));
    }

    private String safeRemovedByName(Entry entry) {
        try {
            return entry.getRemovedByName();
        } catch (Throwable t) {
            return null;
        }
    }

    private String safeExecutorUuid(Entry entry) {
        try {
            return entry.getExecutorUUID();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Console executor = no UUID (LiteBans omits it for console) or an explicit "Console" name. */
    private boolean isConsoleExecutor(Entry entry) {
        String uuid = safeExecutorUuid(entry);
        if (uuid == null || uuid.isBlank()) {
            return true;
        }
        return isConsoleName(entry.getExecutorName());
    }

    /** Treats null/blank/"Console" as the console (LiteBans uses a null remover for system actions). */
    private boolean isConsoleName(String name) {
        return name == null || name.isBlank() || name.equalsIgnoreCase("Console");
    }

    /** Op check by UUID (used for the ban executor, whose UUID LiteBans provides). */
    private boolean isOpByUuid(String uuid) {
        try {
            if (uuid == null || uuid.isBlank()) {
                return false; // console/system - not an op player
            }
            return Bukkit.getOfflinePlayer(UUID.fromString(uuid)).isOp();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Op check by name (used for the unban remover, where only a name is exposed). Resolves the
     * online player only; an offline/console remover is treated as not-op and will be logged.
     */
    private boolean isOpByName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        Player player = Bukkit.getPlayerExact(name);
        return player != null && player.isOp();
    }

    /** Prefer a readable player name; fall back to UUID, then IP (for IP-only bans). */
    private String resolveTarget(Entry entry) {
        String uuid = entry.getUuid();
        if (uuid != null && !uuid.isBlank()) {
            try {
                OfflinePlayer op = Bukkit.getOfflinePlayer(UUID.fromString(uuid));
                if (op.getName() != null) {
                    return op.getName();
                }
            } catch (IllegalArgumentException ignored) {
                // not a valid UUID string - fall through
            }
            return uuid;
        }
        String ip = entry.getIp();
        return (ip != null && !ip.isBlank()) ? ip : "Unknown";
    }

    private boolean safeIsIpBan(Entry entry) {
        try {
            return entry.isIpban();
        } catch (Throwable t) {
            return false;
        }
    }
}
