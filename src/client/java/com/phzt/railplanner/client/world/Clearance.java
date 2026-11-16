package com.phzt.railplanner.client.world;

import com.phzt.railplanner.client.config.RailConfig;
import com.phzt.railplanner.client.geom.BlockPath;
import com.phzt.railplanner.client.model.RailRoute;
import com.phzt.railplanner.client.planner.Planner;
import com.phzt.railplanner.client.planner.RouteStats;
import com.phzt.railplanner.clearance.ClearRules;
import com.phzt.railplanner.clearance.Corridor;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * 客户端这一侧的限界状态：按当前这条线，扫出「范围内哪些格子挡路」。
 *
 * <p>扫出来的东西三处用：世界里画红框（{@link ClearanceGizmos}）、窗口里报数
 * （{@code ClearanceScreen}）、一键清理时把走行线发给服务端（{@code ClearanceNet}）。</p>
 *
 * <p>不逐帧扫——{@code getBlockState} 一次几千格，逐帧扫会把帧率吃掉。重扫的时机是：
 * 尺寸/范围改了、当前线换了、人走开 8 格以上、或者每 2 秒兜底一次（地形加载完了要能自己补上）。</p>
 */
public final class Clearance {

    /** 兜底重扫间隔，tick。 */
    private static final int REFRESH = 40;
    /** 人走开这么多格就重扫。 */
    private static final double MOVE = 8.0;

    private static int[] xs = new int[0];
    private static int[] ys = new int[0];
    private static int[] zs = new int[0];
    private static int[] spineX = new int[0];
    private static int[] spineY = new int[0];
    private static int[] spineZ = new int[0];

    private static int corridorSize;
    private static String why = "";
    private static boolean dirty = true;
    private static int nextAt;
    private static String routeId = "";
    private static double lastX = Double.NaN;
    private static double lastZ = Double.NaN;
    private static int lastWidth;
    private static int lastUp;
    private static int lastDown;
    private static int lastRange;

    private Clearance() {
    }

    /** 待清的格数。 */
    public static int count() {
        return xs.length;
    }

    public static int x(int i) {
        return xs[i];
    }

    public static int y(int i) {
        return ys[i];
    }

    public static int z(int i) {
        return zs[i];
    }

    /** 走廊一共多少格（含不用清的）。 */
    public static int corridorSize() {
        return corridorSize;
    }

    /** 为什么扫不出东西（给窗口/悬停提示用）。扫出来了是空串。 */
    public static String why() {
        return why;
    }

    public static int[] spineX() {
        return spineX;
    }

    public static int[] spineY() {
        return spineY;
    }

    public static int[] spineZ() {
        return spineZ;
    }

    /** 改了尺寸/范围/开关之后叫一下，下一次 tick 立刻重扫。 */
    public static void invalidate() {
        dirty = true;
    }

    public static void tick(Minecraft mc) {
        RailConfig cfg = RailConfig.get();
        if (!cfg.clearanceOn) {
            if (xs.length > 0 || spineX.length > 0) {
                clear("限界标红关着");
            }
            return;
        }
        boolean params = cfg.clearanceWidth != lastWidth || cfg.clearanceUp != lastUp
                || cfg.clearanceDown != lastDown || cfg.clearanceRange != lastRange;
        boolean moved = mc.player != null
                && (Double.isNaN(lastX) || Math.abs(mc.player.getX() - lastX) > MOVE
                        || Math.abs(mc.player.getZ() - lastZ) > MOVE);
        boolean switched = !routeId.equals(currentRouteId());
        if (dirty || params || moved || switched || --nextAt <= 0) {
            rescan(mc);
        }
    }

    private static String currentRouteId() {
        RailRoute route = Planner.displayRoute();
        return route == null || route.id == null ? "" : route.id;
    }

    private static void rescan(Minecraft mc) {
        dirty = false;
        nextAt = REFRESH;
        RailConfig cfg = RailConfig.get();
        lastWidth = cfg.clearanceWidth;
        lastUp = cfg.clearanceUp;
        lastDown = cfg.clearanceDown;
        lastRange = cfg.clearanceRange;
        routeId = currentRouteId();
        if (mc.player == null || mc.level == null) {
            clear("还没进存档");
            return;
        }
        lastX = mc.player.getX();
        lastZ = mc.player.getZ();

        RailRoute route = Planner.displayRoute();
        if (route == null || route.anchors.size() < 2) {
            clear("没有选中任何线");
            return;
        }
        RouteStats stats = Planner.ensureStats();
        if (stats == null || stats.designProfile == null || stats.designProfile.length == 0) {
            clear("统计为空（缺少地形数据）");
            return;
        }
        BlockPath path = route.path();
        int n = Math.min(path.size(), stats.designProfile.length);
        if (n < 2) {
            clear("这条线太短");
            return;
        }

        // 先挑出落在范围里的走行线：范围内没有线就没得标。
        double r2 = (double) cfg.clearanceRange * cfg.clearanceRange;
        double px = mc.player.getX();
        double pz = mc.player.getZ();
        int[] sx = new int[n];
        int[] sy = new int[n];
        int[] sz = new int[n];
        int m = 0;
        for (int i = 0; i < n && m < Corridor.MAX_SPINE; i++) {
            float design = stats.designProfile[i];
            // 地形没探明的那段纵断面是 NaN：拿 floor(NaN) 会变成 0，红线会一路画到基岩去。
            if (Float.isNaN(design)) {
                continue;
            }
            double dx = path.xs[i] + 0.5 - px;
            double dz = path.zs[i] + 0.5 - pz;
            if (dx * dx + dz * dz > r2) {
                continue;
            }
            sx[m] = path.xs[i];
            sy[m] = (int) Math.floor(design);
            sz[m] = path.zs[i];
            m++;
        }
        if (m < 2) {
            clear("范围内没有线（走近一点，或把范围调大）");
            return;
        }
        spineX = Arrays.copyOf(sx, m);
        spineY = Arrays.copyOf(sy, m);
        spineZ = Arrays.copyOf(sz, m);

        Corridor corridor = Corridor.of(spineX, spineY, spineZ, cfg.clearanceWidth, cfg.clearanceUp,
                cfg.clearanceDown);
        corridorSize = corridor.size();
        if (corridor.truncated()) {
            clear("走廊超过 " + Corridor.MAX_BLOCKS + " 格，把范围调小");
            return;
        }

        // 再按方块状态过一遍：只留挡路的。
        Level level = mc.level;
        int[] ox = new int[corridor.size()];
        int[] oy = new int[corridor.size()];
        int[] oz = new int[corridor.size()];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int k = 0;
        for (int i = 0; i < corridor.size(); i++) {
            pos.set(corridor.x(i), corridor.y(i), corridor.z(i));
            if (ClearRules.clearable(level.getBlockState(pos))) {
                ox[k] = corridor.x(i);
                oy[k] = corridor.y(i);
                oz[k] = corridor.z(i);
                k++;
            }
        }
        xs = Arrays.copyOf(ox, k);
        ys = Arrays.copyOf(oy, k);
        zs = Arrays.copyOf(oz, k);
        why = "";
    }

    private static void clear(String reason) {
        xs = new int[0];
        ys = new int[0];
        zs = new int[0];
        spineX = new int[0];
        spineY = new int[0];
        spineZ = new int[0];
        corridorSize = 0;
        why = reason;
    }
}
