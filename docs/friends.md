# 好友系统

好友关系为**双向**：A 加 B 后双方列表中都有对方。数据写入 `storage`（SQLite 单服 / MySQL 跨服共享），
请求有有效期、好友数与请求数上限，所有操作都有公开 API 与事件。

## 界面操作

```text
/friend                      打开好友界面（主指令）
/好友                        中文别名
/liuc friend                 子命令入口，效果相同
```

权限：`liuchat.friend`（默认所有玩家可用）。命令只负责打开界面，列表/按钮使用 Sparrow UI 箱子窗口，添加好友优先用在线玩家选择器，离线/跨服玩家用铁砧输入：

- **主界面直接展示好友**：头像、在线状态、成为好友时间；不需要先点“好友列表”。
- **好友头像**：左键直接传送到好友身边（不需要对方同意），右键写邮件，Shift+右键删除好友（弹出确认）。
- **好友传送**：仅同一服务器生效，好友离线或位于其它子服时会在聊天栏提示；权限 `liuchat.friend.teleport`（默认所有玩家可用），可在配置中关闭或设置冷却。
- **好友请求**：底部铃铛按钮进入，按钮名称直接显示待处理数量（如“好友请求 (3)”）；左键接受、右键拒绝。
- **已发送的请求**：底部按钮进入；左键撤回。
- **添加好友**：底部按钮先列出本服在线玩家，点击头像直接发送请求；可点“手动输入名字”用铁砧添加离线/跨服玩家。
- **分页**：底部上一页/下一页/页码；右下角关闭。

- 手动输入按名字解析在线/离线玩家；需要玩家至少在本服（或 MySQL 共享目录的其他子服）登录过一次。
  解析使用 `player_identity` 名册，不阻塞主线程调用 Mojang。
- 接受/拒绝/撤回/删除直接按请求/好友记录匹配名字，玩家离线或改名后仍可操作。
- 双方同时向对方发送请求时，第二次请求会**自动成为好友**（界面会提示）。
- 好友请求、接受/拒绝/撤回、上线和待处理请求数量等聊天通知都带点击事件：
  点击直接打开好友界面或“好友请求”页，不需要再输入命令。
- 请求默认 168 小时过期（`friends.request-expire-hours`，0 = 永不过期），过期请求由定时任务清理。
同一好友重复上线/跨服往返的提醒默认有 300 秒冷却（`friends.join-notify-cooldown-seconds`，0 = 每次都提醒）。判断会读取共享的 `player_identity.last_seen`，因此切服/快速重连不会在另一个子服又弹一次；子服重启不会清掉这个判断。

## 配置

```yaml
friends:
  enable: true
  max-friends: 100       # 0 = 不限制
  max-requests: 50       # 待处理请求上限，0 = 不限制
  request-expire-hours: 168 # 0 = 永不过期
  page-size: 45             # 每页好友数（箱子 45 个内容格）
  notify-on-join: true   # 好友上线时通知在线好友（跨服也尝试私密投递）
  teleport: true         # 好友之间可直接传送（不需要对方同意），仅同服
  teleport-cooldown-seconds: 0 # 传送冷却（秒）；0 = 不冷却
```

修改后 `/liuc reload` 生效。服务启动后每小时清理一次过期请求；MySQL 模式下关系与请求跨子服共享。

## 外部 API

通过 Bukkit `ServicesManager` 获取，不需要强依赖 LiuChat 类：

```java
FriendApi friends = Bukkit.getServicesManager().load(FriendApi.class);
if (friends == null || !friends.isEnabled()) return;

friends.request(senderId, sender.getName(), targetId, targetName)
       .thenAccept(result -> {
           // result: REQUEST_SENT / ACCEPTED_MUTUAL / ALREADY_FRIENDS / FRIENDS_FULL ...
       });

friends.friends(playerId).thenAccept(list -> {
    for (Friend friend : list) {
        // friend.uuid() / name() / since() / online()
    }
});
```

完整方法见 `com.liu.liuchat.api.FriendApi`：

- `friends(UUID)`、`friend(UUID, UUID)`
- `incomingRequests(UUID)`、`outgoingRequests(UUID)`
- `request(senderId, senderName, targetId, targetName)`
- `accept(playerId, playerName, requesterId, requesterName)`
- `deny(UUID player, UUID requester)`、`cancel(UUID sender, UUID target)`
- `remove(UUID player, UUID friend)`
- `areFriends(UUID, UUID)`
- `teleport(UUID player, UUID friend)`（同服直接传送，不需要对方同意）
- `isOnline(String name)`（本服或跨服在线名单，跨服名单有约 60 秒周期延迟）
- `isEnabled()`

所有方法返回 `CompletableFuture`，数据库与通知在专用线程执行，不会阻塞主线程；
回调可能在工作线程，涉及玩家对象的后续操作请切回对应区域线程。事件通过主线程触发。

### 事件

| 事件 | 时机 | 可取消 |
|---|---|---|
| `FriendRequestEvent` | 请求写入前 | 是（可改 `expiresAt`） |
| `FriendAddedEvent` | 双向关系建立后 | 否 |
| `FriendRemovedEvent` | 关系删除后 | 否 |

事件定义在 `com.liu.liuchat.api.event`。

## 跨服说明

- `storage.type: mysql` 时，好友关系、请求、玩家名目录共享；任意子服都能看到同一份数据。
- 好友上线/请求/接受/删除通知优先发给本服在线玩家；目标在别的子服时经现有跨服私密投递链路发送。
  目标离线时通知不会排队，上线后可通过列表/请求命令查看。
- 单服 SQLite 模式完全本地，不需要跨服。

## 存储表

启动时自动创建，不影响已有表：

- `friend_link`：双向关系（按 UUID 字典序存 low/high，避免重复两行）
- `friend_request`：sender → target 单向请求
- `player_identity`：名字 → UUID 目录，供离线按名字操作
