# LiuChat Repository Guide

Agent-facing guide to this repository. User-facing documentation is in `README.md` and `docs/` (Chinese). When they disagree with source, source wins — several docs are stale (see "Docs" below).

## Project

- LiuChat is a Paper/Leaf 1.21.11 chat plugin (Java 21, Maven), shipped as one shaded jar. It is also Folia-supported (`plugin.yml: folia-supported: true`), so code must work on both Paper and Folia.
- Runtime hard dependency: **LiuLib 1.0.5+** (`depend`; the server refuses to load LiuChat without it). Optional integrations are `softdepend`: PlaceholderAPI, CraftEngine, CustomNameplates, DialogMenu, ArcMenu + ProtocolLib, Citizens, CMI, SoulSpace, HuskHomes. Never assume an optional plugin is installed; every integration must degrade gracefully.
- Entry point `src/main/java/com/liu/liuchat/LiuChat.java` wires every subsystem by hand in `onEnable()` (config → database → chat/cross-server → friends/mail → commands → listeners → QQ → ArcMenu → PAPI/DialogMenu → reminders). `onDisable()` tears subsystems down in dependency order with the database last (it drains queued writes). `/liuc reload` → `reloadChatConfiguration()` reloads YAML-backed config, channels, cross-server secret/Redis, QQ and reminders, but **not** storage type/DB connection, the `storage.sync-interval` task, listener/command registrations, the PAPI expansion, or the ArcMenu workspace.
- Config files are split: **shared** across servers — `config.yml`, `messages.yml`, `shortcut.yml`, `dialogs.yml`, `reminders.yml`, `channels.yml` (`SharedConfig.CONFIG_FILES`); **local per server** — `chat.yml`, `ai.yml`, `npc-assistants.yml`, `qq.yml` (`ConfigDefaults.LOCAL_RESOURCES`). Bundled resources in `src/main/resources/` are copied on first run and then merged additively (missing keys only, local values never overwritten) by `ConfigDefaults.load`.
- `AGENTS.md` is the agent guide; keep `README.md`, `docs/` and this file aligned when behavior or conventions change.

## Build And Test

- `mvn test` — compile + unit tests. `mvn clean package` — tests + shaded jar. `mvn -B clean verify` — matches CI (identical phases; no checkstyle/jacoco/enforcer/integration profiles).
- Jar output: `target/Liu-LiuChat-<version>.jar` (`finalName` = `Liu-${project.artifactId}-${project.version}`). `mvn package` runs tests; add `-DskipTests` only when you intentionally want a fast build.
- Single test: `mvn -Dtest=CrossServerCodecTest test` or `-Dtest=CrossServerCodecTest#roundTrip`.
- The POM targets **Java 21** (`maven.compiler.release`) and CI uses Temurin 21. A newer local JDK can compile it, but verify changes against Java 21 semantics and do not bump `maven.compiler.release` without a reason.
- `libs/repo` is a git-tracked Maven file repository (`file://${project.basedir}/libs/repo`) providing `arcmenu-api` and `soulspace-api`, which are not on public repositories. Keep those jars/poms committed; changing their versions means vendoring matching artifacts. Only `sparrow-ui` is compile-scope, shaded and relocated (`com.liu.liuchat.libs.sparrow.ui`); all optional-plugin deps must stay `provided`. SQLite/MySQL JDBC drivers are downloaded at runtime via `plugin.yml: libraries`, not bundled.
- Tests: JUnit 5 (surefire 3.2.5). There is no MockBukkit/Mockito/AssertJ — tests must not start a server or call `Bukkit.getServer()`; test pure logic, codecs, config merging and JDBC (real SQLite in `@TempDir`). `src/test/java/net/william278/...` contains hand-written HuskHomes API shape stubs for `HuskHomesResolver` tests — not real HuskHomes.
- Many tests load bundled YAML by classpath name (`/config.yml`, `/messages.yml`, `/plugin.yml`, ...), so edits to `src/main/resources/` defaults are a tested surface. Run tests through Maven, not raw javac.
- Real proxy + two-backend + client behavior cannot be unit-tested. Use `docs/e2e-acceptance.md` and the read-only preflight `scripts/e2e/check-environment.sh --help`; CI never runs this.

## Code Map

```text
com.liu.liuchat
├── LiuChat                 plugin entry, manual wiring, main-class public API
├── api/                    ChannelApi/FriendApi/MailApi (Bukkit services) + api/event/ Bukkit events
├── command/                ChatCommand, CommandRouter (/liuc subcommands), CommandSupport, DirectCommandBridge
├── config/                 ConfigDefaults (seed+merge YAML), ConfigManager, MessageManager, SharedConfig
├── hook/                   optional-plugin bridges (class-isolated facade → resolver); hook/qq/ is the zero-dep QQ bot
├── listener/               ChatListener (chat pipeline), CommandAliasListener, NpcAssistantBridge
├── model/                  JDBC row records (FriendRow, MailRow, MuteData, ...)
├── service/                business logic: chat, channels, cross-server, tell/mute, friends/mail, AI, items, logs, menus
├── storage/                Database interface, AbstractJdbcDatabase, Sqlite/Mysql dialects, DbExecutor
└── util/                   Schedulers (Paper/Folia), TextUtil, ColorParser, Mentions, RepeatCheck, ...
```

## Architecture Rules

**Threading / Folia**
- Use `util/Schedulers` for all scheduling; never call `Bukkit.getScheduler()` outside it (it throws on Folia). `run*` = global/main thread, `runAsync*` = pool, `runFor(plugin, entity, …)` = the entity's owning region thread, `runGlobal`/`forEachPlayer` as named.
- `AsyncPlayerChatEvent` is the only chat entry (HIGHEST, `ignoreCancelled`). `ChatListener` cancels it and redispatches to the player's region thread before any Bukkit/PAPI/plugin-messaging work. Do not add a second chat event handler; it will double-process.
- `Schedulers.run`/`runAsync` return a non-cancellable NOOP handle; use `runLater`/`runTimer` when cancellation matters. The methods generally only enqueue (`runGlobal`/`runFor` may execute immediately when already on the right thread) — never assume immediate execution.
- Do not block region/global threads with JDBC, HTTP, socket or WebSocket I/O; use the existing executors/queues (`DbExecutor`, `RedisBus`, QQ sender, `AiClient` async HTTP).
- Send player-facing text through `MessageManager.send` (it re-dispatches to the region thread). `MessageManager` colorizes the template **before** inserting placeholder values so player text cannot inject `&`/colors.

**Chat pipeline** (`ChatListener.sendChannel`): channel access → cancellable `ChannelMessageEvent` → mute → cooldown → repeat/similarity → local blocked-content filter → color decision once on the sender (`liuchat.color`) → dispatch. Global chat = local broadcast + cross-server publish + audit + QQ + public AI; non-GLOBAL channels are per-server unless an external plugin relays the rendered text via the documented API. Rendering happens once per receiving server (not per viewer); PAPI values are captured on the origin into a snapshot and must not be re-resolved on the receiving server. Never re-colorize or re-translate remote message text.

**Storage**
- `storage/Database` is the only DB surface (mutes, profiles, ignores, horn balance, identities, friends, requests, mail). `DatabaseFactory` picks MySQL when configured, else SQLite; a failed connect logs SEVERE and runs memory-only so the plugin still starts.
- `DbExecutor`: one daemon thread, FIFO. Writes are async fire-and-forget; reads are synchronous and queued after all prior writes (read-your-writes). **Never call `read` from inside a write task** (single thread → deadlock); combine read-after-write into one task. `close()` drains the queue before closing the connection.
- Schema is created in `init()` with additive `ALTER TABLE ADD COLUMN` migrations; dialect differences live in `SqliteDatabase`/`MysqlDatabase`. Do not hand-roll item serialization: mail attachments go through LiuLib `ItemCodec` (`ItemSerializer`), chat item snapshots use the existing `ItemShowcase` format.

**Cross-server**
- `service/CrossServerCodec` (pure Java, unit-tested) defines protocol **10** (legacy 9 is receive-only) over BungeeCord plugin messages, optionally signed with HMAC-SHA256 when `cross-server.secret` is set; type tag `LiuChat`. Types: CHAT, TELL, TELL_ACK, DIRECT, ITEM_ANNOUNCEMENT, ANNOUNCEMENT, HORN, MUTE, UNMUTE, PRESENCE, PRESENCE_QUIT, PRESENCE_REQUEST.
- Transport is `proxy` (default) or `redis` (Redis pub/sub, falls back to proxy). Send future = transport accepted, not delivered; cross-server TELL needs `TELL_ACK` within 3 s or the sender gets "not delivered". Duplicate deliveries are suppressed by message IDs (8192/2 min) and origin checks.
- Any payload/type change requires a `PROTOCOL` bump, updated `CrossServerCodecTest`, and **coordinated upgrade of every group server** — a protocol-10 sender is unreadable by protocol-9 peers. `server:` must be unique per sub-server. Keep the codec free of Bukkit types so it stays unit-testable.

**Commands / config / hooks**
- `/liuc` subcommands implement `ChatCommand` and are registered on `CommandRouter` in `onEnable()`. Top-level commands (`/msg`, `/mute`, `/mail`, ...) additionally need a `plugin.yml` `commands:` entry and a `bindDirect(...)` call; `requireCommand` disables the whole plugin if `plugin.yml` is missing one.
- New config key: add it to the bundled YAML (`src/main/resources/...`, which is also the merge default), add a `ConfigManager` accessor, and wire it into the reload path if it must change at runtime. New player-facing text goes in `messages.yml` (new subcommands also need `help.<name>.desc`).
- Optional plugin integrations follow a facade → resolver split: a bridge class with no optional-plugin types, plus a resolver loaded only after an availability check (e.g. `HuskHomesHook`/`HuskHomesResolver`, `SoulSpaceHook`, `CraftEngineEmojiHook`, `ArcMenuBridge`). Direct references from the main plugin path cause `NoClassDefFoundError` when the plugin is absent.
- Public API: `ChannelApi`, `FriendApi`, `MailApi` are registered in Bukkit's `ServicesManager`; events live in `api/event/`; main-class methods (`broadcastAnnouncement`, `broadcastItemAnnouncement`, `deliverPrivate`, `deliverChannelRemote`) must be called on the global/main thread (they redispatch on Folia).

## Conventions

- Comments, Javadoc, log messages, docs and commit messages are **Chinese**; identifiers and user-facing config keys are English.
- This project deliberately avoids third-party frameworks: config, commands, storage, Redis RESP and the QQ WebSocket client are implemented in-repo with zero external runtime deps beyond Paper/LiuLib/Adventure. Do not add a new library for something the repo already implements; new dependencies must be `provided` unless they are shaded (and then relocated).
- Only `plugin.yml` is Maven-filtered (for `${project.version}`). Every other YAML intentionally contains literal placeholders such as `${player}`/`${message}`; never enable filtering for them. Keep the current two-resource-block setup in `pom.xml`.
- Keep `plugin.yml` commands/permissions/softdepends aligned with `LiuChat.java` registrations and `docs/commands.md`. Permission checks belong in `ChatCommand.permission()`/`CommandSupport` or explicit `hasPermission` calls, not hardcoded names.
- Do not commit `target/`, live server configs, or secrets (`ai.yml` API keys, `qq.yml` app secret, DB/Redis credentials). `.gitignore` does not protect you here.
- Version lives only in `pom.xml`; `plugin.yml` is filtered from it. Do not hardcode versions elsewhere.
- Add focused tests when changing parsing, formatting, cross-server codecs, config defaults/merges, or other server-free logic; extend the existing codec/transport/config/storage tests instead of introducing mocks.

## Docs

`docs/configuration.md` (config layout, shared vs local, friends/mail/reminders) · `docs/commands.md` (command + permission table) · `docs/channels.md` · `docs/ai.md` · `docs/qq.md` · `docs/friends.md` · `docs/mail.md` · `docs/cross-server.md` · `docs/api.md` · `docs/architecture.md` · `docs/e2e-acceptance.md` · `docs/features.md` and `docs/roadmap.md` (partly stale).

Known drift to check against source: `docs/cross-server.md` still says protocol 9 (code: 10) and `docs/architecture.md` lists fewer cross-server types; `docs/configuration.md` omits `channels.yml` from the shared-file list; `docs/features.md` header says v0.3; `docs/roadmap.md` still marks the channel system as not done.
