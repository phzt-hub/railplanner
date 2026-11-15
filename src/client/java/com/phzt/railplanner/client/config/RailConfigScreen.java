package com.phzt.railplanner.client.config;

import com.mojang.blaze3d.Blaze3D;
import com.phzt.railplanner.client.RailPlannerClient;
import com.phzt.railplanner.client.planner.Planner;
import com.phzt.railplanner.client.sync.SyncClient;
import com.phzt.railplanner.client.update.Updater;
import com.phzt.railplanner.client.update.UpdateChecker;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 设置页：左栏世界里的 3D 线（开关、穿山、隧道/桥的形态、显示已选的点、距离），
 * 右栏编辑锁定与更新检查，底下一行是按键绑定和完成。
 *
 * <p>所有开关都落盘到 {@code config/railplanner.json}，改完立刻生效（画的时候每次读配置）。
 */
public class RailConfigScreen extends Screen {

    private static final Logger LOG = LoggerFactory.getLogger("railplanner/config");
    private static final int[] INTERVALS = {6, 12, 24, 48};
    private static final int[] RANGES = {64, 128, 256, 512, 1024};
    private static final int ROW = 22;
    private static final int HEADER = 0xFF9AA4AE;
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final Screen parent;
    private int infoTop;
    private int leftX;
    private int rightX;
    private int colW;
    private int headerY;
    private Button installButton;
    private Button restartButton;

    public RailConfigScreen(Screen parent) {
        super(Component.translatable("railplanner.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int gap = 8;
        this.colW = Math.min(200, Math.max(60, (this.width - 3 * gap) / 2));
        this.leftX = this.width / 2 - gap / 2 - this.colW;
        this.rightX = this.width / 2 + gap / 2;
        int top = Math.max(56, this.height / 2 - 84);
        this.headerY = top - 13;
        int left = top;
        int right = top;

        // 左栏：世界里的 3D 线
        this.addRenderableWidget(Button.builder(labelWorldLines(), b -> {
            RailConfig cfg = RailConfig.get();
            cfg.worldLines = !cfg.worldLines;
            cfg.save();
            b.setMessage(labelWorldLines());
            LOG.info("世界里的 3D 线 → {}", cfg.worldLines ? "开" : "关");
            SyncClient.markDirty();
        }).bounds(leftX, left, colW, 20).build());
        left += ROW;

        this.addRenderableWidget(Button.builder(labelXray(), b -> {
            RailConfig cfg = RailConfig.get();
            cfg.worldXray = !cfg.worldXray;
            cfg.save();
            b.setMessage(labelXray());
            LOG.info("3D 线穿山可见 → {}", cfg.worldXray ? "开" : "关");
            SyncClient.markDirty();
        }).bounds(leftX, left, colW, 20).build());
        left += ROW;

        this.addRenderableWidget(Button.builder(labelShape(), b -> {
            RailConfig cfg = RailConfig.get();
            cfg.worldSurfaces = !cfg.worldSurfaces;
            cfg.save();
            b.setMessage(labelShape());
            LOG.info("隧道/桥的画法 → {}", cfg.worldSurfaces ? "半透明面片" : "立柱");
            SyncClient.markDirty();
        }).bounds(leftX, left, colW, 20).build());
        left += ROW;

        this.addRenderableWidget(Button.builder(labelAnchors(), b -> {
            RailConfig cfg = RailConfig.get();
            cfg.worldAnchors = !cfg.worldAnchors;
            cfg.save();
            b.setMessage(labelAnchors());
            LOG.info("世界里的已选点 → {}", cfg.worldAnchors ? "开" : "关");
            SyncClient.markDirty();
        }).bounds(leftX, left, colW, 20).build());
        left += ROW;

        this.addRenderableWidget(Button.builder(labelRange(), b -> {
            RailConfig cfg = RailConfig.get();
            cfg.worldRange = nextRange(cfg.worldRange);
            cfg.save();
            b.setMessage(labelRange());
            LOG.info("3D 线显示距离 → {} 格", Math.round(cfg.worldRange));
            SyncClient.markDirty();
        }).bounds(leftX, left, colW, 20).build());
        left += ROW;

        this.addRenderableWidget(Button.builder(labelHotkeys(), b -> {
            RailConfig cfg = RailConfig.get();
            cfg.hotkeysOff = !cfg.hotkeysOff;
            cfg.save();
            b.setMessage(labelHotkeys());
            Planner.notifyHotkeysOff();
        }).bounds(leftX, left, colW, 20).build());
        left += ROW;

        this.addRenderableWidget(Button.builder(labelSync(), b -> {
            RailConfig cfg = RailConfig.get();
            cfg.syncEnabled = !cfg.syncEnabled;
            cfg.save();
            b.setMessage(labelSync());
            LOG.info("与服务端同步 → {}", cfg.syncEnabled ? "开" : "关（只用本地）");
        }).bounds(leftX, left, colW, 20).build());
        left += ROW;

        // 右栏：编辑锁定 + 更新检查
        this.addRenderableWidget(Button.builder(labelLock(), b -> {
            Planner.toggleLock();
            b.setMessage(labelLock());
        }).bounds(rightX, right, colW, 20).build());
        right += ROW;

        this.addRenderableWidget(Button.builder(labelUpdates(), b -> {
            RailConfig cfg = RailConfig.get();
            cfg.checkUpdates = !cfg.checkUpdates;
            cfg.save();
            b.setMessage(labelUpdates());
            LOG.info("自动检查更新 → {}", cfg.checkUpdates ? "开" : "关");
        }).bounds(rightX, right, colW, 20).build());
        right += ROW;

        this.addRenderableWidget(Button.builder(labelInterval(), b -> {
            RailConfig cfg = RailConfig.get();
            cfg.intervalHours = nextInterval(cfg.intervalHours);
            cfg.save();
            b.setMessage(labelInterval());
            LOG.info("检查间隔 → {} 小时", cfg.intervalHours);
        }).bounds(rightX, right, colW, 20).build());
        right += ROW;

        this.addRenderableWidget(Button.builder(Component.translatable("railplanner.config.check_now"), b -> {
            LOG.info("手动触发检查更新");
            UpdateChecker.checkNow();
        }).bounds(rightX, right, colW, 20).build());
        right += ROW;

        this.addRenderableWidget(Button.builder(labelAutoUpdate(), b -> {
            RailConfig cfg = RailConfig.get();
            cfg.autoUpdate = !cfg.autoUpdate;
            cfg.save();
            b.setMessage(labelAutoUpdate());
            LOG.info("自动下载并安装更新 → {}", cfg.autoUpdate ? "开" : "关");
        }).bounds(rightX, right, colW, 20).build());
        right += ROW;

        this.installButton = this.addRenderableWidget(Button.builder(labelInstall(), b -> {
            LOG.info("手动下载并安装 {}", RailConfig.get().latestVersion);
            Updater.downloadNow();
            b.setMessage(labelInstall());
        }).bounds(rightX, right, colW, 20).build());
        right += ROW;

        // 只退出、不重拉：装好之后重启由玩家从启动器点（手册 §16）
        this.restartButton = this.addRenderableWidget(Button.builder(labelRestart(), b -> {
            LOG.info("退出游戏（待应用的新版本：{}）", Updater.version());
            if (!Updater.quitGame()) {
                b.setMessage(Component.literal("退出失败，请手动关掉游戏"));
            }
        }).bounds(rightX, right, colW, 20).build());
        right += ROW;

        // 底下一行：按键绑定 | 打开下载页 | 完成（两栏高度可能差一行，取低的那条）
        int bottom = Math.max(left, right);
        int totalW = colW * 2 + 8;
        int w3 = (totalW - 8) / 3;
        this.addRenderableWidget(Button.builder(Component.translatable("railplanner.config.keybinds"),
                b -> this.minecraft.gui.setScreen(new KeyBindsScreen(this, this.minecraft.options)))
                .bounds(leftX, bottom, w3, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("railplanner.config.open_page"), b -> {
            String url = pageUrl();
            LOG.info("打开下载页 {}", url);
            try {
                Blaze3D.openUri(URI.create(url));
            } catch (RuntimeException e) {
                LOG.warn("打开 {} 失败：{}", url, e.toString());
            }
        }).bounds(leftX + w3 + 4, bottom, w3, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("railplanner.config.done"),
                b -> this.minecraft.gui.setScreen(this.parent))
                .bounds(leftX + 2 * (w3 + 4), bottom, w3, 20).build());

        this.infoTop = bottom + ROW + 6;
        LOG.info("设置页打开：{}x{}，左栏 {} 行，右栏 {} 行", this.width, this.height,
                (left - top) / ROW, (right - top) / ROW);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        if (installButton != null) {
            installButton.setMessage(labelInstall());
        }
        if (restartButton != null) {
            restartButton.setMessage(labelRestart());
        }

        g.centeredText(this.font, this.getTitle(), this.width / 2, 24, 0xFFFFFFFF);
        g.text(this.font, Component.translatable("railplanner.config.col_world"), leftX, headerY, HEADER);
        g.text(this.font, Component.translatable("railplanner.config.col_edit"), rightX, headerY, HEADER);

        int text = 0xFFE6E6E6;
        int y = infoTop;
        if (y < this.height - 20) {
            g.text(this.font, Component.translatable("railplanner.config.version",
                    UpdateChecker.currentVersion(), latestLabel()), leftX, y, text);
        }

        y += 12;
        if (y < this.height - 20) {
            long last = UpdateChecker.lastCheckEpochMs();
            String when = last <= 0
                    ? Component.translatable("railplanner.config.never").getString()
                    : TIME.format(Instant.ofEpochMilli(last));
            g.text(this.font, Component.translatable("railplanner.config.last_check", when), leftX, y, text);
        }

        y += 12;
        if (y < this.height - 20) {
            g.text(this.font, statusLine(), leftX, y, statusColor());
        }

        y += 12;
        if (y < this.height - 20) {
            g.text(this.font, Component.literal("同步：" + syncLine()), leftX, y, 0xFF9AA4AE);
        }

        y += 12;
        if (y < this.height - 20) {
            g.text(this.font, Component.literal("更新：" + Updater.statusText()), leftX, y,
                    Updater.state() == Updater.State.FAILED ? 0xFFFF6E6E : 0xFF9AA4AE);
        }

        y += 14;
        if (y < this.height - 20) {
            Component keys = Component.translatable("railplanner.config.keys",
                    RailPlannerClient.keyName(RailPlannerClient.TOGGLE),
                    RailPlannerClient.keyName(RailPlannerClient.SETTINGS));
            g.text(this.font, keys, leftX, y, HEADER);
        }

        y += 12;
        if (y < this.height - 20) {
            g.text(this.font, Component.literal("停用后只剩设置页这一键，其余按键与鼠标操作都不响应"), leftX, y,
                    0xFF8A949E);
        }

        if (this.height >= 320) {
            Component note = Component.translatable("railplanner.config.note");
            g.text(this.font, note, this.width / 2 - this.font.width(note) / 2, this.height - 26, 0xFF8A949E);
        }
    }

    /** 有新版本就打开 release 页，否则打开仓库的 releases 列表。 */
    private static String pageUrl() {
        String url = RailConfig.get().latestUrl;
        return url == null || url.isEmpty() ? UpdateChecker.RELEASES_PAGE : url;
    }

    private static Component latestLabel() {
        String latest = RailConfig.get().latestVersion;
        return latest == null || latest.isEmpty()
                ? Component.translatable("railplanner.config.unknown")
                : Component.literal(latest);
    }

    private static Component labelWorldLines() {
        return Component.translatable(RailConfig.get().worldLines
                ? "railplanner.config.world_lines_on" : "railplanner.config.world_lines_off");
    }

    private static Component labelXray() {
        return Component.translatable(RailConfig.get().worldXray
                ? "railplanner.config.world_xray_on" : "railplanner.config.world_xray_off");
    }

    private static Component labelShape() {
        return Component.translatable(RailConfig.get().worldSurfaces
                ? "railplanner.config.world_shape_surface" : "railplanner.config.world_shape_post");
    }

    private static Component labelAnchors() {
        return Component.translatable(RailConfig.get().worldAnchors
                ? "railplanner.config.world_anchors_on" : "railplanner.config.world_anchors_off");
    }

    private static Component labelRange() {
        return Component.translatable("railplanner.config.world_range", Math.round(RailConfig.get().worldRange));
    }

    private static Component labelAutoUpdate() {
        return Component.literal(RailConfig.get().autoUpdate ? "自动下载更新：开" : "自动下载更新：关");
    }

    private static Component labelHotkeys() {
        return Component.literal(RailConfig.get().hotkeysOff ? "停用全部快捷键：开" : "停用全部快捷键：关");
    }

    private static Component labelSync() {
        return Component.literal(RailConfig.get().syncEnabled ? "同步到服务端：开" : "同步到服务端：关");
    }

    private static String syncLine() {
        String key = SyncClient.key();
        String where = key == null || key.isEmpty() ? "" : "（" + key + "）";
        return SyncClient.hudText() + where;
    }

    private static Component labelInstall() {
        if (Updater.installed()) {
            return Component.literal("已装好 " + Updater.version() + "，待重启");
        }
        if (Updater.downloading()) {
            return Component.literal("正在下载…");
        }
        String latest = RailConfig.get().latestVersion;
        if (UpdateChecker.isNewer() && latest != null && !latest.isEmpty()) {
            return Component.literal("下载并安装 " + latest);
        }
        return Component.literal("下载并安装（已是最新）");
    }

    private static Component labelRestart() {
        return Component.literal(Updater.installed() ? "退出游戏以应用新版本" : "退出游戏");
    }

    private static Component labelLock() {
        return Component.translatable(RailConfig.get().anchorLock
                ? "railplanner.config.lock_on" : "railplanner.config.lock_off");
    }

    private static Component labelUpdates() {
        return Component.translatable(RailConfig.get().checkUpdates
                ? "railplanner.config.updates_on" : "railplanner.config.updates_off");
    }

    private static Component labelInterval() {
        return Component.translatable("railplanner.config.interval", RailConfig.get().intervalHours);
    }

    private static int nextInterval(int current) {
        for (int i = 0; i < INTERVALS.length; i++) {
            if (INTERVALS[i] == current) {
                return INTERVALS[(i + 1) % INTERVALS.length];
            }
        }
        return RailConfig.DEFAULT_INTERVAL_HOURS;
    }

    private static int nextRange(double current) {
        int now = (int) Math.round(current);
        for (int i = 0; i < RANGES.length; i++) {
            if (RANGES[i] == now) {
                return RANGES[(i + 1) % RANGES.length];
            }
        }
        return 256;
    }

    private static Component statusLine() {
        return switch (UpdateChecker.status()) {
            case NEVER -> Component.translatable("railplanner.config.status.never");
            case CHECKING -> Component.translatable("railplanner.config.status.checking");
            case UP_TO_DATE -> Component.translatable("railplanner.config.status.up_to_date");
            case NEWER -> Component.translatable("railplanner.config.status.newer", UpdateChecker.detail());
            case FAILED -> Component.translatable("railplanner.config.status.failed", UpdateChecker.detail());
        };
    }

    private static int statusColor() {
        return switch (UpdateChecker.status()) {
            case NEWER -> 0xFFFFD740;
            case FAILED -> 0xFFFF8A80;
            case CHECKING -> 0xFF9AA4AE;
            default -> 0xFF69F0AE;
        };
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }
}
