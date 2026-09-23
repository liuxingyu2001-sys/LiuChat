package com.liu.liuchat.service;

import com.liu.liuchat.model.MuteData;
import com.liu.liuchat.storage.Database;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 禁言服务：内存缓存 + SQLite 落库，监听器（异步线程）和命令都走这里。
 */
public final class MuteService {

    private final Database database;
    /** key = uuid */
    private final Map<String, MuteData> cache = new ConcurrentHashMap<>();

    public MuteService(Database database) {
        this.database = database;
    }

    /** 启动时载入未过期的禁言，顺手清掉已过期的历史数据 */
    public void loadAll() {
        if (!database.isReady()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (MuteData mute : database.loadMutes()) {
            if (mute.isExpired(now)) {
                database.deleteMute(mute.uuid());
            } else {
                cache.put(mute.uuid(), mute);
            }
        }
    }

    public void mute(MuteData mute) {
        cache.put(mute.uuid(), mute);
        database.saveMute(mute);
    }

    public void unmute(MuteData mute) {
        cache.remove(mute.uuid());
        database.deleteMute(mute.uuid());
    }

    /**
     * 判断是否被禁言：先按 uuid 查，查不到再按名字兜底
     * （离线玩家 getOfflinePlayer(name) 拿到的 uuid 可能与真实 uuid 不一致）。
     * 命中过期记录会顺手清除并同步删库。
     */
    public Optional<MuteData> check(String uuid, String name) {
        MuteData mute = cache.get(uuid);
        if (mute == null && name != null) {
            String lower = name.toLowerCase(Locale.ROOT);
            mute = cache.values().stream()
                    .filter(m -> m.name() != null && m.name().toLowerCase(Locale.ROOT).equals(lower))
                    .findFirst()
                    .orElse(null);
        }
        if (mute == null) {
            return Optional.empty();
        }
        if (mute.isExpired(System.currentTimeMillis())) {
            evict(mute);
            return Optional.empty();
        }
        return Optional.of(mute);
    }

    /** 按 uuid 或玩家名查找（unmute / tab 补全用），同样处理过期 */
    public Optional<MuteData> find(String nameOrUuid) {
        return check(nameOrUuid, nameOrUuid);
    }

    /** 禁言列表里的所有玩家名（unmute tab 补全用） */
    public List<String> mutedNames() {
        return cache.values().stream()
                .map(MuteData::name)
                .filter(name -> name != null)
                .toList();
    }

    private void evict(MuteData mute) {
        if (cache.remove(mute.uuid(), mute)) {
            database.deleteMute(mute.uuid());
        }
    }
}
