package com.liu.liuchat.service;

import com.liu.liuchat.config.ConfigManager;
import com.liu.liuchat.model.MuteData;
import com.liu.liuchat.storage.Database;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 禁言服务：内存缓存 + 数据库落库，监听器（异步线程）和命令都走这里。
 * <p>
 * {@link #loadAll()} 是<b>对账式全量刷新</b>（启动时调用，storage.sync-interval
 * 定时再调）：库里的放进缓存、过期的删掉删库、库里已不存在的移出缓存 ——
 * MySQL 跨服部署时，其他子服的禁言/解禁靠这个同步过来。
 */
public final class MuteService {

    private final Database database;
    /** key = uuid */
    private final Map<String, MuteData> cache = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong revisions = new java.util.concurrent.atomic.AtomicLong();

    public MuteService(Database database) {
        this.database = database;
    }

    /** 对账式全量刷新，见类注释 */
    public synchronized void loadAll() {
        if (!database.isReady()) {
            return;
        }
        long revision = revisions.get();
        long now = System.currentTimeMillis();
        List<MuteData> rows = database.loadMutes();
        if (rows == null || revision != revisions.get()) return;
        Set<String> seen = new HashSet<>();
        for (MuteData row : rows) {
            seen.add(row.uuid());
            if (row.isExpired(now)) {
                cache.remove(row.uuid());
                database.deleteMute(row.uuid());
            } else {
                cache.put(row.uuid(), row);
            }
        }
        // 库里不存在的 = 其他子服已解除（或写库失败），移出缓存
        cache.keySet().removeIf(uuid -> !seen.contains(uuid));
    }

    public synchronized void mute(MuteData mute) {
        revisions.incrementAndGet();
        cache.put(mute.uuid(), mute);
        database.saveMute(mute);
    }

    public synchronized void unmute(MuteData mute) {
        revisions.incrementAndGet();
        cache.remove(mute.uuid());
        database.deleteMute(mute.uuid());
    }

    public synchronized void applyRemote(MuteData mute) {
        revisions.incrementAndGet();
        cache.put(mute.uuid(), mute);
    }

    public synchronized void removeRemote(String uuid) {
        revisions.incrementAndGet();
        cache.remove(uuid);
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
