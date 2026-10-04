# 配置要点

聊天交互在 `chat.yml`，正则快捷触发在 `shortcut.yml`，Paper Dialog 快捷操作在 `dialogs.yml`；`config.yml` 控制喇叭、AI、每日聊天记录与跨服（`cross-server.transport`：`proxy` 默认经代理转发，`redis` 改走 Redis pub/sub 直连、空服可收发、断连自动回落）。启动或 `/liuc reload` 自动补全缺失键，不覆盖现有值。物品展示 token 写在 `chat.yml` 的 `item` 段：`[i]` 主手、`[i1]`..`[i9]`/`[i1234]`/`[i1-9]` 快捷栏、`[盔甲]`（`[armor]`）盔甲 4 件、`[副手]`（`[offhand]`）1 件，全部不区分大小写，`item.slots`/`item.armor`/`item.offhand` 可分别关闭；`item.separator`、`item.multi-content`、`item.max-count` 控制多件展示的分隔、2 件以上的紧凑样式与上限。记录写到 `plugins/LiuChat/logs/YYYY-MM-DD.log`。`liuchat.color` 只允许玩家输入颜色/样式标签，不能注入点击指令。CE 表情以发送者权限调用其 CHAT 解析器，图片和悬浮内容会在跨服消息中随占位符快照传递；发送服需要安装 CraftEngine，客户端需加载对应资源包。本服公聊审核通过后会调用 CustomNameplates 的 `ChatManager.onChat` 触发聊天气泡（频道 `Global`）；气泡的显示仍受其 `bubble.yml` 的 `sender-requirements`、`viewer-requirements`、`blacklist-channels`、`max-lines` 等设置控制，不满足条件时正常聊天不受影响。

CustomNameplates API 支持：在 `chat.yml` 独立的玩家节点设置 `text: '&e${nick}'` 和 `image: {type: background, id: bedrock_1, left-margin: 1, right-margin: 1}`；也可用 `type: nameplate` 和对应的铭牌 ID。安装 CustomNameplates 并让客户端加载其资源包后生效；未安装或 ID 不存在时显示原文本。图片节点不能同时包含 `${message}` 或 `${head}`，头像可拆为另一个节点。跨服聊天由接收服使用相同 ID 生成图片，所有子服应安装并配置相同的图片资源。原有 `%nameplates_...%` 变量仍通过 PlaceholderAPI 在发送服预解析。

CMI 同名指令由 `commands.prefer-liuchat: true` 将 `/msg`、`/tell`、`/w`、`/whisper`、`/horn` 转到 `liuchat:` 命名空间；不自动修改服务器 `commands.yml`。`/pm` 不由 LiuChat 注册或重定向。

## 共享配置

在每台服务器本地的 `plugins/LiuChat/config.yml` 设置：

```yaml
settings:
  shared-config-path: '/mc/shared/liuchat-config'
  shared-skills-path: '' # 留空使用配置目录下的 skills/；也可单独指定共享路径
  auto-reload-config: false
```

目录选择优先级为显式路径、已存在的 `/mc/shared/liuchat-config`、插件本地目录。显式路径不存在时尝试创建，失败会记录警告并回退本地；未指定路径时不会主动创建默认共享目录。路径修改需要重启。共享目录为空时仅复制缺失的内置默认文件，不会迁移或覆盖本地自定义配置，请将需要保留的配置预先放入共享目录。

共享文件包括 `config.yml`、`messages.yml`、`shortcut.yml`、`dialogs.yml`、`npc-assistants.yml`、`reminders.yml` 和 `skills/`。聊天本地屏蔽词属于全服策略，放在共享 `config.yml` 的 `chat-filter` 段。以下文件始终从各子服本地 `plugins/LiuChat/` 读取，不共享，共享目录里的同名文件不会读取、覆盖本地文件或触发重载：`chat.yml`（聊天格式）、`ai.yml`（AI 键/模型/助手/审核/公屏 AI）。开启自动重载时仍监听这些本地文件。`settings.shared-config-path` 与 `settings.shared-skills-path` 从本地配置读取，修改需重启；`server` 也始终保留本地值，各子服必须使用不同标识。单独指定的 skills 目录同样参与自动监听。

共享配置目录中的 `reminders.yml` 用于定时消息提醒。提醒可使用 `interval-seconds` 或 `times`（二选一），`times` 为指定时刻，`timezone` 控制时区，`days` 使用数字 `1` 到 `7` 分别表示周一到周日，空列表表示每天。共享配置下各子服按同一计划各自本地投递一次，不通过代理转发，因此不会重复；`servers` 可限制子服。

数据库、AI 会话持久化文件仍留在本地。升级到本版首次启动时，旧 `config.yml` 的 `ai` 段会自动迁移：AI 端点/助手/审核等复制到本地 `ai.yml` 并标记 `migrated: true`；屏蔽词、联系方式、IP/域名开关改迁到共享 `chat-filter`（仅当 `config.yml` 还没有 `chat-filter` 段时执行，不覆盖新配置）。之后 AI 配置只从 `ai.yml` 读取、屏蔽词只从 `chat-filter` 读取，`config.yml` 里旧的 `ai` 段不再生效，可自行删除。聊天记录可通过 `chat-log.shared-path` 指向共享目录，插件会按子服标识隔离到 `servers/server-<编码>/`，避免多台服务器并发追加同一个文件；AI 审核默认读取各子服自己的历史，避免重复审核。共享目录需对插件可写，历史本地文件不会自动迁移。共享日志按接收子服保存，跨服聊天可能在不同子服文件中各有一份，汇总时应注意重复记录。

**自动重载默认关闭。** 关闭时修改配置后需在各子服执行 `/liuc reload`。启用时，将实际配置目录中的 `settings.auto-reload-config` 改为 `true` 并执行 `/liuc reload`，之后每 10 秒异步检查上述文件的路径、修改时间和大小，变化后在主线程执行与手动命令相同的重载流程。手动重载会重新记录基线，停用插件时取消监听。开启后也能监听 `skills/` 的新增、修改和删除；YAML 语法错误会在刷新组件前拒绝重载。存储连接和数据库同步间隔仍需重启生效，已有 AI 定时任务每次执行读取当前配置，无需重复创建。

## 定时提醒

在实际配置目录的 `reminders.yml` 设置，例如：

```yaml
enable: true
timezone: 'Asia/Shanghai'
reminders:
  evening:
    enable: true
    times: ['20:00', '22:30']
    days: [1, 2, 3, 4, 5] # 周一至周五；空列表 = 每天
    servers: [] # 所有子服
    permission: '' # 所有玩家
    console: false # true 时控制台也输出提醒
    messages:
      - '&6[提醒] &f记得适当休息！'
```

间隔模式将 `times` 替换为 `interval-seconds: 600`，按 Unix 时间基准每 600 秒触发，所有子服使用同一时间槽，而非从各自启动时刻计时。每个计划的 `messages` 列表按顺序全部发送，支持颜色、渐变、`${server}` 和按接收玩家解析的 PlaceholderAPI。`days` 只接受整数 `1` 到 `7`。配置修改执行 `/liuc reload`，不重置已有计划的发送进度；首次启动不补发历史提醒，延迟执行最多发送最近一次，不积压刷屏。指定时刻支持 `HH:mm` 或 `HH:mm:ss`；夏令时不存在的时刻跳过，重复的时刻仅发送一次。服务端卡顿可能导致延迟，群组服应同步系统时间并使用相同配置。无需代理或在线玩家承载跨服消息，每台安装本插件的服务器都能独立投递。

共享记录配置示例（实际 `config.yml`）：

```yaml
chat-log:
  shared-path: '/mc/shared/liuchat-records'
```

文件位于 `servers/server-<子服标识UTF-8十六进制>/logs/` 和 `audit-history/`。修改路径后 `/liuc reload` 生效，仅影响后续记录；私聊审核历史可能含敏感信息，请限制共享目录访问权限。审核报告仍保存在各子服本地。

命令格式：`/horn <消息>`，别名 `/lb`；喇叭余额使用 `/lb balance`，管理员发放为 `/horn give <玩家> <数量>`。`horn.message-format`、`horn.title-message-format` 和 `horn.actionbar-message-format` 分别控制聊天、Title 内容、ActionBar 文本，BossBar 使用 Title 内容；控制台跟随聊天格式。三个模板均支持 `${server}`、`${player}`、`${message}`。旧 `horn.format` 只作为缺少专用格式时的兼容回退。禁言及跨服超长消息检查在扣费前执行。

## 聊天颜色

聊天颜色通过 `/liuc dialog chatcolor` 打开，支持单色和渐变色。单色模式使用第一组红、绿、蓝滑块；渐变模式另外使用结束色的红、绿、蓝三组滑块。红 R、绿 G、蓝 B 分别代表组成颜色的三种原色通道，数值范围都是 0-255。保存后会写入聊天资料，并支持 `<gradient:#1afff0:#2ea4ff>` 格式。也可以用 `/liuc chatcolor <&a|&#RRGGBB|off>` 直接设置，与 Dialog 同一存储格式；输入只允许颜色码（`&a`、`&#RRGGBB`、`&x` 形式、`<gradient:...>` 等），含正文或其他 MiniMessage 标签会被判为格式无效并拒绝，`off` 清除颜色。

## 私聊格式

私聊独立格式配置在 `chat.yml` 的 `private.to.format`（发送者回显）和 `private.from.format`（接收者显示），每个节点与公共聊天一样支持 `text`、`hover`、`click`、`clickSuggest`、`url` 和图片。`${player}`/`${nick}` 表示发送者，`${target}` 表示接收者，`${message}` 为正文；`private.enable: false` 恢复 `messages.yml` 的旧文本样式。跨服私聊发送者的昵称、UUID、世界与占位符快照随消息发送，接收服无需发送者在线。跨服在线名单在加入、退出时同步，并每分钟刷新；失联子服的名单约 150 秒后过期，供 `/tell`、`/msg` 等命令补全。**本次跨服协议升级至 9，所有子服须一起更新**，否则旧版子服间的消息会被拒收。
