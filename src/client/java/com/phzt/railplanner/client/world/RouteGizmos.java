package com.phzt.railplanner.client.world;

import com.phzt.railplanner.client.config.RailConfig;
import com.phzt.railplanner.client.geom.Alignment;
import com.phzt.railplanner.client.geom.BlockPath;
import com.phzt.railplanner.client.geom.Vec2;
import com.phzt.railplanner.client.model.Anchor;
import com.phzt.railplanner.client.model.RailRoute;
import com.phzt.railplanner.client.planner.Planner;
import com.phzt.railplanner.client.planner.RouteStats;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoProperties;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.Vec3;

/**
 * 把当前这条线画进三维世界。
 *
 * <p>26.3 这一版已经没有 Fabric 的 WorldRenderEvents 了，原版自带的 gizmos 是唯一不碰渲染
 * 状态机的入口：{@link Gizmos#line} 往当前线程的收集器里塞线段，{@code GameRenderer} 提取
 * 那一帧时统一收走，透明/不透明分组、深度测试、穿墙开关都由原版处理。
 *
 * <p>这里画四样东西：
 * <ul>
 *   <li>设计标高线（线本色；隧道段蓝、桥段橙）——轨道最后走的位置；</li>
 *   <li>地面标高线（暗绿细线）——沿线的地形；</li>
 *   <li>隧道/桥的半透明面片——两侧挡墙 + 设计面的走廊底，围出要挖 / 要架的那一块体积；</li>
 *   <li>填挖立柱——只在差得不够多、没到隧道/桥的段落补一根，免得那里什么都没有。</li>
 * </ul>
 *
 * <p>面片用 {@link Gizmos#rect} 画，渲染管线是 {@code debugFilledBox}（position_color、
 * 半透明混合、关背面剔除、不写深度），所以单面的方块两面都能看见，颜色里的 alpha 也照用。
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
    /** 面片网格步长，格。 */
    private static final double FACE = 8.0;
    /** 轨道走廊半宽，格。 */
    private static final double HALF_WIDTH = 1.5;
    /** 设计面与地面差多少算隧道/桥（和统计面板同一口径）。 */
    private static final double CUT = 4.0;
    /** 立柱至少要差这么多才画，免得地面贴着设计面时糊成一片。 */
    private static final double POST_MIN = 2.0;
    /** 两侧挡墙的 alpha（0x00–0xFF），叠起来不至于糊成实心。 */
    private static final int WALL_ALPHA = 0x3A;
    /** 设计面走廊底/顶的 alpha，比挡墙实一点。 */
    private static final int DECK_ALPHA = 0x50;
    /** 一帧最多塞多少条线段，超过就停（远处先丢）。 */
    private static final int MAX_LINES = 1200;
    /** 一帧最多塞多少个面片，超过就停。 */
    private static final int MAX_FACES = 400;

    /** 起点（绿）。 */
    private static final int ANCHOR_FIRST = 0xFF69F0AE;
    /** 终点（红）。 */
    private static final int ANCHOR_LAST = 0xFFFF5252;
    /** 中间的点（白）。 */
    private static final int ANCHOR_MID = 0xFFFFFFFF;
    /** 针至少这么高，免得贴着地面只剩一个点。 */
    private static final double FLAG_MIN = 3.0;
    /** 一帧最多给几个点标序号（文本最贵）。 */
    private static final int MAX_LABELS = 24;

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

        if (config.worldSurfaces) {
            emitSurfaces(al, path, design, ground, n, camX, camZ, range2, onTop);
        }
        emitLines(al, path, design, ground, n, route.color, config, camX, camZ, range2, onTop);

        // 正在编辑的那条线才画点：完成之后那一条是拿来照着修的，点不用再刷存在感。
        if (config.worldAnchors && Planner.current() != null) {
            emitAnchors(route, path, design, ground, n, camX, camZ, range2, onTop);
        }
    }

    /**
     * 把玩家点过的每个点画出来：地面上一根竖针、设计标高一个方块框、头顶一个序号。
     *
     * <p>标高取这一点最近的那个路径格——锚点就是折线的顶点，路径从它上面过，所以最近格
     * 的设计/地面标高就是这一点的位置。锁住时整体压暗，一眼能看出「现在拖不动」。
     */
    private static void emitAnchors(RailRoute route, BlockPath path, float[] design, float[] ground, int n,
            double camX, double camZ, double range2, boolean onTop) {
        List<Anchor> anchors = route.anchors;
        int count = anchors.size();
        if (count == 0) {
            return;
        }
        boolean locked = RailConfig.get().anchorLock;
        int labels = 0;
        for (int i = 0; i < count; i++) {
            Anchor a = anchors.get(i);
            double cx = a.x() + 0.5;
            double cz = a.z() + 0.5;
            double dx = cx - camX;
            double dz = cz - camZ;
            if (dx * dx + dz * dz > range2) {
                continue;
            }
            int idx = nearest(path, a.x(), a.z(), n);
            if (idx < 0) {
                continue;
            }
            float d = design[idx];
            if (Float.isNaN(d)) {
                continue;
            }
            float g = ground[idx];
            // 针从低的那一头（地面 / 设计面里低的）扎起，扎到高的那一头上面，再挑一个小方块当旗、标序号：
            // 平地上就是一根 3 格的短针，隧道里针会从设计面一路穿到山表面上。
            double low = Float.isNaN(g) ? d : Math.min(d, g);
            double high = Float.isNaN(g) ? d : Math.max(d, g);
            if (high - low < FLAG_MIN) {
                high = low + FLAG_MIN;
            }
            int color = anchorColor(i, count, locked);

            GizmoProperties pin = Gizmos.line(new Vec3(cx, low + 1.0, cz), new Vec3(cx, high + 1.0, cz),
                    color, 2.0f);
            if (onTop) {
                pin.setAlwaysOnTop();
            }
            GizmoProperties flag = Gizmos.cuboid(new BlockPos(a.x(), (int) Math.floor(high + 1.0), a.z()),
                    GizmoStyle.stroke(color));
            if (onTop) {
                flag.setAlwaysOnTop();
            }
            if (labels < MAX_LABELS) {
                labels++;
                GizmoProperties text = Gizmos.billboardText(String.valueOf(i + 1),
                        new Vec3(cx, high + 3.4, cz), TextGizmo.Style.forColorAndCentered(color));
                if (onTop) {
                    text.setAlwaysOnTop();
                }
            }
        }
    }

    /** 起点绿、终点红、中间白；锁住时压暗。 */
    private static int anchorColor(int index, int count, boolean locked) {
        int color = index == 0 ? ANCHOR_FIRST : (index == count - 1 && count > 1 ? ANCHOR_LAST : ANCHOR_MID);
        return locked ? (0x80 << 24) | (color & 0x00FFFFFF) : color;
    }

    /** 路径上离这个方块最近的那一格。 */
    private static int nearest(BlockPath path, int bx, int bz, int n) {
        int best = -1;
        long bestD = Long.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            long dx = path.xs[i] - bx;
            long dz = path.zs[i] - bz;
            long dd = dx * dx + dz * dz;
            if (dd < bestD) {
                bestD = dd;
                best = i;
            }
        }
        return best;
    }

    /** 隧道/桥段落的两侧挡墙 + 设计面走廊，围出一块半透明的体积。 */
    private static void emitSurfaces(Alignment al, BlockPath path, float[] design, float[] ground, int n,
            double camX, double camZ, double range2, boolean onTop) {
        double length = al.length();
        float[] chainage = path.chainage;
        int budget = MAX_FACES;
        int idx = 0;
        for (double s = 0; s + FACE <= length; s += FACE) {
            if (budget < 3) {
                break;
            }
            double s1 = s + FACE;
            while (idx + 1 < n && chainage[idx + 1] <= s) {
                idx++;
            }
            float d0 = sample(design, idx, chainage, s, n);
            float g0 = sample(ground, idx, chainage, s, n);
            float d1 = sample(design, idx, chainage, s1, n);
            float g1 = sample(ground, idx, chainage, s1, n);
            int kind = kindOf(d0, g0);
            if (kind == 0 || kind != kindOf(d1, g1)) {
                continue;
            }
            Vec2 p0 = al.pointAt(s);
            Vec2 p1 = al.pointAt(s1);
            double mx = (p0.x + p1.x) * 0.5 - camX;
            double mz = (p0.z + p1.z) * 0.5 - camZ;
            if (mx * mx + mz * mz > range2) {
                continue;
            }
            double dx = p1.x - p0.x;
            double dz = p1.z - p0.z;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1e-6) {
                continue;
            }
            double nx = -dz / len * HALF_WIDTH;
            double nz = dx / len * HALF_WIDTH;

            Vec3 l0 = new Vec3(p0.x + nx, d0 + 1.0, p0.z + nz);
            Vec3 l1 = new Vec3(p1.x + nx, d1 + 1.0, p1.z + nz);
            Vec3 r0 = new Vec3(p0.x - nx, d0 + 1.0, p0.z - nz);
            Vec3 r1 = new Vec3(p1.x - nx, d1 + 1.0, p1.z - nz);
            Vec3 l0g = new Vec3(p0.x + nx, g0 + 1.0, p0.z + nz);
            Vec3 l1g = new Vec3(p1.x + nx, g1 + 1.0, p1.z + nz);
            Vec3 r0g = new Vec3(p0.x - nx, g0 + 1.0, p0.z - nz);
            Vec3 r1g = new Vec3(p1.x - nx, g1 + 1.0, p1.z - nz);

            int color = kind > 0 ? TUNNEL : BRIDGE;
            GizmoStyle wall = GizmoStyle.fill((WALL_ALPHA << 24) | (color & 0x00FFFFFF));
            GizmoStyle deck = GizmoStyle.fill((DECK_ALPHA << 24) | (color & 0x00FFFFFF));
            budget -= rect(l0, l1, l1g, l0g, wall, onTop);
            budget -= rect(r0, r1, r1g, r0g, wall, onTop);
            budget -= rect(l0, l1, r1, r0, deck, onTop);
        }
    }

    /** 设计线、地面线，以及（面片关掉时）填挖立柱。 */
    private static void emitLines(Alignment al, BlockPath path, float[] design, float[] ground, int n,
            int baseColor, RailConfig config, double camX, double camZ, double range2, boolean onTop) {
        int budget = MAX_LINES;
        int idx = 0;
        Vec3 prevDesign = null;
        Vec3 prevGround = null;
        int prevColor = baseColor;
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

            int color = colorFor(d, g, baseColor);

            if (near && prevDesign != null) {
                budget -= line(prevDesign, head, prevColor, 3.0f, onTop);
            }
            if (near && foot != null && prevGround != null) {
                budget -= line(prevGround, foot, TERRAIN, 1.0f, onTop);
            }
            if (near && foot != null && s >= nextPost) {
                nextPost = s + POST;
                // 已经有面片的段落不再补立柱，只有不够高/不够深的零碎段落才补。
                boolean covered = config.worldSurfaces && kindOf(d, g) != 0;
                if (!covered && Math.abs(g - d) >= POST_MIN) {
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

    /** 1 = 隧道（地面在设计面之上），-1 = 桥（地面在设计面之下），0 = 贴着地面。 */
    private static int kindOf(float design, float ground) {
        if (Float.isNaN(ground) || Float.isNaN(design)) {
            return 0;
        }
        if (ground > design + CUT) {
            return 1;
        }
        if (ground < design - CUT) {
            return -1;
        }
        return 0;
    }

    /** 设计面与地面相差多少决定这一段是地面/隧道/桥。 */
    private static int colorFor(float design, float ground, int baseColor) {
        int kind = kindOf(design, ground);
        if (kind > 0) {
            return TUNNEL;
        }
        return kind < 0 ? BRIDGE : baseColor;
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

    private static int rect(Vec3 a, Vec3 b, Vec3 c, Vec3 d, GizmoStyle style, boolean onTop) {
        GizmoProperties props = Gizmos.rect(a, b, c, d, style);
        if (onTop) {
            props.setAlwaysOnTop();
        }
        return 1;
    }
}
