# 实际代理 + 双后端 + 客户端端到端验收

这份文档定义 LiuChat 发布前的真实群组服验收流程。它验证客户端经过 Velocity 或 BungeeCord 代理连接两个 Paper/Leaf 后端时，跨服通信、共享配置、定时提醒、AI 提及和记录落盘是否符合预期。

单元测试只验证 Java 逻辑和协议编码，不能证明代理插件消息转发、后端线程调度、客户端组件显示、资源包、挂载目录和重启流程正常。因此该验收必须在真实或等价的测试网络执行。

## 验收拓扑

```text
Minecraft 客户端
        |
        v
Velocity/BungeeCord
   |              |
   v              v
backend-a      backend-b
Paper/Leaf     Paper/Leaf
   |              |
   +------ 共享目录 ------+
          config / skills / reminders / records
```

推荐使用 Velocity 3.x + 两个相同版本的 Paper/Leaf 1.21.11。BungeeCord 也必须单独跑一遍代理兼容项。两个后端使用不同的 `server` 标识，例如 `lobby` 和 `survival`，但使用同一份共享业务配置。

## 准备条件

- Java 21。
- 一个可登录的测试 Minecraft 客户端账号。
- 一个 Velocity 或 BungeeCord 实例。
- 两个干净的 Paper/Leaf 后端，端口不同，均安装同一版 LiuChat。
- 代理和两个后端都能看到对应的在线玩家；跨服插件消息不能在代理侧被禁用。
- 代理中登记的后端名称必须与各后端 LiuChat 的 `server` 值一致且唯一；私聊 ACK 使用它定向路由，不能任意起一个只用于显示的别名。
- 两个后端的时区、系统时间和 Java 时钟同步。
- 一个可写的共享目录；建议先使用本机 bind mount 或 NFS/SMB 测试挂载，再验证生产挂载。
- 如果验证 PlaceholderAPI、CraftEngine、CustomNameplates、Citizens 或 MySQL，还要在两个后端安装完全相同的版本和资源包。

执行前在每个后端确认：

```text
/version
/plugins
/liuc reload
```

两个后端必须使用相同的协议版本和 LiuChat jar。不同版本混用时，跨服协议、配置结构和客户端组件可能不兼容。

## 配置基线

每个后端本地 `plugins/LiuChat/config.yml` 至少设置：

```yaml
server: 'lobby' # backend-b 改为 survival，必须唯一
settings:
  shared-config-path: '/absolute/path/to/shared/liuchat-config'
  shared-skills-path: ''
  auto-reload-config: false
cross-server:
  enable: true
  secret: '仅测试网络使用的相同密钥'
chat-log:
  enable: true
  shared-path: '/absolute/path/to/shared/liuchat-records'
storage:
  type: sqlite
```

真实验收的第一轮保持实际配置目录的 `auto-reload-config: false`，先验证手动 `/liuc reload`；第二轮再开启自动重载。本地文件设置 `server` 与共享配置/skills 路径；跨服开关、密钥和日志路径必须设置在实际配置目录的 `config.yml` 中，使用共享目录时即共享文件。密钥两端必须一致。代理 forwarding secret 与 LiuChat 跨服 secret 是两种独立配置。

共享配置目录至少包含：

```text
config.yml          # 业务配置；server 等本地字段由各后端覆盖
messages.yml
shortcut.yml
dialogs.yml
npc-assistants.yml
reminders.yml
skills/
```

各后端的 `chat.yml` 与 `ai.yml` 保存在本地 `plugins/LiuChat/`，分别用于子服独立聊天格式与 AI 配置，不参与共享。共享目录里的同名文件不会生效。

开始前使用独立测试实例和全新测试目录，保存旧测试证据，确认后端只加载一个 LiuChat 主 jar。不要直接清空生产实例、生产密钥、玩家聊天或数据库。

## 自动环境检查

仓库提供只读检查脚本：

```bash
scripts/e2e/check-environment.sh \
  --jar target/Liu-LiuChat-<version>.jar \
  --proxy velocity \
  --proxy-config /srv/liuchat-e2e/proxy/velocity.toml \
  --backend-a /srv/liuchat-e2e/backend-a \
  --backend-b /srv/liuchat-e2e/backend-b \
  --shared /srv/liuchat-e2e/shared
```

脚本只检查 Java、jar、目录、本地 `server` 配置唯一性、共享目录可写性、两个后端的主 jar 与候选 jar 是否一致和代理配置中的后端名称。它不启动服务器、不删除文件、不打印密钥，也不验证登录和实际通道通信。脚本使用 Python 3 标准库识别常见 YAML/TOML 部署字段，不替代服务器完整配置解析。没有真实服务器目录时，先执行 `--help` 查看参数，不能把脚本成功当作端到端验收通过。

## 启动顺序

1. 准备共享配置和 skills，确认两个后端都能读取。
2. 启动 backend-a 和 backend-b，确认 LiuChat 启用且没有配置解析异常。
3. 启动代理，确认两个后端都已注册并可切换。
4. 用客户端进入 backend-a，再切换到 backend-b；确认两个后端的 `server` 标识显示正确。
5. 先做单服项目，再做跨服项目；每个项目保存时间、后端、客户端现象、控制台日志和结果。
6. 测试完成后停止后端，检查异步日志线程和监听任务是否正常关闭，再重新启动验证持久化和基线行为。

## 必测矩阵

| 编号 | 场景 | 操作 | 通过标准 |
|---|---|---|---|
| P01 | 单服聊天 | 客户端在 backend-a 发普通聊天 | 本服显示一次，颜色、格式、日志正确 |
| P02 | 跨服聊天 | A 发言，客户端切到 B | B 收到一次，显示发送端 `server`，无回环 |
| P03 | 跨服私聊 | A 的玩家给 B 的玩家执行 `/msg` | B 收到一次，A 收到回显，ACK 后不出现超时提示；目标不在线时约 60 tick 后明确失败 |
| P04 | 玩家名单 | 玩家在 A/B 加入退出并执行私聊补全 | 跨服名单更新，不出现已退出玩家的长期残留 |
| P05 | AI 提及 | AI 回复 `@玩家 内容` | 本地和跨服客户端均高亮玩家名，提及后的正文颜色恢复 |
| P06 | 颜色权限 | 分别用有、无 `liuchat.color` 的玩家发言 | 输入颜色权限和 AI/普通提及高亮行为符合配置 |
| P07 | 物品公告 | 发送物品展示和跨服公告 | B 能显示悬浮物品；无资源或过大快照时安全降级 |
| P08 | 跨服防回环 | 连续发送聊天、喇叭和公告 | 每个后端最多显示一次，不重复转发 |
| P09 | 提醒间隔 | `interval-seconds: 60`，A/B 同时在线 | 两个后端各收到一次，客户端不会因代理转发收到两份 |
| P10 | 提醒准确时间 | `times: ['当前时间后 2 分钟']` | 指定时区触发一次，延迟不积压补发 |
| P11 | 提醒筛选 | `days: [1]`、`servers: ['lobby']`、permission | 只有匹配日期、子服和权限的玩家收到 |
| P12 | 提醒重载 | 修改 messages 后两服执行 `/lc reload` | 新内容生效，旧计划不会短时间重复发送 |
| P13 | 共享配置与本地格式 | 修改共享 messages/reminders/skills；A 只改本地 chat.yml/ai.yml | 两服共享业务配置生效；chat.yml/ai.yml 只影响所属子服，不覆盖另一服 |
| P14 | 自动重载 | 开启开关，修改一个 YAML 和一个 skill | 约 10 秒后两服各重载一次；错误 YAML 保留旧状态 |
| P15 | 共享记录 | A/B 各发聊天和私聊 | `servers/server-<hex>/` 下按子服隔离，文件不互相覆盖 |
| P16 | 重启恢复 | 依次重启 A、B、代理 | 配置、skills、提醒计划、日志和跨服通信恢复；首次启动不补发旧提醒 |
| P17 | 空服代理 | 停止 B 或让 B 无在线玩家，A 发跨服消息 | A 不崩溃、不循环重试刷屏；恢复 B 后连接和 presence 恢复 |
| P18 | 网络抖动 | 重启代理或短暂断开一个后端 | 跨服失败有日志，私聊按超时提示，不阻塞本服聊天 |
| P19 | 非法配置 | 写入错误 YAML、非法 days；单独测试重复 server | YAML/计划错误拒绝重载且旧状态可用；重复 server 当前不主动拒绝，环境检查必须拦截并修正，不能作为成功部署 |
| P20 | 客户端资源 | 启用头像、CraftEngine、Nameplates、Dialog | 客户端实际组件、悬浮、点击、资源包效果正确；缺依赖时安全降级 |
| P21 | AI 本地化 | 两台后端配不同 `ai.yml`（不同 url/key/model/助手） | 各自使用自己的 AI 配置，互不影响；旧 `config.yml` 的 `ai` 段已迁移且不再生效 |

## 客户端验收重点

截图或录屏必须覆盖以下结果：

- 发送端、接收端和控制台的 `server` 来源显示。
- 普通聊天、AI 提及、渐变、提及后的正文颜色。
- `/msg`、`/reply` 的本地回显、远程投递和未送达提示。
- 定时提醒的玩家显示和 `console` 开关效果。
- 物品悬浮、头像插入、点击建议命令或 Dialog。
- 客户端切换后消息只出现一次。

客户端不能只看纯文本日志判断成功。必须在实际客户端检查颜色、悬浮、点击和资源包结果；控制台日志只能作为辅助证据。

## 失败定位

- 两个后端都收不到：先查代理连接、通道开关、在线玩家承载插件消息和共享 secret。
- A 能发 B 不能收：查 B 的 incoming channel、插件启用日志、`server` 是否与 A 重名、代理后端是否在线。
- 消息重复：查多个 LiuChat jar、代理重复转发、后端 `server` 重名和手动/自动任务重复注册。
- 私聊无回执：查目标服玩家 presence、代理定向 Forward、ACK 的目标服名和 3 秒超时日志。
- 配置只在一服生效：查共享挂载、文件权限、`configRoot` 日志、自动重载开关和修改时间。
- skills 只在一服生效：查 `shared-skills-path`、skills 目录权限及文件扩展名。
- 日志混写：查 `chat-log.shared-path`、子服标识和 `servers/server-<hex>` 目录。
- 客户端颜色或悬浮错误：先抓客户端截图，再核对接收服 Paper 版本、可选插件版本和资源包，不要只修改代理配置。

## 通过门禁

一次发布只有同时满足以下条件才算端到端通过：

- P01-P08、P09-P16、P19 必须通过。
- 至少用 Velocity 和 BungeeCord 各完成一次 P02、P03、P08；若只支持一种代理，必须在发布说明中明确。
- P17-P18 至少执行一次并记录预期的降级行为。
- P20 涉及的可选功能只在依赖已安装时判定；基础聊天不能因可选依赖缺失而失败。
- `mvn -B clean verify` 通过，工作区无未提交测试产物和密钥。
- 保存验收记录：构建版本、jar SHA-256、代理版本、后端版本、客户端版本、配置快照、测试时间、操作者、每项结果和失败日志。

## 结果模板

```text
LiuChat 版本：
Jar SHA-256：
代理及版本：
后端 A / server：
后端 B / server：
客户端版本：
共享目录类型：
测试时间及时区：

P01-P20：PASS / FAIL / N/A
失败编号及复现步骤：
控制台日志位置：
客户端截图或录屏位置：
是否允许发布：是 / 否
复测人：
```

人工验收通过后，才可以在路线图中将“实际代理 + 双后端 + 客户端端到端验收”标记为完成。开发机上只有 Maven 测试或构建通过时，该项必须保持未完成。
