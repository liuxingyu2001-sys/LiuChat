# AI 功能配置

AI 配置在每台子服本地的 `plugins/LiuChat/ai.yml`，**不随共享配置同步**（不同子服可用各自的 url、key、model 与助手）。**聊天屏蔽词属于全服统一的审核策略，在共享的 `config.yml` 的 `chat-filter` 段，不在 `ai.yml`。** 本文中的 `ai.xxx` 键对应 `ai.yml` 里的 `xxx`；`/liuc reload` 后生效。升级首次启动会把旧 `config.yml` 的 `ai` 段自动迁移到 `ai.yml`（标记 `migrated: true`），其中屏蔽词部分改迁到共享 `chat-filter`，之后请只改对应文件。

## 聊天审核

聊天屏蔽词在共享 `config.yml` 的 `chat-filter` 段，与 AI 无关：`chat-filter.enable` 是本地屏蔽总开关（不请求 AI），屏蔽词（含 `*`、`?` 有限通配）、数字联系方式、IPv4 和域名在发送时直接拦截，未命中则立即广播；所有子服读取同一份策略。`ai.enable` 只控制历史采集和 AI 相关功能（审核、助手、公屏 AI）。`ai.review.enable: true` 才启动定时 AI 审查，默认每 60 分钟分析最近 1 小时本服已发送的公开聊天；`ai.review.manual-enable: true` 允许管理员用 `/liuc audit <1-24>` 审查指定小时数。两项开关互不影响。记录单独存于 `audit-history/YYYY-MM-DD.jsonl`，不依赖可自定义格式的普通聊天日志；报告写入 `audit-reports/` 并通知 `liuchat.audit.notify` 管理员。每次最多提交最近 250 条、每条最多 300 字，报告记录超量丢弃数；模型只生成待人工复核的报告，不自动禁言。跨服需在各子服分别执行审核。审核 URL/模型沿用 `ai.url` / `ai.model`，`ai.review.prompt` 与 `ai.review.timeout-seconds` 单独配置。`/liuc reload` 可切换定时/手动开关。

`chat-filter.keywords` 支持有限通配：普通词自动容忍每两个字符之间插入最多 2 个任意字符（`cnm` 可拦 `c.n.m`、`c你n好m`），`*` 匹配最多 8 字，`?` 匹配 1 字；所有命中均直接屏蔽，不再调用 AI。短词可能误拦，请针对服务器用语调整。因为是共享文件，一处修改需执行 `/liuc reload`（或开启自动重载）后各子服统一生效。

## AI 助手

AI 助手独立于审核，可单独开启 `ai.assistant.enable`。在 `ai.assistant.profiles` 下配置多个助手及其 `skill` 目录，例如 `profiles.bot.skill: bot`、`profiles.guide.skill: guide`。`/liuc ask <问题>` 使用 `ai.assistant.default` 指定的助手（默认 `bot`），`/liuc ask guide <问题>` 选择其他助手；`/liuc ask list` 列出助手。无需 `@`。每个 skill 位于 `plugins/LiuChat/skills/<目录名>/`，读取 `SKILL.md` 及下层 `.md`/`.txt`；`/liuc reload` 刷新。skill 仅作为提示词知识，不会执行脚本或调用工具。

助手可以自定义显示名称：`ai.assistant.name` 是全局默认（默认 `聊天助手`），`ai.assistant.profiles.<助手名>.name` 单独覆盖，例如 `profiles.bot.name: '久久酱'`。显示名用在回答前缀和 `/liuc dialog ai` 的标题上，`ai.assistant.default` 仍然是命令读的助手 ID。回答前的 `[显示名]` 前缀由 `ai.assistant.answer-prefix` 开关（默认 `true`，关掉就只发正文）；前缀与正文的格式分别写在 `messages.yml` 的 `ai.answer-prefix`、`ai.answer-text`，`/liuc reload` 生效。

`/liuc ask` 的回答按**一条消息**发送：回答里的换行与段落空行原样保留（段落之间就是一个空行），不再逐行刷出 N 条消息；折行按显示宽度算（中文全角算 2 个半角，对齐聊天框 320px，中文长句不会被顶出屏幕），指令高亮为青色并在段尾复位颜色，避免整条消息被染色。输出只剥 Markdown记号（代码围栏、行首标题符、成对的 `**粗体**`、`` `代码` ``），正文符号一律保留 —— 指令占位符 `<名称>` 里的 `>`、`/tp ~ ~ ~` 的 `~`、`player_name` 的 `_` 都不会被吃掉。

助手会话按助手名全服共享：`/liuc ask`、`/liuc dialog ai`、以及绑到同一助手的 NPC 右键，都接在同一条会话上，任何玩家的提问与回答都会留给后续玩家当上下文，直到超过上限才从最旧开始丢。上限由 `ai.assistant` 下的 `history-messages`（默认 20，提问与回答各算 1 条，0 = 关闭上下文）、`history-chars`（默认 4000 字符，0 = 不限）、`history-seconds`（默认 0 = 闲置永不清空，设 600~3600 可进一步省 token）控制；请求还没返回的那轮不计入上下文，失败的整轮丢弃、不留半截。会话写入 `plugins/LiuChat/ai-sessions.json`（`history-persist: false` 则只留内存），改动每 30 秒异步落盘 + 关服同步保存，重启不丢，`/liuc reload` 只重载配置、会话仍在内存里；损坏的会话文件会被忽略并告警，不影响启用。例：绑了“游玩指南”和“服务器聊天助手”两个 NPC，就是两份互不串台、全服玩家共用的会话。

省 token 相关：`max-tokens`（默认 1024，0 = 不限制）限制单次回答的输出 —— 超出 `max-answer` 的部分本来就会被截掉不显示，模型却已经生成并计费；`cache-seconds`（默认 300，0 = 关闭）让“相同配置 + 相同上下文 + 相同问题”在有效期内直接返回缓存答案，不请求模型 = 0 token（缓存 key 含上下文指纹，会话一推进自动失效，不会答非所问）。system 提示词（`prompt` + skill 全文）每次全量重发，且刻意保持逐字节稳定、排在消息最前面，用于命中 OpenAI/DeepSeek/Kimi 等服务的前缀缓存（命中部分约 1/10 计价）；因此不要往 `prompt` 里拼时间戳、玩家名等动态内容，skill 内容也尽量少改。

`/liuc dialog ai` 是独立的 AI 对话 Dialog，标题取该助手的自定义显示名称。回答正文宽度由 `ai.yml` 中的 `ai.assistant.dialog-width` 控制，默认 520，范围 100-800。

NPC 助手使用 Citizens 软依赖，不需要 CustomNameplates：在 `npc-assistants.yml` 中按 Citizens NPC ID 绑定助手名（例如 `npcs.'12'.assistant: bot`）；右键该 NPC 打开专用提问 Dialog，回答仅对点击者可见，但问答会进入该助手的共享会话供其他玩家续上。配置支持 `max-distance`、`cancel-other-actions`；未绑定的 NPC 不受影响。**本轮不做聊天气泡。** `npc-assistants.yml` 和技能在 `/liuc reload` 后重载。
