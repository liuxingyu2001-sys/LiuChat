# 聊天频道与外部 API

`channels.yml` 位于 LiuChat 配置根目录（启用共享目录时与其他共享配置一起存放），支持 `/liuc reload` 与配置自动重载。无效范围、半径或默认频道会拒绝加载，保留已有频道定义。

## 玩家操作

- `/liuc channel` / `/liuc channel list`：列出有接收权限的频道与当前频道。
- `/liuc channel <id>`：切换普通聊天输入的频道。
- `/liuc channel join <id>`：订阅频道。MEMBERS 频道必须先订阅再切换或发言。
- `/liuc channel leave <id>`：退出订阅；若是当前频道则恢复默认。
- `/liuc channel send <id> <消息>`：临时向指定频道发言，不改变当前频道。

`liuchat.channel` 默认所有人拥有；管理频道要求 `liuchat.channel.staff`（默认 op）。当前频道与订阅只保存在会话内，退出清除。接收权限在每次投递时重新判断，权限被撤销立即停止接收。

## 配置

```yaml
default: global
channels:
  global:
    name: 全服
    scope: GLOBAL
  trade:
    name: 交易
    scope: MEMBERS
    send-permission: liuchat.channel.trade
    receive-permission: liuchat.channel.trade
    format: '&6[${channel_name}] &f${nick}&7: ${message}'
  nearby:
    name: 附近
    scope: NEARBY
    radius: 100
```

ID 为小写字母开头，允许字母、数字、下划线与短横线，最长 48 字符；大小写统一转为小写。空权限表示不限制。范围：

| scope | 接收者 |
| --- | --- |
| GLOBAL | 使用既有公共聊天富文本与跨服链路 |
| SERVER | 本服所有有接收权限的在线玩家 |
| WORLD | 本服同世界、有接收权限的玩家 |
| NEARBY | 本服同世界、三维距离在 radius 内的玩家 |
| MEMBERS | 本服已订阅且有接收权限的玩家 |

GLOBAL 是现有公共聊天通道，应仅用于公开聊天；它使用 `chat.yml` 的既有格式与接收行为，不使用这里的 `format`/成员订阅。其余范围仅本服发送，格式支持传统颜色、PAPI 和时间变量，以及 `${channel}`、`${channel_name}`、`${server}`、`${world}`、`${player}`、`${nick}`、`${message}`。其余范围目前不渲染物品展示、头像与点击/悬浮节点。所有玩家发言（包括 API 发言）仍检查禁言、冷却、重复和本地违规过滤。局部频道不触发公共 AI，不跨服广播，聊天日志以 `CHANNEL:<id>` 记录。公开频道的远端协议保持不变。

## PlaceholderAPI

| 占位符 | 返回值 |
| --- | --- |
| `%liuchat_channel%` / `%liuchat_channel_id%` | 当前频道 ID |
| `%liuchat_channel_name%` | 当前频道显示名 |
| `%liuchat_channel_scope%` | 当前频道范围，如 NEARBY |
| `%liuchat_channel_count%` | 已配置及外部注册的频道数 |
| `%liuchat_channel_subscriptions%` | 已订阅 ID，排序后逗号分隔；无订阅为空 |
| `%liuchat_channel_can_send%` | 当前频道权限与成员检查结果 true/false；不包含禁言与冷却 |

## Java API

依赖 LiuChat jar（provided），在 `plugin.yml` 声明 `depend: [LiuChat]` 或 `softdepend: [LiuChat]`。通过 Bukkit 服务取得接口：

```java
ChannelApi api = Bukkit.getServicesManager().load(ChannelApi.class);
if (api == null) return;
api.channel("nearby").ifPresent(channel -> getLogger().info(channel.displayName()));
api.send(player, "nearby", "大家好").thenAccept(result -> {
    // SENT 表示本服接纳发言；不表示跨服 ACK 或接收者已经读取。
    getLogger().info("发送结果: " + result);
});
```

包名：`com.liu.liuchat.api.ChannelApi`、`com.liu.liuchat.api.ChatChannel`。也可通过 `LiuChat.instance().getChannelApi()` 获取。

- `channels()` / `channel(id)`：不可变查询快照。
- `current(UUID)` / `subscriptions(UUID)`：会话状态快照；已删除当前频道回退默认。
- `register(ChatChannel)`：注册运行时频道，重名返回 false；配置定义优先。注册不写配置文件。
- `unregister(id)`：只删除运行时频道，无法删除配置频道；调用插件停用时应注销自身注册项。
- `switchChannel(Player,id)` / `subscribe(Player,id)`：必须在玩家所属线程调用（Paper 主线程 / Folia 玩家区域线程），返回结构化结果。
- `unsubscribe(UUID,id)`：退出订阅并恢复默认频道（如果该频道为当前频道）。
- `send(Player,id,message)`：任意线程可调用，自动调度到玩家区域。返回 CompletableFuture，断线/停用完成为 OFFLINE/CANCELLED；调度或内部异常异常完成。禁止在服务器线程使用 `join()` 阻塞等待。

Result：SENT、UNKNOWN_CHANNEL、NO_PERMISSION、NOT_MEMBER、MUTED、COOLDOWN、REPEATED、FILTERED、OFFLINE、CANCELLED。普通发言与 API 共享冷却和重复状态，API 无绕过审核入口。原文最大 4096 字符，空白拒绝。

## Bukkit 事件

`com.liu.liuchat.api.event.ChannelSwitchEvent`：切换前触发，可取消；包含玩家、之前和目标频道。

`com.liu.liuchat.api.event.ChannelMessageEvent`：权限检查后、审核前触发，可取消或 `setMessage()` 改正文；修改后的正文仍检查长度、禁言、冷却和违规过滤。两个事件都在玩家所属线程触发；Folia 监听器必须遵守区域线程限制，不直接操作其他区域玩家。send future 的回调没有固定线程承诺，操作 Bukkit 对象时应再次调度。

```java
@EventHandler
public void onChannelMessage(ChannelMessageEvent event) {
    if (event.getChannel().id().equals("trade")) {
        // 可修改正文或取消，此处修改不能绕过后续审核。
        event.setMessage(event.getMessage().trim());
    }
}
```

测试服务器保持关闭；世界/距离投递、权限变更与客户端显示尚需服务器联机验证。
