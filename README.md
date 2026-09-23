# LiuChat

零第三方框架依赖的聊天插件（不依赖 HandyLib，基础设施全部自建）。

- **平台**：Spigot / Paper 1.21.x（api-version 1.21，Java 21）
- **存储**：SQLite（单服）/ MySQL（跨服共享），驱动经 plugin.yml `libraries` 由 Paper 自动下载
- **跨服**：BungeeCord plugin messaging（BungeeCord / Velocity 均原生支持）
- **可选依赖**：PlaceholderAPI（软依赖，不装不影响任何功能）

## 功能（v0.2）

| 功能 | 说明 |
|---|---|
| 聊天格式化 | `format` / `console-format`，支持 `&` 颜色、`${server}` `${player}` `${world}` `${message}` 与 PAPI 变量 |
| **跨服聊天** | 经代理转发到其他子服，收端按自己的 format 渲染、`${server}` 显示发送端子服；无代理/单服开着无副作用 |
| **顶层私聊命令** | 直接注册 `/msg`（别名 `/w` `/whisper` `/pm`）与 `/tell`，全部带 tab 补全 |
| 禁言 | `/lc mute`，`30s / 5m / 1h30m / 0=永久`，过期自动清；uuid + 名字双查兜底 |
| **MySQL 跨服共享禁言** | 各子服连同一库，`sync-interval` 定时对账，A 服禁言 B 服同步生效 |
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
/msg <玩家> <消息>                私聊（别名 w/whisper/pm）  权限: liuchat.tell
/tell <玩家> <消息>               私聊             权限: liuchat.tell
```

`/lc` 别名：`/lc`

> **部署提示**：
> - plugin.yml 直接声明 `msg`/`tell` 会覆盖原版同名命令（EssentialsX 同一机制）；
> - BungeeCord 代理端自带 `/msg`，若它把输入拦在了代理上，需在代理侧禁用（bungee.yml `disabledCommands`）；
> - 跨服消息里的 PAPI 变量不会解析（发送端玩家不在收端，没有玩家上下文），`${server}` `${player}` `${message}` 正常。

## 配置要点

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
# 产物: target/Liu-LiuChat-0.2.0.jar  （含单元测试）
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
│   ├── CrossServerService  BungeeCord plugin messaging 收发（防回环/防串台）
│   ├── CrossServerCodec    跨服协议编解码（纯 Java，有单测）
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

## 路线图（仿 PlayerChat 逐步补齐）

- [ ] 频道系统（channel.yml + 权限 + 跨服已就绪）
- [ ] 屏蔽列表（ignore，跨服需共享存储）
- [ ] 大喇叭（horn）、物品上屏（item chat）
- [ ] 昵称（nick）、聊天颜色/样式
- [ ] 跨服私聊（当前 /msg 仅本服；协议已带 uuid，扩展子通道即可）
- [ ] AI 审核 + 投票禁言
- [ ] DiscordSRV 桥接
