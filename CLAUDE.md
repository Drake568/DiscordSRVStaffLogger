# CLAUDE.md — DiscordSRVStaffLogger

Guidance for working on this repo. Read the README for the user-facing feature list; this
file covers how the code is built and the non-obvious integration details.

## What this is

A Paper (Minecraft server) plugin that logs sensitive **staff actions** to a Discord channel
via **DiscordSRV**. Deliberately **no log file and no database** — Discord is the only sink.

Actions captured:
1. **Bans / unbans / mutes / unmutes** — via the LiteBans API (`entryAdded` / `entryRemoved`).
   These four are logged **unconditionally** — `ignore-ops`, `ignore-console`, and
   `ignored-staff` do not apply to them, unlike every other action type below.
2. **CoreProtect rollback / undo** — `/co rollback`, `/co rb`, `/co undo` (command detection).
3. **Enchanting** — `/enchant`, and Enchants Squared `/es enchant|give|remove` (command detection).
4. **Item add/remove on another player's inventory** — `/give` & `/clear` commands (only when
   targeting another player), plus InvSee++ GUI edits (per-click diff, with item name/lore/enchants).

Staff members can be exempted from action types 2-4 via `ignored-staff` (username list) or
`ignore-ops`/`ignore-console` in `config.yml` - these exemptions never apply to bans/mutes (1).
`/staffloggerreload` (alias `/dslreload`, permission `discordsrvstafflogger.reload`) applies
config edits without restarting the server.

## Build

Requires **JDK 25** and **Maven**. Paper 26.2's API is compiled to Java 25 bytecode, so a
JDK 21 (or lower) **cannot** compile against it — confirmed locally: compiling against the
`paper-api` jar with a JDK 21 `javac` fails with `class file has wrong version 69.0, should be
65.0`.

Standard:
```bash
mvn clean package    # -> target/DiscordSRVStaffLogger-1.4.1.jar
```

Standalone Maven is available on this dev machine via `sdkman` (`mvn` on `PATH`, Apache Maven
3.9.16). JDK 25 is installed via Homebrew (`openjdk@25`, keg-only — not symlinked into
`/opt/homebrew`). The build command that works here:
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@25 mvn -B -ntp clean package
```

There is **no test suite**; a successful compile is the only automated check. Real verification
requires a live Paper 26.2 server with the integration plugins installed (see README checklist) —
note InvSee++ needs v0.31.15+ and CoreProtect's latest stable release has open reports of failing
to load on 26.2 (see the "Dependency versions" section below).

## Layout

```
pom.xml                                  Java 25, all integrations `provided` (never shaded)
libs/                                     vendored local Maven repo (InvSee++ API jar - see libs/README.md)
src/main/resources/plugin.yml            softdepend on all integration plugins; version filtered from pom;
                                          declares /staffloggerreload + its permission node
src/main/resources/config.yml            toggles, permission nodes, command keyword lists, watch list,
                                          ignore-ops/ignore-console/ignored-staff exemptions
src/main/java/com/discordsrvstafflogger/
  DiscordSRVStaffLogger.java                        JavaPlugin main; wireListeners()/unwireListeners() build +
                                                     tear down every listener from config, so /staffloggerreload
                                                     can re-run them cleanly; onCommand() handles the reload
  DiscordNotifier.java                    builds JDA embeds, resolves the DiscordSRV channel, sends
  listener/LiteBansListener.java           LiteBans Events.Listener (ban + unban + mute + unmute)
  listener/CommandListener.java           PlayerCommandPreprocessEvent + ServerCommandEvent
  listener/InventoryEditListener.java     InvSee++ spectator-inventory per-click diff
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

**LiteBans** (`LiteBansListener`)
- Ban/mute logging is the single source of truth: `entryAdded` (filter on `getType()` being
  `"ban"` or `"mute"`) and `entryRemoved` for unbans/unmutes. Events fire **async**;
  `Bukkit.getOfflinePlayer(UUID)` is a local cache lookup so no main-thread hop is needed.
- "Log exactly once" depends on **LiteBans owning the ban/mute commands** — the server must
  disable EssentialsX `/ban`/`/tempban`/`/banip`/`/mute`/`/tempmute` (via Essentials
  `disabled-commands`). Native Essentials bans/mutes do not reach LiteBans and would not be
  logged. This is a server-config prerequisite, not code.
- These four entry types bypass every exemption (`ignore-ops`, `ignore-console`,
  `ignored-staff`) by design — `LiteBansListener` has no knowledge of them at all. If asked to
  make bans/mutes respect an exemption again, that's a deliberate behavior change, not a bug fix.
- Unregister the listener in `onDisable`.

**InvSee++** (`InventoryEditListener`)
- Spectator inventories implement `com.janboerman.invsee.spigot.api.SpectatorInventory` (a real
  Bukkit `Inventory`), so plain `InventoryOpenEvent`/`InventoryCloseEvent` fire. Identify with
  `instanceof SpectatorInventory` and read `getSpectatedPlayerName()`/`getSpectatedPlayerId()`;
  `EnderSpectatorInventory` distinguishes ender chest from main inventory.
- Spectator inventories are backed directly by the target's **live** inventory (for online targets),
  so diffing a single open-vs-close snapshot would misattribute the target's own concurrent play
  (mob drops, gear breaking, death, ...) to the staff member. Instead we diff around each individual
  `InventoryClickEvent`/`InventoryDragEvent`: snapshot immediately before the click applies, snapshot
  again a tick later, and accumulate just that click's delta into a running per-session total, reported
  on close. A session with no clicks (or a net-zero delta) isn't logged. Items are keyed by full
  identity (ItemStack cloned to amount 1) so name/lore/enchants are tracked distinctly and rendered
  via Adventure's `PlainTextComponentSerializer`.
- API classes live in the `invsee-plus-plus_common` artifact, which is **vendored locally** under
  `libs/` (see `libs/README.md`) rather than pulled from Repsy - that repo now requires auth for
  every request, including anonymous metadata reads.

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
  fires once, only on success. Add a guarded listener wired in `DiscordSRVStaffLogger.wireListeners()`
  (and torn down in `unwireListeners()`, so `/staffloggerreload` picks it up).
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
- Absent key -> code default applies (e.g. `log.unbans`, `log.mutes`/`log.unmutes`, and the
  `enchant.subcommand-bases` default `es` all work without editing an old config).
- Present-but-outdated key -> the old value **overrides** the new default (e.g. adding `undo` to the
  default `rollback.subcommands` did nothing for servers whose config already listed `[rollback, rb]`;
  they had to add `undo` or regenerate). Always tell the user which case applies for a given change.

## Dependency versions (pinned in pom.xml)

Paper `26.2.build.121-stable` (re-check papermc's maven-metadata.xml for the newest `*-stable`
build before bumping further), DiscordSRV `1.30.5`, LiteBans API `0.6.1`, InvSee++
`0.31.22` (vendored locally under `libs/`, re-packaged from the public GitHub Release jar since
Repsy now requires auth - see `libs/README.md`; compile-time API only — the server's InvSee++
*plugin* separately needs v0.31.15+ to load on 26.2). CoreProtect's latest stable release (v24.0) has open reports
of failing to load on 26.2 ([PlayPro/CoreProtect#919](https://github.com/PlayPro/CoreProtect/issues/919));
this plugin has no compile dependency on it (command-string matching only in `CommandListener`),
so it's an operational caveat, not a code concern. Verify latest against each repo before
bumping. Package is `com.discordsrvstafflogger`, `api-version: '26.2'`, Java 25.
