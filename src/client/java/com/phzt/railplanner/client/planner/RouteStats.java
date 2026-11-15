package com.phzt.railplanner.client.planner;

import com.phzt.railplanner.client.geom.BlockPath;
import com.phzt.railplanner.client.geom.VerticalProfile;
import com.phzt.railplanner.client.model.Anchor;
import com.phzt.railplanner.client.model.RailRoute;
import com.phzt.railplanner.client.terrain.XaeroTerrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import xaero.map.MapProcessor;

/**
 * 沿线统计：长度、坡度、隧道/桥/地面、材料、行车时间。
 *
 * 坡度分两套口径：
 * - 地面坡：直接用地形高度算，代表这段地形本身有多难修。
 * - 设计坡：先按限坡 2.5% 做一遍前向/后向剖切再平滑，得到一条修得出来的纵断面，
 *   隧道/桥就是按这条纵断面和地面的高差判出来的。
 */
public final class RouteStats {

    /** 限制坡度（%）：Minecraft 里动力铁轨可以推比较陡的坡，默认 8%（约每 12 格升 1 格）。 */
    public static double MAX_DESIGN_GRADE = 8.0;

    public static void nudgeMaxDesignGrade(double delta) {
        MAX_DESIGN_GRADE = Math.max(1.0, Math.min(30.0, MAX_DESIGN_GRADE + delta));
    }

    public double geometricLength;
    public double railLength;
    public int cells;
    public int knownCells;
    public int unknownCells;

    public double maxTerrainGradePct;
    public double maxDesignGradePct;
    public double climb;
    public double descent;

    public double tunnelLength;
    public double bridgeLength;
    public double groundLength;

    public int rails;
    public int poweredRails;
    public int torches;

    public double secondsAt8;
    public double secondsAt4;

    /** 设计标高与地面标高，给坡度曲线用。 */
    public float[] designProfile;
    public float[] terrainProfile;
    /** 抹掉单格尖刺后的地面标高，给世界里的地面线用（原始剖面留着算坡）。 */
    public float[] smoothTerrainProfile;

    public static RouteStats compute(RailRoute route, MapProcessor mp) {
        RouteStats st = new RouteStats();
        BlockPath path = route.path();
        st.cells = path.size();
        st.geometricLength = route.alignment().length();
        st.railLength = path.length;
        if (st.cells == 0) {
            st.designProfile = new float[0];
            st.terrainProfile = new float[0];
            return st;
        }

        int n = st.cells;
        float[] terrain = new float[n];
        double[] dist = new double[n];
        for (int i = 0; i < n; i++) {
            int h = height(mp, path.xs[i], path.zs[i]);
            if (h == XaeroTerrain.UNKNOWN) {
                terrain[i] = Float.NaN;
                st.unknownCells++;
            } else {
                terrain[i] = h;
                st.knownCells++;
            }
            if (i > 0) {
                dist[i] = Math.hypot(path.xs[i] - path.xs[i - 1], path.zs[i] - path.zs[i - 1]);
            }
        }
        st.terrainProfile = terrain;

        // 地面坡：先按 5 格窗口抹掉单格尖刺（树、柱、悬崖边缘），再看最大坡
        float[] terrainSmooth = boxSmooth(interpolateUnknown(terrain), 2);
        st.smoothTerrainProfile = terrainSmooth;
        if (terrainSmooth != null) {
            for (int i = 1; i < n; i++) {
                if (dist[i] <= 0) {
                    continue;
                }
                double g = Math.abs(terrainSmooth[i] - terrainSmooth[i - 1]) / dist[i] * 100.0;
                st.maxTerrainGradePct = Math.max(st.maxTerrainGradePct, g);
            }
        }

        float[] design = designProfile(terrain, dist, MAX_DESIGN_GRADE / 100.0);
        // 点过的每个点都钉着自己的 Y：纵断面必须从这些高度上过。
        pinAnchors(route, path, design);
        st.designProfile = design;

        for (int i = 1; i < n; i++) {
            if (dist[i] <= 0) {
                continue;
            }
            if (!Float.isNaN(design[i]) && !Float.isNaN(design[i - 1])) {
                double dh = design[i] - design[i - 1];
                st.maxDesignGradePct = Math.max(st.maxDesignGradePct, Math.abs(dh) / dist[i] * 100.0);
                if (dh > 0) {
                    st.climb += dh;
                } else {
                    st.descent -= dh;
                }
            }
            float t = terrain[i];
            float d = design[i];
            if (Float.isNaN(t) || Float.isNaN(d)) {
                continue;
            }
            if (t > d + 4.0f) {
                st.tunnelLength += dist[i];
            } else if (t < d - 4.0f) {
                st.bridgeLength += dist[i];
            } else {
                st.groundLength += dist[i];
            }
        }

        st.rails = st.cells;
        st.poweredRails = (int) Math.ceil(st.cells / 16.0);
        st.torches = (int) Math.ceil(st.cells / 10.0);
        st.secondsAt8 = st.railLength / 8.0;
        st.secondsAt4 = st.railLength / 4.0;
        return st;
    }

    /**
     * 某一格的地面高度：先用 Xaero 已探明的数据，没有就退回当前加载着的区块。
     *
     * <p>世界里的 3D 线一般是在没开地图的时候画的，那会儿 Xaero 的会话可能还没起来，
     * 或者那片地方压根没探过——退回真实区块至少玩家脚下这一段是准的。
     */
    public static int height(MapProcessor mp, int x, int z) {
        if (mp != null) {
            int h = XaeroTerrain.height(mp, x, z);
            if (h != XaeroTerrain.UNKNOWN) {
                return h;
            }
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null && level.hasChunk(x >> 4, z >> 4)) {
            return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        }
        return XaeroTerrain.UNKNOWN;
    }

    /**
     * 把每个点钉在自己的 Y 上。
     *
     * <p>规则只有一条：<b>线走到这个点，标高就是它的 Y</b>。两点之间怎么办，分两种情况：</p>
     * <ul>
     *   <li><b>这段高差放得进限坡</b>（|ΔY| ≤ 限坡 × 段长）：在「从两头按限坡追出去能到达的范围」
     *       里贴着地形走——地形平缓时就是原来那条贴地的线，地形起伏时被两头夹住，但绝不超限。</li>
     *   <li><b>放不进限坡</b>：两头都是硬钉住的，本来就不可能同时满足限坡，于是按两点之间的直线走，
     *       面板上的「设计最大坡」会标红，告诉玩家这一段在限坡上办不到。</li>
     * </ul>
     *
     * <p>里程一律沿走行线从上一个点往后找（{@link #projectCell}），不回头——环线的另一头常常
     * 离这个点更近，用全局最近格会把顺序搅乱，两点之间挤出一段近乎垂直的假坡。</p>
     */
    private static void pinAnchors(RailRoute route, BlockPath path, float[] design) {
        int n = design.length;
        int count = route.anchors.size();
        if (n < 2 || count == 0) {
            return;
        }
        float[] base = interpolateUnknown(design);

        int[] cell = new int[count];
        cell[0] = 0;
        for (int i = 1; i < count; i++) {
            Anchor prev = route.anchors.get(i - 1);
            Anchor cur = route.anchors.get(i);
            double span = Math.hypot(cur.x() - prev.x(), cur.z() - prev.z());
            cell[i] = projectCell(path, cur, cell[i - 1], (int) Math.ceil(span * 2.0 + 32.0), n);
        }
        // 最后一个点就是走行线的终点，直接认末格，免得量化把它挪回去。
        cell[count - 1] = n - 1;

        // 两个点落在同一格（或者只隔两三格）时不可能是两个标高：后一个把前一个顶掉，
        // 否则两点之间会算出一段近乎垂直的「假坡」。
        double[] chain = new double[count];
        float[] target = new float[count];
        int pins = 0;
        for (int i = 0; i < count; i++) {
            double s = path.chainage[cell[i]];
            float y = route.anchors.get(i).y();
            if (pins > 0 && s - chain[pins - 1] < MIN_PIN_GAP) {
                if (pins == 1 || s > chain[pins - 2] + 1.0E-6) {
                    chain[pins - 1] = s;
                    target[pins - 1] = y;
                }
                continue;
            }
            chain[pins] = s;
            target[pins] = y;
            pins++;
        }
        VerticalProfile.apply(path.chainage, java.util.Arrays.copyOf(chain, pins),
                java.util.Arrays.copyOf(target, pins), base, MAX_DESIGN_GRADE / 100.0, design);
    }

    /** 两个点在线路上离得比这还近（格），就当同一个点，只留后一个。 */
    private static final double MIN_PIN_GAP = 4.0;

    /** 走行线上离这个点最近的一格，只从 {@code from} 往后找（含窗口上限）。 */
    private static int projectCell(BlockPath path, Anchor a, int from, int window, int n) {
        int end = Math.min(n - 1, from + Math.max(8, window));
        int best = from;
        long bestD = Long.MAX_VALUE;
        for (int i = from; i <= end; i++) {
            long dx = path.xs[i] - a.x();
            long dz = path.zs[i] - a.z();
            long d = dx * dx + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** 限坡纵断面：前向/后向剖切 + 两次平滑，再重新限坡。 */
    static float[] designProfile(float[] terrain, double[] dist, double maxGrade) {
        int n = terrain.length;
        float[] base = interpolateUnknown(terrain);
        if (base == null) {
            float[] nan = new float[n];
            java.util.Arrays.fill(nan, Float.NaN);
            return nan;
        }
        // 先按 24 格窗口取趋势，避免纵断面被起点标高"钉住"，再按限坡剖切
        float[] d = boxSmooth(base, 24);
        limit(d, dist, maxGrade, true);
        limit(d, dist, maxGrade, false);
        for (int k = 0; k < 2; k++) {
            float[] s = boxSmooth(d, 8);
            limit(s, dist, maxGrade, true);
            limit(s, dist, maxGrade, false);
            d = s;
        }
        return d;
    }

    /** 滑动平均；输入全 NaN 时返回 null。 */
    private static float[] boxSmooth(float[] src, int r) {
        if (src == null) {
            return null;
        }
        int n = src.length;
        float[] out = new float[n];
        double[] prefix = new double[n + 1];
        int[] count = new int[n + 1];
        for (int i = 0; i < n; i++) {
            boolean ok = !Float.isNaN(src[i]);
            prefix[i + 1] = prefix[i] + (ok ? src[i] : 0);
            count[i + 1] = count[i] + (ok ? 1 : 0);
        }
        boolean any = count[n] > 0;
        if (!any) {
            java.util.Arrays.fill(out, Float.NaN);
            return out;
        }
        for (int i = 0; i < n; i++) {
            int lo = Math.max(0, i - r);
            int hi = Math.min(n, i + r + 1);
            int c = count[hi] - count[lo];
            out[i] = c == 0 ? Float.NaN : (float) ((prefix[hi] - prefix[lo]) / c);
        }
        return out;
    }

    private static void limit(float[] h, double[] dist, double maxGrade, boolean forward) {
        int n = h.length;
        if (forward) {
            for (int i = 1; i < n; i++) {
                double lim = maxGrade * Math.max(0.0, dist[i]);
                if (h[i] > h[i - 1] + lim) {
                    h[i] = (float) (h[i - 1] + lim);
                }
                if (h[i] < h[i - 1] - lim) {
                    h[i] = (float) (h[i - 1] - lim);
                }
            }
        } else {
            for (int i = n - 2; i >= 0; i--) {
                double lim = maxGrade * Math.max(0.0, dist[i + 1]);
                if (h[i] > h[i + 1] + lim) {
                    h[i] = (float) (h[i + 1] + lim);
                }
                if (h[i] < h[i + 1] - lim) {
                    h[i] = (float) (h[i + 1] - lim);
                }
            }
        }
    }

    private static float[] interpolateUnknown(float[] src) {
        int n = src.length;
        int firstKnown = -1;
        for (int i = 0; i < n; i++) {
            if (!Float.isNaN(src[i])) {
                firstKnown = i;
                break;
            }
        }
        if (firstKnown < 0) {
            return null;
        }
        float[] out = new float[n];
        int prev = firstKnown;
        for (int i = 0; i < firstKnown; i++) {
            out[i] = src[firstKnown];
        }
        out[firstKnown] = src[firstKnown];
        for (int i = firstKnown + 1; i < n; i++) {
            if (!Float.isNaN(src[i])) {
                int gap = i - prev;
                for (int j = prev + 1; j < i; j++) {
                    out[j] = src[prev] + (src[i] - src[prev]) * (j - prev) / gap;
                }
                out[i] = src[i];
                prev = i;
            }
        }
        for (int i = prev + 1; i < n; i++) {
            out[i] = src[prev];
        }
        return out;
    }

    public static String time(double seconds) {
        int s = (int) Math.round(seconds);
        return String.format("%d:%02d", s / 60, s % 60);
    }
}
