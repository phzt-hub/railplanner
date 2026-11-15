package com.phzt.railplanner.client.render;

import com.phzt.railplanner.client.RailPlannerClient;
import com.phzt.railplanner.client.config.RailConfig;
import com.phzt.railplanner.client.geom.Alignment;
import com.phzt.railplanner.client.geom.BlockPath;
import com.phzt.railplanner.client.geom.Vec2;
import com.phzt.railplanner.client.model.Anchor;
import com.phzt.railplanner.client.model.PointKind;
import com.phzt.railplanner.client.model.RailRoute;
import com.phzt.railplanner.client.planner.Planner;
import com.phzt.railplanner.client.planner.RouteStats;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** 世界地图上的路线绘制与统计面板。 */
public final class PlannerRenderer {

    /** 面板底色：留一点点透，但盖得住 Xaero 自己画在左上角的按钮。 */
    private static final int PANEL_BG = 0xF2101418;
    private static final int PANEL_BORDER = 0xFF3A7BD5;
    private static final int TITLE = 0xFFFFD740;
    private static final int TEXT = 0xFFE6E6E6;
    private static final int DIM = 0xFF9AA4AE;
    private static final int ACCENT = 0xFF69F0AE;
    private static final int WARN = 0xFFFF6E6E;
    private static final int CAUTION = 0xFFFFB74D;
    /** 隧道/桥占已判长度的比例超过这个数（且超过 32 格）就算工程偏大。 */
    /** 上一帧画出来的「线名」文字框，双击它改名字和颜色。 */
    private static int nameX;
    private static int nameY;
    private static int nameW;
    private static int nameH;
    private static RailRoute nameRoute;

    /** 上一帧画出来的色板 / 色相条，点它们改颜色。 */
    private static final int[] SWATCH_X = new int[12];
    private static int swatchY;
    private static int swatchSize = 14;
    private static int hueX;
    private static int hueY;
    private static int hueW;
    private static int hueH;

    private static final double TUNNEL_BRIDGE_WARN_SHARE = 0.35;
    private static final double WARN_MIN_LENGTH = 32.0;

    private PlannerRenderer() {
    }

    public static void render(GuiGraphicsExtractor g, MapView view, int mouseX, int mouseY,
                              List<RailRoute> routes, RailRoute current, int hoverIndex, int dragIndex) {
        nameRoute = null;
        RailRoute shown = Planner.displayRoute();
        for (RailRoute route : routes) {
            drawRoute(g, view, route, route == current);
            drawRouteName(g, view, route, route == shown);
        }
        if (current != null) {
            drawCells(g, view, current);
            drawVertices(g, view, current, hoverIndex, dragIndex);
            drawRubberBand(g, view, current, mouseX, mouseY);
        }
        if (Planner.isActive()) {
            // 面板跟世界里的 3D 线看同一条：没在编辑时退回最后那条。
            if (RailConfig.get().hudCollapsed) {
                drawHudCollapsed(g, shown);
            } else {
                drawHud(g, view, mouseX, mouseY, shown);
            }
            drawHover(g, view, mouseX, mouseY, current);
            drawNotice(g, view);
            drawPointEditor(g, view, current);
            drawRouteEditor(g, view);
        } else if (!routes.isEmpty()) {
            Font font = Minecraft.getInstance().font;
            badge(g, font, 6, 6, RailConfig.get().hotkeysOff
                    ? "铁路选线 · 快捷键已停用，设置页里可恢复（" + routes.size() + " 条线）"
                    : "铁路选线 · 按 " + RailPlannerClient.keyName(RailPlannerClient.TOGGLE)
                            + " 进入规划模式（" + routes.size() + " 条线）");
        }
    }

    private static void drawRoute(GuiGraphicsExtractor g, MapView view, RailRoute route, boolean selected) {
        Alignment al = route.alignment();
        if (al.length() < 0.1) {
            return;
        }
        int baseColor = route.color;
        int color = selected ? baseColor : ((baseColor & 0x00FFFFFF) | 0xB0000000);
        int thickness = selected ? 3 : 2;
        double pxPerBlock = Math.max(0.01, view.pixelsPerBlock());
        double stepWorld = Math.max(0.75, 7.0 / pxPerBlock);

        for (Alignment.Piece piece : al.pieces()) {
            int n = (int) Math.max(1, Math.min(800, Math.ceil(piece.length / stepWorld)));
            Vec2 prev = al.pointAt(piece.startS);
            double prevX = view.guiX(prev.x);
            double prevY = view.guiY(prev.z);
            for (int i = 1; i <= n; i++) {
                double s = piece.startS + piece.length * i / n;
                Vec2 p = al.pointAt(s);
                double x = view.guiX(p.x);
                double y = view.guiY(p.z);
                if (visible(view, prevX, prevY, x, y)) {
                    thickLine(g, prevX, prevY, x, y, color, thickness);
                }
                prevX = x;
                prevY = y;
            }
        }
    }

    private static void drawCells(GuiGraphicsExtractor g, MapView view, RailRoute route) {
        double pxPerBlock = view.pixelsPerBlock();
        if (pxPerBlock < 1.2) {
            return;
        }
        BlockPath path = route.path();
        int drawn = 0;
        int size = Math.max(1, (int) Math.round(pxPerBlock * 0.8));
        int color = (route.color & 0x00FFFFFF) | 0x66000000;
        for (int i = 0; i < path.size() && drawn < 4000; i++) {
            double x = view.guiX(path.xs[i] + 0.5);
            double y = view.guiY(path.zs[i] + 0.5);
            if (x < -8 || y < -8 || x > view.width() + 8 || y > view.height() + 8) {
                continue;
            }
            int half = size / 2;
            g.fill((int) x - half, (int) y - half, (int) x - half + size, (int) y - half + size, color);
            drawn++;
        }
    }

    private static void drawVertices(GuiGraphicsExtractor g, MapView view, RailRoute route, int hover, int drag) {
        Font font = Minecraft.getInstance().font;
        boolean nameable = view.pixelsPerBlock() >= 0.4;
        for (int i = 0; i < route.anchors.size(); i++) {
            Anchor a = route.anchors.get(i);
            double x = view.guiX(a.x() + 0.5);
            double y = view.guiY(a.z() + 0.5);
            if (x < -14 || y < -14 || x > view.width() + 14 || y > view.height() + 14) {
                continue;
            }
            boolean active = i == drag || i == hover;
            drawIcon(g, (int) Math.round(x), (int) Math.round(y), a.kind(), active);
            if (nameable && !a.name().isEmpty()) {
                int tx = (int) Math.round(x) + 8;
                int ty = (int) Math.round(y) - 13;
                int w = font.width(a.name());
                g.fill(tx - 2, ty - 1, tx + w + 2, ty + 10, 0xA0101418);
                g.text(font, a.name(), tx, ty, active ? 0xFFFFFFFF : 0xFFD8E0E6);
            }
        }
    }

    /** 点的图标：类型决定形状，选中/悬停时放大一圈。 */
    private static void drawIcon(GuiGraphicsExtractor g, int cx, int cy, PointKind kind, boolean active) {
        int r = active ? 6 : 5;
        int dark = 0xFF101418;
        int color = 0xFF000000 | (kind.color & 0xFFFFFF);
        switch (kind) {
            case STATION -> {
                disc(g, cx, cy, r, dark);
                disc(g, cx, cy, r - 1, color);
                disc(g, cx, cy, Math.max(1, r - 3), 0xFFFFFFFF);
            }
            case CURVE -> {
                diamond(g, cx, cy, r + 1, dark);
                diamond(g, cx, cy, r, color);
            }
            case TUNNEL -> {
                triangle(g, cx, cy, r + 1, dark);
                triangle(g, cx, cy, r, color);
            }
            case BRIDGE -> {
                g.fill(cx - r - 1, cy - 2, cx + r + 2, cy + 3, dark);
                g.fill(cx - r, cy - 1, cx + r + 1, cy + 2, color);
            }
            case SIGNAL -> {
                thickLine(g, cx - r, cy - r, cx + r, cy + r, dark, 4);
                thickLine(g, cx - r, cy + r, cx + r, cy - r, dark, 4);
                thickLine(g, cx - r, cy - r, cx + r, cy + r, color, 2);
                thickLine(g, cx - r, cy + r, cx + r, cy - r, color, 2);
            }
            default -> {
                g.fill(cx - r, cy - r, cx + r + 1, cy + r + 1, dark);
                g.fill(cx - r + 1, cy - r + 1, cx + r, cy + r, 0xFF000000 | (0xE6E6E6 & 0xFFFFFF));
            }
        }
        if (kind != PointKind.PLAIN) {
            g.fill(cx - r - 1, cy - r - 1, cx - r, cy - r, dark);
        }
    }

    private static void disc(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int half = (int) Math.sqrt(Math.max(0, r * r - dy * dy));
            g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
        }
    }

    private static void diamond(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int half = r - Math.abs(dy);
            g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
        }
    }

    private static void triangle(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int half = (dy + r) * r / (2 * r + 1);
            g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
        }
    }

    /** 线名挂在线中间：2D 地图上常显，别的线淡一点。 */
    private static void drawRouteName(GuiGraphicsExtractor g, MapView view, RailRoute route, boolean shown) {
        String text = route.name == null ? "" : route.name;
        Alignment al = route.alignment();
        if (text.isEmpty() || al.length() < 1.0) {
            return;
        }
        Vec2 p = al.pointAt(al.length() * 0.5);
        double x = view.guiX(p.x);
        double y = view.guiY(p.z);
        if (x < -80 || y < -20 || x > view.width() + 80 || y > view.height() + 20) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int w = font.width(text);
        int tx = (int) Math.round(x) - w / 2;
        int ty = (int) Math.round(y) - 16;
        g.fill(tx - 3, ty - 2, tx + w + 3, ty + 11, shown ? 0xC0101418 : 0x90101418);
        g.text(font, text, tx, ty, shown ? 0xFFFFF3C4 : 0xFFAAB4BE);
    }

    private static void drawRubberBand(GuiGraphicsExtractor g, MapView view, RailRoute route, int mouseX, int mouseY) {
        if (route.anchors.isEmpty() || Planner.dragIndex >= 0) {
            return;
        }
        Anchor last = route.anchors.get(route.anchors.size() - 1);
        double x0 = view.guiX(last.x() + 0.5);
        double y0 = view.guiY(last.z() + 0.5);
        int color = (route.color & 0x00FFFFFF) | 0x80000000;
        thickLine(g, x0, y0, mouseX, mouseY, color, 1);
        double d = Math.hypot(mouseX - x0, mouseY - y0) / Math.max(0.001, view.pixelsPerBlock());
        Font font = Minecraft.getInstance().font;
        g.text(font, String.format("%.0f 格", d), mouseX + 6, mouseY + 6, TEXT);
    }

    private static void drawHud(GuiGraphicsExtractor g, MapView view, int mouseX, int mouseY, RailRoute current) {
        Font font = Minecraft.getInstance().font;
        RouteStats st = Planner.stats();
        Alignment al = current == null ? null : current.alignment();

        List<String> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        if (current == null) {
            add(lines, colors, "左键点地图，开始画一条新线", TEXT);
            add(lines, colors, RailPlannerClient.keyName(RailPlannerClient.TOGGLE) + " 退出规划 · Esc 关闭地图", TEXT);
        } else {
            add(lines, colors, String.format("%s · %d 点 · R %.0f", current.name, current.anchors.size(), current.radius), TEXT);
            if (al != null && al.length() > 0.1) {
                add(lines, colors, String.format("长 %.0f 格 · 轨道 %d 格 · 曲线 %d 段",
                        al.length(), current.path().size(), al.corners().size()), TEXT);
                if (!al.corners().isEmpty()) {
                    double minR = Double.MAX_VALUE;
                    double maxDefl = 0;
                    for (Alignment.Corner c : al.corners()) {
                        minR = Math.min(minR, c.radius);
                        maxDefl = Math.max(maxDefl, c.deflectionDegrees());
                    }
                    add(lines, colors, String.format("最小半径 %.1f · 最大偏角 %.1f°", minR, maxDefl), TEXT);
                }
            }
            if (st != null) {
                // 点的 Y 钉住之后，设计坡是玩家自己定的，可能顶到限坡以上：两个坡分开标。
                boolean terrainBad = gradeOverLimit(st);
                boolean designBad = st.maxDesignGradePct > RouteStats.MAX_DESIGN_GRADE + 0.05;
                add(lines, colors, String.format("地面最大坡 %.1f%%%s · 设计最大坡 %.1f%%%s · 限坡 %.1f%%（%s %s 调）",
                        st.maxTerrainGradePct, terrainBad ? "（超限）" : "",
                        st.maxDesignGradePct, designBad ? "（超限）" : "",
                        RouteStats.MAX_DESIGN_GRADE,
                        RailPlannerClient.keyName(RailPlannerClient.GRADE_DOWN),
                        RailPlannerClient.keyName(RailPlannerClient.GRADE_UP)),
                        (terrainBad || designBad) ? WARN : TEXT);
                add(lines, colors, String.format("爬 %d · 降 %d", Math.round(st.climb), Math.round(st.descent)), TEXT);
                boolean tunnelBad = shareOverLimit(st.tunnelLength, st);
                boolean bridgeBad = shareOverLimit(st.bridgeLength, st);
                add(lines, colors, String.format("隧道 %d%s · 桥 %d%s · 地面 %d · 未知 %d",
                        Math.round(st.tunnelLength), tunnelBad ? "（超限）" : "",
                        Math.round(st.bridgeLength), bridgeBad ? "（超限）" : "",
                        Math.round(st.groundLength), st.unknownCells),
                        (tunnelBad || bridgeBad) ? WARN : (st.unknownCells > 0 ? CAUTION : TEXT));
                add(lines, colors, String.format("铁轨 %d · 动力 %d · 火把 %d", st.rails, st.poweredRails, st.torches), TEXT);
                add(lines, colors, String.format("矿车 %s · %s（8 / 4 格每秒）",
                        RouteStats.time(st.secondsAt8), RouteStats.time(st.secondsAt4)), TEXT);
            }
        }
        add(lines, colors, "左键加点/拖顶点 · Ctrl+左键在线上插点 · 右键删点 · "
                + RailPlannerClient.keyName(RailPlannerClient.FINISH) + " 完成 · "
                + RailPlannerClient.keyName(RailPlannerClient.RADIUS) + " 半径 · "
                + RailPlannerClient.keyName(RailPlannerClient.AUTOROUTE) + " 自动选线 · "
                + RailPlannerClient.keyName(RailPlannerClient.HUD_COLLAPSE) + " 折叠", DIM);
        add(lines, colors, "双击顶点改 Y/名字/类型 · 双击上面那行线名改名字与颜色", DIM);

        boolean locked = RailConfig.get().anchorLock;
        add(lines, colors, RailPlannerClient.keyName(RailPlannerClient.WORLD_TOGGLE) + " 世界里的 3D 线 · "
                + RailPlannerClient.keyName(RailPlannerClient.WORLD_XRAY) + " 穿山可见 · "
                + RailPlannerClient.keyName(RailPlannerClient.LOCK) + (locked ? " 点已锁" : " 锁点"),
                locked ? CAUTION : DIM);

        int x = 6;
        int y = 6;
        int w = 306;
        for (String line : lines) {
            w = Math.max(w, font.width(line) + 18);
        }
        w = Math.max(w, font.width("铁路选线") + 74);
        int lineH = 11;
        int chartH = st != null ? 42 : 0;
        int h = 20 + lines.size() * lineH + chartH + 8;
        panel(g, x, y, w, h);

        int tx = x + 8;
        int ty = y + 7;
        g.text(font, "铁路选线", tx, ty, TITLE);
        g.text(font, Planner.isActive() ? "● 规划中" : "○ 只读", tx + 62, ty, Planner.isActive() ? ACCENT : DIM);
        ty += lineH + 3;
        for (int i = 0; i < lines.size(); i++) {
            g.text(font, lines.get(i), tx, ty, colors.get(i));
            if (i == 0 && current != null) {
                nameX = tx;
                nameY = ty;
                nameW = font.width(current.name == null ? "" : current.name);
                nameH = 10;
                nameRoute = current;
            }
            ty += lineH;
        }
        if (st != null) {
            drawGradeChart(g, x + 8, ty + 6, w - 16, 28, st);
        }
    }

    /** 折叠状态下的单行面板：线名、点数、坡与隧桥，超限的项标红。 */
    private static void drawHudCollapsed(GuiGraphicsExtractor g, RailRoute current) {
        Font font = Minecraft.getInstance().font;
        RouteStats st = Planner.stats();
        List<String> texts = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        if (current == null) {
            add(texts, colors, "铁路选线 · 左键点地图开始画线", TEXT);
        } else {
            add(texts, colors, current.name, TITLE);
            add(texts, colors, String.format(" · %d 点", current.anchors.size()), TEXT);
            if (st != null) {
                boolean gradeBad = gradeOverLimit(st);
                add(texts, colors, String.format(" · 坡 %.1f%%%s", st.maxTerrainGradePct,
                        gradeBad ? "（超限）" : ""), gradeBad ? WARN : TEXT);
                int tunnel = (int) Math.round(st.tunnelLength);
                int bridge = (int) Math.round(st.bridgeLength);
                if (tunnel > 0) {
                    boolean tunnelBad = shareOverLimit(st.tunnelLength, st);
                    add(texts, colors, String.format(" · 隧 %d%s", tunnel, tunnelBad ? "（超限）" : ""),
                            tunnelBad ? WARN : TEXT);
                }
                if (bridge > 0) {
                    boolean bridgeBad = shareOverLimit(st.bridgeLength, st);
                    add(texts, colors, String.format(" · 桥 %d%s", bridge, bridgeBad ? "（超限）" : ""),
                            bridgeBad ? WARN : TEXT);
                }
                if (st.unknownCells > 0) {
                    add(texts, colors, String.format(" · 未知 %d", st.unknownCells), CAUTION);
                }
            }
        }
        add(texts, colors, " · " + RailPlannerClient.keyName(RailPlannerClient.HUD_COLLAPSE) + " 展开", DIM);

        int x = 6;
        int y = 6;
        int w = 26;
        for (String t : texts) {
            w += font.width(t);
        }
        int h = 18;
        panel(g, x, y, w, h);
        // 左边一小块状态色，省掉「铁路选线 · 规划中」那几个字
        g.fill(x + 6, y + 6, x + 12, y + 12, Planner.isActive() ? ACCENT : DIM);
        int tx = x + 17;
        int ty = y + 6;
        for (int i = 0; i < texts.size(); i++) {
            g.text(font, texts.get(i), tx, ty, colors.get(i));
            if (i == 0 && current != null) {
                nameX = tx;
                nameY = ty;
                nameW = font.width(current.name == null ? "" : current.name);
                nameH = 10;
                nameRoute = current;
            }
            tx += font.width(texts.get(i));
        }
    }

    /** 鼠标停在顶点或曲线上的小提示，折叠时也照常显示。 */
    private static void drawHover(GuiGraphicsExtractor g, MapView view, int mouseX, int mouseY, RailRoute current) {
        Font font = Minecraft.getInstance().font;
        Alignment al = current == null ? null : current.alignment();
        // 正在改 Y 的时候不弹顶点提示：两个框会叠在一起，数字被坐标盖住。
        if (current != null && !Planner.isEditing() && Planner.hoverIndex >= 0
                && Planner.hoverIndex < current.anchors.size()) {
            Anchor a = current.anchors.get(Planner.hoverIndex);
            String title = a.name().isEmpty() ? ("点 " + (Planner.hoverIndex + 1)) : a.name();
            tooltip(g, font, mouseX + 12, mouseY - 30, title + " · " + a.kind().label);
            tooltip(g, font, mouseX + 12, mouseY - 17, "(" + a.x() + ", " + a.y() + ", " + a.z() + ")"
                    + (a.manual() ? " · 手填" : ""));
            tooltip(g, font, mouseX + 12, mouseY - 4, "双击改 Y / 名字 / 类型");
        }
        Alignment.Corner near = nearestCorner(al, view, mouseX, mouseY);
        if (near != null) {
            tooltip(g, font, mouseX + 12, mouseY + 10, String.format("R %.1f · Δ %.1f° · T %.1f · L %.1f · E %.1f",
                    near.radius, near.deflectionDegrees(), near.tangent, near.arcLength, near.external));
        }
    }

    private static void add(List<String> lines, List<Integer> colors, String text, int color) {
        lines.add(text);
        colors.add(color);
    }

    /** 地面坡是否超过设计限坡（留 0.05% 的浮点余量）。 */
    private static boolean gradeOverLimit(RouteStats st) {
        return st != null && st.maxTerrainGradePct > RouteStats.MAX_DESIGN_GRADE + 0.05;
    }

    /** 隧道或桥占已判长度的比例过高（> 35% 且超过 32 格）才算工程偏大。 */
    private static boolean shareOverLimit(double length, RouteStats st) {
        if (st == null || length <= WARN_MIN_LENGTH) {
            return false;
        }
        double total = st.tunnelLength + st.bridgeLength + st.groundLength;
        return total > 0 && length > total * TUNNEL_BRIDGE_WARN_SHARE;
    }

    private static Alignment.Corner nearestCorner(Alignment al, MapView view, int mouseX, int mouseY) {
        if (al == null) {
            return null;
        }
        Alignment.Corner best = null;
        double bestD = 22 * 22;
        for (Alignment.Corner c : al.corners()) {
            double sx = view.guiX(c.x);
            double sy = view.guiY(c.z);
            double d = (sx - mouseX) * (sx - mouseX) + (sy - mouseY) * (sy - mouseY);
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        return best;
    }

    private static void drawGradeChart(GuiGraphicsExtractor g, int x, int y, int w, int h, RouteStats st) {
        g.fill(x, y, x + w, y + h, 0x60000000);
        float[] design = st.designProfile;
        float[] terrain = st.terrainProfile;
        if (design == null || design.length < 2) {
            return;
        }
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (int i = 0; i < design.length; i++) {
            if (!Float.isNaN(terrain[i])) {
                min = Math.min(min, terrain[i]);
                max = Math.max(max, terrain[i]);
            }
            if (!Float.isNaN(design[i])) {
                min = Math.min(min, design[i]);
                max = Math.max(max, design[i]);
            }
        }
        if (max - min < 6) {
            max = min + 6;
        }
        drawProfile(g, x, y, w, h, terrain, min, max, 0x8870C0FF, 1);
        drawProfile(g, x, y, w, h, design, min, max, ACCENT, 2);
        Font font = Minecraft.getInstance().font;
        g.text(font, String.format("%.0f", max), x + 2, y + 1, DIM);
        g.text(font, String.format("%.0f", min), x + 2, y + h - 9, DIM);
        String legend = "绿 设计 · 蓝 地面";
        g.text(font, legend, x + w - font.width(legend) - 3, y + 1, DIM);
    }

    private static void drawProfile(GuiGraphicsExtractor g, int x, int y, int w, int h, float[] prof,
                                    float min, float max, int color, int thickness) {
        double prevX = 0;
        double prevY = 0;
        boolean hasPrev = false;
        for (int i = 0; i < w; i++) {
            int idx = (int) ((long) i * (prof.length - 1) / Math.max(1, w - 1));
            float v = prof[idx];
            if (Float.isNaN(v)) {
                hasPrev = false;
                continue;
            }
            double px = x + i;
            double py = y + h - 2 - (v - min) / (max - min) * (h - 5);
            if (hasPrev) {
                thickLine(g, prevX, prevY, px, py, color, thickness);
            }
            prevX = px;
            prevY = py;
            hasPrev = true;
        }
    }

    /** 双击顶点之后浮在旁边的编辑框：Y、名字、类型三行。 */
    private static void drawPointEditor(GuiGraphicsExtractor g, MapView view, RailRoute route) {
        int index = Planner.editIndex();
        if (route == null || index < 0 || index >= route.anchors.size()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        Anchor a = route.anchors.get(index);
        int row = Planner.editRow();
        String head = "点 " + (index + 1) + " · " + Planner.editKind().label;
        String hint = "Tab 换行 · ← → 换类型 · 回车保存 · Esc 取消";
        int w = Math.max(278, font.width(hint) + 18);
        int h = 86;
        int anchorX = (int) view.guiX(a.x() + 0.5);
        int anchorY = (int) view.guiY(a.z() + 0.5);
        int x = anchorX + 14;
        int y = anchorY - 24;
        if (x + w > view.width() - 4) {
            x = anchorX - w - 14;
        }
        x = Math.max(4, Math.min(x, Math.max(4, view.width() - w - 4)));
        y = Math.max(4, Math.min(y, Math.max(4, view.height() - h - 4)));
        panel(g, x, y, w, h);
        g.text(font, head, x + 9, y + 5, TITLE);
        rowLine(g, font, x + 9, y + 19, w - 18, row == 0, "Y", Planner.editYText());
        rowLine(g, font, x + 9, y + 38, w - 18, row == 1, "名字", Planner.editNameText());
        rowLine(g, font, x + 9, y + 57, w - 18, row == 2, "类型",
                "◀ " + Planner.editKind().label + " ▶");
        g.text(font, hint, x + 9, y + 74, DIM);
    }

    /** 一行「标签 + 输入框」，当前行高亮并带光标。 */
    private static void rowLine(GuiGraphicsExtractor g, Font font, int x, int y, int w, boolean active,
            String label, String value) {
        int boxX = x + 36;
        int boxW = w - 36;
        g.fill(boxX, y - 1, boxX + boxW, y + 12, active ? 0xE6000000 : 0xC8000000);
        if (active) {
            g.fill(boxX, y - 1, boxX + boxW, y, ACCENT);
            g.fill(boxX, y + 11, boxX + boxW, y + 12, ACCENT);
        }
        g.text(font, label, x, y + 2, active ? ACCENT : DIM);
        String shown = value == null ? "" : value;
        g.text(font, shown, boxX + 4, y + 2, 0xFFFFFFFF);
        if (active) {
            int caret = boxX + 5 + font.width(shown);
            g.fill(caret, y + 2, caret + 1, y + 10, ACCENT);
        }
    }

    /** 双击面板上那行线名之后弹出的框：名字 + 色板 + 色相条 + RGB。 */
    private static void drawRouteEditor(GuiGraphicsExtractor g, MapView view) {
        if (!Planner.isEditingRoute()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int w = 330;
        int h = 118;
        int x = Math.max(4, (view.width() - w) / 2);
        int y = Math.max(4, (view.height() - h) / 2);
        panel(g, x, y, w, h);
        g.text(font, "线路属性", x + 9, y + 5, TITLE);
        g.text(font, "Tab 换行 · ← → 调色相 · 回车保存 · Esc 取消", x + 62, y + 5, DIM);

        int row = Planner.routeRow();
        rowLine(g, font, x + 9, y + 19, w - 18, row == 0, "名字", Planner.routeNameText());

        // 色板：一排 12 个色块
        swatchSize = 16;
        swatchY = y + 40;
        int sx = x + 45;
        g.text(font, "色板", x + 9, swatchY + 4, row == 1 ? ACCENT : DIM);
        for (int i = 0; i < SWATCH_X.length; i++) {
            SWATCH_X[i] = sx + i * (swatchSize + 3);
            int color = 0xFF000000 | Planner.COLOR_PRESETS[i];
            g.fill(SWATCH_X[i] - 1, swatchY - 1, SWATCH_X[i] + swatchSize + 1, swatchY + swatchSize + 1,
                    row == 1 ? 0xFFB0BEC5 : 0xFF101418);
            g.fill(SWATCH_X[i], swatchY, SWATCH_X[i] + swatchSize, swatchY + swatchSize, color);
            if ((Planner.routeColor() & 0xFFFFFF) == Planner.COLOR_PRESETS[i]) {
                g.fill(SWATCH_X[i] + 5, swatchY + 5, SWATCH_X[i] + swatchSize - 5, swatchY + 5 + 1, 0xFF000000);
                g.fill(SWATCH_X[i] + 5, swatchY + swatchSize - 6, SWATCH_X[i] + swatchSize - 5,
                        swatchY + swatchSize - 5, 0xFF000000);
            }
        }

        // 色相条：当前颜色占一格，拖它换色相
        hueX = x + 45;
        hueY = y + 62;
        hueW = 200;
        hueH = 10;
        for (int i = 0; i < hueW; i++) {
            float[] hsb = toHsb(Planner.routeColor());
            int color = fromHsb(i / (float) hueW, Math.max(0.55f, hsb[1]), Math.max(0.65f, hsb[2]));
            g.fill(hueX + i, hueY, hueX + i + 1, hueY + hueH, 0xFF000000 | color);
        }
        g.text(font, "色相", x + 9, hueY, row == 1 ? ACCENT : DIM);
        float[] now = toHsb(Planner.routeColor());
        int marker = hueX + Math.round(now[0] * (hueW - 1));
        g.fill(marker - 1, hueY - 2, marker + 2, hueY + hueH + 2, 0xFFFFFFFF);
        g.fill(x + 252, hueY - 2, x + 268, hueY + hueH + 2, 0xFF000000 | (Planner.routeColor() & 0xFFFFFF));

        // RGB 行
        String[] labels = {"R", "G", "B"};
        int rx = x + 45;
        g.text(font, "RGB", x + 9, y + 78, row == 2 ? ACCENT : DIM);
        for (int i = 0; i < 3; i++) {
            boolean active = row == 2 && Planner.routeChannel() == i;
            String text = labels[i] + " " + Planner.routeChannelText(i);
            int tw = font.width(text) + 10;
            g.fill(rx, y + 76, rx + tw, y + 89, active ? 0xE6000000 : 0xC0000000);
            g.text(font, text, rx + 4, y + 78, active ? 0xFFFFFFFF : 0xFFB0BEC5);
            rx += tw + 8;
        }
        g.text(font, "#" + String.format("%06X", Planner.routeColor() & 0xFFFFFF), x + 250, y + 78, DIM);
    }

    private static float[] toHsb(int rgb) {
        float r = ((rgb >> 16) & 0xFF) / 255.0f;
        float gg = ((rgb >> 8) & 0xFF) / 255.0f;
        float b = (rgb & 0xFF) / 255.0f;
        float max = Math.max(r, Math.max(gg, b));
        float min = Math.min(r, Math.min(gg, b));
        float d = max - min;
        float hue = 0;
        if (d > 0.0001f) {
            if (max == r) {
                hue = ((gg - b) / d) % 6.0f;
            } else if (max == gg) {
                hue = (b - r) / d + 2.0f;
            } else {
                hue = (r - gg) / d + 4.0f;
            }
            hue /= 6.0f;
        }
        if (hue < 0) {
            hue += 1.0f;
        }
        return new float[]{hue % 1.0f, max <= 0 ? 0 : d / max, max};
    }

    private static int fromHsb(float hue, float sat, float bri) {
        float hh = (hue - (float) Math.floor(hue)) * 6.0f;
        int i = (int) Math.floor(hh);
        float f = hh - i;
        float p = bri * (1 - sat);
        float q = bri * (1 - sat * f);
        float t = bri * (1 - sat * (1 - f));
        float r;
        float gg;
        float b;
        switch (i % 6) {
            case 0 -> {
                r = bri;
                gg = t;
                b = p;
            }
            case 1 -> {
                r = q;
                gg = bri;
                b = p;
            }
            case 2 -> {
                r = p;
                gg = bri;
                b = t;
            }
            case 3 -> {
                r = p;
                gg = q;
                b = bri;
            }
            case 4 -> {
                r = t;
                gg = p;
                b = bri;
            }
            default -> {
                r = bri;
                gg = p;
                b = q;
            }
        }
        return (clamp255(r) << 16) | (clamp255(gg) << 8) | clamp255(b);
    }

    private static int clamp255(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255.0f)));
    }

    /** 鼠标点到哪个色块；没点到返回 -1。 */
    public static int hitSwatch(double mx, double my) {
        if (!Planner.isEditingRoute()) {
            return -1;
        }
        for (int i = 0; i < SWATCH_X.length; i++) {
            if (mx >= SWATCH_X[i] && mx <= SWATCH_X[i] + swatchSize
                    && my >= swatchY && my <= swatchY + swatchSize) {
                return i;
            }
        }
        return -1;
    }

    /** 鼠标点到色相条上的哪个比例；没点到返回 -1。 */
    public static double hitHue(double mx, double my) {
        if (!Planner.isEditingRoute() || hueW <= 0) {
            return -1;
        }
        if (mx < hueX - 4 || mx > hueX + hueW + 4 || my < hueY - 6 || my > hueY + hueH + 6) {
            return -1;
        }
        return Math.max(0, Math.min(1, (mx - hueX) / (double) hueW));
    }

    /** 鼠标是不是停在那行线名上（双击它改名字和颜色）。 */
    public static boolean hitName(double mx, double my) {
        return nameRoute != null && nameW > 0
                && mx >= nameX - 2 && mx <= nameX + nameW + 2
                && my >= nameY - 2 && my <= nameY + nameH + 2;
    }

    public static RailRoute nameRoute() {
        return nameRoute;
    }

    /** 地图中间下方那行一闪而过的提示（「点已锁定，拖不动」之类）。 */
    private static void drawNotice(GuiGraphicsExtractor g, MapView view) {
        String notice = Planner.notice();
        if (notice == null) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int w = font.width(notice) + 18;
        int x = (view.width() - w) / 2;
        int y = view.height() - 56;
        panel(g, x, y, w, 20);
        g.text(font, notice, x + 9, y + 6, CAUTION);
    }

    private static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PANEL_BG);
        g.fill(x, y, x + w, y + 1, PANEL_BORDER);
        g.fill(x, y + h - 1, x + w, y + h, PANEL_BORDER);
        g.fill(x, y, x + 1, y + h, PANEL_BORDER);
        g.fill(x + w - 1, y, x + w, y + h, PANEL_BORDER);
    }

    private static void badge(GuiGraphicsExtractor g, Font font, int x, int y, String text) {
        int w = font.width(text) + 10;
        g.fill(x, y, x + w, y + 14, 0xE8101418);
        g.text(font, text, x + 5, y + 3, TEXT);
    }

    private static void tooltip(GuiGraphicsExtractor g, Font font, int x, int y, String text) {
        int w = font.width(text) + 8;
        g.fill(x, y, x + w, y + 13, 0xF0101418);
        g.text(font, text, x + 4, y + 3, 0xFFFFF3C4);
    }

    private static boolean visible(MapView view, double x0, double y0, double x1, double y1) {
        double minX = Math.min(x0, x1);
        double maxX = Math.max(x0, x1);
        double minY = Math.min(y0, y1);
        double maxY = Math.max(y0, y1);
        return maxX >= -4 && minX <= view.width() + 4 && maxY >= -4 && minY <= view.height() + 4;
    }

    private static void thickLine(GuiGraphicsExtractor g, double x0, double y0, double x1, double y1, int color, int thickness) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double len = Math.hypot(dx, dy);
        if (len < 0.4) {
            return;
        }
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate((float) x0, (float) y0);
        pose.rotate((float) Math.atan2(dy, dx));
        int half = thickness / 2;
        g.fill(0, -half, (int) Math.ceil(len), thickness - half, color);
        pose.popMatrix();
    }
}
