# 插件 API

其他插件可在服务器主线程调用 LiuChat 主类的公开方法。调用方应将 LiuChat 声明为 `softdepend`，并在插件存在且启用时调用；缺失时自行回退到本服广播。

## 广播公告

```java
((LiuChat) Bukkit.getPluginManager().getPlugin("LiuChat"))
    .broadcastAnnouncement(在线玩家, BaseComponent...);
```

广播预格式化公告：本服立即投递，开启跨服时经代理/Redis 转发到其他子服。需要至少一名在线玩家作为跨服载体（无跨服时仅本服）。

```java
((LiuChat) Bukkit.getPluginManager().getPlugin("LiuChat"))
    .broadcastItemAnnouncement(在线玩家, 含%item%的模板, ItemStack);
```

广播完整物品快照公告（原生附魔/Lore 悬浮与点击只读预览）。enchantboost 的强化成功公告已使用物品快照 API。

## 私密投递（推荐）

```java
boolean accepted = ((LiuChat) Bukkit.getPluginManager().getPlugin("LiuChat"))
    .deliverPrivate(在线玩家, "目标玩家名", BaseComponent...);
```

把预格式化富文本**只发给指定玩家**（本服或跨服在线）：目标在本服立即投递，否则经跨服链路直达目标所在子服。不广播给其他人，也不参与忽略列表等聊天规则判断。适合经济插件转账提示等场景。

- `accepted`：`true` = 本服已投递或已交给跨服链路；`false` = 跨服未开启或转发失败，调用方可自行回退本服提示。
- 跨服载体玩家可为 `null`：Redis 传输不需要载体，代理传输会自动借任一在线玩家转发。
- 必须在服务器主线程调用（Folia 上会自动投回全局区域）。

## 控制台支持

LiuChat 的命令支持控制台 / 命令方块执行：

- `/msg <玩家> <消息>`、`/tell <玩家> <消息>`：控制台可作为发送者跨服私聊，显示名取自 `config.yml` 的 `console.name`。
- `/horn <消息>`：控制台可直接使用全服喇叭，不需要喇叭余额、不受禁言限制，同样会跨服广播。

## 协议兼容

- 跨服公告 / 物品公告 / 私密投递需要两端 LiuChat 协议一致（当前协议 10），且发送服有在线玩家（`transport: redis` 时空服也可发送）。
- 跨服公告需要两端 LiuChat 均为协议 9 以上。

## 好友 API

```java
FriendApi friends = Bukkit.getServicesManager().load(FriendApi.class);
if (friends != null && friends.isEnabled()) {
    friends.request(senderId, sender.getName(), targetId, targetName)
           .thenAccept(result -> { /* FriendResult */ });
}
```

`FriendApi` 提供好友列表、请求查询、发送/接受/拒绝/撤回/删除、关系查询与在线状态。
方法返回 `CompletableFuture`，数据库与通知在专用线程执行；事件统一回主线程触发。
详见 [好友系统](friends.md)。

## 邮件 API

```java
MailApi mail = Bukkit.getServicesManager().load(MailApi.class);
if (mail != null && mail.isEnabled()) {
    mail.send(new MailDraft(senderId, senderName, recipientId, recipientName,
            "主题", "正文", List.of(item))).thenAccept(result -> { /* MailResult */ });
}
```

`MailApi` 提供发送、分页邮箱、详情、未读数、标记已读、领取附件与删除。
附件使用 Paper 二进制 `ItemStack` 序列化并持久化，领取时切回玩家区域线程。
详见 [邮件系统](mail.md)。

## 玩家名目录

好友/邮件命令按名字查找离线玩家时使用插件内部的 `player_identity` 表；
玩家每次登录/退出都会更新，MySQL 模式下跨子服共享。外部 API 直接传 UUID 即可，
不需要依赖该目录。新增玩家后建议通过在线玩家获取 UUID，或等待其至少登录一次。
