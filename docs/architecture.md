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
│   ├── ChatService         本服广播 + 跨服落地渲染（broadcastRemote；逐玩家渲染合并为一次）
│   ├── CrossServerService  跨服收发总入口（transport: proxy=代理插件消息 / redis=Redis pub/sub，同协议双链路）
│   ├── CrossServerCodec    跨服协议编解码（CHAT/TELL/ACK/HORN/MUTE/UNMUTE）+ stripForward
│   ├── RedisBus            可选 Redis 传输层（手写 RESP，零第三方依赖，失败自动回落代理）
│   ├── TellService         私聊：本服直达 + 跨服回执（送达确认/超时离线提示）
│   └── MuteService         禁言缓存（uuid + 名字索引）+ 对账式全量刷新（跨服同步）
├── storage/                Database 接口 + AbstractJdbcDatabase
│   ├── DbExecutor          单线程任务队列：写异步落库（主线程不等往返）、读带读己之写屏障
│   ├── SqliteDatabase / MysqlDatabase（方言在子类）+ DatabaseFactory
├── config/                 ConfigManager / MessageManager
├── model/MuteData          数据记录
├── hook/                   PapiHook + LiuChatExpansion（类隔离）
└── util/TextUtil           颜色 / 时长解析 / 相似度
```

关键设计：
- **颜色裁决在监听器做一次**，处理后的文本同时用于本服广播与跨服转发，权限两边一致
- **渲染顺序**：模板先翻译 `&`（含 PAPI），`${message}` 最后插入 → 无权限玩家无法注入颜色
- **跨服防回环**：代理 Forward ALL 天然不含发送端 + server 名兜底丢弃 + 协议 tag 隔离其他插件；
  Redis 链路在此之上另加 origin 校验与 MUTE/UNMUTE 幂等（自回环无副作用）
- **存储异步化**：JDBC 全部串行到专用线程（DbExecutor）；命令写库不阻塞主线程，
  读操作自动排在此前所有写之后（对账不漏未落盘数据），停服先排空队列再关连接
- **数据库降级**：连不上自动转仅内存运行，不阻塞启用
- maven 资源过滤只作用于 `plugin.yml`，配置里的 `${player}` 等占位符不会被 maven 碰
