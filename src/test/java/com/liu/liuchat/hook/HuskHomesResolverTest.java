package com.liu.liuchat.hook;

import net.william278.huskhomes.teleport.TeleportBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用与 HuskHomes 4.x 同名的测试存根校验反射调用链：
 * 类名、方法名、参数类型或返回值任一变化都会在这里暴露，而不是等线上点击才失败。
 */
class HuskHomesResolverTest {

    @Test
    void invokesApiChainAndPassesFriendName() throws ReflectiveOperationException {
        TeleportBuilder.lastTarget = null;
        boolean accepted = HuskHomesResolver.invoke(
                HuskHomesResolverTest.class.getClassLoader(), null, "Alex");
        assertTrue(accepted);
        assertEquals("Alex", TeleportBuilder.lastTarget);
    }
}
