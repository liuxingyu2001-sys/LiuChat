package net.william278.huskhomes.api;

import net.william278.huskhomes.teleport.TeleportBuilder;
import net.william278.huskhomes.user.OnlineUser;
import org.bukkit.entity.Player;

/** 仅测试用的 HuskHomes API 形状存根。 */
public class HuskHomesAPI {
    private static final HuskHomesAPI INSTANCE = new HuskHomesAPI();

    public static HuskHomesAPI getInstance() {
        return INSTANCE;
    }

    public OnlineUser adaptUser(Player player) {
        return new OnlineUser();
    }

    public TeleportBuilder teleportBuilder() {
        return new TeleportBuilder();
    }
}
