# QQ 官方机器人

LiuChat 内置 QQ 官方机器人（QQ 开放平台）的 WebSocket 桥接，不依赖 OneBot / NapCat 等服务。
使用 JDK 自带的 `HttpClient` 与 `WebSocket`，不需要额外插件或动态库。

- **QQ -> 游戏**：群聊 @机器人、私聊、频道 @机器人、频道私信进入游戏公屏（固定格式）。
- **游戏 -> QQ**：本服公聊按配置发到群/私聊/频道；只转发本服，避免多子服重复。
- **连接管理**：AppAccessToken 自动刷新、心跳、断线指数退避、`op 7` resume、`op 9` 重新鉴权。
- **安全**：QQ 消息不会执行命令；正文最后插入，不能注入颜色码或占位符；仍受 `/liuc ignore` 屏蔽与忽略列表约束。

## 1. 开放平台准备

1. 在 [QQ 开放平台](https://q.qq.com/) 创建机器人，取得 **AppID** 与 **AppSecret**。
2. 在机器人后台开启需要的消息事件：群聊 @机器人、私聊、频道 @机器人或私信。
3. 按平台要求配置 IP 白名单 / 沙箱环境；正式上线前先用 `sandbox: true` 验证。
4. 同一个机器人只应在一台子服启用。多台子服同时连接会让平台把事件随机分发到其中一台，
   表现为消息时有时无。需要多服各自接入时，请为每台服创建不同的机器人。

官方文档：

- [WebSocket 方式](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/event-emit/websocket.html)
- [获取访问凭证](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/access-token.html)
- [发送群聊消息](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_messages.post.html)

## 2. 配置

配置在每台子服本地的 `plugins/LiuChat/qq.yml`，**不随共享配置同步**。修改后执行 `/liuc reload`；
只有凭证、网关、intents 或超时变化才重建连接，只改格式/目标不会断线。

```yaml
enable: true
app-id: '102012345'
app-secret: '你的 AppSecret'
sandbox: false

intents: [group, c2c, guild] # 按机器人实际开通的事件选择

receive:
  enable: true
  channels: [] # 空 = 全部；也可只填某个群 openid / 用户 openid / 频道 ID
  format: '&8[&9QQ&8] &b${sender}&8» &f${message}'
  cross-server: false
  log: true
  log-targets: true

send:
  enable: true
  targets:
    - group:群openid
  format: '[${server}] ${player}: ${message}'
```

### 2.1 事件订阅

| 值 | 事件 | 用途 |
|---|---|---|
| `group` | `GROUP_AT_MESSAGE_CREATE` | 群聊里 @机器人 |
| `c2c` | `C2C_MESSAGE_CREATE` | 机器人私聊 |
| `guild` | `AT_MESSAGE_CREATE` | 公域频道 @机器人（`PUBLIC_GUILD_MESSAGES`） |
| `guild-private` | `AT_MESSAGE_CREATE` | 私域频道 @机器人（`GUILD_MESSAGES`） |
| `direct` | `DIRECT_MESSAGE_CREATE` | 频道私信 |
| `all` | 群聊 + 私聊 + 公域频道 + 频道私信 | 调试用；私域频道请显式写 `guild-private` |

`intents` 同时决定订阅位与接收过滤：例如只写 `[group]` 时，即使网关也推送私聊事件，
LiuChat 也不会把它发进游戏。需要订阅未预设的事件时用 `intents-bits` 直接加位
（十进制或 `0x` 十六进制，例如 `intents-bits: 0x2000000`）。

### 2.2 找到群 / 用户 / 频道 ID

开启 `receive.log-targets: true` 后，某个会话第一次收到消息时控制台会打印：

```text
QQ 会话首次收到消息: group:1A2B3C...（可填入 qq.yml 的 send.targets）
```

把 `group:...` 原样填入 `send.targets` 即可。私聊是 `c2c:<用户openid>`，频道是
`channel:<频道ID>`，频道私信是 `direct:<频道ID>`。也可以不带前缀，此时按 `group` 处理。

### 2.3 接收格式

`receive.format` 支持：

| 占位符 | 内容 |
|---|---|
| `${sender}` / `${player}` | QQ 昵称；群聊/私聊拿不到昵称时是 `fallback-name·openid 尾号` |
| `${message}` | 消息正文 |
| `${source}` | `群聊` / `私聊` / `频道` / `私信` |
| `${target}` / `${group}` | 会话 ID |

模板先按 `&` 色码 / MiniMessage 上色，正文最后插入，因此正文里的 `&`、`${...}`
会原样显示，不会被当成格式；正文中的 `§` 颜色码会被清掉。

### 2.4 发送格式与目标

`send.format` 支持 `${server}`、`${player}`、`${message}`，可写多条 `targets`。
QQ 官方机器人的主动消息通常需要额外权限，因此：

- 收到 QQ 消息后的 `send.passive-window-seconds`（默认 240 秒）内，游戏消息会附带最近一条
  QQ 消息的 `msg_id` + 递增 `msg_seq`，作为官方允许的**被动回复**发送；
- 超出窗口才尝试主动消息，可能被平台拒绝；日志会打印 HTTP 状态与返回体摘要。
- `send.interval-ms` 控制两条消息的最小间隔；发送队列最多缓存 256 条，满时丢弃并限频告警。
- 游戏消息里的 `§` / `&` 颜色码会被去掉；物品展示 token 目前按原文（如 `[i]`）发送，
  不会渲染成 QQ 图片。

只转发**本服**的全局公屏。其他子服的聊天不会再次触发本桥接；如果希望把其他子服的聊天也发到 QQ，
请在每台子服使用不同的机器人并分别开启 `send`（同一个机器人只连一台服），或使用独立的转发插件。
如果希望 QQ 消息同步到其他子服，开启 `receive.cross-server: true`；接收服沿用发送服的
`receive.format`，不会套用各服自己的 `chat.yml`。

## 3. 运行与排查

- 控制台出现 `QQ 机器人已连接（WebSocket READY...）` 才算成功。
- 认证失败：检查 AppID/AppSecret、机器人是否已上线、IP 白名单、`sandbox` 是否与凭证匹配。
- 能收不能发：多为主动消息权限不足；先在 5 分钟内由 QQ 侧发一条消息再测试被动回复。
- 频道 @ 收不到：公域机器人用 `guild`，私域机器人用 `guild-private`；同时确认后台已订阅事件。
- 多子服重复或丢消息：同一个机器人只保留一台服的 `enable: true`；其他子服若要各自把聊天发到 QQ，请为它们配置不同的机器人。
- `/liuc reload` 会在凭证/网关/intents 变化时自动重连；停服会关闭 WebSocket 与发送线程。

聊天日志中 QQ 入站消息类型为 `EXTERNAL`。QQ 消息和游戏消息都不会执行命令，
管理员不需要为机器人额外开放 `liuchat.*` 权限。
