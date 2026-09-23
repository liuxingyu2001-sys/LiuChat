package com.liu.liuchat.storage;

import com.liu.liuchat.model.MuteData;

import java.util.List;

/**
 * 存储层接口：SQLite（单服）/ MySQL（跨服共享）双实现，
 * 由 {@link DatabaseFactory} 按 config.yml 的 storage.type 选择。
 * <p>
 * 实现必须保证：初始化失败时降级为"仅内存"（isReady()=false），不阻塞插件启用。
 */
public interface Database extends AutoCloseable {

    boolean isReady();

    List<MuteData> loadMutes();

    void saveMute(MuteData mute);

    void deleteMute(String uuid);

    @Override
    void close();
}
