package net.william278.huskhomes.teleport;

/** 仅测试用的 HuskHomes API 形状存根。 */
public class TeleportBuilder {
    public static volatile String lastTarget;

    public TeleportBuilder teleporter(Teleportable teleporter) {
        return this;
    }

    public TeleportBuilder target(String target) {
        lastTarget = target;
        return this;
    }

    public boolean buildAndComplete(boolean timed, String... args) {
        return true;
    }
}
