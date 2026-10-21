package com.discordsrvstafflogger.listener;

import com.discordsrvstafflogger.DiscordNotifier;
import com.discordsrvstafflogger.DiscordSRVStaffLogger;
import litebans.api.Entry;
import litebans.api.Events;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.UUID;

/**
 * Logs bans, unbans, mutes, and unmutes by subscribing to the LiteBans API. {@code entryAdded}
 * fires once, after an entry is committed to LiteBans' database, and {@code entryRemoved} fires
 * when one is lifted. Both carry structured data (executor, target, reason, duration). This is
 * the single source of truth for bans/mutes - see the README for the requirement that LiteBans
 * owns the ban/mute commands so every one of them flows through it.
 *
 * <p>Unlike every other action type in this plugin, these four are logged unconditionally -
 * {@code ignore-ops}, {@code ignore-console}, and {@code ignored-staff} do not apply here, since
 * bans/mutes are considered too security-critical to ever suppress, even for trusted staff.
 *
 * <p>LiteBans fires these events on an async thread. {@link DiscordNotifier#send} is
 * async-safe, and {@link Bukkit#getOfflinePlayer(UUID)} is a local cache lookup, so no
 * main-thread hop is required here.
 */
public final class LiteBansListener {

    private final DiscordSRVStaffLogger plugin;
    private final DiscordNotifier notifier;
    private final boolean logBans;
    private final boolean logUnbans;
    private final boolean logMutes;
    private final boolean logUnmutes;
    private Events.Listener listener;

    public LiteBansListener(DiscordSRVStaffLogger plugin, DiscordNotifier notifier,
                             boolean logBans, boolean logUnbans, boolean logMutes, boolean logUnmutes) {
        this.plugin = plugin;
        this.notifier = notifier;
        this.logBans = logBans;
        this.logUnbans = logUnbans;
        this.logMutes = logMutes;
        this.logUnmutes = logUnmutes;
    }

    public void register() {
        listener = new Events.Listener() {
            @Override
            public void entryAdded(Entry entry) {
                try {
                    if (logBans && "ban".equals(entry.getType())) {
                        handleBan(entry);
                    } else if (logMutes && "mute".equals(entry.getType())) {
                        handleMute(entry);
                    }
                } catch (Throwable t) {
                    plugin.getLogger().warning("Failed to log LiteBans entry: " + t);
                }
            }

            @Override
            public void entryRemoved(Entry entry) {
                try {
                    if (logUnbans && "ban".equals(entry.getType())) {
                        handleUnban(entry);
                    } else if (logUnmutes && "mute".equals(entry.getType())) {
                        handleUnmute(entry);
                    }
                } catch (Throwable t) {
                    plugin.getLogger().warning("Failed to log LiteBans entry removal: " + t);
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
        notifier.send("Player Banned", DiscordNotifier.BAN_COLOR, DiscordSRVStaffLogger.fields(
                "Staff", staffName(entry.getExecutorName()),
                "Player", resolveTarget(entry),
                "Reason", reasonOrDefault(entry.getReason()),
                "Duration", duration(entry),
                "Type", safeIsIpBan(entry) ? "IP ban" : "Ban"));
    }

    private void handleUnban(Entry entry) {
        notifier.send("Player Unbanned", DiscordNotifier.UNBAN_COLOR, DiscordSRVStaffLogger.fields(
                "Staff", staffName(safeRemovedByName(entry)),
                "Player", resolveTarget(entry),
                "Type", safeIsIpBan(entry) ? "IP ban" : "Ban"));
    }

    private void handleMute(Entry entry) {
        notifier.send("Player Muted", DiscordNotifier.MUTE_COLOR, DiscordSRVStaffLogger.fields(
                "Staff", staffName(entry.getExecutorName()),
                "Player", resolveTarget(entry),
                "Reason", reasonOrDefault(entry.getReason()),
                "Duration", duration(entry)));
    }

    private void handleUnmute(Entry entry) {
        notifier.send("Player Unmuted", DiscordNotifier.UNMUTE_COLOR, DiscordSRVStaffLogger.fields(
                "Staff", staffName(safeRemovedByName(entry)),
                "Player", resolveTarget(entry)));
    }

    private String staffName(String name) {
        return (name == null || name.isBlank()) ? "Console" : name;
    }

    private String reasonOrDefault(String reason) {
        return (reason == null || reason.isBlank()) ? "No reason provided" : reason;
    }

    private String duration(Entry entry) {
        try {
            return entry.isPermanent() ? "Permanent" : entry.getDurationString();
        } catch (Throwable t) {
            return "Unknown";
        }
    }

    private String safeRemovedByName(Entry entry) {
        try {
            return entry.getRemovedByName();
        } catch (Throwable t) {
            return null;
        }
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
