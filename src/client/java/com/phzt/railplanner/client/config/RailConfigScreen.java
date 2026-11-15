package com.phzt.railplanner.client.config;

import com.mojang.blaze3d.Blaze3D;
import com.phzt.railplanner.client.RailPlannerClient;
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

/** 设置页：更新检查开关/间隔/立即检查，外加跳去原版按键绑定。 */
public class RailConfigScreen extends Screen {

    private static final Logger LOG = LoggerFactory.getLogger("railplanner/config");
    private static final int[] INTERVALS = {6, 12, 24, 48};
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final Screen parent;
    private int infoTop;

    public RailConfigScreen(Screen parent) {
        super(Component.translatable("railplanner.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int x = this.width / 2 - 100;
        int y = Math.max(46, this.height / 2 - 78);

        this.addRenderableWidget(Button.builder(labelUpdates(), b -> {
            RailConfig cfg = RailConfig.get();
            cfg.checkUpdates = !cfg.checkUpdates;
            cfg.save();
            b.setMessage(labelUpdates());
            LOG.info("自动检查更新 → {}", cfg.checkUpdates ? "开" : "关");
        }).bounds(x, y, 200, 20).build());

        y += 24;
        this.addRenderableWidget(Button.builder(labelInterval(), b -> {
            RailConfig cfg = RailConfig.get();
            cfg.intervalHours = nextInterval(cfg.intervalHours);
            cfg.save();
            b.setMessage(labelInterval());
            LOG.info("检查间隔 → {} 小时", cfg.intervalHours);
        }).bounds(x, y, 200, 20).build());

        y += 24;
        this.addRenderableWidget(Button.builder(Component.translatable("railplanner.config.check_now"), b -> {
            LOG.info("手动触发检查更新");
            UpdateChecker.checkNow();
        }).bounds(x, y, 200, 20).build());

        y += 24;
        Button open = Button.builder(Component.translatable("railplanner.config.open_page"), b -> {
            String url = pageUrl();
            LOG.info("打开下载页 {}", url);
            try {
                Blaze3D.openUri(URI.create(url));
            } catch (RuntimeException e) {
                LOG.warn("打开 {} 失败：{}", url, e.toString());
            }
        }).bounds(x, y, 200, 20).build();
        this.addRenderableWidget(open);

        y += 24;
        this.addRenderableWidget(Button.builder(Component.translatable("railplanner.config.keybinds"),
                b -> this.minecraft.gui.setScreen(new KeyBindsScreen(this, this.minecraft.options)))
                .bounds(x, y, 200, 20).build());

        y += 24;
        this.addRenderableWidget(Button.builder(Component.translatable("railplanner.config.done"),
                b -> this.minecraft.gui.setScreen(this.parent)).bounds(x, y, 200, 20).build());

        this.infoTop = y + 30;
        LOG.info("设置页打开：{}x{}，按钮 6 个", this.width, this.height);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        g.centeredText(this.font, this.getTitle(), this.width / 2, 24, 0xFFFFFFFF);

        int left = this.width / 2 - 100;
        int y = this.infoTop;
        int text = 0xFFE6E6E6;

        g.text(this.font, Component.translatable("railplanner.config.version",
                UpdateChecker.currentVersion(), latestLabel()), left, y, text);

        y += 12;
        long last = UpdateChecker.lastCheckEpochMs();
        String when = last <= 0
                ? Component.translatable("railplanner.config.never").getString()
                : TIME.format(Instant.ofEpochMilli(last));
        g.text(this.font, Component.translatable("railplanner.config.last_check", when), left, y, text);

        y += 12;
        g.text(this.font, statusLine(), left, y, statusColor());

        y += 14;
        Component keys = Component.translatable("railplanner.config.keys",
                RailPlannerClient.keyName(RailPlannerClient.TOGGLE),
                RailPlannerClient.keyName(RailPlannerClient.SETTINGS));
        g.text(this.font, keys, left, y, 0xFF9AA4AE);

        Component note = Component.translatable("railplanner.config.note");
        g.text(this.font, note, this.width / 2 - this.font.width(note) / 2, this.height - 26, 0xFF8A949E);
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
