package com.phzt.railplanner.client.render;

import com.phzt.railplanner.client.RailPlannerClient;
import com.phzt.railplanner.client.config.RailConfig;
import com.phzt.railplanner.client.geom.Alignment;
import com.phzt.railplanner.client.geom.BlockPath;
import com.phzt.railplanner.client.geom.Vec2;
import com.phzt.railplanner.client.model.Anchor;
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
    private static final double TUNNEL_BRIDGE_WARN_SHARE = 0.35;
    private static final double WARN_MIN_LENGTH = 32.0;

    private PlannerRenderer() {
    }

    public static void render(GuiGraphicsExtractor g, MapView view, int mouseX, int mouseY,
                              List<RailRoute> routes, RailRoute current, int hoverIndex, int dragIndex) {
        for (RailRoute route : routes) {
            drawRoute(g, view, route, route == current);
        }
        if (current != null) {
            drawCells(g, view, current);
            drawVertices(g, view, current, hoverIndex, dragIndex);
            drawRubberBand(g, view, current, mouseX, mouseY);
        }
        if (Planner.isActive()) {
            // 面板跟世界里的 3D 线看同一条：没在编辑时退回最后那条。
            RailRoute shown = Planner.displayRoute();
            if (RailConfig.get().hudCollapsed) {
                drawHudCollapsed(g, shown);
            } else {
                drawHud(g, view, mouseX, mouseY, shown);
            }
            drawHover(g, view, mouseX, mouseY, current);
            drawNotice(g, view);
            drawYEditor(g, view, current);
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
        for (int i = 0; i < route.anchors.size(); i++) {
            Anchor a = route.anchors.get(i);
            double x = view.guiX(a.x() + 0.5);
            double y = view.guiY(a.z() + 0.5);
            if (x < -10 || y < -10 || x > view.width() + 10 || y > view.height() + 10) {
                continue;
            }
            int r = (i == drag) ? 5 : (i == hover ? 5 : 3);
            int color = (i == drag) ? 0xFFFFD740 : (i == hover ? 0xFFFFFFFF : 0xFF202020);
            g.fill((int) x - r, (int) y - r, (int) x - r + r * 2, (int) y - r + r * 2, color);
            g.fill((int) x - r + 1, (int) y - r + 1, (int) x - r + r * 2 - 1, (int) y - r + r * 2 - 1,
                    0xFF000000 | (route.color & 0xFFFFFF));
        }
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
        add(lines, colors, "左键加点/拖顶点 · 右键删点 · 双击顶点改 Y · "
                + RailPlannerClient.keyName(RailPlannerClient.FINISH) + " 完成 · "
                + RailPlannerClient.keyName(RailPlannerClient.RADIUS) + " 半径 · "
                + RailPlannerClient.keyName(RailPlannerClient.AUTOROUTE) + " 自动选线 · "
                + RailPlannerClient.keyName(RailPlannerClient.HUD_COLLAPSE) + " 折叠", DIM);

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
            tx += font.width(texts.get(i));
        }
    }

    /** 鼠标停在顶点或曲线上的小提示，折叠时也照常显示。 */
    private static void drawHover(GuiGraphicsExtractor g, MapView view, int mouseX, int mouseY, RailRoute current) {
        Font font = Minecraft.getInstance().font;
        Alignment al = current == null ? null : current.alignment();
        // 正在改 Y 的时候不弹顶点提示：两个框会叠在一起，数字被坐标盖住。
        if (current != null && !Planner.isEditingY() && Planner.hoverIndex >= 0
                && Planner.hoverIndex < current.anchors.size()) {
            Anchor a = current.anchors.get(Planner.hoverIndex);
            tooltip(g, font, mouseX + 12, mouseY - 17, "点 " + (Planner.hoverIndex + 1) + "  ("
                    + a.x() + ", " + a.y() + ", " + a.z() + ")");
            tooltip(g, font, mouseX + 12, mouseY - 4, a.manual() ? "双击改 Y（手填）" : "双击改 Y");
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

    /** 双击顶点之后浮在旁边的 Y 输入框。 */
    private static void drawYEditor(GuiGraphicsExtractor g, MapView view, RailRoute route) {
        int index = Planner.editIndex();
        if (route == null || index < 0 || index >= route.anchors.size()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        Anchor a = route.anchors.get(index);
        String head = "点 " + (index + 1) + " 的 Y";
        String value = Planner.editText();
        String hint = "回车确认 · Esc 取消 · 退格删一个";
        int w = Math.max(font.width(head), Math.max(font.width(value) + 26, font.width(hint))) + 18;
        int h = 48;
        int x = (int) view.guiX(a.x() + 0.5) + 12;
        int y = (int) view.guiY(a.z() + 0.5) - 20;
        if (x + w > view.width() - 4) {
            x = (int) view.guiX(a.x() + 0.5) - w - 12;
        }
        x = Math.max(4, x);
        y = Math.max(4, Math.min(y, view.height() - h - 4));
        panel(g, x, y, w, h);
        g.text(font, head, x + 9, y + 6, TITLE);
        // 输入框：黑底、白字、后面一根一直亮着的竖线当光标
        int boxTop = y + 19;
        int boxW = font.width(value) + 12;
        g.fill(x + 9, boxTop, x + 9 + Math.max(24, boxW), boxTop + 13, 0xCC000000);
        g.text(font, value, x + 14, boxTop + 3, 0xFFFFFFFF);
        int caret = x + 14 + font.width(value) + 1;
        g.fill(caret, boxTop + 3, caret + 1, boxTop + 11, ACCENT);
        g.text(font, hint, x + 9, y + 35, DIM);
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
