# DiscordSRVStaffLogger

A lightweight Paper plugin that watches four sensitive **staff actions** and posts a record
of each into a Discord channel (through DiscordSRV). No log file, no database — Discord is the
only sink.

## What it logs

| # | Action | How it's detected |
|---|--------|-------------------|
| 1 | A staff member **bans** or **unbans** a player | LiteBans API (`entryAdded` / `entryRemoved`) — structured staff/target/reason/duration, logged exactly once |
| 2 | A staff member runs a **CoreProtect rollback / undo** | `/co rollback`, `/co rb`, `/co undo` (configurable bases/subcommands) |
| 3 | A staff member **enchants** an item | `/enchant`, and Enchants Squared `/es enchant\|give\|remove` |
| 4 | A staff member **adds/removes items from another player's inventory** | `/give` & `/clear` commands (only when targeting *another* player) **and** live edits through the InvSee++ GUI (net add/remove per session, including item name/lore/enchants) |

You can also log **any extra command** generically via the `commands.watch` list in `config.yml`.
Failed no-permission command attempts are not logged (configurable per action).

## Requirements

- **Server:** Paper **1.21.x** (runs on Java 21).
- **Plugins on the server** (each is optional; a missing one just disables its path with a warning):
  - **DiscordSRV** — required for anything to be posted.
  - **LiteBans** — for ban logging.
  - **InvSeePlusPlus (InvSee++)** — for GUI inventory-edit logging.
  - CoreProtect / EssentialsX — the commands being watched (no dependency needed).

## Building

This targets Paper 1.21, whose API is compiled to **Java 21 bytecode**, so you need a
**JDK 21** to build (a JDK 17 will fail to read the Paper API). This project uses **Maven**.

**Option A — IntelliJ (matches the existing project):**
1. `File ▸ Open` → select this folder's `pom.xml` → *Open as Project*.
2. `File ▸ Project Structure ▸ SDK` → add/download a **JDK 21** and set it as the project SDK.
3. Open the Maven tool window → run **`clean` + `package`** (or the `mvn` command below).

**Option B — command line:** install a JDK 21 and Maven, then:
```bash
mvn clean package
```

The built jar is `target/DiscordSRVStaffLogger-1.0.0.jar`. Drop it in your server's `plugins/` folder.

> During the build you'll see a couple of `[ERROR]`/`[WARNING]` lines about
> `invsee-plus-plus_common`'s `systemPath` for `BungeePerms`/`UltraPermissionsAPI`. These come
> from InvSee++'s own published POM and are **harmless** — they don't affect our compile and
> the build still reports `BUILD SUCCESS`.

> Dependency versions are pinned in `pom.xml` (Paper `1.21.8`, DiscordSRV `1.30.5`,
> LiteBans API `0.6.1`, InvSee++ `0.31.1-SNAPSHOT`). Bump them there if needed; the InvSee++
> and Paper artifacts come from snapshot repos.

## Setup

Do these in order on your Paper 1.21 server.

### 1. Install the plugins
Put each plugin **jar directly in `plugins/`** — **not** inside a subfolder. A folder like
`plugins/DiscordSRV/` is only the plugin's *data/config* directory; a jar placed there will not
load. (This is the most common setup mistake — the plugin appears "installed" but never shows in
`/plugins`.)

- **DiscordSRV** — required for anything to be posted.
- **LiteBans** — for ban/unban logging.
- **InvSeePlusPlus (InvSee++)** — for GUI inventory-edit logging.
- **CoreProtect**, **EssentialsX**, **Enchants Squared** — the plugins whose commands are watched
  (no build dependency; only needed if you use those features).
- **DiscordSRVStaffLogger** — build it (see above) and drop `DiscordSRVStaffLogger-1.0.0.jar` in.

Restart the server (a full stop/start, not `/reload`) and run `/plugins` to confirm each is
loaded (green). DiscordSRVStaffLogger logs a line per integration on enable, e.g.
`Hooked into LiteBans - ban/unban logging active.`

### 2. Set up DiscordSRV (bot + connection)
If DiscordSRV isn't already running, follow the official guide to create the bot, invite it, and
connect it: **https://docs.discordsrv.com/installation/initial-setup/#install-the-plugin**.
When it's working you'll see `DiscordSRV is ready` (or a "connected" line) in the console. Nothing
DiscordSRVStaffLogger sends will appear until DiscordSRV is connected.

### 3. Point DiscordSRVStaffLogger at a Discord channel
DiscordSRVStaffLogger posts through DiscordSRV, so the target channel is mapped in **DiscordSRV's own**
`config.yml` (`plugins/DiscordSRV/config.yml`) under `Channels:` — key is a name you choose, value
is the Discord channel ID (enable Discord Developer Mode, right-click the channel → *Copy Channel ID*):
```yaml
Channels: {"global": "1111111111", "staff-log": "2222222222"}
```
Then set the matching name in **DiscordSRVStaffLogger's** `config.yml` (`plugins/DiscordSRVStaffLogger/config.yml`):
```yaml
discord:
  channel-name: "staff-log"
```
Make sure the DiscordSRV bot has **View Channel**, **Send Messages**, and **Embed Links** in that
channel. If the name isn't mapped, DiscordSRVStaffLogger warns
`DiscordSRV has no channel mapped to "staff-log"...` and skips — a clear signal to fix the mapping.

### 4. Make LiteBans the ban authority (for exactly-once ban logging)
Ban logging reads LiteBans' events, which only fire for bans that go through LiteBans. With
EssentialsX also installed, disable its ban commands so every ban/unban flows through LiteBans —
in `plugins/Essentials/config.yml`:
```yaml
disabled-commands:
- ban
- unban
- banip
- unbanip
- tempban
```
Then full-restart and verify LiteBans owns `/ban` (compare `/ban` and `/litebans:ban` output).
(This is the standard LiteBans setup.)

### 5. Tune DiscordSRVStaffLogger's config
`plugins/DiscordSRVStaffLogger/config.yml` lets you:
- enable/disable each action (`log.*`),
- **ignore actions by op players** (`ignore-ops: true`) — audit only lower-ranked staff,
- **ignore console / command-block actions** (`ignore-console: true`),
- set the permission nodes that gate each command action (`commands.permissions.*`),
- edit the matched command bases/subcommands/aliases (`commands.rollback`, `commands.enchant`, …),
- add extra commands to log generically (`commands.watch`).

> **Editing an existing config:** `config.yml` is only generated on first run, so new options
> added in an update won't appear in your existing file automatically. Add the keys you want by
> hand, or delete `config.yml` and restart to regenerate the fully-commented default.

## Verifying end-to-end

On a Paper 1.21 test server with DiscordSRV, LiteBans, InvSee++, EssentialsX, CoreProtect and
Enchants Squared installed and the channel mapped, run each and confirm a single Discord embed appears:

- `/ban <player> <reason>` → **Player Banned** (staff / target / reason / duration).
- `/unban <player>` → **Player Unbanned**.
- `/co rollback u:<player> t:1h` → **CoreProtect Rollback**; `/co undo` → **CoreProtect Undo**.
- `/enchant <player> sharpness 5` → **Item Enchanted**; `/es enchant …` → **Item Enchanted**.
- `/give <otherplayer> diamond 64` → **Inventory Changed (command)**; `/give <yourself> ...`
  or self-give → **no** post.
- `/invsee <player>`, add or remove an item, close the GUI → **Inventory Edited via InvSee**
  showing the net items (with name/lore/enchants); opening and closing without changes → **no** post.
- As a **non-op with no permission**, run one of the tracked commands → **no** post.

Watch the server console for guard warnings if DiscordSRV / the channel / LiteBans / InvSee++
are missing.

## License

MIT — see [LICENSE](LICENSE).

This plugin only *hooks* the following plugins at runtime (via `provided`-scope APIs; it never
bundles or redistributes them), and they retain their own licenses: DiscordSRV (GPL-3.0),
InvSee++ (LGPL-2.1), the Paper API, and LiteBans (commercial; developer API used as published).
