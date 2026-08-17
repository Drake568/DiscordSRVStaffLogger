package com.discordsrvstafflogger.listener;

import com.janboerman.invsee.spigot.api.EnderSpectatorInventory;
import com.janboerman.invsee.spigot.api.SpectatorInventory;
import com.discordsrvstafflogger.DiscordNotifier;
import com.discordsrvstafflogger.DiscordSRVStaffLogger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Logs when a staff member adds or removes items from another player's inventory through
 * the InvSee++ GUI (e.g. {@code /invsee}, {@code /endersee}).
 *
 * <p>InvSee++'s spectator inventories implement {@link SpectatorInventory}, which is a real
 * Bukkit {@link Inventory} <b>backed directly by the target's live inventory</b> (for online
 * targets). That means its contents can change for reasons that have nothing to do with the
 * staff member watching it - the target equipping/unequipping gear, picking up or consuming
 * items, mob-drop pickups, armor/tools breaking, or dying - all while the GUI happens to be
 * open. Diffing a single snapshot taken on open against one taken on close (as this used to do)
 * attributes all of that incidental drift to the staff member.
 *
 * <p>Instead we diff around each individual {@link InventoryClickEvent}/{@link
 * InventoryDragEvent} the staff member performs on the GUI: snapshot immediately before the
 * click is applied, snapshot again a tick later once it has been applied, and accumulate just
 * that click's delta into a running per-session total. Passive changes to the live inventory that
 * happen between clicks (or during a session with no clicks at all) never get diffed, so they're
 * never misattributed. The accumulated total is reported when the GUI closes; a session with no
 * net change is not logged.
 *
 * <p>Items are keyed by their full identity (material + display name + lore + enchants + other
 * meta), so a renamed/enchanted item is tracked distinctly and its details are included in the
 * log line.
 */
public final class InventoryEditListener implements Listener {

    private static final int MAX_LORE_CHARS = 200;

    private final DiscordSRVStaffLogger plugin;
    private final DiscordNotifier notifier;
    private final boolean ignoreOps;
    private final Set<String> ignoredStaff;

    // One open spectator session per viewer at a time -> keyed by the viewer's UUID.
    // Value accumulates the net add/remove delta from each click, across the whole session.
    private final Map<UUID, Map<ItemStack, Integer>> sessionDeltas = new HashMap<>();

    public InventoryEditListener(DiscordSRVStaffLogger plugin, DiscordNotifier notifier) {
        this.plugin = plugin;
        this.notifier = notifier;
        this.ignoreOps = plugin.getConfig().getBoolean("ignore-ops", false);
        this.ignoredStaff = plugin.lowerSet("ignored-staff", List.of());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onOpen(InventoryOpenEvent event) {
        if (ignoreOps && event.getPlayer().isOp()) {
            return; // op edits are exempt - don't even track
        }
        if (ignoredStaff.contains(event.getPlayer().getName().toLowerCase(Locale.ROOT))) {
            return; // explicitly exempted staff member - don't even track
        }
        Inventory top = event.getView().getTopInventory();
        if (top instanceof SpectatorInventory) {
            sessionDeltas.put(event.getPlayer().getUniqueId(), new LinkedHashMap<>());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        trackClick(event.getWhoClicked(), event.getView().getTopInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        trackClick(event.getWhoClicked(), event.getView().getTopInventory());
    }

    /** Diffs just this one click's effect on the spectator inventory into the session total. */
    private void trackClick(HumanEntity viewer, Inventory top) {
        Map<ItemStack, Integer> accumulator = sessionDeltas.get(viewer.getUniqueId());
        if (accumulator == null || !(top instanceof SpectatorInventory)) {
            return;
        }
        Map<ItemStack, Integer> before = countItems(top);
        // The click hasn't been applied to the inventory yet at MONITOR priority - the server
        // applies it right after event dispatch finishes. Re-snapshot next tick and merge just
        // this click's delta, so drift from the target's own concurrent play never gets counted.
        Bukkit.getScheduler().runTask(plugin, () -> {
            Map<ItemStack, Integer> after = countItems(top);
            merge(accumulator, diff(before, after));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        HumanEntity viewer = event.getPlayer();
        if (!sessionDeltas.containsKey(viewer.getUniqueId())) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        if (!(top instanceof SpectatorInventory)) {
            sessionDeltas.remove(viewer.getUniqueId());
            return;
        }
        SpectatorInventory<?> spectator = (SpectatorInventory<?>) top;

        // Defer by a tick so a click's post-close diff (scheduled above) has merged in first.
        Bukkit.getScheduler().runTask(plugin, () -> report(viewer, spectator, sessionDeltas.remove(viewer.getUniqueId())));
    }

    private void report(HumanEntity viewer, SpectatorInventory<?> spectator, Map<ItemStack, Integer> delta) {
        if (delta == null || delta.isEmpty()) {
            return; // only viewed / rearranged - nothing changed
        }

        StringJoiner added = new StringJoiner("\n");
        StringJoiner removed = new StringJoiner("\n");
        for (Map.Entry<ItemStack, Integer> entry : delta.entrySet()) {
            int amount = entry.getValue();
            String line = Math.abs(amount) + "x " + describe(entry.getKey());
            if (amount > 0) {
                added.add(line);
            } else {
                removed.add(line);
            }
        }

        String kind = (spectator instanceof EnderSpectatorInventory) ? "Ender Chest" : "Inventory";

        notifier.send("Inventory Edited via InvSee (" + kind + ")", DiscordNotifier.INVENTORY_COLOR,
                DiscordSRVStaffLogger.fields(
                        "Staff", viewer.getName(),
                        "Target", spectator.getSpectatedPlayerName(),
                        "Added", added.length() == 0 ? null : added.toString(),
                        "Removed", removed.length() == 0 ? null : removed.toString()));
    }

    /** Maps each distinct item (normalised to a single unit) to the total amount present. */
    private Map<ItemStack, Integer> countItems(Inventory inventory) {
        Map<ItemStack, Integer> counts = new HashMap<>();
        for (ItemStack item : inventory.getContents()) {
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }
            ItemStack key = item.clone();
            key.setAmount(1); // ignore stack size when grouping; sum real amounts as the value
            counts.merge(key, item.getAmount(), Integer::sum);
        }
        return counts;
    }

    /** after - before, keeping only item identities whose net count changed. */
    private Map<ItemStack, Integer> diff(Map<ItemStack, Integer> before, Map<ItemStack, Integer> after) {
        Map<ItemStack, Integer> delta = new LinkedHashMap<>();
        Set<ItemStack> keys = new HashSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        for (ItemStack key : keys) {
            int change = after.getOrDefault(key, 0) - before.getOrDefault(key, 0);
            if (change != 0) {
                delta.put(key, change);
            }
        }
        return delta;
    }

    /** Adds a single click's delta into the running session total, dropping entries that net to zero. */
    private void merge(Map<ItemStack, Integer> accumulator, Map<ItemStack, Integer> clickDelta) {
        for (Map.Entry<ItemStack, Integer> entry : clickDelta.entrySet()) {
            accumulator.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
        accumulator.values().removeIf(amount -> amount == 0);
    }

    /** Renders a readable item description: material, custom name, enchants, and lore. */
    private String describe(ItemStack item) {
        StringBuilder sb = new StringBuilder(item.getType().getKey().getKey());

        ItemMeta meta = item.hasItemMeta() ? item.getItemMeta() : null;
        if (meta != null) {
            if (meta.hasDisplayName()) {
                String name = plain(meta.displayName());
                if (!name.isBlank()) {
                    sb.append(" \"").append(name).append('"');
                }
            }

            String enchants = enchantString(item, meta);
            if (!enchants.isEmpty()) {
                sb.append(" [").append(enchants).append(']');
            }

            if (meta.hasLore()) {
                List<Component> lore = meta.lore();
                if (lore != null && !lore.isEmpty()) {
                    String loreText = lore.stream()
                            .map(this::plain)
                            .filter(s -> !s.isBlank())
                            .collect(Collectors.joining(" / "));
                    if (loreText.length() > MAX_LORE_CHARS) {
                        loreText = loreText.substring(0, MAX_LORE_CHARS - 3) + "...";
                    }
                    if (!loreText.isBlank()) {
                        sb.append(" | lore: ").append(loreText);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** Active enchants plus stored enchants (enchanted books), as "name level, ...". */
    private String enchantString(ItemStack item, ItemMeta meta) {
        Map<Enchantment, Integer> enchants = new LinkedHashMap<>(item.getEnchantments());
        if (meta instanceof EnchantmentStorageMeta stored) {
            enchants.putAll(stored.getStoredEnchants());
        }
        if (enchants.isEmpty()) {
            return "";
        }
        return enchants.entrySet().stream()
                .map(e -> e.getKey().getKey().getKey() + " " + e.getValue())
                .collect(Collectors.joining(", "));
    }

    private String plain(Component component) {
        return component == null ? "" : PlainTextComponentSerializer.plainText().serialize(component);
    }
}
