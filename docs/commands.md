# 命令（全部支持 tab 补全）

```
/liuc                              查看帮助
/liuc reload                       重载配置        权限: liuchat.reload
/mute <玩家> <时长|0永久> [原因]   禁言（同 /liuc mute）    权限: liuchat.mute
/unmute <玩家>                 解除禁言（同 /liuc unmute） 权限: liuchat.unmute
/msg <玩家> <消息>                私聊（别名 w/whisper，跨服）  权限: liuchat.tell
/tell <玩家> <消息>               私聊（跨服）             权限: liuchat.tell
/reply <消息>                     回复最近私聊对象（别名 /r，跨服） 权限: liuchat.tell
/horn <消息>                      全服喇叭             权限: liuchat.horn
/ignore <玩家>                      屏蔽玩家（同 /liuc ignore）
/liuc unignore <玩家>               取消屏蔽
/ignorelist                         查看屏蔽列表（同 /liuc ignorelist）
/liuc nick <昵称|off>               聊天昵称             权限: liuchat.nick（默认 OP）
/liuc chatcolor <&a|&#RRGGBB|off>   聊天颜色             权限: liuchat.chatcolor
/liuc ask <问题>                    使用默认 AI 助手    权限: liuchat.ask
/liuc ask <助手名> <问题>          使用指定助手       权限: liuchat.ask
/liuc ask list                     列出助手
/liuc audit <1-24>                 审查最近 N 小时聊天  权限: liuchat.audit
/liuc dialog ai                    AI 聊天助手 Dialog
/liuc dialog chatcolor              聊天颜色与渐变 Dialog
```

`/liuc dialog chatcolor` 打开聊天颜色与渐变设置 Dialog。

- `/reply`（`/r`）回复最近一次成功收发私聊的玩家；跨服发出消息在收到回执后才更新，未送达、被屏蔽或审核拦截的私聊不会改变最近联系人。记录只在本次插件运行期间有效，不随玩家切服迁移或重启保留；跨服收到私聊的玩家可直接回复发送者。

`/liuc`（无参数或未知子命令）打印帮助，帮助行按命令的真实入口显示：在 plugin.yml 里直挂成顶层的 `/mute` `/unmute` `/msg` `/tell` `/reply` `/ignore` `/ignorelist` `/horn` `/nick` `/chatcolor` 显示为顶层命令，其余显示 `/liuc xxx`（模板 `help.cmd` 的 `${command}` 由代码拼）。老语言文件 `help.*.desc` 里重复写的用法命令名（`/liuc mute <玩家>`、`/mute <玩家>`）会被自动去掉，只留参数，不用手改已有 `messages.yml`。

插件消息是否带 `messages.yml` 的 `prefix` 前缀由同文件的 `prefix-enable` 控制（默认 `true`，`/liuc reload` 生效）；不需要前缀时设 `false` 即可，不用把 `prefix` 清空。

`/liuc` 别名：`/liuc`

> **部署提示**：
> - plugin.yml 直接声明 `msg`/`tell`/`reply`/`ignore`/`ignorelist` 会覆盖原版或其他插件的同名命令；
> - BungeeCord 代理端自带 `/msg`，若它把输入拦在了代理上，需在代理侧禁用（bungee.yml `disabledCommands`）；
> - 跨服 PAPI 变量在发送服预解析并传输；接收服不直接解析远端玩家。所有子服需部署同一协议版本；CustomNameplates 需启用 PAPI。
