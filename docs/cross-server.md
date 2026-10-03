# 跨服

跨服链路：BungeeCord plugin messaging（BungeeCord / Velocity 均原生支持）。

```yaml
server: "lobby"          # 每个子服必须配成不同值（跨服消息按它区分来源）

cross-server:
  enable: true           # 群组服跨服聊天开关

storage:
  type: mysql            # sqlite（单服）| mysql（跨服共享禁言）
  sync-interval: 30      # 每 30 秒与数据库对账，同步其他子服的禁言/解禁，0 = 关
  mysql: { host: ..., port: 3306, database: liuchat, username: ..., password: ... }
```

## 跨服私聊链路

本服在线直达 → 不在本服则 TELL 广播（代理 Forward ALL）→ 目标所在服投递
并回 TELL_ACK（定向回发送端子服）→ 发送端凭回执确认送达；超时 3 秒视为离线并补提示。
目标不在线时先本地回显、3 秒后补“消息未送达”——两段式反馈，不静默丢消息。

**跨服协议升级至 9，所有子服须一起更新**，否则旧版子服间的消息会被拒收。

**物品展示的 `itemData` 载荷向后兼容**：字段仍是协议 9 里的同一个 `writeUTF`，内部从单件演进为
`item:`（第一件，旧版只读这个）+ `i1..iN` + `counts:`（每个 token 实际展示的件数，空槽已跳过）。
因此**协议号不变、不需要协调升级**；混跑时旧子服收到 `[i12]`、`[盔甲]` 这类新 token 匹配不到它认识的
`[i]`，会原样显示文本，新子服收到旧版单件则照常显示，全部子服升级后即恢复正常。

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
