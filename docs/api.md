# 插件 API

其他插件可在服务器主线程调用 `((LiuChat) Bukkit.getPluginManager().getPlugin("LiuChat")).broadcastAnnouncement(在线玩家, BaseComponent...)` 广播预格式化公告，或调用 `broadcastItemAnnouncement(在线玩家, 含%item%的模板, ItemStack)` 广播完整物品快照公告（原生附魔/Lore 悬浮与点击只读预览）。本服立即投递，开启跨服时经代理转发到其他子服。调用方应将 LiuChat 声明为 `softdepend` 并在插件存在且启用时调用；缺失时自行回退到本服广播。enchantboost 的强化成功公告已使用物品快照 API。跨服公告需要两端 LiuChat 均为协议 9，且发送服有在线玩家。
