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
│   ├── RedisBus            可选 Redis 传输层（RESP，有界串行异步发布，零第三方依赖）
│   ├── MessageTransport    异步传输结果接口；FallbackTransport 统一 Redis → 代理回落
│   ├── PendingRequests     请求确认/失败/超时管理，完成后取消定时任务，关闭时清理
│   ├── TellService         私聊：本服直达 + 跨服回执（送达确认/超时离线提示）
│   └── MuteService         禁言缓存（uuid + 名字索引）+ 对账式全量刷新（跨服同步）
├── storage/                Database 接口 + AbstractJdbcDatabase
│   ├── DbExecutor          单线程任务队列：写异步落库（主线程不等往返）、读带读己之写屏障
│   ├── SqliteDatabase / MysqlDatabase（方言在子类）+ DatabaseFactory
├── config/                 ConfigManager / MessageManager
├── model/MuteData          数据记录
├── hook/                   PapiHook + LiuChatExpansion（类隔离）
└── util/
    ├── TextUtil            颜色 / 时长解析 / 相似度
    └── Schedulers          Paper / Folia 调度器兼容层（全局/异步/实体区域）
```

关键设计：
- **颜色裁决在监听器做一次**，处理后的文本同时用于本服广播与跨服转发，权限两边一致
- **渲染顺序**：模板先翻译 `&`（含 PAPI），`${message}` 最后插入 → 无权限玩家无法注入颜色
- **跨服防回环**：代理 Forward ALL 天然不含发送端 + server 名兜底丢弃 + 协议 tag 隔离其他插件；
  Redis 链路在此之上另加 origin 校验与 MUTE/UNMUTE 幂等（自回环无副作用）
- **跨服异步发送**：Redis 发布使用单线程、256 条有界队列，保持入队顺序，不在玩家区域或
  全局线程等待网络；队列满、连接失败或无订阅者时尝试代理回落。代理发送在载体玩家区域
  执行，无在线载体则返回失败。传输成功只表示链路接受，私聊仍需 3 秒内收到 TELL_ACK；
  确认、失败、超时只产生一次结果，跨服关闭时取消待确认请求。验签保持原样。
- **跨服重复投递防护**：协议 10 为每个发送包加入唯一 UUID，标识纳入签名；同一发送包
  经 Redis 和代理回落时 ID 不变。接收端用最多 8192 条、2 分钟有效期的缓存去重；
  连续发送相同内容因 ID 不同仍能正常显示。私聊按来源服 + 请求 ID 去重，重复请求只回
  ACK，不再次显示或记录。协议 9 仍可接收，但其普通消息无 ID，不能可靠去重。
  新发送端使用协议 10，旧节点无法接收，部署时必须统一更新所有群组服。
  Pub/Sub 断线消息不补发；缓存过期、容量淘汰或进程重启后仍可能重复，不保证恰好一次。
- **存储异步化**：JDBC 全部串行到专用线程（DbExecutor）；命令写库不阻塞主线程，
  读操作自动排在此前所有写之后（对账不漏未落盘数据），停服先排空队列再关连接
- **数据库降级**：连不上自动转仅内存运行，不阻塞启用
- **Folia 调度兼容**：禁止直接调用 `Bukkit.getScheduler()`（Folia 上抛异常）。全服级逻辑
  （广播/计时器/异步 IO）走 `Schedulers.run*`，触碰玩家/实体的逻辑走
  `Schedulers.runFor` / `forEachPlayer`，投递到该实体所在的区域线程；聊天事件等任意线程
  回调统一经兼容层回到区域线程。运行环境由 `Schedulers.isFolia()` 自动探测，Paper 上
  仍走原生 Bukkit 调度器，行为不变
- maven 资源过滤只作用于 `plugin.yml`，配置里的 `${player}` 等占位符不会被 maven 碰
