package com.phzt.railplanner.client.render;

import com.phzt.railplanner.client.Loc;
import com.phzt.railplanner.client.RailPlannerClient;
import com.phzt.railplanner.client.config.RailConfig;
import com.phzt.railplanner.client.model.RailRoute;
import com.phzt.railplanner.client.planner.Planner;
import com.phzt.railplanner.client.planner.RouteStats;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** 世界里那一行状态条：不开地图也能看到当前线的坡、隧道与桥。 */
public final class WorldHud {

    private static final int PANEL = 0xC8101418;
    private static final int BORDER = 0x663A7BD5;
    private static final int TITLE = 0xFFFFD740;
    private static final int TEXT = 0xFFE6E6E6;
    private static final int DIM = 0xFF9AA4AE;
    private static final int WARN = 0xFFFF6E6E;
    private static final int CAUTION = 0xFFFFB74D;

    private WorldHud() {
    }

    public static void render(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        // 地图开着的时候由地图里那块面板负责，这里就不重复了。
        if (mc.level == null || mc.gui.screen() != null || !Planner.isActive()) {
            return;
        }
        RailRoute route = Planner.displayRoute();
        if (route == null) {
            return;
        }
        RouteStats st = Planner.ensureStats();
        RailConfig config = RailConfig.get();
        Font font = mc.font;

        List<String> texts = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        texts.add(route.name);
        colors.add(TITLE);
        if (st != null) {
            boolean bad = st.maxTerrainGradePct > RouteStats.MAX_DESIGN_GRADE + 0.05;
            texts.add(String.format(Loc.s(" · 坡 %.1f%%", " · grade %.1f%%"), st.maxTerrainGradePct));
            colors.add(bad ? WARN : TEXT);
            if (bad) {
                texts.add(Loc.s("（超限）", " (over limit)"));
                colors.add(WARN);
            }
            int tunnel = (int) Math.round(st.tunnelLength);
            int bridge = (int) Math.round(st.bridgeLength);
            if (tunnel > 0) {
                texts.add(Loc.s(" · 隧 ", " · tunnel ") + tunnel);
                colors.add(TEXT);
            }
            if (bridge > 0) {
                texts.add(Loc.s(" · 桥 ", " · bridge ") + bridge);
                colors.add(TEXT);
            }
            if (st.unknownCells > 0) {
                texts.add(Loc.s(" · 未知 ", " · unknown ") + st.unknownCells);
                colors.add(CAUTION);
            }
        }
        if (!config.worldLines) {
            texts.add(Loc.s(" · 3D 线关", " · 3D line off"));
            colors.add(DIM);
        }
        if (config.anchorLock) {
            texts.add(" · 点已锁");
            colors.add(CAUTION);
        }
        texts.add(" · " + RailPlannerClient.keyName(RailPlannerClient.WORLD_TOGGLE) + Loc.s(" 开关 · ", " toggle · ")
                + RailPlannerClient.keyName(RailPlannerClient.WORLD_XRAY) + Loc.s(" 穿山", " through"));
        colors.add(DIM);

        int width = 12;
        for (String t : texts) {
            width += font.width(t);
        }
        int x = 6;
        int y = 6;
        int h = 16;
        g.fill(x, y, x + width, y + h, PANEL);
        g.fill(x, y, x + width, y + 1, BORDER);
        g.fill(x, y + h - 1, x + width, y + h, BORDER);

        int tx = x + 6;
        int ty = y + 4;
        for (int i = 0; i < texts.size(); i++) {
            g.text(font, texts.get(i), tx, ty, colors.get(i));
            tx += font.width(texts.get(i));
        }
    }
}
