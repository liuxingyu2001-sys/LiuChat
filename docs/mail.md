# 邮件系统

支持离线收件、主题/正文、持久化物品附件、已读状态、附件领取、过期清理。数据写入
`storage`（SQLite 单服 / MySQL 跨服共享），邮件内容与附件跨重启保留。

## 界面操作

```text
/mail                        打开邮箱界面（主指令）
/邮件                        中文别名
/liuc mail                   子命令入口，效果相同
```

权限：

- `liuchat.mail`：打开邮箱（默认所有玩家）
- `liuchat.mail.attach`：写邮件时附带主手物品（默认所有玩家）

命令只负责打开界面，列表/详情/按钮使用 Sparrow UI 箱子窗口；写邮件的收件人使用铁砧输入，主题/正文/附件使用 Paper Dialog（原版与 Sparrow 没有多行文本控件）：

- **邮箱列表**：左键阅读，右键删除（弹出确认）；底部可翻页。
- **阅读邮件**：打开时自动标记已读；正文与附件在同一个界面展示。
- **领取附件**：点击“领取附件”按钮，附件全部进入背包；背包不足时返回提示且不标记领取。
- **写邮件**：点击右下角按钮打开输入框，填写收件人、主题、正文，可勾选附带主手物品；不需要在聊天栏输入。
- 收到新邮件、上线未读数量等聊天通知都带点击事件，点击直接打开邮箱。
- 勾选附带主手物品时，发送前先从背包扣除；发送失败会自动退回（背包满则掉落在脚下）。
- 邮件主题在界面输入框中输入，因此支持空格；列表显示完整主题。

## 配置

```yaml
mail:
  enable: true
  expire-days: 30          # 0 = 永不过期
  max-per-player: 100      # 0 = 不限制；满时拒收
  page-size: 45             # 每页邮件数（箱子 45 个内容格）
  max-subject-length: 64
  max-body-length: 2000
  max-attachments: 3
  max-attachment-bytes: 16384 # 单件物品序列化后的字节上限
  send-cooldown-seconds: 5
  notify-on-join: true
  allow-self: false
```

修改后 `/liuc reload` 生效。服务每小时清理过期邮件；过期邮件不会出现在列表/详情中。

## 附件

- 使用 Paper 的 `ItemStack.serializeAsBytes()` → Base64 存储，**不使用 YAML 反序列化**，
  避免从数据库内容实例化任意配置类。
- 领取前会先检查背包容量（模拟加入 36 格存储），放不下返回 `INVENTORY_FULL`，不会部分领取。
- 领取成功后邮件标记 `claimed`，不能重复领取；若容量检查后背包又被占用，剩余物品会掉落在玩家脚下而不是吞掉。
- 附件在详情界面中展示为实际物品图标，但点击界面不会取走；必须点击“领取附件”才会放入背包。

## 外部 API

```java
MailApi mail = Bukkit.getServicesManager().load(MailApi.class);
if (mail == null || !mail.isEnabled()) return;

MailDraft draft = new MailDraft(
        sender.getUniqueId(), sender.getName(),
        recipientId, recipientName,
        "活动奖励", "感谢参与，附件请查收。",
        List.of(new ItemStack(Material.DIAMOND, 8)));
mail.send(draft).thenAccept(result -> {
    // OK / MAILBOX_FULL / COOLDOWN / ATTACHMENT_TOO_LARGE ...
});

mail.inbox(playerId, 1).thenAccept(page -> {
    // page.mails() / total() / unread() / pageSize()
});

mail.claim(player, mailId).thenAccept(result -> {
    // OK / INVENTORY_FULL / ALREADY_CLAIMED ...
});
```

完整方法见 `com.liu.liuchat.api.MailApi`：

- `send(MailDraft)`
- `inbox(UUID recipient, int page)`
- `mail(UUID recipient, String idOrPrefix)`
- `unread(UUID recipient)`
- `markRead(UUID recipient, String idOrPrefix)`
- `claim(Player player, String idOrPrefix)`
- `delete(UUID recipient, String idOrPrefix)`
- `isEnabled()`

`MailDraft` 的附件列表会被复制并在发送时克隆，调用方之后修改原物品不会影响邮件。
`MailApi` 只负责持久化与投递，不会从玩家背包扣除物品；通过 API 发送附件时，调用方需自行扣除（GUI 路径已在发送前自动扣除，失败会退回）。
`claim` 必须传在线玩家，服务会切回该玩家区域线程再操作背包。

### 事件

| 事件 | 时机 | 可取消 |
|---|---|---|
| `MailSendEvent` | 写入数据库前 | 是 |
| `MailReceivedEvent` | 成功落库后 | 否 |
| `MailClaimEvent` | 附件放入背包前 | 是 |
| `MailReadEvent` | 标记已读后 | 否 |
| `MailDeleteEvent` | 删除邮件后 | 否 |

事件定义在 `com.liu.liuchat.api.event`。

## 跨服说明

- `storage.type: mysql` 时邮件跨子服共享；收件人在任意子服打开邮箱都能看到。
- 新邮件通知优先发本服在线玩家；目标在别的子服时经现有跨服私密投递链路发送。
  离线收件人上线时会提示未读数量（`mail.notify-on-join`）。
- 单服 SQLite 模式完全本地。

## 存储表

启动时自动创建：

- `mail`：`id`（UUID）、发件人/收件人 UUID 与名字、主题、正文、附件 Base64、创建/过期时间、已读/已领取标记
- 另见 [好友系统](friends.md) 的 `player_identity` / `friend_link` / `friend_request`
