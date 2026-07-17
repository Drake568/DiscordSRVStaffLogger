# CLAUDE.md — DiscordSRVStaffLogger

Guidance for working on this repo. Read the README for the user-facing feature list; this
file covers how the code is built and the non-obvious integration details.

## What this is

A Paper (Minecraft server) plugin that logs sensitive **staff actions** to a Discord channel
via **DiscordSRV**. Deliberately **no log file and no database** — Discord is the only sink.

Actions captured:
1. **Bans / unbans** — via the LiteBans API (`entryAdded` / `entryRemoved`).
2. **CoreProtect rollback / undo** — `/co rollback`, `/co rb`, `/co undo` (command detection).
3. **Enchanting** — `/enchant`, and Enchants Squared `/es enchant|give|remove` (command detection).
4. **Item add/remove on another player's inventory** — `/give` & `/clear` commands (only when
   targeting another player), plus InvSee++ GUI edits (snapshot-diff, with item name/lore/enchants).

## Build

Requires **JDK 21** and **Maven**. Paper 1.21's API is compiled to Java 21 bytecode, so a
JDK 17 (or lower) **cannot** compile against it.

Standard:
```bash
mvn clean package    # -> target/DiscordSRVStaffLogger-1.0.0.jar
```

This dev machine has no standalone Maven and no JDK 21 on `java_home`, but both exist elsewhere.
The build command that works here (IntelliJ's bundled Maven + Homebrew JDK 21):
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
  "/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" -B -ntp clean package
```

**Expected benign noise:** the build prints `[ERROR]`/`[WARNING]` lines about
`invsee-plus-plus_common`'s `systemPath` for `BungeePerms`/`UltraPermissionsAPI`. These come from
InvSee++'s own published POM, don't affect our compile, and the build still reports `BUILD SUCCESS`.
Filter with `| grep -v systemPath` when checking output.

There is **no test suite**; a successful compile is the only automated check. Real verification
requires a live Paper 1.21 server with the integration plugins installed (see README checklist).

## Layout

```
pom.xml                                  Java 21, all integrations `provided` (never shaded)
src/main/resources/plugin.yml            softdepend on all integration plugins; version filtered from pom
src/main/resources/config.yml            toggles, permission nodes, command keyword lists, watch list
src/main/java/com/discordsrvstafflogger/
  DiscordSRVStaffLogger.java                        JavaPlugin main; reads config, guards + wires each listener
  DiscordNotifier.java                    builds JDA embeds, resolves the DiscordSRV channel, sends
  listener/BanListener.java               LiteBans Events.Listener (ban + unban)
  listener/CommandListener.java           PlayerCommandPreprocessEvent + ServerCommandEvent
  listener/InventoryEditListener.java     InvSee++ spectator-inventory snapshot/diff
```

Every integration is `softdepend` + runtime-guarded (`getPlugin(name) != null`), so a missing
plugin only disables its path (with a one-time warning) rather than breaking the plugin.

## Integration gotchas (important)

**DiscordSRV** (`DiscordNotifier`)
- DiscordSRV **shades JDA 4** relocated to `github.scarsz.discordsrv.dependencies.jda.*`. Import
  JDA types from there, **never** `net.dv8tion.jda.*`.
- Send embeds with `channel.sendMessageEmbeds(embed).queue()`. `queue()` is async/thread-safe, so
  it's fine from the main thread or LiteBans' async thread. Never `.complete()` on the main thread.
- Get the channel via `DiscordSRV.getPlugin().getDestinationTextChannelForGameChannelName(name)`;
  the admin maps that name in **DiscordSRV's own** `config.yml` under `Channels:`. Returns null if
  unmapped or DiscordSRV hasn't connected yet — guard for it (we warn once).
- Do not send during `onEnable` (DiscordSRV connects to Discord asynchronously afterward). All our
  sends are event-driven, so this is satisfied naturally.
- Compile-time DiscordSRV version should match the server's so the relocated JDA classes line up.
  A runtime `NoSuchMethodError`/`NoClassDefFoundError` mentioning `jda` means version skew.

**LiteBans** (`BanListener`)
- Ban logging is the single source of truth: `entryAdded` (filter `"ban".equals(getType())`) and
  `entryRemoved` for unbans. Events fire **async**; `Bukkit.getOfflinePlayer(UUID)` is a local
  cache lookup so no main-thread hop is needed.
- "Log exactly once" depends on **LiteBans owning the ban commands** — the server must disable
  EssentialsX `/ban`/`/tempban`/`/banip` (via Essentials `disabled-commands`). Native Essentials
  bans do not reach LiteBans and would not be logged. This is a server-config prerequisite, not code.
- Unregister the listener in `onDisable`.

**InvSee++** (`InventoryEditListener`)
- Spectator inventories implement `com.janboerman.invsee.spigot.api.SpectatorInventory` (a real
  Bukkit `Inventory`), so plain `InventoryOpenEvent`/`InventoryCloseEvent` fire. Identify with
  `instanceof SpectatorInventory` and read `getSpectatedPlayerName()`/`getSpectatedPlayerId()`;
  `EnderSpectatorInventory` distinguishes ender chest from main inventory.
- We snapshot contents on open and diff on close (net add/remove per session; pure viewing/shuffling
  yields zero delta and isn't logged). Items are keyed by full identity (ItemStack cloned to amount
  1) so name/lore/enchants are tracked distinctly and rendered via Adventure's
  `PlainTextComponentSerializer`.
- API classes live in the `invsee-plus-plus_common` artifact.

**Command detection** (`CommandListener`)
- Listens at `EventPriority.MONITOR`, `ignoreCancelled = true`. Handles both
  `PlayerCommandPreprocessEvent` (leading `/`) and `ServerCommandEvent` (no leading `/`).
- Strips a `namespace:` prefix (`minecraft:give` -> `give`) before matching.
- **Permission gating:** `PlayerCommandPreprocessEvent` fires *before* the command's permission
  check, and vanilla commands report a `null` permission (so Bukkit's `testPermissionSilent` returns
  true for them — an earlier reflection-based attempt failed for exactly this reason). Instead we
  gate on an explicit, configurable set of permission nodes per action
  (`commands.permissions.*`), passing if the sender holds **any** node. Ops satisfy any node;
  console/command-block senders always pass.

## Adding a new tracked command

- **Plugin with an events API** (like bans): prefer the API over command-sniffing — structured data,
  fires once, only on success. Add a guarded listener wired in `DiscordSRVStaffLogger.onEnable`.
- **No API** (rollback/enchant/give): add it in `CommandListener`. Two shapes exist to copy:
  - whole-command (like `/enchant`): a base set + permission set.
  - base + subcommand (like `/co rollback`, `/es enchant`): base set + subcommand set.
  Make the keyword lists **configurable** in `config.yml` and read them via `plugin.lowerSet(path,
  defaults)`. Route to an existing embed "type" or add a color to `DiscordNotifier`.
- Owners can also add arbitrary commands without code via the `commands.watch` list (generic
  "who ran what" logging).

## Config caveat (matters when advising the user)

`config.yml` is written only on first run (`saveDefaultConfig`). New keys added to the default file
do **not** appear in an existing server's config. Behavior depends on the reader:
- Absent key -> code default applies (e.g. `log.unbans`, the `enchant.subcommand-bases` default `es`
  both work without editing an old config).
- Present-but-outdated key -> the old value **overrides** the new default (e.g. adding `undo` to the
  default `rollback.subcommands` did nothing for servers whose config already listed `[rollback, rb]`;
  they had to add `undo` or regenerate). Always tell the user which case applies for a given change.

## Dependency versions (pinned in pom.xml)

Paper `1.21.8-R0.1-SNAPSHOT`, DiscordSRV `1.30.5`, LiteBans API `0.6.1`, InvSee++
`0.31.1-SNAPSHOT` (Repsy). Verify latest against each repo before bumping. Package is
`com.discordsrvstafflogger`, `api-version: '1.21'`, Java 21.
