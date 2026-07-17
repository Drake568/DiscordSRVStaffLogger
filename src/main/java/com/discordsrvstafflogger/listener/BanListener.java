package com.discordsrvstafflogger.listener;

import com.discordsrvstafflogger.DiscordNotifier;
import com.discordsrvstafflogger.DiscordSRVStaffLogger;
import litebans.api.Entry;
import litebans.api.Events;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

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
    private Events.Listener listener;

    public BanListener(DiscordSRVStaffLogger plugin, DiscordNotifier notifier, boolean logBans, boolean logUnbans) {
        this.plugin = plugin;
        this.notifier = notifier;
        this.logBans = logBans;
        this.logUnbans = logUnbans;
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
