package com.phzt.railplanner.client.sync;

import com.phzt.railplanner.RailPlanner;
import com.phzt.railplanner.sync.Dto;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;

/**
 * 同步基线：上一次成功同步时，每条线的内容指纹。
 *
 * <p>它不参与渲染，只服务一件事——下一次同步时判断「哪边动过」。
 * 文件在 {@code railplanner/sync/<世界身份>.json}，删掉它等于「这台机器当作第一次同步」。</p>
 */
public final class SyncBase {

    public String version = "1";
    public String worldKey;
    public String settings;
    public Map<String, String> routes = new LinkedHashMap<>();

    public static Path file(String worldKey) {
        return FabricLoader.getInstance().getGameDir()
                .resolve("railplanner").resolve("sync")
                .resolve(SyncKeys.clean(worldKey) + ".json");
    }

    public static SyncBase load(String worldKey) {
        SyncBase base = new SyncBase();
        base.worldKey = SyncKeys.clean(worldKey);
        Path p = file(worldKey);
        if (!Files.isRegularFile(p)) {
            return base;
        }
        try {
            SyncBase read = Dto.GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), SyncBase.class);
            if (read != null) {
                read.worldKey = base.worldKey;
                if (read.routes == null) {
                    read.routes = new LinkedHashMap<>();
                }
                return read;
            }
        } catch (Exception e) {
            RailPlanner.LOG.warn("读取同步基线失败 {}: {}", p, e.toString());
        }
        return base;
    }

    public void save() {
        Path p = file(worldKey);
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, Dto.GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (Exception e) {
            RailPlanner.LOG.warn("写入同步基线失败 {}: {}", p, e.toString());
        }
    }
}
