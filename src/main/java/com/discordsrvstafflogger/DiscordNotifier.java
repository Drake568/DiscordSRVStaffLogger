package com.discordsrvstafflogger;

import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.dependencies.jda.api.EmbedBuilder;
import github.scarsz.discordsrv.dependencies.jda.api.entities.MessageEmbed;
import github.scarsz.discordsrv.dependencies.jda.api.entities.TextChannel;
import org.bukkit.Bukkit;

import java.awt.Color;
import java.time.Instant;
import java.util.Map;

/**
 * Sends staff-action records to Discord through DiscordSRV.
 *
 * <p>DiscordSRV ships a relocated fork of JDA 4, so the JDA types are imported from
 * {@code github.scarsz.discordsrv.dependencies.jda.*} (never {@code net.dv8tion.jda.*}),
 * and messages are sent with {@code sendMessage(MessageEmbed)} (JDA 4) rather than
 * {@code sendMessageEmbeds(...)} (JDA 5).
 *
 * <p>{@code queue()} dispatches asynchronously on JDA's own threads, so this is safe to
 * call from the main server thread or from LiteBans' async event thread.
 */
public final class DiscordNotifier {

    // Palette per action.
    public static final Color BAN_COLOR       = new Color(0xE74C3C); // red
    public static final Color UNBAN_COLOR     = new Color(0x2ECC71); // green
    public static final Color ROLLBACK_COLOR  = new Color(0xE67E22); // orange
    public static final Color ENCHANT_COLOR   = new Color(0x1ABC9C); // aqua
    public static final Color INVENTORY_COLOR = new Color(0xF1C40F); // gold
    public static final Color WATCH_COLOR     = new Color(0x5865F2); // blurple (custom watched commands)

    private final DiscordSRVStaffLogger plugin;
    private final String channelName;
    private boolean warnedMissingDiscordSRV = false;
    private boolean warnedMissingChannel = false;

    public DiscordNotifier(DiscordSRVStaffLogger plugin, String channelName) {
        this.plugin = plugin;
        this.channelName = channelName;
    }

    /**
     * Builds and sends an embed. Fields are rendered in insertion order (pass a
     * {@link java.util.LinkedHashMap}). Blank/absent values are skipped. Never throws.
     */
    public void send(String title, Color color, Map<String, String> fields) {
        if (Bukkit.getPluginManager().getPlugin("DiscordSRV") == null) {
            if (!warnedMissingDiscordSRV) {
                plugin.getLogger().warning("DiscordSRV is not installed - staff action logs will not be posted.");
                warnedMissingDiscordSRV = true;
            }
            return;
        }
        try {
            DiscordSRV srv = DiscordSRV.getPlugin();
            if (srv == null) {
                return; // DiscordSRV present but not yet enabled
            }
            TextChannel channel = srv.getDestinationTextChannelForGameChannelName(channelName);
            if (channel == null) {
                if (!warnedMissingChannel) {
                    plugin.getLogger().warning("DiscordSRV has no channel mapped to \"" + channelName
                            + "\". Add it to DiscordSRV's config.yml under Channels, or update discord.channel-name.");
                    warnedMissingChannel = true;
                }
                return;
            }

            EmbedBuilder embed = new EmbedBuilder()
                    .setTitle(title)
                    .setColor(color)
                    .setTimestamp(Instant.now());
            for (Map.Entry<String, String> field : fields.entrySet()) {
                String value = field.getValue();
                if (value == null || value.isBlank()) {
                    continue;
                }
                // Discord caps a field value at 1024 chars.
                if (value.length() > 1024) {
                    value = value.substring(0, 1021) + "...";
                }
                embed.addField(field.getKey(), value, false);
            }

            MessageEmbed built = embed.build();
            channel.sendMessageEmbeds(built).queue(
                    null,
                    error -> plugin.getLogger().warning("Failed to deliver DiscordSRVStaffLogger message to Discord: " + error.getMessage()));
        } catch (Throwable t) {
            // Never let logging break the action that triggered it.
            plugin.getLogger().warning("Error while sending DiscordSRVStaffLogger message to Discord: " + t);
        }
    }
}
