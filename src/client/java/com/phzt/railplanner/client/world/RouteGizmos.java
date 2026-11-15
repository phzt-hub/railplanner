package com.phzt.railplanner.client.world;

import com.phzt.railplanner.client.config.RailConfig;
import com.phzt.railplanner.client.geom.Alignment;
import com.phzt.railplanner.client.geom.BlockPath;
import com.phzt.railplanner.client.geom.Vec2;
import com.phzt.railplanner.client.model.RailRoute;
import com.phzt.railplanner.client.planner.Planner;
import com.phzt.railplanner.client.planner.RouteStats;
import net.minecraft.gizmos.GizmoProperties;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.Vec3;

/**
 * 把当前这条线画进三维世界。
 *
 * <p>26.3 这一版已经没有 Fabric 的 WorldRenderEvents 了，原版自带的 gizmos 是唯一不碰渲染
 * 状态机的入口：{@link Gizmos#line} 往当前线程的收集器里塞线段，{@code GameRenderer} 提取
 * 那一帧时统一收走，透明/不透明分组、深度测试、穿墙开关都由原版处理。
 *
 * <p>这里画三样东西：
 * <ul>
 *   <li>设计标高线（线本色；隧道段蓝、桥段橙）——轨道最后走的位置；</li>
 *   <li>地面标高线（暗绿细线）——沿线的地形；</li>
 *   <li>填挖立柱——两者之间每隔几格一根竖线，往下=要挖，往上=要架桥。</li>
 * </ul>
 */
public final class RouteGizmos {

    /** 隧道段（地面在设计面之上）。 */
    private static final int TUNNEL = 0xFF42A5F5;
    /** 桥段（地面在设计面之下）。 */
    private static final int BRIDGE = 0xFFFF9800;
    /** 地面标高线（压暗一点，别抢设计线的视线）。 */
    private static final int TERRAIN = 0xFF6E8262;

    /** 线带采样间隔，格。 */
    private static final double SAMPLE = 2.0;
    /** 填挖立柱间距，格。 */
    private static final double POST = 8.0;
    /** 设计面与地面差多少算隧道/桥（和统计面板同一口径）。 */
    private static final double CUT = 4.0;
    /** 立柱至少要差这么多才画，免得地面贴着设计面时糊成一片。 */
    private static final double POST_MIN = 2.0;
    /** 一帧最多塞多少条，超过就停（远处先丢）。 */
    private static final int MAX_GIZMOS = 1200;

    private RouteGizmos() {
    }

    public static void emit(double camX, double camY, double camZ) {
        RailConfig config = RailConfig.get();
        if (!config.worldLines || !Planner.isActive()) {
            return;
        }
        RailRoute route = Planner.displayRoute();
        if (route == null || route.anchors.size() < 2) {
            return;
        }
        try {
            emitRoute(route, config, camX, camY, camZ);
        } catch (RuntimeException e) {
            // 没有收集器（例如帧外的误调用）时原版直接抛 IllegalStateException，不该让游戏崩。
        }
    }

    private static void emitRoute(RailRoute route, RailConfig config, double camX, double camY, double camZ) {
        Alignment al = route.alignment();
        if (al.length() < 1.0) {
            return;
        }
        RouteStats st = Planner.ensureStats();
        if (st == null || st.designProfile == null || st.designProfile.length < 2) {
            return;
        }
        BlockPath path = route.path();
        int n = path.size();
        float[] design = st.designProfile;
        if (n < 2 || design.length != n) {
            return;
        }
        float[] ground = st.smoothTerrainProfile != null && st.smoothTerrainProfile.length == n
                ? st.smoothTerrainProfile : st.terrainProfile;
        if (ground == null || ground.length != n) {
            return;
        }

        boolean onTop = config.worldXray;
        double range = Math.max(32.0, Math.min(2048.0, config.worldRange));
        double range2 = range * range;
        int budget = MAX_GIZMOS;

        int idx = 0;
        Vec3 prevDesign = null;
        Vec3 prevGround = null;
        int prevColor = route.color;
        double nextPost = 0;
        double length = al.length();

        for (double s = 0; s <= length; s += SAMPLE) {
            if (budget <= 0) {
                break;
            }
            while (idx + 1 < n && path.chainage[idx + 1] <= s) {
                idx++;
            }
            float d = sample(design, idx, path.chainage, s, n);
            float g = sample(ground, idx, path.chainage, s, n);
            if (Float.isNaN(d)) {
                prevDesign = null;
                prevGround = null;
                continue;
            }
            Vec2 p = al.pointAt(s);
            // 轨面在方块顶面之上，所以标高加一格。
            Vec3 head = new Vec3(p.x, d + 1.0, p.z);
            Vec3 foot = Float.isNaN(g) ? null : new Vec3(p.x, g + 1.0, p.z);

            double dx = p.x - camX;
            double dz = p.z - camZ;
            boolean near = dx * dx + dz * dz <= range2;

            int color = colorFor(d, g, route.color);

            if (near && prevDesign != null) {
                budget -= line(prevDesign, head, prevColor, 3.0f, onTop);
            }
            if (near && foot != null && prevGround != null) {
                budget -= line(prevGround, foot, TERRAIN, 1.0f, onTop);
            }
            if (near && foot != null && s >= nextPost) {
                nextPost = s + POST;
                if (Math.abs(g - d) >= POST_MIN) {
                    double low = Math.min(d, g);
                    double high = Math.max(d, g);
                    int postColor = (color & 0x00FFFFFF) | 0xA0000000;
                    budget -= line(new Vec3(p.x, low + 1.0, p.z), new Vec3(p.x, high + 1.0, p.z),
                            postColor, 2.0f, onTop);
                }
            }

            prevDesign = head;
            prevGround = foot;
            prevColor = color;
        }
    }

    /** 设计面与地面相差多少决定这一段是地面/隧道/桥。 */
    private static int colorFor(float design, float ground, int baseColor) {
        if (Float.isNaN(ground)) {
            return baseColor;
        }
        if (ground > design + CUT) {
            return TUNNEL;
        }
        if (ground < design - CUT) {
            return BRIDGE;
        }
        return baseColor;
    }

    /** 按链程在相邻两格之间线性取标高。 */
    private static float sample(float[] values, int idx, float[] chainage, double s, int n) {
        float a = values[idx];
        if (idx + 1 >= n) {
            return a;
        }
        float b = values[idx + 1];
        if (Float.isNaN(a) || Float.isNaN(b)) {
            return Float.isNaN(a) ? b : a;
        }
        double span = chainage[idx + 1] - chainage[idx];
        if (span <= 1e-6) {
            return a;
        }
        double t = Math.max(0.0, Math.min(1.0, (s - chainage[idx]) / span));
        return (float) (a + (b - a) * t);
    }

    private static int line(Vec3 from, Vec3 to, int color, float width, boolean onTop) {
        GizmoProperties props = Gizmos.line(from, to, color, width);
        if (onTop) {
            props.setAlwaysOnTop();
        }
        return 1;
    }
}
