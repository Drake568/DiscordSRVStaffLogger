# StaffLogger

A lightweight Paper plugin that watches four sensitive **staff actions** and posts a record
of each into a Discord channel (through DiscordSRV). No log file, no database — Discord is the
only sink.

## What it logs

| # | Action | How it's detected |
|---|--------|-------------------|
| 1 | A staff member **bans** a player | LiteBans API (`Events.Listener.entryAdded`) — structured staff/target/reason/duration, logged exactly once |
| 2 | A staff member runs a **CoreProtect rollback** | `/co rollback` command (configurable bases/subcommands) |
| 3 | A staff member **enchants** an item | `/enchant` command |
| 4 | A staff member **adds/removes items from another player's inventory** | `/give` & `/clear` commands (only when targeting *another* player) **and** live edits through the InvSee++ GUI (net add/remove per session) |

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

The built jar is `target/StaffLogger-1.0.0.jar`. Drop it in your server's `plugins/` folder.

> During the build you'll see a couple of `[ERROR]`/`[WARNING]` lines about
> `invsee-plus-plus_common`'s `systemPath` for `BungeePerms`/`UltraPermissionsAPI`. These come
> from InvSee++'s own published POM and are **harmless** — they don't affect our compile and
> the build still reports `BUILD SUCCESS`.

> Dependency versions are pinned in `pom.xml` (Paper `1.21.8`, DiscordSRV `1.30.5`,
> LiteBans API `0.6.1`, InvSee++ `0.31.1-SNAPSHOT`). Bump them there if needed; the InvSee++
> and Paper artifacts come from snapshot repos.

## Configuration

### 1. Point StaffLogger at a Discord channel (via DiscordSRV)
StaffLogger sends through DiscordSRV, so you map the channel in **DiscordSRV's** `config.yml`
under `Channels:` (key = a name you choose, value = the Discord channel ID):
```yaml
Channels: {"global": "1111111111", "staff-log": "2222222222"}
```
Then in StaffLogger's `config.yml` set the matching name:
```yaml
discord:
  channel-name: "staff-log"
```
Make sure the DiscordSRV bot has **View Channel**, **Send Messages**, and **Embed Links** in
that channel.

### 2. Make LiteBans the ban authority (for exactly-once ban logging)
Ban logging reads LiteBans' events, which only fire for bans that go through LiteBans. If
EssentialsX is also installed, disable its ban commands (`/ban`, `/tempban`, `/banip`) — e.g.
via an EssentialsX command override or by denying the `essentials.ban*` permissions — so every
ban flows through LiteBans. (This is the standard LiteBans setup.)

### 3. Toggles & command lists
`config.yml` lets you enable/disable each action and edit the command bases/aliases matched for
rollbacks, enchants, and give/clear (add your server's custom aliases there).

## Verifying end-to-end

On a Paper 1.21 test server with DiscordSRV, LiteBans, InvSee++, EssentialsX and CoreProtect
installed and the channel mapped, run each and confirm a single Discord embed appears:

- `/ban <player> <reason>` → **Player Banned** (staff / target / reason / duration).
- `/co rollback u:<player> t:1h` → **CoreProtect Rollback**.
- `/enchant <player> sharpness 5` → **Item Enchanted**.
- `/give <otherplayer> diamond 64` → **Inventory Changed (command)**; `/give <yourself> ...`
  or self-give → **no** post.
- `/invsee <player>`, add or remove an item, close the GUI → **Inventory Edited via InvSee**
  showing the net items; opening and closing without changes → **no** post.

Watch the server console for guard warnings if DiscordSRV / the channel / LiteBans / InvSee++
are missing.
