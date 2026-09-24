# LiuChat

零第三方框架依赖的聊天插件（不依赖 HandyLib，基础设施全部自建）。

- **平台**：Paper / Leaf 1.21.11（Java 21；完整物品悬浮及 Dialogs 使用 Paper API）
- **存储**：SQLite（单服）/ MySQL（跨服共享），驱动经 plugin.yml `libraries` 由 Paper 自动下载
- **跨服**：BungeeCord plugin messaging（BungeeCord / Velocity 均原生支持）
- **可选依赖**：PlaceholderAPI（含 CustomNameplates 的 PAPI 占位符）、CraftEngine；无这些插件时基础聊天可用。

## 功能（v0.3）

| 功能 | 说明 |
|---|---|
| 聊天格式化 | `chat.yml` 的 `chat.default.format` 有序节点支持 hover/click/clickSuggest/url；控制台使用 `console-format` |
| 物品与头像 | `[i]` 主手物品快照，悬浮显示原生数据组件（item_name、Lore、附魔），点击查看只读 GUI；`${head}` 显示 UUID 头像。CE 物品名支持 zh_cn 资源包翻译键 |
| 全服喇叭 | `/horn` 或 `/lc horn`，可配置聊天/Title/ActionBar/BossBar 与音效 |
| 快捷触发 | `shortcut.yml` 正则替换，支持 hover、点击命令/建议/复制/URL |
| 屏蔽与资料 | `/lc ignore`、`unignore`、`ignorelist`、`nick`；MySQL 共享持久化 |
| 扩展 | PAPI（含 CustomNameplates 的 PAPI 占位符）、Paper Dialog 可配置布局、独立开关的 AI 聊天审核与私聊助手、每日聊天日志 |
| **跨服聊天** | 经代理转发到其他子服，收端按自己的 format 渲染、`${server}` 显示发送端子服；无代理/单服开着无副作用 |
| **顶层私聊命令** | 直接注册 `/msg`（别名 `/w` `/whisper` `/pm`）与 `/tell`，全部带 tab 补全 |
| **跨服私聊** | 目标在其他子服也能收到；TELL 广播只在目标所在服落地，**回执机制**保证送达 —— 3 秒未收到回执则提示“不在线，消息未送达”，不会静默丢失 |
| 禁言 | `/lc mute`，`30s / 5m / 1h30m / 0=永久`，过期自动清；uuid + 名字双查兜底 |
| **MySQL 跨服共享禁言** | 写库并广播 MUTE/UNMUTE 到其他子服内存；发送服无在线玩家或目标服断线时由共享库的定时对账补漏 |
| 聊天冷却 | 按权限节点分级（`liuchat.cooldown.<键>`），`liuchat.cooldown.bypass` 免除 |
| 重复/相似发言检测 | 时间窗口 + Levenshtein 相似度 |
| 颜色权限 | `liuchat.color` 控制玩家消息里的 `&` 是否生效（本服/跨服同一套裁决） |
| PAPI 变量 | `%liuchat_server%` `%liuchat_world%` `%liuchat_muted%` `%liuchat_muted_time%` `%liuchat_muted_reason%` |

## 命令（全部支持 tab 补全）

```
/lc                              查看帮助
/lc reload                       重载配置        权限: liuchat.reload
/lc mute <玩家> <时长|0永久> [原因]  禁言           权限: liuchat.mute
/lc unmute <玩家>                 解除禁言         权限: liuchat.unmute
/msg <玩家> <消息>                私聊（别名 w/whisper/pm，跨服）  权限: liuchat.tell
/tell <玩家> <消息>               私聊（跨服）             权限: liuchat.tell
/horn <消息>                      全服喇叭             权限: liuchat.horn
/lc ignore <玩家>                 屏蔽玩家
/lc unignore <玩家>               取消屏蔽
/lc ignorelist                    查看屏蔽列表
/lc nick <昵称|off>               聊天昵称             权限: liuchat.nick（默认 OP）
/lc chatcolor <颜色|off>           聊天颜色             权限: liuchat.chatcolor
/lc ask <问题>                    使用默认 AI 助手    权限: liuchat.ask
/lc ask <助手名> <问题>          使用指定助手       权限: liuchat.ask
/lc ask list                     列出助手
/lc audit <1-24>                 审查最近 N 小时聊天  权限: liuchat.audit
/lc dialog ai                    AI 聊天助手 Dialog
/lc dialog chatcolor              聊天颜色与渐变 Dialog
```

跨服私聊链路：本服在线直达 → 不在本服则 TELL 广播（代理 Forward ALL）→ 目标所在服投递
并回 TELL_ACK（定向回发送端子服）→ 发送端凭回执确认送达；超时 3 秒视为离线并补提示。
目标不在线时先本地回显、3 秒后补“消息未送达”——两段式反馈，不静默丢消息。

`/lc` 别名：`/lc`

> **部署提示**：
> - plugin.yml 直接声明 `msg`/`tell` 会覆盖原版同名命令（EssentialsX 同一机制）；
> - BungeeCord 代理端自带 `/msg`，若它把输入拦在了代理上，需在代理侧禁用（bungee.yml `disabledCommands`）；
> - 跨服 PAPI 变量在发送服预解析并传输；接收服不直接解析远端玩家。所有子服需部署同一协议版本；CustomNameplates 需启用 PAPI。

## 配置要点

聊天交互在 `chat.yml`，正则快捷触发在 `shortcut.yml`，Paper Dialog 快捷操作在 `dialogs.yml`；`config.yml` 控制喇叭、AI、每日聊天记录与跨服。启动或 `/lc reload` 自动补全缺失键，不覆盖现有值。记录写到 `plugins/LiuChat/logs/YYYY-MM-DD.log`。`liuchat.color` 只允许玩家输入颜色/样式标签，不能注入点击指令。

CMI 同名指令由 `commands.prefer-liuchat: true` 将 `/msg`、`/tell`、`/w`、`/pm`、`/horn` 转到 `liuchat:` 命名空间；不自动修改服务器 `commands.yml`。

`ai.enable` 是即时本地屏蔽和历史采集的总开关：屏蔽词（含 `*`、`?` 有限通配）、数字联系方式、IPv4 和域名在发送时直接拦截，未命中则立即广播。`ai.review.enable: true` 才启动定时 AI 审查，默认每 60 分钟分析最近 1 小时本服已发送的公开聊天；`ai.review.manual-enable: true` 允许管理员用 `/lc audit <1-24>` 审查指定小时数。两项开关互不影响。记录单独存于 `audit-history/YYYY-MM-DD.jsonl`，不依赖可自定义格式的普通聊天日志；报告写入 `audit-reports/` 并通知 `liuchat.audit.notify` 管理员。每次最多提交最近 250 条、每条最多 300 字，报告记录超量丢弃数；模型只生成待人工复核的报告，不自动禁言。旧版仅有普通聊天日志的历史无法倒查；跨服需在各子服分别执行审核。审核 URL/模型沿用 `ai.url` / `ai.model`，`ai.review.prompt` 与 `ai.review.timeout-seconds` 单独配置。`/lc reload` 可切换定时/手动开关。

`ai.review.keywords` 支持有限通配：普通词自动容忍每两个字符之间插入最多 2 个任意字符（`cnm` 可拦 `c.n.m`、`c你n好m`），`*` 匹配最多 8 字，`?` 匹配 1 字；所有命中均直接屏蔽，不再调用 AI。短词可能误拦，请针对服务器用语调整；已有配置的关键词列表不会被自动覆盖，需要手动加入 `cnm` 等新关键词。

AI 助手独立于审核，可单独开启 `ai.assistant.enable`。在 `ai.assistant.profiles` 下配置多个助手及其 `skill` 目录，例如 `profiles.bot.skill: bot`、`profiles.guide.skill: guide`。`/lc ask <问题>` 使用 `ai.assistant.default` 指定的助手（默认 `bot`），`/lc ask guide <问题>` 选择其他助手；`/lc ask list` 列出助手。无需 `@`。每个 skill 位于 `plugins/LiuChat/skills/<目录名>/`，读取 `SKILL.md` 及下层 `.md`/`.txt`；`/lc reload` 刷新。skill 仅作为提示词知识，不会执行脚本或调用工具。

聊天颜色现在只通过 `/lc dialog chatcolor` 打开，支持单色和渐变色。单色模式使用第一组红、绿、蓝滑块；渐变模式另外使用结束色的红、绿、蓝三组滑块。红 R、绿 G、蓝 B 分别代表组成颜色的三种原色通道，数值范围都是 0-255。保存后会写入聊天资料，并支持 `<gradient:#1afff0:#2ea4ff>` 格式。

`/lc dialog ai` 是独立的 AI 对话 Dialog。回答正文宽度由 `config.yml` 中的 `ai.assistant.dialog-width` 控制，默认 520，范围 100-800。

NPC 助手使用 Citizens 软依赖，不需要 CustomNameplates：在 `npc-assistants.yml` 中按 Citizens NPC ID 绑定助手名（例如 `npcs.'12'.assistant: bot`）；右键该 NPC 打开专用提问 Dialog，回答仅对点击者可见。配置支持 `max-distance`、`cancel-other-actions`；未绑定的 NPC 不受影响。**本轮不做聊天气泡。** `npc-assistants.yml` 和技能在 `/lc reload` 后重载。

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

## 架构（仿 PlayerChat 的分层，去掉了 HandyLib）

```
com.liu.liuchat
├── LiuChat                 主类：装配一切
├── command/                ChatCommand 接口 + CommandRouter（/lc 子命令）
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

## 路线图（仿 PlayerChat 逐步补齐）

- [x] 跨服私聊（协议新增 TELL/TELL_ACK 子类型，回执式送达确认）
- [x] 全服喇叭（聊天/Title/ActionBar/BossBar/音效）、物品上屏及悬浮
- [x] 昵称、聊天颜色、屏蔽列表、快捷触发、AI 可选审核、每日聊天日志、Dialogs
- [ ] 频道系统（channel.yml + 权限 + 跨服已就绪）
- [ ] 更多频道/条件节点与 CustomNameplates 专有 API 适配
- [ ] 实际代理 + 双后端 + 客户端端到端验收
- [ ] 聊天颜色样式选择 GUI（当前支持 `/lc chatcolor` 命令）
- [ ] AI 投票禁言（当前仅支持可选审核）
- [ ] DiscordSRV 桥接
