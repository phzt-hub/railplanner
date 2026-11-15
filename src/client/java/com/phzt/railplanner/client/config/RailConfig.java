package com.phzt.railplanner.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** <code>config/railplanner.json</code>：更新检查开关、间隔与上次结果。 */
public final class RailConfig {

    private static final Logger LOG = LoggerFactory.getLogger("railplanner/config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 默认自动检查间隔，小时。 */
    public static final int DEFAULT_INTERVAL_HOURS = 12;

    /** 自动检查更新（默认开）。 */
    public boolean checkUpdates = true;
    /** 自动检查的最小间隔，小时。 */
    public int intervalHours = DEFAULT_INTERVAL_HOURS;
    /** 上次检查的时间戳（毫秒）。 */
    public long lastCheckEpochMs;
    /** 上次响应的 ETag，命中缓存时 GitHub 返回 304、不计入限流。 */
    public String lastEtag = "";
    /** 上次成功读到的远程版本（如 v0.2.0）。 */
    public String latestVersion = "";
    /** 上次读到的 release 页面。 */
    public String latestUrl = "";
    /** 地图上的信息面板是否折叠成一行（按 H 切换，记住状态）。 */
    public boolean hudCollapsed;
    /** 在三维世界里画当前这条线（按 V 切换，记住状态）。 */
    public boolean worldLines = true;
    /** 3D 线穿山可见，画在所有东西之上（按 X 切换）。 */
    public boolean worldXray;
    /** 3D 线只画离镜头这么远以内的段，格。 */
    public double worldRange = 256.0;

    private static RailConfig instance = new RailConfig();

    private RailConfig() {
    }

    public static RailConfig get() {
        return instance;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("railplanner.json");
    }

    public static void load() {
        Path path = file();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            RailConfig read = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), RailConfig.class);
            if (read != null) {
                instance = read.sanitized();
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("读取 {} 失败，沿用默认值：{}", path, e.toString());
        }
    }

    private RailConfig sanitized() {
        intervalHours = Math.max(1, Math.min(720, intervalHours));
        worldRange = Math.max(32.0, Math.min(2048.0, worldRange));
        if (lastEtag == null) {
            lastEtag = "";
        }
        if (latestVersion == null) {
            latestVersion = "";
        }
        if (latestUrl == null) {
            latestUrl = "";
        }
        return this;
    }

    public static void save() {
        Path path = file();
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(tmp, GSON.toJson(instance), StandardCharsets.UTF_8);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.warn("写入 {} 失败：{}", path, e.toString());
        }
    }
}
