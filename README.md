# LiuChat

[![Maven CI](https://github.com/liuxingyu2001/LiuChat/actions/workflows/maven.yml/badge.svg)](https://github.com/liuxingyu2001/LiuChat/actions/workflows/maven.yml)


- **平台**：Paper / Leaf 1.21.11（Java 21；完整物品悬浮及 Dialogs 使用 Paper API）
- **存储**：SQLite（单服）/ MySQL（跨服共享），驱动经 plugin.yml `libraries` 由 Paper 自动下载
- **跨服**：BungeeCord plugin messaging（BungeeCord / Velocity 均原生支持）
- **可选依赖**：PlaceholderAPI（含 CustomNameplates 的 PAPI 占位符）、CustomNameplates API（聊天图片节点与聊天气泡）、CraftEngine；无这些插件时基础聊天可用。

## 功能（v0.3）

| 功能 | 说明 |
|---|---|
| 聊天格式化 | `chat.yml` 的 `chat.default.format` 有序节点支持 hover/click/clickSuggest/url；`private.to.format` 和 `private.from.format` 独立控制私聊；控制台使用 `console-format` |
| 物品与头像 | `[i]` 主手物品快照，悬浮显示原生数据组件（item_name、Lore、附魔），点击查看只读 GUI；**潜影盒点击查看盒内物品预览**（27 格只读，含染色潜影盒）；`${head}` 显示 UUID 头像。CE 物品名支持 zh_cn 资源包翻译键，CE 聊天表情保留其配置的图片与悬浮提示 |
| 灵魂空间戒指预览 | 安装 SoulSpace 后，`[i]` 展示戒指可点击打开展示者空间的**只读预览**（54 格分页翻阅、无限堆叠标注真实数量、拿不走）；**按数量排序**（默认从多到少，界面按钮可切换从少到多/空间原顺序，`item.soulspace.sort` 配置默认值）；需 `liuchat.soulspace.preview` 权限，无权限点击仍是普通物品预览；每台服务器每条目仅首次点击读一次数据（本服在线零 IO），共用 MySQL 的多服跨服可预览 |
| 公屏 AI 聊天 | AI 像真实玩家一样参与公共聊天：公屏点名（@AI 或提到它的名字）就回复，**固定聊天格式**（`ai.chat.format` 支持 `${player}`、`${message}` 和 `${head}` 头像；`ai.chat.head-uuid` 可指定皮肤；支持 `&` 色码与 MiniMessage 混写如 `<gradient:..>`；假人拿不到玩家/其他插件的占位符输出，不解析其他变量；跨服回复携带发送服的格式与头像设置，接收服按发送服样式展示；`/liuc ignore` 屏蔽、聊天日志都生效），并把最近公屏消息作为聊天氛围上下文；可配概率插话、冷却、上下文条数；需开 `ai.enable` 与 `ai.assistant.enable`，另有 `ai.chat` 段开关 |
| **@ 提及** | `chat.yml` 的 `at` 节点：输入 `@玩家ID` 或直接输入在线玩家 ID（自动补 @），被 @ 的玩家收到提示音（`at.sound`，默认铁砧 `BLOCK_ANVIL_LAND`），玩家 ID 按 `atColor` 高亮并保留消息原有颜色/样式（`keepAt` 控制是否显示 @）；高亮在颜色权限裁决之后注入，**无 `liuchat.color` 权限的玩家 @ 人同样变色**；跨服在线玩家同样可被 @ |
| 全服喇叭 | `/horn` 或 `/liuc horn`，可配置聊天/Title/ActionBar/BossBar 与音效 |
| 快捷触发 | `shortcut.yml` 正则替换，支持 hover、点击命令/建议/复制/URL |
| 屏蔽与资料 | `/liuc ignore`、`unignore`、`ignorelist`、`nick`；MySQL 共享持久化 |
| 扩展 | PAPI（含 CustomNameplates 的 PAPI 占位符）、Paper Dialog 可配置布局、独立开关的 AI 聊天审核与私聊助手（按助手共享多轮会话、答案缓存、输出上限）、每日聊天日志 |
| **跨服聊天** | 经代理转发到其他子服，收端按自己的 format 渲染、`${server}` 显示发送端子服；无代理/单服开着无副作用 |
| **顶层私聊命令** | 直接注册 `/msg`（别名 `/w` `/whisper`）与 `/tell`，全部带 tab 补全；跨服在线名单由子服同步供玩家名补全 |
| **消息前缀** | `messages.yml` 的 `prefix-enable` 开关（默认 `true`）控制所有插件消息是否带 `prefix` 前缀，关闭后只发正文，`/liuc reload` 生效 |
| **跨服私聊** | 目标在其他子服也能收到；TELL 广播只在目标所在服落地，**回执机制**保证送达 —— 3 秒未收到回执则提示“不在线，消息未送达”，不会静默丢失 |
| 禁言 | `/mute`（亦可 `/liuc mute`），`30s / 5m / 1h30m / 0=永久`，过期自动清；uuid + 名字双查兜底 |
| **MySQL 跨服共享禁言** | 写库并广播 MUTE/UNMUTE 到其他子服内存；发送服无在线玩家或目标服断线时由共享库的定时对账补漏 |
| 聊天冷却 | 按权限节点分级（`liuchat.cooldown.<键>`），`liuchat.cooldown.bypass` 免除 |
| 重复/相似发言检测 | `repeat-check` 时间窗口 + Levenshtein 相似度：完全相同不受 `min-length` 限制，相似度比较要求长度达标；**含 `[i]` 物品展示的消息整条跳过**（每次展示的物品可能不同，同文案不算刷屏） |
| 颜色权限 | `liuchat.color` 控制玩家消息里的其他 `&` 颜色代码与安全的样式标签；所有玩家可在公聊、私聊和喇叭中使用 `&f`、`&r` 重置颜色（本服/跨服同一套裁决） |
| PAPI 变量 | `%liuchat_nick%`（未设置则原名）、`%liuchat_nick_raw%`（未设置则空）、`%liuchat_server%` `%liuchat_world%` `%liuchat_muted%` `%liuchat_muted_time%` `%liuchat_muted_reason%` |

## 命令（全部支持 tab 补全）

```
/liuc                              查看帮助
/liuc reload                       重载配置        权限: liuchat.reload
/mute <玩家> <时长|0永久> [原因]   禁言（同 /liuc mute）    权限: liuchat.mute
/unmute <玩家>                 解除禁言（同 /liuc unmute） 权限: liuchat.unmute
/msg <玩家> <消息>                私聊（别名 w/whisper，跨服）  权限: liuchat.tell
/tell <玩家> <消息>               私聊（跨服）             权限: liuchat.tell
/horn <消息>                      全服喇叭             权限: liuchat.horn
/liuc ignore <玩家>                 屏蔽玩家
/liuc unignore <玩家>               取消屏蔽
/liuc ignorelist                    查看屏蔽列表
/liuc nick <昵称|off>               聊天昵称             权限: liuchat.nick（默认 OP）
/liuc chatcolor <&a|&#RRGGBB|off>   聊天颜色             权限: liuchat.chatcolor
/liuc ask <问题>                    使用默认 AI 助手    权限: liuchat.ask
/liuc ask <助手名> <问题>          使用指定助手       权限: liuchat.ask
/liuc ask list                     列出助手
/liuc audit <1-24>                 审查最近 N 小时聊天  权限: liuchat.audit
/liuc dialog ai                    AI 聊天助手 Dialog
/liuc dialog chatcolor              聊天颜色与渐变 Dialog
```

`/liuc dialog chatcolor` 打开聊天颜色与渐变设置 Dialog。

`/liuc`（无参数或未知子命令）打印帮助，帮助行按命令的真实入口显示：在 plugin.yml 里直挂成顶层的 `/mute` `/unmute` `/msg` `/tell` `/horn` `/nick` `/chatcolor` 显示为顶层命令，其余显示 `/liuc xxx`（模板 `help.cmd` 的 `${command}` 由代码拼）。老语言文件 `help.*.desc` 里重复写的用法命令名（`/liuc mute <玩家>`、`/mute <玩家>`）会被自动去掉，只留参数，不用手改已有 `messages.yml`。

插件消息是否带 `messages.yml` 的 `prefix` 前缀由同文件的 `prefix-enable` 控制（默认 `true`，`/liuc reload` 生效）；不需要前缀时设 `false` 即可，不用把 `prefix` 清空。

跨服私聊链路：本服在线直达 → 不在本服则 TELL 广播（代理 Forward ALL）→ 目标所在服投递
并回 TELL_ACK（定向回发送端子服）→ 发送端凭回执确认送达；超时 3 秒视为离线并补提示。
目标不在线时先本地回显、3 秒后补“消息未送达”——两段式反馈，不静默丢消息。

私聊独立格式配置在 `chat.yml` 的 `private.to.format`（发送者回显）和 `private.from.format`（接收者显示），每个节点与公共聊天一样支持 `text`、`hover`、`click`、`clickSuggest`、`url` 和图片。`${player}`/`${nick}` 表示发送者，`${target}` 表示接收者，`${message}` 为正文；`private.enable: false` 恢复 `messages.yml` 的旧文本样式。跨服私聊发送者的昵称、UUID、世界与占位符快照随消息发送，接收服无需发送者在线。跨服在线名单在加入、退出时同步，并每分钟刷新；失联子服的名单约 150 秒后过期，供 `/tell`、`/msg` 等命令补全。**本次跨服协议升级至 7，所有子服须一起更新**，否则旧版子服间的消息会被拒收。

`/liuc` 别名：`/liuc`

> **部署提示**：
> - plugin.yml 直接声明 `msg`/`tell` 会覆盖原版同名命令（EssentialsX 同一机制）；
> - BungeeCord 代理端自带 `/msg`，若它把输入拦在了代理上，需在代理侧禁用（bungee.yml `disabledCommands`）；
> - 跨服 PAPI 变量在发送服预解析并传输；接收服不直接解析远端玩家。所有子服需部署同一协议版本；CustomNameplates 需启用 PAPI。

## 配置要点

聊天交互在 `chat.yml`，正则快捷触发在 `shortcut.yml`，Paper Dialog 快捷操作在 `dialogs.yml`；`config.yml` 控制喇叭、AI、每日聊天记录与跨服。启动或 `/liuc reload` 自动补全缺失键，不覆盖现有值。记录写到 `plugins/LiuChat/logs/YYYY-MM-DD.log`。`liuchat.color` 只允许玩家输入颜色/样式标签，不能注入点击指令。CE 表情以发送者权限调用其 CHAT 解析器，图片和悬浮内容会在跨服消息中随占位符快照传递；发送服需要安装 CraftEngine，客户端需加载对应资源包。本服公聊审核通过后会调用 CustomNameplates 的 `ChatManager.onChat` 触发聊天气泡（频道 `Global`）；气泡的显示仍受其 `bubble.yml` 的 `sender-requirements`、`viewer-requirements`、`blacklist-channels`、`max-lines` 等设置控制，不满足条件时正常聊天不受影响。

CustomNameplates API 支持：在 `chat.yml` 独立的玩家节点设置 `text: '&e${nick}'` 和 `image: {type: background, id: bedrock_1, left-margin: 1, right-margin: 1}`；也可用 `type: nameplate` 和对应的铭牌 ID。安装 CustomNameplates 并让客户端加载其资源包后生效；未安装或 ID 不存在时显示原文本。图片节点不能同时包含 `${message}` 或 `${head}`，头像可拆为另一个节点。跨服聊天由接收服使用相同 ID 生成图片，所有子服应安装并配置相同的图片资源。原有 `%nameplates_...%` 变量仍通过 PlaceholderAPI 在发送服预解析。

CMI 同名指令由 `commands.prefer-liuchat: true` 将 `/msg`、`/tell`、`/w`、`/whisper`、`/horn` 转到 `liuchat:` 命名空间；不自动修改服务器 `commands.yml`。`/pm` 不由 LiuChat 注册或重定向。

`ai.enable` 是即时本地屏蔽和历史采集的总开关：屏蔽词（含 `*`、`?` 有限通配）、数字联系方式、IPv4 和域名在发送时直接拦截，未命中则立即广播。`ai.review.enable: true` 才启动定时 AI 审查，默认每 60 分钟分析最近 1 小时本服已发送的公开聊天；`ai.review.manual-enable: true` 允许管理员用 `/liuc audit <1-24>` 审查指定小时数。两项开关互不影响。记录单独存于 `audit-history/YYYY-MM-DD.jsonl`，不依赖可自定义格式的普通聊天日志；报告写入 `audit-reports/` 并通知 `liuchat.audit.notify` 管理员。每次最多提交最近 250 条、每条最多 300 字，报告记录超量丢弃数；模型只生成待人工复核的报告，不自动禁言。跨服需在各子服分别执行审核。审核 URL/模型沿用 `ai.url` / `ai.model`，`ai.review.prompt` 与 `ai.review.timeout-seconds` 单独配置。`/liuc reload` 可切换定时/手动开关。

`ai.review.keywords` 支持有限通配：普通词自动容忍每两个字符之间插入最多 2 个任意字符（`cnm` 可拦 `c.n.m`、`c你n好m`），`*` 匹配最多 8 字，`?` 匹配 1 字；所有命中均直接屏蔽，不再调用 AI。短词可能误拦，请针对服务器用语调整；已有配置的关键词列表不会被自动覆盖，需要手动加入 `cnm` 等新关键词。

AI 助手独立于审核，可单独开启 `ai.assistant.enable`。在 `ai.assistant.profiles` 下配置多个助手及其 `skill` 目录，例如 `profiles.bot.skill: bot`、`profiles.guide.skill: guide`。`/liuc ask <问题>` 使用 `ai.assistant.default` 指定的助手（默认 `bot`），`/liuc ask guide <问题>` 选择其他助手；`/liuc ask list` 列出助手。无需 `@`。每个 skill 位于 `plugins/LiuChat/skills/<目录名>/`，读取 `SKILL.md` 及下层 `.md`/`.txt`；`/liuc reload` 刷新。skill 仅作为提示词知识，不会执行脚本或调用工具。

助手可以自定义显示名称：`ai.assistant.name` 是全局默认（默认 `聊天助手`），`ai.assistant.profiles.<助手名>.name` 单独覆盖，例如 `profiles.bot.name: '久久酱'`。显示名用在回答前缀和 `/liuc dialog ai` 的标题上，`ai.assistant.default` 仍然是命令读的助手 ID。回答前的 `[显示名]` 前缀由 `ai.assistant.answer-prefix` 开关（默认 `true`，关掉就只发正文）；前缀与正文的格式分别写在 `messages.yml` 的 `ai.answer-prefix`、`ai.answer-text`，`/liuc reload` 生效。

`/liuc ask` 的回答按**一条消息**发送：回答里的换行与段落空行原样保留（段落之间就是一个空行），不再逐行刷出 N 条消息；折行按显示宽度算（中文全角算 2 个半角，对齐聊天框 320px，中文长句不会被顶出屏幕），指令高亮为青色并在段尾复位颜色，避免整条消息被染色。输出只剥 Markdown记号（代码围栏、行首标题符、成对的 `**粗体**`、`` `代码` ``），正文符号一律保留 —— 指令占位符 `<名称>` 里的 `>`、`/tp ~ ~ ~` 的 `~`、`player_name` 的 `_` 都不会被吃掉。

助手会话按助手名全服共享：`/liuc ask`、`/liuc dialog ai`、以及绑到同一助手的 NPC 右键，都接在同一条会话上，任何玩家的提问与回答都会留给后续玩家当上下文，直到超过上限才从最旧开始丢。上限由 `ai.assistant` 下的 `history-messages`（默认 20，提问与回答各算 1 条，0 = 关闭上下文）、`history-chars`（默认 4000 字符，0 = 不限）、`history-seconds`（默认 0 = 闲置永不清空，设 600~3600 可进一步省 token）控制；请求还没返回的那轮不计入上下文，失败的整轮丢弃、不留半截。会话写入 `plugins/LiuChat/ai-sessions.json`（`history-persist: false` 则只留内存），改动每 30 秒异步落盘 + 关服同步保存，重启不丢，`/liuc reload` 只重载配置、会话仍在内存里；损坏的会话文件会被忽略并告警，不影响启用。例：绑了“游玩指南”和“服务器聊天助手”两个 NPC，就是两份互不串台、全服玩家共用的会话。

省 token 相关：`max-tokens`（默认 1024，0 = 不限制）限制单次回答的输出 —— 超出 `max-answer` 的部分本来就会被截掉不显示，模型却已经生成并计费；`cache-seconds`（默认 300，0 = 关闭）让“相同配置 + 相同上下文 + 相同问题”在有效期内直接返回缓存答案，不请求模型 = 0 token（缓存 key 含上下文指纹，会话一推进自动失效，不会答非所问）。system 提示词（`prompt` + skill 全文）每次全量重发，且刻意保持逐字节稳定、排在消息最前面，用于命中 OpenAI/DeepSeek/Kimi 等服务的前缀缓存（命中部分约 1/10 计价）；因此不要往 `prompt` 里拼时间戳、玩家名等动态内容，skill 内容也尽量少改。

聊天颜色通过 `/liuc dialog chatcolor` 打开，支持单色和渐变色。单色模式使用第一组红、绿、蓝滑块；渐变模式另外使用结束色的红、绿、蓝三组滑块。红 R、绿 G、蓝 B 分别代表组成颜色的三种原色通道，数值范围都是 0-255。保存后会写入聊天资料，并支持 `<gradient:#1afff0:#2ea4ff>` 格式。也可以用 `/liuc chatcolor <&a|&#RRGGBB|off>` 直接设置，与 Dialog 同一存储格式；输入只允许颜色码（`&a`、`&#RRGGBB`、`&x` 形式、`<gradient:...>` 等），含正文或其他 MiniMessage 标签会被判为格式无效并拒绝，`off` 清除颜色。

`/liuc dialog ai` 是独立的 AI 对话 Dialog，标题取该助手的自定义显示名称。回答正文宽度由 `config.yml` 中的 `ai.assistant.dialog-width` 控制，默认 520，范围 100-800。

NPC 助手使用 Citizens 软依赖，不需要 CustomNameplates：在 `npc-assistants.yml` 中按 Citizens NPC ID 绑定助手名（例如 `npcs.'12'.assistant: bot`）；右键该 NPC 打开专用提问 Dialog，回答仅对点击者可见，但问答会进入该助手的共享会话供其他玩家续上。配置支持 `max-distance`、`cancel-other-actions`；未绑定的 NPC 不受影响。**本轮不做聊天气泡。** `npc-assistants.yml` 和技能在 `/liuc reload` 后重载。

```yaml
server: "lobby"          # 每个子服必须配成不同值（跨服消息按它区分来源）

cross-server:
  enable: true           # 群组服跨服聊天开关

storage:
  type: mysql            # sqlite（单服）| mysql（跨服共享禁言）
  sync-interval: 30      # 每 30 秒与数据库对账，同步其他子服的禁言/解禁，0 = 关
  mysql: { host: ..., port: 3306, database: liuchat, username: ..., password: ... }
```

## 构建

```bash
mvn clean package
# 产物: target/Liu-LiuChat-0.3.0.jar  （含单元测试）
```

## 开源协议

LiuChat 使用 [MIT License](LICENSE) 发布。你可以自由使用、复制、修改和分发本项目，但必须保留版权声明和许可证文本。Paper、Leaf、Adventure、SQLite JDBC、MySQL Connector/J、PlaceholderAPI 以及服务器中安装的其他可选插件均按各自项目的许可证提供，不因 LiuChat 使用 MIT License 而改变其许可证。

## 持续集成

GitHub Actions 工作流位于 `.github/workflows/maven.yml`，使用 Java 21 执行：

```bash
mvn -B clean verify
```

工作流会在 push 和 pull request 时运行，包含编译、单元测试和打包。

```
com.liu.liuchat
├── LiuChat                 主类：装配一切
├── command/                ChatCommand 接口 + CommandRouter（/liuc 子命令）
│   ├── CommandSupport      子命令与顶层命令共用的权限/异常/tab 逻辑
│   ├── DirectCommandBridge 顶层直注册命令（/msg /tell）的桥
│   └── ReloadCommand / MuteCommand / UnmuteCommand / TellCommand
├── listener/ChatListener   禁言 → 冷却 → 重复检测 → 颜色裁决 → 分发+跨服
├── service/
│   ├── ChatService         本服广播 + 跨服落地渲染（broadcastRemote）
│   ├── CrossServerService  BungeeCord plugin messaging 收发（按类型分流/通道归一化/防回环）
│   ├── CrossServerCodec    跨服协议编解码（CHAT/TELL/ACK/HORN/MUTE/UNMUTE）
│   ├── TellService         私聊：本服直达 + 跨服回执（送达确认/超时离线提示）
│   └── MuteService         禁言缓存 + 对账式全量刷新（跨服同步）
├── storage/                Database 接口 + AbstractJdbcDatabase
│   ├── SqliteDatabase / MysqlDatabase（方言在子类）+ DatabaseFactory
├── config/                 ConfigManager / MessageManager
├── model/MuteData          数据记录
├── hook/                   PapiHook + LiuChatExpansion（类隔离）
└── util/TextUtil           颜色 / 时长解析 / 相似度
```

关键设计：
- **颜色裁决在监听器做一次**，处理后的文本同时用于本服广播与跨服转发，权限两边一致
- **渲染顺序**：模板先翻译 `&`（含 PAPI），`${message}` 最后插入 → 无权限玩家无法注入颜色
- **跨服防回环**：代理 Forward ALL 天然不含发送端 + server 名兜底丢弃 + 协议 tag 隔离其他插件
- **数据库降级**：连不上自动转仅内存运行，不阻塞启用
- maven 资源过滤只作用于 `plugin.yml`，配置里的 `${player}` 等占位符不会被 maven 碰

## Velocity 3.5.0 兼容性（源码级验证）

跨服链路的每一环都对过官方源码，不是照 wiki 猜的：

| 环节 | 结论 | 证据 |
|---|---|---|
| `Forward` 子通道 | **Velocity 3.x 原生支持**，入口在后端消息处理的第一行 | Velocity `BackendPlaySessionHandler#handle` → `BungeeCordMessageResponder` |
| 配置开关 | `velocity.toml` 的 `bungee-plugin-message-channel = true`（**默认就是开的**） | Velocity `VelocityConfiguration` |
| 转发报文格式 | **两代代理同构**：`UTF 通道名 \| ushort 长度 \| 数据`，无 `Forwarded` 前缀 | BungeeCord `DownstreamBridge` 与 Velocity `processForwardToServer` 逐行比对；`decodeInbound` 两种形态都兼容 |
| 通道名 | Velocity 发往 1.13+ 后端时把 `BungeeCord` 改写为 `bungeecord:main`；Bukkit 将两者归一化为同一个注册项，本插件注册一次并接受两种回调名称 | Velocity `PluginMessagePacket#encode`、spigot-api `StandardMessenger` 字节码 |
| 防回环 | 代理 Forward ALL 天然排除发送端 + `server` 名兜底丢弃 + 协议 tag 隔离 | 三层机制 |
| 定向转发 | `Forward` 的 mode 可以是**具体子服名**（TELL 回执用它送回发送端子服） | 两代代理 `processForwardToServer` / `DownstreamBridge` 同分支 |
| 空服投递 | Velocity 只向**有玩家在线**的后端投递插件消息，空服收不到（只影响那边的控制台刷屏，玩家视角无感知） | `VelocityRegisteredServer#sendPluginMessage` |

部署清单：
1. 每个子服 `server:` 配成代理 `[servers]` 里**互不相同**的登记名；
2. 确认 `velocity.toml` 里 `bungee-plugin-message-channel` 没被改成 `false`；
3. Velocity 代理**没有**原生 `/msg`，`/msg` `/tell` 直达后端，无需像 BungeeCord 那样在代理侧禁用。

## 路线图（独立开发计划）

- [x] 跨服私聊（协议新增 TELL/TELL_ACK 子类型，回执式送达确认）
- [x] 全服喇叭（聊天/Title/ActionBar/BossBar/音效）、物品上屏及悬浮
- [x] 昵称、聊天颜色、屏蔽列表、快捷触发、AI 可选审核、每日聊天日志、Dialogs
- [ ] 频道系统（channel.yml + 权限 + 跨服已就绪）
- [ ] 更多频道/条件节点与 CustomNameplates 专有 API 适配
- [ ] 实际代理 + 双后端 + 客户端端到端验收
- [x] 聊天颜色样式选择 Dialog（`/liuc dialog chatcolor`）
- [ ] AI 投票禁言（当前仅支持可选审核）
- [ ] DiscordSRV 桥接
