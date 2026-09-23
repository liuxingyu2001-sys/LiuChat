# LiuChat

零第三方框架依赖的聊天插件（不依赖 HandyLib，基础设施全部自建）。

- **平台**：Spigot / Paper 1.21.x（api-version 1.21，Java 21）
- **存储**：SQLite（`plugins/LiuChat/liuchat.db`，驱动经 plugin.yml `libraries` 由 Paper 自动下载）
- **可选依赖**：PlaceholderAPI（软依赖，不装不影响任何功能）

## 功能（v0.1）

| 功能 | 说明 |
|---|---|
| 聊天格式化 | `config.yml` 的 `format` / `console-format`，支持 `&` 颜色、`${server}` `${player}` `${world}` `${message}` 与 PAPI 变量 |
| 禁言 | `/lc mute`，支持 `30s / 5m / 1h30m / 0=永久`，SQLite 持久化，过期自动清除，按 uuid + 名字双查兜底 |
| 聊天冷却 | 按权限节点分级（`liuchat.cooldown.<键>`），`liuchat.cooldown.bypass` 免除 |
| 重复/相似发言检测 | 时间窗口 + Levenshtein 相似度 |
| 私聊 | `/lc tell` |
| 颜色权限 | `liuchat.color` 控制玩家消息里的 `&` 是否生效 |
| PAPI 变量 | `%liuchat_server%` `%liuchat_world%` `%liuchat_muted%` `%liuchat_muted_time%` `%liuchat_muted_reason%` |

## 命令

```
/lc                      查看帮助
/lc reload               重载配置与语言文件        权限: liuchat.reload
/lc mute <玩家> <时长|0永久> [原因]   禁言        权限: liuchat.mute
/lc unmute <玩家>         解除禁言                  权限: liuchat.unmute
/lc tell <玩家> <消息>     私聊                      权限: liuchat.tell（默认所有人）
```

别名：`/lc`

## 构建

```bash
mvn clean package
# 产物: target/Liu-LiuChat-0.1.0.jar
```

## 架构（仿 PlayerChat 的分层，去掉了 HandyLib）

```
com.liu.liuchat
├── LiuChat                 主类：装配一切
├── command/                ChatCommand 接口 + CommandRouter 路由，一个子命令一个类
│   ├── ReloadCommand / MuteCommand / UnmuteCommand / TellCommand
├── listener/ChatListener   禁言 → 冷却 → 重复检测 → 分发（异步线程）
├── service/
│   ├── ChatService         格式化 + 自行分发（后续屏蔽/频道在这里扩展）
│   └── MuteService         禁言缓存 + 落库
├── storage/Database        SQLite（失败降级为仅内存）
├── config/                 ConfigManager（config.yml）/ MessageManager（messages.yml）
├── model/MuteData          数据记录
├── hook/                   PapiHook + LiuChatExpansion（类隔离，没装 PAPI 不加载）
└── util/TextUtil           颜色 / 时长解析 / 相似度
```

与 PlayerChat 的关键差异：
- HandyLib 的 283 处依赖 → 全部自建，约 17 个类，代码完全可控
- 颜色处理顺序保证：**模板先翻译 `&`，`${message}` 最后插入**，无权限玩家无法注入颜色
- maven 资源过滤只作用于 `plugin.yml`，配置文件里的 `${player}` 等占位符不会被 maven 碰

## 路线图（仿 PlayerChat 逐步补齐）

- [ ] 频道系统（channel.yml + 权限 + 跨服）
- [ ] 屏蔽列表（ignore）
- [ ] 大喇叭（horn）、物品上屏（item chat）
- [ ] 昵称（nick）、聊天颜色/样式
- [ ] 命令别名映射（`msg -> lc tell`）
- [ ] AI 审核 + 投票禁言
- [ ] DiscordSRV 桥接
