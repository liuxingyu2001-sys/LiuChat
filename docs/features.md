# 功能一览（v0.3）

| 功能 | 说明 |
|---|---|
| 聊天格式化 | `chat.yml` 的 `chat.default.format` 有序节点支持 hover/click/clickSuggest/url；`private.to.format` 和 `private.from.format` 独立控制私聊；控制台使用 `console-format` |
| 物品与头像 | `[i]` 主手物品快照，悬浮显示原生数据组件（item_name、Lore、附魔），点击查看只读 GUI；**一条消息可展示多件**：`[i1]`..`[i9]` 快捷栏左起第 1..9 格、`[i1234]` 紧凑写法（一格一个数字、重复去重）、`[i1-9]` 连续区间（含降序 `[i9-1]`）、`[盔甲]`（`[armor]`）头胸腿脚 4 件、`[副手]`（`[offhand]`）1 件，全部不区分大小写，每个 chip 各自悬浮与各自点击预览；空槽自动跳过，`item.max-count`（默认 14）与快照预算超出时从尾部丢弃并只提示发送者；`item.separator` 控制相邻展示的分隔，`item.multi-content` 是 2 件以上时的紧凑样式（单件仍用 `item.content`）；`[i0]`、`[i10]` 等不识别的写法原样当文字；**潜影盒点击查看盒内物品预览**（27 格只读，含染色潜影盒）；**原版收纳袋点击查看袋内快照**（含染色收纳袋，超出一页时翻页，只读）；`${head}` 显示 UUID 头像。CE 物品名支持 zh_cn 资源包翻译键，CE 聊天表情保留其配置的图片与悬浮提示 |
| 灵魂空间戒指预览 | 安装 SoulSpace 后，`[i]` 展示戒指可点击打开展示者空间的**只读预览**（54 格分页翻阅、无限堆叠标注真实数量、拿不走）；**按数量排序**（默认从多到少，界面按钮可切换从少到多/空间原顺序，`item.soulspace.sort` 配置默认值）；需 `liuchat.soulspace.preview` 权限，无权限点击仍是普通物品预览；每台服务器每条目仅首次点击读一次数据（本服在线零 IO），共用 MySQL 的多服跨服可预览 |
| 公屏 AI 聊天 | AI 像真实玩家一样参与公共聊天：公屏点名（@AI 或提到它的名字）、配置的关键词命中时回复；`ai.chat.keyword-trigger.enable` 可单独关闭关键词触发，`mention-player` 控制关键词回复是否先 `@` 发言玩家；可配置有人发言时随机插话，或定时主动发言（需本服有玩家在线）；**固定聊天格式**（`ai.chat.format` 支持 `${player}`、`${message}` 和 `${head}` 头像；`ai.chat.head-uuid` 可指定皮肤；支持 `&` 色码与 MiniMessage 混写如 `<gradient:..>`；假人拿不到玩家/其他插件的占位符输出，不解析其他变量；跨服回复携带发送服的格式与头像设置；`/liuc ignore` 屏蔽、聊天日志都生效）；需开 `ai.assistant.enable` 与 `ai.chat.enable` |
| **@ 提及** | `chat.yml` 的 `at` 节点：输入 `@玩家ID` 或直接输入在线玩家 ID（自动补 @），被 @ 的玩家收到提示音（`at.sound`，默认铁砧 `BLOCK_ANVIL_LAND`），玩家 ID 按 `atColor` 高亮并保留消息原有颜色/样式（`keepAt` 控制是否显示 @）；高亮在颜色权限裁决之后注入，**无 `liuchat.color` 权限的玩家 @ 人同样变色**；跨服在线玩家同样可被 @ |
| **全服喇叭** | `/horn` 或 `/lb`，支持聊天、Title、ActionBar、BossBar 与音效；禁言或超长喊话不扣余额，跨服模式会预检代理包长度；**控制台可直接使用**（无需余额与禁言检查） |
| 快捷触发 | `shortcut.yml` 正则替换，支持 hover、点击命令/建议/复制/URL |
| 屏蔽与资料 | `/liuc ignore`、`unignore`、`ignorelist`、`nick`；MySQL 共享持久化 |
| 扩展 | PAPI（含 CustomNameplates 的 PAPI 占位符）、Paper Dialog 可配置布局、聊天菜单界面（`/liuc menu`：DialogMenu 内置菜单，或 ArcMenu 聊天工作区 `liuchat:chat`，未安装则自动降级）、独立开关的 AI 聊天审核与私聊助手（共享/按玩家记忆可切，答案缓存、输出上限）、每日聊天日志 |
| **跨服聊天** | 两种传输：proxy（默认，经代理转发到其他子服）/ redis（`cross-server.transport: redis`，Redis pub/sub 直连，空服也能收发、不依赖在线玩家载体，发布失败自动回落代理）；收端按自己的 format 渲染、`${server}` 显示发送端子服；无代理/单服开着无副作用 |
| **QQ 官方机器人** | `qq.yml` 配置 AppID/AppSecret 后通过官方 WebSocket 网关双向桥接：群聊 @机器人 / 私聊 / 频道 @ / 频道私信进游戏，本服公屏可按目标发到 QQ；token 自动刷新、心跳、断线退避、op 7 resume、op 9 重新鉴权；优先在 5 分钟窗口内被动回复，超出才尝试主动消息；QQ 正文不能注入颜色/占位符，不执行命令 |
| **好友系统** | **Sparrow UI 窗口 + 铁砧输入**：主界面直接展示好友（头像/在线/时间），左键直接传送（同服落地、跨服经 HuskHomes）、右键写邮件、Shift+右键删除；请求/已发送分页，接受/拒绝/撤回、在线玩家选择器/铁砧输入添加；双向关系 + 单向请求（过期/上限）、在线状态、上线通知（跨服 last_seen 去重，重复上线有冷却）；SQLite/MySQL 持久化；聊天通知可点击打开界面；`FriendApi` + 事件 |
| **邮件系统** | **Sparrow UI 窗口 + 铁砧/正文 Dialog**：收件箱/发件箱分页、阅读/标记已读/回复、发送状态（对方已读时间/附件领取时间/已撤回）、撤回未读、收发双方独立删除、附件领取、写邮件（收件人选择器/铁砧 + Dialog 主题/正文/附带主手）；离线收件、过期清理、背包检查与防重复领取；附件发送前扣除、失败退回；聊天通知可点击；`MailApi` + 事件 |
| **顶层私聊命令** | 直接注册 `/msg`（别名 `/w` `/whisper`）与 `/tell`，全部带 tab 补全；跨服在线名单由子服同步供玩家名补全；**控制台可作为发送者** |
| **定向私密投递** | 公开 API `LiuChat#deliverPrivate(玩家, 目标名, BaseComponent...)`：把预格式化富文本只发给指定玩家（本服或跨服），不参与忽略/禁言判断；经济插件转账提示等场景使用 |
| **消息前缀** | `messages.yml` 的 `prefix-enable` 开关（默认 `true`）控制所有插件消息是否带 `prefix` 前缀，关闭后只发正文，`/liuc reload` 生效 |
| **跨服私聊** | 目标在其他子服也能收到；TELL 广播只在目标所在服落地，**回执机制**保证送达 —— 3 秒未收到回执则提示“不在线，消息未送达”，不会静默丢失；`/reply`（`/r`）回复本次插件运行期间最近成功收发私聊的对象，跨服发件收到回执后更新 |
| 禁言 | `/mute`（亦可 `/liuc mute`），`30s / 5m / 1h30m / 0=永久`，过期自动清；uuid + 名字双查兜底 |
| **MySQL 跨服共享禁言** | 写库并广播 MUTE/UNMUTE 到其他子服内存（异步落库，命令不阻塞主线程）；发送服无在线玩家或目标服断线时由共享库的定时对账补漏 |
| 聊天冷却 | 按权限节点分级（`liuchat.cooldown.<键>`），`liuchat.cooldown.bypass` 免除 |
| 重复/相似发言检测 | `repeat-check` 时间窗口 + Levenshtein 相似度：完全相同不受 `min-length` 限制，相似度比较要求长度达标；**含物品展示 token（`[i]`/`[i12]`/`[盔甲]` 等）的消息整条跳过**（每次展示的物品可能不同，同文案不算刷屏） |
| 颜色权限 | `liuchat.color` 控制玩家消息里的其他 `&` 颜色代码与安全的样式标签；所有玩家可在公聊、私聊和喇叭中使用 `&f`、`&r` 重置颜色（本服/跨服同一套裁决） |
| PAPI 变量 | `%liuchat_nick%`（未设置则原名）、`%liuchat_nick_raw%`（未设置则空）、`%liuchat_server%` `%liuchat_world%` `%liuchat_muted%` `%liuchat_muted_time%` `%liuchat_muted_reason%`；面板/菜单用的 `%liuchat_ignore_count%`（屏蔽数）、`%liuchat_recent_contact%`（最近私聊对象，无则 `-`）、`%liuchat_horn_balance%`（喇叭余额，5 秒缓存，不可用为 `-`）、`%liuchat_cooldown%`（剩余冷却秒数）、`%liuchat_ai%`（AI 是否可用） |
