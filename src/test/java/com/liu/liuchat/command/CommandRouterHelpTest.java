package com.liu.liuchat.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandRouterHelpTest {

    @Test void directCommandsShowTopLevelLabel() {
        // /mute、/msg 等已在 plugin.yml 里直挂成顶层命令，帮助不该再写 /liuc mute
        assertEquals("/mute", CommandRouter.label("mute", "mute"));
        assertEquals("/tell", CommandRouter.label("tell", "tell"));
        assertEquals("/liuc ask", CommandRouter.label("ask", null));
        assertEquals("/liuc reload", CommandRouter.label("reload", null));
    }

    @Test void legacyDescUsageDoesNotRepeatTheCommand() {
        // 老语言文件两种写法都要收敛成「只有参数」：/liuc xxx <参数> 与顶层 /xxx <参数>
        assertEquals("&7禁言玩家 &e<玩家>", CommandRouter.fixDesc("mute", "mute",
                "&7禁言玩家 &e/liuc mute <玩家>"));
        assertEquals("&7解除禁言 &e<玩家>", CommandRouter.fixDesc("unmute", "unmute",
                "&7解除禁言 &e/unmute <玩家>"));
        assertEquals("&7私聊 &e<玩家> <消息>", CommandRouter.fixDesc("tell", "tell",
                "&7私聊 &e/liuc tell <玩家> <消息>"));
        assertEquals("&7询问聊天助手 &e<问题>", CommandRouter.fixDesc("ask", null,
                "&7询问聊天助手 &e/liuc ask <问题>"));
    }

    @Test void freshDescsWithoutCommandStayUntouched() {
        assertEquals("&7重载配置与语言文件", CommandRouter.fixDesc("reload", null,
                "&7重载配置与语言文件"));
        assertEquals("&7查看聊天中分享的物品", CommandRouter.fixDesc("item", null,
                "&7查看聊天中分享的物品"));
        assertEquals("&7禁言玩家 &e<玩家> <时长|0永久> [原因]", CommandRouter.fixDesc("mute", "mute",
                "&7禁言玩家 &e<玩家> <时长|0永久> [原因]"));
    }
}
