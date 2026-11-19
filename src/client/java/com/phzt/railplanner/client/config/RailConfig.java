package com.phzt.railplanner.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
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
    /**
     * 内网更新源：开着时先查 railplannerrelease.phzt.lan 的 latest.json，查不到再退回 GitHub。
     * 正式包默认关（内网域名在外面解析不了）；版本号里带 -debug 的调试构建自动开。
     */
    public boolean lanUpdates = false;
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
    /** 隧道/桥用半透明面片画出要挖/要架的那一块（关掉就退回一根根立柱）。 */
    public boolean worldSurfaces = true;
    /** 编辑时在世界里把已选的点画出来（一根针 + 一个方块框 + 序号）。 */
    public boolean worldAnchors = true;
    /** 锁住已选的点：左键按住不再拖动，免得误触把线扯歪（按 L 切换）。 */
    public boolean anchorLock;
    /**
     * 世界里拿着触发物品右键点中一个点，直接弹出编辑框改它的 Y / 名字 / 类型。
     * 关掉之后右键就只是原版的「放方块」。
     */
    public boolean poiEdit = true;
    /**
     * 正交布线：新画的线尽量沿 X / Z 轴铺，方向变了用一个 90° 弯去拐（默认开）。
     *
     * <p>只管新建的线：老线路存盘里没有这一项，读出来是关的，要转就在地图面板上点
     * 「正交化」。关掉之后想怎么斜就怎么斜（山区展线、螺旋展线）。</p>
     */
    public boolean orthogonal = true;
    /**
     * 缓和曲线：新画的线每个弯都铺「回旋线—圆弧—回旋线」（默认开）。
     *
     * <p>老线路存盘里没有这一项，读出来是关的，线形不变；要转就在地图面板上点
     * 「缓和化」。关掉之后新线是纯圆曲线。</p>
     */
    public boolean easement = true;
    /** 触发编辑框的物品 id，默认普通铁轨；写错只是不触发，不会崩。 */
    public String poiEditItem = "minecraft:rail";
    /**
     * 切换「这一点锁不锁」的物品 id，默认锁链：三维世界里拿着它右键点中一个点，
     * 就把这一点的锁翻过来（锁上之后拖不动它）。写错只是不触发。
     *
     * <p>1.21.9 起锁链的 id 从 {@code minecraft:chain} 改成了 {@code minecraft:iron_chain}
     * （显示名也成了 Iron Chain），本模组跟的是新版本，所以默认写新的那个。</p>
     */
    public String poiLockItem = "minecraft:iron_chain";

    /**
     * 铁路限界标红：在三维世界里把限界内挡路的方块画成红框（默认关）。
     *
     * <p>下面是它的四个数：宽度按奇数（3/5/7…，以中心线左右对称），{@code clearanceUp}
     * 是轨面以上要留的净空，{@code clearanceDown} 是轨面以下要挖的深度，
     * {@code clearanceRange} 是「只算玩家周围这么多格」。见 {@code clearance.Corridor}。</p>
     */
    public boolean clearanceOn;
    public int clearanceWidth = 5;
    public int clearanceUp = 6;
    public int clearanceDown;
    public int clearanceRange = 64;

    /**
     * 停用本模组的全部快捷键与鼠标操作（防和其他模组抢键）。
     * 只剩「打开设置页」那个键还能用——否则关掉之后就再也开不回来了。
     */
    public boolean hotkeysOff;
    /**
     * 诊断日志（logs/railplanner-diag.log）：记渲染/加载/同步每一步在做什么，外加每 10 秒
     * 一行心跳。null = 按构建类型决定——版本号带 -debug 的调试包默认开，正式包默认关，
     * 玩家可以在设置页或 /railplanner diag on 打开。
     */
    public Boolean diag;
    /** 诊断级别：basic 只记变化/事件/异常/心跳，verbose 再加高频细节。 */
    public String diagLevel = "basic";
    /** 和服务端同步：服务端装了本模组才生效，关掉就纯本地。 */
    public boolean syncEnabled = true;
    /** 已经提示过「服务端没装」的服务器，免得每次进服都在聊天栏刷一遍。 */
    public List<String> noModNotified = new ArrayList<>();
    /**
     * 查到新版本就自动下载、校验、替换 mods 里的 jar（重启后生效）。
     * 默认关：模组改自己占着的那份 jar 属于要玩家点头的动作，设置页里的「下载并安装」就是手动那一下。
     */
    public boolean autoUpdate = false;
    /** 线上版本 jar 的下载地址（从 release 的 assets 里挑出来的）。 */
    public String pendingAssetUrl = "";
    /** 线上版本的 sha256，形如 sha256:xxxx。 */
    public String pendingSha256 = "";
    /** 线上版本的字节数，下载完用来挡掉截断。 */
    public long pendingSize;

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
        if (noModNotified == null) {
            noModNotified = new ArrayList<>();
        }
        if (poiEditItem == null || poiEditItem.isBlank()) {
            poiEditItem = "minecraft:rail";
        }
        if (poiLockItem == null || poiLockItem.isBlank()) {
            poiLockItem = "minecraft:iron_chain";
        }
        if (!"verbose".equalsIgnoreCase(diagLevel)) {
            diagLevel = "basic";
        }
        clearanceWidth = com.phzt.railplanner.clearance.Corridor.fixWidth(clearanceWidth);
        clearanceUp = Math.max(0, Math.min(com.phzt.railplanner.clearance.Corridor.MAX_UP, clearanceUp));
        clearanceDown = Math.max(0, Math.min(com.phzt.railplanner.clearance.Corridor.MAX_DOWN, clearanceDown));
        clearanceRange = Math.max(16, Math.min(256, clearanceRange));
        return this;
    }

    /** 版本号是不是 debug 构建：每次都查 FabricLoader 太浪费，判一次记住。 */
    private static Boolean debugBuildCache;

    /** 诊断日志开不开：配置里写过就听配置，没写过就按构建类型（debug 包默认开）。 */
    public boolean diagOn() {
        if (diag != null) {
            return diag;
        }
        if (debugBuildCache == null) {
            debugBuildCache = com.phzt.railplanner.client.update.UpdateChecker.debugBuild();
        }
        return debugBuildCache;
    }

    /** 规整过的触发物品 id：空着算普通铁轨，没写命名空间算 minecraft。 */
    public String poiItemId() {
        return normalizeItem(poiEditItem, "minecraft:rail");
    }

    /** 规整过的锁链物品 id：空着算锁链，没写命名空间算 minecraft。 */
    public String lockItemId() {
        return normalizeItem(poiLockItem, "minecraft:iron_chain");
    }

    private static String normalizeItem(String raw, String fallback) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (value.isEmpty()) {
            return fallback;
        }
        return value.indexOf(':') >= 0 ? value : "minecraft:" + value;
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
