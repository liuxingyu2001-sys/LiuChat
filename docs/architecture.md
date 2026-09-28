# 架构

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
