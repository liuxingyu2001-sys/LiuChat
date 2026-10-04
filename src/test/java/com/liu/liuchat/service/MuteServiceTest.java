package com.liu.liuchat.service;

import com.liu.liuchat.model.MuteData;
import com.liu.liuchat.storage.Database;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 禁言缓存：名字索引（O(1) 兜底查询）与对账刷新的行为。
 * Redis 传输会把本服自己发布的 MUTE/UNMUTE 回传，幂等性也在用例内覆盖。
 */
class MuteServiceTest {

    /** 同步内存版，只关心 mute 相关的表 */
    private static final class FakeDatabase implements Database {
        final List<MuteData> rows = new CopyOnWriteArrayList<>();
        private boolean ready = true;

        @Override public boolean isReady() { return ready; }
        @Override public List<MuteData> loadMutes() { return new ArrayList<>(rows); }
        @Override public void saveMute(MuteData mute) {
            rows.removeIf(row -> row.uuid().equals(mute.uuid()));
            rows.add(mute);
        }
        @Override public void deleteMute(String uuid) { rows.removeIf(row -> row.uuid().equals(uuid)); }
        @Override public Profile loadProfile(String owner) { return new Profile("", ""); }
        @Override public void saveProfile(String owner, Profile profile) { }
        @Override public List<String> loadIgnores(String owner) { return new ArrayList<>(); }
        @Override public void addIgnore(String owner, String name) { }
        @Override public void removeIgnore(String owner, String name) { }
        @Override public int hornBalance(String owner) { return 0; }
        @Override public int addHorns(String owner, int amount) { return 0; }
        @Override public boolean spendHorn(String owner) { return false; }
        @Override public void close() { ready = false; }
    }

    private static MuteData mute(String uuid, String name, long expireAt) {
        return new MuteData(uuid, name, expireAt, "reason", "Admin");
    }

    @Test
    void findsByNameCaseInsensitivelyThroughIndex() {
        FakeDatabase db = new FakeDatabase();
        MuteService service = new MuteService(db);
        service.mute(mute("uuid-alice", "Alice", MuteData.PERMANENT));

        assertTrue(service.check("uuid-alice", null).isPresent());
        assertTrue(service.find("Alice").isPresent());
        assertTrue(service.find("alice").isPresent());
        assertTrue(service.find("ALICE").isPresent());
        assertTrue(service.find("uuid-alice").isPresent());
        assertFalse(service.find("bob").isPresent());
    }

    @Test
    void unmuteClearsNameIndexAndDatabase() {
        FakeDatabase db = new FakeDatabase();
        MuteService service = new MuteService(db);
        MuteData data = mute("uuid-bob", "Bob", MuteData.PERMANENT);
        service.mute(data);
        assertTrue(service.find("bob").isPresent());
        service.unmute(data);
        assertFalse(service.find("bob").isPresent());
        assertFalse(service.find("uuid-bob").isPresent());
        assertTrue(db.rows.isEmpty(), "解禁必须删库");
        assertTrue(service.mutedNames().isEmpty());
    }

    @Test
    void remoteApplyAndRemoveAreIdempotent() {
        FakeDatabase db = new FakeDatabase();
        MuteService service = new MuteService(db);
        MuteData data = mute("uuid-carol", "Carol", MuteData.PERMANENT);
        service.applyRemote(data);
        assertTrue(service.find("carol").isPresent());
        service.applyRemote(data); // Redis 自回环重复包：状态不变
        assertTrue(service.find("carol").isPresent());
        service.removeRemote("uuid-carol");
        assertFalse(service.find("carol").isPresent());
        service.removeRemote("uuid-carol"); // 重复解禁：无副作用
        assertFalse(service.find("carol").isPresent());
        assertTrue(db.rows.isEmpty(), "远程禁言不写本地库（由发起端负责落库）");
    }

    @Test
    void loadAllReconcilesCacheAndIndex() {
        FakeDatabase db = new FakeDatabase();
        MuteService service = new MuteService(db);
        service.mute(mute("uuid-stays", "Stays", MuteData.PERMANENT));
        // 其他子服写入的禁言（库里有、缓存无）
        db.rows.add(mute("uuid-from-other", "Other", MuteData.PERMANENT));
        // 本地缓存有、库里已被其他子服解除
        service.applyRemote(mute("uuid-removed", "Gone", MuteData.PERMANENT));
        db.rows.removeIf(row -> row.uuid().equals("uuid-removed"));

        service.loadAll();

        assertTrue(service.find("Stays").isPresent());
        assertTrue(service.find("other").isPresent(), "对账应拉取其他子服的禁言");
        assertFalse(service.find("Gone").isPresent(), "对账应移出库里已不存在的禁言");
        assertFalse(service.find("uuid-removed").isPresent(), "名字索引必须同步清理");
    }

    @Test
    void loadAllDropsExpiredRowsAndDeletesThem() {
        FakeDatabase db = new FakeDatabase();
        MuteService service = new MuteService(db);
        db.rows.add(mute("uuid-expired", "Expired", System.currentTimeMillis() - 1000));

        service.loadAll();

        assertFalse(service.find("expired").isPresent());
        assertFalse(service.find("uuid-expired").isPresent());
        assertTrue(db.rows.isEmpty(), "过期记录应顺手删库");
    }

    @Test
    void checkEvictsExpiredEntryFromCacheAndIndex() {
        FakeDatabase db = new FakeDatabase();
        MuteService service = new MuteService(db);
        service.mute(mute("uuid-old", "Old", System.currentTimeMillis() - 1000));

        assertFalse(service.check("uuid-old", "Old").isPresent(), "过期禁言不应拦截");
        assertFalse(service.find("old").isPresent());
        assertTrue(db.rows.isEmpty(), "命中过期记录应删库");
        assertTrue(service.mutedNames().isEmpty());
    }

    @Test
    void nameFallbackDoesNotDisturbUuidFastPath() {
        FakeDatabase db = new FakeDatabase();
        MuteService service = new MuteService(db);
        // 禁言时登记的名字与查询名大小写不一致也能命中
        service.mute(mute("uuid-dave", "Dave", MuteData.PERMANENT));
        assertTrue(service.check("uuid-dave", "dAvE").isPresent());
        // 名字索引不覆盖的场景（重名不存在）：uuid 优先
        assertEquals("uuid-dave", service.find("Dave").orElseThrow().uuid());
    }

    @Test
    void reMuteWithNewNameClearsOldNameIndexEntry() {
        FakeDatabase db = new FakeDatabase();
        MuteService service = new MuteService(db);
        service.mute(mute("uuid-1", "OldName", MuteData.PERMANENT));
        // 同一 uuid 改名后重新禁言：旧名字不应还能查到
        service.mute(mute("uuid-1", "NewName", MuteData.PERMANENT));
        assertFalse(service.find("oldname").isPresent(), "旧名字索引必须清理");
        assertTrue(service.find("newname").isPresent());
        assertTrue(service.find("uuid-1").isPresent());
    }
}
