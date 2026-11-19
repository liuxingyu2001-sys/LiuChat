# LiuChat Repository Guide

Agent-facing guide to this repository. User-facing documentation is in `README.md` and `docs/` (Chinese). When docs disagree with source, source wins — several docs are stale (see "Docs" at the end).

## Project

- LiuChat is a Paper/Leaf 1.21.11 chat plugin (Java 21, Maven), shipped as one shaded jar. `plugin.yml` declares `folia-supported: true`, so the same jar must run on Paper, Leaf and Folia; all scheduling goes through `util/Schedulers`.
- Hard runtime dependency: **LiuLib 1.0.5+** (`depend`; the server refuses to load LiuChat without it). LiuChat reuses LiuLib for the ItemStack NBT codec (`service/ItemSerializer` → LiuLib `ItemCodec`) and the scheduler facade.
- Optional integrations (`softdepend`, never assume installed): PlaceholderAPI, CraftEngine, CustomNameplates, DialogMenu, ArcMenu + ProtocolLib, Citizens, CMI, SoulSpace, HuskHomes. Each has to degrade gracefully; see "Optional integrations".
- Entry point `src/main/java/com/liu/liuchat/LiuChat.java` wires every subsystem by hand in `onEnable()`: bundled config seeding + shared config root → `ConfigManager`/`MessageManager` → database → mute/profile/ignore/item/chat/channel presentation → cross-server + tell → friends/mail (Sparrow UI or plain fallback) → commands/router/direct bindings → listeners → QQ bot → ArcMenu workspace → storage sync task → PAPI → DialogMenu menu install → reminders → config auto-reload watcher. `onDisable()` tears down in dependency order with the database last (it drains queued writes).
- `/liuc reload` (`ReloadCommand` → `LiuChat.reloadChatConfiguration()`): validates YAML first, then reloads `config.yml`, `messages.yml`, `chat.yml`, `shortcut.yml`, `dialogs.yml`, `ai.yml`, `npc-assistants.yml`, AI skills, channels, cross-server secret/`enable`/Redis params, QQ settings, reminders and the watcher baseline. AI sessions stay in memory and only re-sync the persist flag.
  It does **not** reload: `storage.type`/DB connection, the `storage.sync-interval` task, listener/command registrations, the PAPI expansion, or the ArcMenu workspace.
- Config layout:
  - **shared across servers**: `config.yml`, `messages.yml`, `shortcut.yml`, `dialogs.yml`, `reminders.yml`, `channels.yml` (`SharedConfig.CONFIG_FILES`) plus `skills/`.
  - **local per server**: `chat.yml`, `ai.yml`, `npc-assistants.yml`, `qq.yml` (`ConfigDefaults.LOCAL_RESOURCES` / `SharedConfig.LOCAL_FILES`).
  - Root: local `config.yml` `settings.shared-config-path` (blank → existing `/mc/shared/liuchat-config` → plugin data folder); `settings.shared-skills-path` can relocate skills. Both need a restart. `server:` always stays local and must be unique per sub-server.
  - Bundled `src/main/resources/` files are copied when missing, then merged additively by `ConfigDefaults.load` (missing keys only; local values and comments are never overwritten). One-time migrations in `ConfigDefaults.merge`: split legacy `horn.format` into per-display formats, migrate legacy `ai.*` filter keys to shared `chat-filter`, upgrade old default colors/formats only when still untouched.
  - `settings.auto-reload-config: true` → `SharedConfig.watch` polls every 200 ticks (≈10 s) and watches the shared files, `skills/` (`*.md`/`*.txt`) and the local files, reloading through `reloadChatConfiguration()`.
- Version lives only in `pom.xml`; `plugin.yml` is filtered from it. Keep `README.md`, `docs/` and this file aligned when behavior or conventions change.

## Build And Test

- `mvn test` = compile + unit tests; `mvn clean package` = tests + shaded jar; `mvn -B clean verify` matches CI (`.github/workflows/maven.yml`; same phases, no checkstyle/jacoco/enforcer profiles).
- Jar: `target/Liu-LiuChat-<version>.jar` (`finalName` = `Liu-${project.artifactId}-${project.version}`). `package` runs tests; use `-DskipTests` only for a deliberate fast build.
- Single test: `mvn -Dtest=CrossServerCodecTest test`; one method: `mvn -Dtest=CrossServerCodecTest#chatRoundTrip test` (method names include `chatRoundTrip`, `tellRoundTrip`, … — there is no plain `roundTrip`).
- The POM targets **Java 21** via `liuchat.java.release` + compiler `<release>` (deliberately **not** `maven.compiler.release`: a global `~/.m2/settings.xml` profile may set that property to the local JDK, which makes Maven compile a higher bytecode version and breaks shade 3.5.3). CI uses Temurin 21. A newer local JDK compiles fine (JDK 25), but keep Java 21 semantics and do not bump `liuchat.java.release` without a reason.
- `AiSkillServiceTest.skipsSymlinksAndOversizedFiles` creates a symbolic link; when the OS refuses (Windows without Developer Mode/admin), the test aborts via JUnit `Assumptions` instead of failing, so `mvn clean package` stays green locally while CI (Ubuntu) still exercises the real check.
- Tests: JUnit 5, surefire 3.2.5, ~356 tests across ~65 classes. No MockBukkit/Mockito/AssertJ — tests must not start a server or call `Bukkit.getServer()`; test pure logic, codecs, config merging, formatting and real SQLite in `@TempDir`. `src/test/java/net/william278/...` holds hand-written HuskHomes API shape stubs (not the real plugin).
- Many tests load bundled YAML by classpath name (`/config.yml`, `/messages.yml`, `/plugin.yml`, `/chat.yml`, `/channels.yml`, `/reminders.yml`, `/qq.yml`, `dialogmenu/liuchat.yml`), so `src/main/resources/` defaults are a tested surface. Run tests through Maven, not raw javac.
- `libs/repo` is a committed Maven file repository (`file://${project.basedir}/libs/repo`) providing `arcmenu-api` and `soulspace-api`, which are not on public repositories; if you bump their versions, vendor the matching artifact. `sparrow-ui` is the only `compile`-scope dependency: shaded and relocated to `com.liu.liuchat.libs.sparrow.ui`. Every optional-plugin dependency is `provided`. SQLite/MySQL JDBC drivers are downloaded at runtime via `plugin.yml: libraries`, not bundled.
- Real proxy + two-backend + client behavior is not unit-testable. Follow `docs/e2e-acceptance.md`; the read-only preflight is `scripts/e2e/check-environment.sh --help` (wrapper around `check_environment.py`, not run by CI).

## Code Map

```text
com.liu.liuchat
├── LiuChat                 entry point, manual wiring, public API methods
├── api/                    ChannelApi/FriendApi/MailApi (Bukkit services) + api/event/ events
├── command/                ChatCommand, CommandRouter, CommandSupport, DirectCommandBridge, one class per subcommand, dialogs
├── config/                 ConfigDefaults (seed/merge/migrations), ConfigManager, MessageManager, SharedConfig
├── hook/                   optional-plugin facades (facade → resolver, class-isolated); hook/qq/ = zero-dep QQ bot
├── listener/               ChatListener (chat pipeline), CommandAliasListener, NpcAssistantBridge
├── model/                  JDBC row records (FriendRow, FriendRequestRow, MailRow, MuteData, PlayerIdentity)
├── service/                business logic: chat/channels/cross-server/tell, mute/ignore/profile, friends/mail, AI, items, logs, menus, reminders
├── storage/                Database, AbstractJdbcDatabase, Sqlite/MysqlDatabase, DbExecutor, DatabaseFactory
└── util/                   Schedulers (Paper/Folia), TextUtil, ColorParser, Mentions, RepeatCheck, TimePlaceholders, ...
```

`LiuChat` also exposes `getChannelApi()/getFriendApi()/getMailApi()`, `broadcastAnnouncement`, `broadcastItemAnnouncement`, `deliverPrivate`, `deliverChannelRemote` (see "Public API").

## Architecture Rules

### Threading / Folia
- Use `util/Schedulers` for **all** scheduling; `Bukkit.getScheduler()` throws on Folia and must not be called outside `Schedulers`. `run*` = global/main thread, `runAsync*` = pool, `runFor(plugin, entity, …)` = the entity's owning region thread, `runGlobal`/`forEachPlayer` as named. Environment is detected once via `Schedulers.isFolia()`.
- `Schedulers.run`/`runAsync` return a non-cancellable NOOP `Handle`; use `runLater`/`runTimer`/`runAsyncTimer` when cancellation matters. Methods generally only enqueue; `runGlobal`/`runFor` may execute immediately when already on the right thread — never assume deferred execution.
- `AsyncPlayerChatEvent` is the only chat entry (HIGHEST, `ignoreCancelled`). `ChatListener` cancels it immediately and redispatches to the player's region thread (`runFor`) before any Bukkit/PAPI/plugin-messaging work. Do not add a second chat-event handler; it will double-process.
- Never block region/global threads with JDBC, HTTP, socket, Redis or WebSocket I/O: use `DbExecutor`, `RedisBus`, the QQ sender, `AiClient` async HTTP and the existing daemon workers.
- Send player-facing text through `MessageManager.send` (it re-dispatches to the region thread). `MessageManager` colorizes the template **before** inserting placeholder values, so player-controlled text cannot inject `&`/colors; `liuchat.color` is the only way to allow color codes.

### Chat pipeline
- `ChatListener.sendChannel` (used by chat, channel commands and `ChannelApi.send`): channel access → cancellable `ChannelMessageEvent` (text may be replaced) → re-check access/length → mute → cooldown → repeat/similarity (item-token messages skip repeat detection) → local blocked-content filter (`chat-filter`; bypass `liuchat.moderation.bypass`; blocked text is echoed only to the sender, sender notice `liuchat.moderation.blocked-notice`, moderators `liuchat.moderation.notify`) → record last-chat time → color decision once on the sender (`ColorParser.playerText(text, hasPermission("liuchat.color"))`) → dispatch.
- GLOBAL dispatch = local broadcast (`ChatService.broadcast`: render once, per-viewer delivery + ignore filter + mention sounds) + chat audit + cross-server publish + QQ + public AI. Non-GLOBAL channels are local-only (`ChatService.broadcastChannel`): permission/scope/world/radius checks, ignores, console echo, `CHANNEL:<id>` log, `ChannelDeliveredEvent`.
- Rendering happens once per receiving server (not per viewer); PAPI values are snapshotted on the origin (`ChatPresentation.snapshotPlaceholders`) and shipped with the message — never re-resolve them on the receiving server. Never re-colorize or re-translate remote message text: color authority stays on the origin.
- Cross-server chat re-renders only from the transported snapshot; item data travels as a snapshot string rendered on the receiving server.

### Channels
- Scopes: `GLOBAL` (the normal public chat, uses `chat.yml` formats and the cross-server link), `SERVER`, `WORLD`, `NEARBY` (with `radius`), `MEMBERS` (subscription/permission based; subscriptions and current channel are session state and reset on quit).
- `channels.yml` is shared and reloadable; `ChannelService` is the `ChannelApi` implementation. Non-GLOBAL channels are **not** relayed by LiuChat; a trusted external plugin may call `LiuChat.deliverChannelRemote(...)` for `MEMBERS` only (it re-checks local subscription, receive permission and ignores, and does not re-fire delivery events).
- `ChannelMessageEvent` is cancellable and mutable; `ChannelDeliveredEvent` fires only for local non-GLOBAL delivery.

### Storage
- `storage/Database` is the only DB surface: mutes, chat profiles (nick/color), ignores, horn balance, player identity directory (including `last_seen`, which powers friend "last online" and join-notify dedupe), friends/requests, mail. `DatabaseFactory` picks MySQL when `storage.type: mysql`, else SQLite (unknown type warns and falls back to SQLite).
- `AbstractJdbcDatabase` creates the schema in `init()` and applies additive `ALTER TABLE ... ADD COLUMN` migrations (`ensureColumn`); dialect differences live in `SqliteDatabase`/`MysqlDatabase` (MySQL also has `MODIFY COLUMN` upgrades). A failed connect logs SEVERE and runs memory-only (`isReady() == false`) so the plugin still starts.
- `DbExecutor`: one daemon thread, FIFO. Writes are async fire-and-forget; reads are synchronous and queued after all prior writes (read-your-writes). **Never call `read` from inside a write task** (single thread → deadlock); combine read-after-write into one task. `close()` drains the queue (10 s) before closing the connection.
- Do not hand-roll item serialization: mail attachments go through LiuLib `ItemCodec` (`service/ItemSerializer`); chat item snapshots use `ItemShowcase`.
- `storage.sync-interval` schedules a periodic `muteService.loadAll()` reconciliation (MySQL cross-server mutes), registered once in `onEnable`; changing it needs a restart.

### Cross-server
- `service/CrossServerCodec` (pure Java, unit-tested, no Bukkit types) defines protocol **10** (legacy 9 receive-only) over BungeeCord plugin messages, tag `LiuChat`, optional HMAC-SHA256 when `cross-server.secret` is set. Types: `CHAT`, `TELL`, `TELL_ACK`, `DIRECT`, `ITEM_ANNOUNCEMENT`, `ANNOUNCEMENT`, `HORN`, `MUTE`, `UNMUTE`, `PRESENCE`, `PRESENCE_QUIT`, `PRESENCE_REQUEST`.
- Transport is `proxy` (default, BungeeCord/Velocity plugin messaging) or `redis` (`RedisBus` pub/sub, bounded 256-item queue, falls back to proxy on failure). A send future means *transport accepted*, not delivered. Cross-server TELL needs `TELL_ACK` within 3 s (60 ticks) or the sender gets "not delivered". Duplicate deliveries are suppressed by message IDs (`RecentMessageIds`, 8192 entries / 2 min) and origin checks.
- Presence/remote-player directory: `PRESENCE` broadcast, `PRESENCE_REQUEST` on join, `PRESENCE_QUIT`; a silent sub-server's entries expire after 150 s (`RemotePlayers`).
- Any payload/type change requires a `PROTOCOL` bump, updated `CrossServerCodecTest`, and **coordinated upgrade of every group server** — a protocol-10 sender is unreadable by protocol-9 peers. `server:` must be unique per sub-server. Keep the codec free of Bukkit types.

### Commands / config / hooks
- `/liuc` subcommands implement `ChatCommand` (`name()`, optional `permission()`, `playerOnly()`, `execute()`, `tabComplete()`) and are registered on `CommandRouter` in `onEnable()`. Current subcommands: `channel`, `audit`, `ask`, `item`, `dialog`, `menu`, `reload`, `mute`, `unmute`, `horn`, `ignore`, `unignore`, `ignorelist`, `nick`, `chatcolor`, `tell`, `reply`, `friend`, `mail`. The real command name is `liuchat` with alias `liuc`; old comments/config text saying `/lc` are stale — `/lc` is not registered. Conflicting labels (`/msg`, `/tell`, `/w`, `/whisper`, `/r`, `/reply`, `/ignore`, `/ignorelist`, `/horn`, `/lb`) are re-routed to `/liuchat:<cmd>` by `CommandAliasListener` when `commands.prefer-liuchat: true`.
- Top-level commands (`/msg`, `/tell`, `/reply`, `/ignore`, `/ignorelist`, `/horn`, `/nick`, `/chatcolor`, `/mute`, `/unmute`, `/friend`, `/mail`) additionally need a `plugin.yml` `commands:` entry and a `bindDirect(...)` call in `LiuChat.onEnable()`; `requireCommand` disables the whole plugin if a `plugin.yml` definition is missing. `router.markDirect` makes help display the top-level name.
- New config key: add it to the bundled YAML (`src/main/resources/...`, which is also the merge default), add a `ConfigManager` accessor, and wire it into the reload path if it must change at runtime. New player-facing text goes in `messages.yml`; every subcommand needs `help.<name>.desc` (help strips a duplicated command prefix). `messages.yml` supports `&&`/`<<` escapes for literal `&`/`<`.
- Optional integrations follow a **facade → resolver** split: a bridge class with no optional-plugin types, plus a resolver loaded only after an availability check (e.g. `HuskHomesHook`/`HuskHomesResolver`, `SoulSpaceHook`/`SoulSpaceResolver`, `CraftEngineEmojiHook`/`CraftEngineEmojiResolver`, `NameplatesHook`/`NameplatesImageResolver`, `ArcMenuBridge`/`ArcMenuWorkspace`). A direct reference from the main plugin path causes `NoClassDefFoundError` when the plugin is absent.

### Optional integrations
- **PlaceholderAPI** — `PapiHook` + `LiuChatExpansion` (`%liuchat_*%`: server/world/nick/muted/ignore_count/recent_contact/horn_balance/cooldown/ai …). Not installed → `setPlaceholders` returns text unchanged.
- **CraftEngine** — emoji resolution (`CraftEngineEmojiHook`); `CraftEngineNames` resolves CE `<lang:key>` item names for chat labels. Missing → plain text.
- **CustomNameplates** — `NameplatesChatHook` pushes accepted public chat to CN chat bubbles, `NameplatesHook` renders CN image nodes; CN needs PAPI enabled.
- **DialogMenu** — `DialogMenuBridge` only writes the bundled `dialogmenu/liuchat.yml` to `plugins/DialogMenu/menus/liuchat.yml` **when missing** (never overwrites); menu buttons execute LiuChat commands, so permissions stay in LiuChat. `/liuc menu` tries DialogMenu first.
- **ArcMenu (+ ProtocolLib)** — `ArcMenuBridge.available()` requires both plugins and non-Folia; registers the `liuchat:chat` workspace used by `/liuc menu`. On Folia / missing API it silently falls back to DialogMenu or `menu.unavailable`.
- **Citizens** — `NpcAssistantBridge` reflectively listens to `NPCRightClickEvent` and binds NPC ids (`npc-assistants.yml`) to AI assistant dialogs; no Citizens classes are loaded when absent.
- **SoulSpace** — `SoulSpaceHook` reads read-only space snapshots for `[i]` ring previews; missing → rings are normal items.
- **HuskHomes** — `HuskHomesHook` reflectively calls `HuskHomesAPI` for cross-server friend teleport; missing or cross-server disabled → same-server teleport only.
- **CMI** — declared `softdepend` for load order/command conflicts only; there is no CMI API hook.

### Public API
- `ChannelApi`, `FriendApi`, `MailApi` are registered in Bukkit's `ServicesManager` (also reachable via `LiuChat.getChannelApi()/getFriendApi()/getMailApi()`); events live in `api/event/`.
- Main-class methods `broadcastAnnouncement`, `broadcastItemAnnouncement`, `deliverPrivate`, `deliverChannelRemote` must be called on the global/main thread (they redispatch on Folia). `ChannelApi` query methods are thread-safe; `ChannelApi.send` returns a future that completes when the message is accepted **locally**, not on remote ACK.
- `FriendApi`/`MailApi` reuse the same services and storage as the UI; `MailApi` goes through `MailAttachmentFilter` too (blacklist-only: materials / blocked PDC keys / binding-rent keywords). `Friend` carries `uuid/name/since/online/lastSeen`; `lastSeen` is the latest join/quit timestamp (0 = unknown) and the friend menu shows it for offline friends.

### AI / QQ / reminders / logs
- AI: `AiClient` async HTTP; `AiReviewService` (per-message moderation, fail-open configurable); `HourlyChatAudit` reviews persisted public chat per hour and reports (no auto-punishment); `AiAssistantService` (`/liuc ask`, dialogs, NPCs); `PublicChatAiService` participates in public chat as a fake identity (mention/keyword/proactive); `AiSkillService` loads `skills/` from the shared skills root; `AiSessionStore` persists to local `ai-sessions.json` (never shared).
- QQ: `hook/qq/` is a zero-dependency QQ official-bot WebSocket client (JDK `HttpClient`/`WebSocket`, own daemon threads); config is per-server `qq.yml`; one bot should connect from one sub-server only. Inbound QQ messages are broadcast with a fixed format (ignores/chat-log apply); outbound public chat is queued/rate-limited.
- Reminders: shared `reminders.yml`, parsed into `ReminderPlan`; each server delivers locally (never via proxy) and `servers` filters by `server:`; `/liuc reload` re-parses.
- Chat logs: `ChatLogService` appends per-day files off-thread: local `plugins/LiuChat/logs/YYYY-MM-DD.log`, or `chat-log.shared-path/servers/server-<hex-of-server>/logs/`; audit history uses the same per-server isolation.

## Conventions

- Comments, Javadoc, log messages, docs and commit messages are **Chinese**; identifiers and user-facing config keys are English.
- This project deliberately avoids third-party frameworks: config, commands, storage, Redis RESP and the QQ WebSocket client are implemented in-repo with zero extra runtime deps beyond Paper/LiuLib/Adventure. Do not add a library for something the repo already implements; new dependencies must be `provided` unless shaded and relocated.
- Only `plugin.yml` is Maven-filtered (for `${project.version}`). Every other YAML intentionally contains literal placeholders such as `${player}`/`${message}`; never enable filtering for them. Keep the two-resource-block setup in `pom.xml`.
- Keep `plugin.yml` commands/permissions/softdepends aligned with `LiuChat.java` registrations and `docs/commands.md`. Permission checks belong in `ChatCommand.permission()`/`CommandSupport` or explicit `hasPermission` calls, not hardcoded strings.
- Do not commit `target/`, live server configs, or secrets (`ai.yml` API keys, `qq.yml` app secret, DB/Redis credentials). `.gitignore` does not protect you here.
- Add focused tests when changing parsing, formatting, cross-server codecs, config defaults/merges, or other server-free logic; extend the existing codec/transport/config/storage tests instead of introducing mocks.
- Player-facing text goes through `messages.yml`, config comments stay Chinese, and new bundled keys need a default plus a `ConfigManager` accessor.

## Where To Add Things

- **New config option**: bundled YAML default → `ConfigManager` accessor → reload path (`ConfigManager.load()` or the owning service's `reload()`), plus `docs/configuration.md`.
- **New `/liuc` subcommand**: `ChatCommand` implementation → `router.register(...)` → `messages.yml` `help.<name>.desc` (+ permissions) → `docs/commands.md`; add `bindDirect` + `plugin.yml` only if it must also be a top-level command.
- **New message**: `messages.yml` under the existing namespace; `MessageManager.get/send` placeholders are `${name}`.
- **New optional-plugin hook**: facade (no optional types) + resolver (optional types) + availability check; add the plugin to `plugin.yml: softdepend` and document the fallback.
- **Protocol change**: bump `CrossServerCodec.PROTOCOL`, add round-trip tests in `CrossServerCodecTest`, update `docs/cross-server.md`, and treat deployment as a coordinated group-wide upgrade.
- **New DB column/table**: additive migration in `AbstractJdbcDatabase.init()` (and dialect overrides in `SqliteDatabase`/`MysqlDatabase`), `Database` default methods so old implementations compile, plus a real-SQLite test.
- **New bundled resource**: add to `src/main/resources/` (defaults are also the merge source and a tested surface); if it is DialogMenu-only content, keep it under `dialogmenu/` so `ConfigDefaults` does not copy it.

## Docs

`docs/configuration.md` (config layout, shared vs local, friends/mail/reminders) · `docs/commands.md` (command + permission table) · `docs/channels.md` · `docs/ai.md` · `docs/qq.md` · `docs/friends.md` · `docs/mail.md` · `docs/cross-server.md` · `docs/api.md` · `docs/architecture.md` · `docs/e2e-acceptance.md` · `docs/features.md` and `docs/roadmap.md` (partly stale).

Known drift to check against source (`source wins`): `docs/cross-server.md` still says protocol 9 (code: 10) and describes protocol-9-era behavior; `docs/architecture.md` summarizes fewer cross-server types; `docs/configuration.md` omits `channels.yml` from the shared-file list; `docs/features.md` header says v0.3; `docs/roadmap.md` still marks the channel system as not done; `docs/commands.md` shows `/liuc nick`/`/liuc chatcolor` although both are also top-level, and omits `/liuc item`.
