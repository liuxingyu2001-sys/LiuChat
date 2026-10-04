# 跨服

跨服链路（二选一，`cross-server.transport`）：

| transport | 机制 | 特点 |
|---|---|---|
| `proxy`（默认） | BungeeCord plugin messaging（BungeeCord / Velocity 均原生支持） | 零额外组件；空服收不到、发送需在线玩家载体 |
| `redis` | Redis pub/sub 直连（手写 RESP，零第三方依赖） | 空服也能收发、不依赖玩家载体；发布失败自动回落 proxy |

```yaml
server: "lobby"          # 每个子服必须配成不同值（跨服消息按它区分来源）

cross-server:
  enable: true           # 群组服跨服聊天开关
  transport: proxy       # proxy | redis
  redis: { host: 127.0.0.1, port: 6379, password: '', db: 0, channel: liuchat }

storage:
  type: mysql            # sqlite（单服）| mysql（跨服共享禁言）
  sync-interval: 30      # 每 30 秒与数据库对账，同步其他子服的禁言/解禁，0 = 关
  mysql: { host: ..., port: 3306, database: liuchat, username: ..., password: ... }
```

## Redis 传输（transport: redis）

- **复用同一套协议载荷**（`CrossServerCodec` 协议 9，无协议号变更、不需要协调升级）：
  发送时 `stripForward` 把 Forward 外层剥成接收帧，接收时与代理链路走同一个
  `decodeInbound` 分发口；HMAC 密钥校验同样生效。
- **只发一条、两条都听**：每条消息只走当前可用的一条链路（Redis 优先，发布失败回落代理），
  接收端代理监听与 Redis 订阅同时开着——天然无重复投递，链路互为兑底。
- **自回环防护**：Redis 会把自己发布的包回传；各消息类型的 origin 校验 + MUTE/UNMUTE
  幂等处理已覆盖，不会重复渲染/重复改状态。
- **失败语义**：连接/发布失败 → 当条消息回落代理；发布失败后 5 秒冷却（冷却内直接走代理，
  避免主线程反复付连接成本）；订阅线程指数退避自动重连（1s → 30s）。
- **部署要求**：全组子服的 `redis` 指向同一实例、相同 `channel`/`db`/密码；
  跨服 `secret` 照常必填（既是签名密钥，也隔离共用 Redis 的不同群组）。
- 单元测试不依赖真实 Redis：RESP 编解码 + 假服务器链路见 `RedisBusTest`。

## 跨服私聊链路

本服在线直达 → 不在本服则 TELL 广播（代理 Forward ALL）→ 目标所在服投递
并回 TELL_ACK（定向回发送端子服）→ 发送端凭回执确认送达；超时 3 秒视为离线并补提示。
目标不在线时先本地回显、3 秒后补“消息未送达”——两段式反馈，不静默丢消息。

**跨服协议升级至 9，所有子服须一起更新**，否则旧版子服间的消息会被拒收。

**物品展示的 `itemData` 载荷向后兼容**：字段仍是协议 9 里的同一个 `writeUTF`，内部从单件演进为
`item:`（第一件，旧版只读这个）+ `i1..iN` + `counts:`（每个 token 实际展示的件数，空槽已跳过）。
因此**协议号不变、不需要协调升级**；混跑时旧子服收到 `[i12]`、`[盔甲]` 这类新 token 匹配不到它认识的
`[i]`，会原样显示文本，新子服收到旧版单件则照常显示，全部子服升级后即恢复正常。

## 故障自查与本次加固（0.6.7）

链路本身没问题但消息过不去时，按这个顺序查：

| 现象 | 原因 | 现在的表现 |
|---|---|---|
| 改了 `cross-server.secret` 后跨服突然全断 | **原实现密钥只在启动时读一次**，改完 `/lc reload` 不生效，签出来的包被所有对端验签拒绝并**静默丢弃** | `/lc reload` 已接上密钥、`enable` 开关、**Redis 连接参数**（改了就地重连）三者的热生效 |
| 跨服像"没反应"、零日志 | 两个子服 `server:` 撞名（都用了默认值 `server`），防回环把对方消息全当本服的丢掉 | 启动时 `server:` 为空/为默认值会直接 **WARNING 提示** |
| 链路断了但之前已经报过一次错 | `warnOnce` 原是「第一次之后**永久**静默」，后续任何故障都看不到日志 | 改为 **60 秒时间窗**限流，会持续报 |
| 消息有时解析不了 | 密钥不一致 / 版本协议不符，与「同通道别的插件的包」在返回值上无法区分 | 协议层单独计数，**每 60 秒最多一条 WARNING** 汇总 |
| 崩溃的子服旧名单残留 | `PRESENCE` 只增不减，残留要等 150 秒 TTL，期间 @提及/tab 补全指向不在的人 | 识别**新一轮完整名单并整体重置**，崩溃服最长残留一个广播周期 |
| MySQL 重启 / `wait_timeout` 后功能永久失效 | `ready` 一旦为 true 终身不变，断线后所有写入失败到 MC 服重启为止 | 查询失败时检测连接健康度，**坏了自动重连**并降为 WARNING |

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
