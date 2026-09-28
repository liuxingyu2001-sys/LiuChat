# Repository Guide

## Project
- LiuChat is a Paper/Leaf 1.21.11 chat plugin built with Java 21 and Maven. Entry point: `src/main/java/com/liu/liuchat/LiuChat.java`.
- `src/main/java/com/liu/liuchat/` is organized by responsibility: `command` (command routing and handlers), `listener` (chat/events), `service` (chat, AI, cross-server messaging, moderation), `storage` (SQLite/MySQL), `config`, `hook` (optional plugins), and `util`.
- Defaults and plugin metadata live in `src/main/resources/`; unit tests mirror the Java package layout under `src/test/java/`. Consult `README.md` for configuration and behavior details, but check source when documentation and code disagree.

## Build And Verification
- Run `mvn test` for unit tests; run `mvn clean package` for the full build, or `mvn -B clean verify` to match CI. The versioned jar is written to `target/Liu-LiuChat-<version>.jar`.
- External Paper and optional-plugin dependencies may require configured Maven repositories or locally installed artifacts. Do not assume optional plugins are present at runtime.
- Add or update focused JUnit 5 tests when changing parsing, message formatting, cross-server codecs, configuration defaults, or other testable logic. Server-dependent behavior should also be checked on a compatible Paper server when possible.

## Change Constraints
- Keep `plugin.yml` command, permission, and soft-dependency declarations aligned with registrations in `LiuChat.java`; optional integrations must continue to degrade gracefully when absent.
- Cross-server message format changes require coordinated deployment across servers. Check `CrossServerCodec` and its tests when changing payloads or message types.
- Only `plugin.yml` is Maven-filtered. Other YAML resources intentionally preserve placeholders such as `${player}`; do not enable filtering for them.
- Be mindful of Bukkit main-thread requirements, async chat handlers, scheduled tasks, and database/HTTP work when changing event or service code.
- Do not commit generated `target/` artifacts or live server configuration/secrets.
