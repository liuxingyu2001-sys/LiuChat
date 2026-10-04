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
    /** 名字兜底查询索引：key = 小写玩家名，value = uuid（与 cache 同步增删，避免 O(n) 扫描） */
    private final Map<String, String> uuidByName = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong revisions = new java.util.concurrent.atomic.AtomicLong();

    public MuteService(Database database) {
        this.database = database;
    }

    /** 所有缓存写入的唯一入口，保证 uuidByName 与 cache 不分叉 */
    private void cachePut(MuteData mute) {
        MuteData previous = cache.put(mute.uuid(), mute);
        // 同一 uuid 改名重禁（改名/大小写变化）时清掉旧名字的索引残留
        if (previous != null && previous.name() != null
                && !previous.name().equalsIgnoreCase(mute.name())) {
            uuidByName.remove(previous.name().toLowerCase(Locale.ROOT), mute.uuid());
        }
        if (mute.name() != null && !mute.name().isBlank()) {
            uuidByName.put(mute.name().toLowerCase(Locale.ROOT), mute.uuid());
        }
    }

    /** 所有缓存删除的唯一入口；仅当索引仍指向该 uuid 时才移除（防同名覆盖被误删） */
    private void cacheRemove(String uuid, String name) {
        cache.remove(uuid);
        if (name != null && !name.isBlank()) {
            uuidByName.remove(name.toLowerCase(Locale.ROOT), uuid);
        }
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
                cacheRemove(row.uuid(), row.name());
                database.deleteMute(row.uuid());
            } else {
                cachePut(row);
            }
        }
        // 库里不存在的 = 其他子服已解除（或写库失败），移出缓存
        for (String uuid : new HashSet<>(cache.keySet())) {
            if (!seen.contains(uuid)) {
                MuteData removed = cache.get(uuid);
                cacheRemove(uuid, removed == null ? null : removed.name());
            }
        }
    }

    public synchronized void mute(MuteData mute) {
        revisions.incrementAndGet();
        cachePut(mute);
        database.saveMute(mute);
    }

    public synchronized void unmute(MuteData mute) {
        revisions.incrementAndGet();
        cacheRemove(mute.uuid(), mute.name());
        database.deleteMute(mute.uuid());
    }

    public synchronized void applyRemote(MuteData mute) {
        // 幂等：Redis 传输会把自己发布的包也回给本服，内容一致时跳过（避免无意义的修订号跳变）
        if (mute.equals(cache.get(mute.uuid()))) return;
        revisions.incrementAndGet();
        cachePut(mute);
    }

    public synchronized void removeRemote(String uuid) {
        MuteData removed = cache.get(uuid);
        if (removed == null) return; // 已解除，幂等跳过
        revisions.incrementAndGet();
        cacheRemove(uuid, removed.name());
    }

    /**
     * 判断是否被禁言：先按 uuid 查，查不到再按名字兜底
     * （离线玩家 getOfflinePlayer(name) 拿到的 uuid 可能与真实 uuid 不一致）。
     * 命中过期记录会顺手清除并同步删库。
     */
    public Optional<MuteData> check(String uuid, String name) {
        MuteData mute = cache.get(uuid);
        if (mute == null && name != null) {
            String matched = uuidByName.get(name.toLowerCase(Locale.ROOT));
            mute = matched == null ? null : cache.get(matched);
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
            uuidByName.remove(mute.name() == null ? "" : mute.name().toLowerCase(Locale.ROOT), mute.uuid());
            database.deleteMute(mute.uuid());
        }
    }
}
