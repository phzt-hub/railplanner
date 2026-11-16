package com.phzt.railplanner.clearance;

/**
 * 铁路限界扫出来的那一条走廊。
 *
 * <p>沿走行线每一格取一个横断面：宽 {@code width} 格（奇数，以中心线左右对称）、
 * 从轨面往上是 {@code up} 格、往下是 {@code down} 格。横断面垂直于行进方向。</p>
 *
 * <p>斜着走的那一步会先拆成两小步（曼哈顿化），再按台阶取横断面——原因是不拆的话，
 * 45° 的横断面本身也是一条斜线，相邻两条之间正好漏掉一条同样斜的格子带，扫出来是
 * 一道道平行条纹。拆成台阶之后每个横断面都是正十字，相邻两格边贴着边，走廊是连通的，
 * 而且跟真正铺出来的斜线轨道一个形状。</p>
 *
 * <p>纯整数几何，不碰任何 Minecraft 类型——客户端拿它算标红预览，服务端拿它算真要清的方块。
 * 客户端只把走行线（每格的 x/y/z）和三个尺寸发过去，服务端用同一个类自己重算，
 * 绝不相信客户端给过来的坐标表。</p>
 */
public final class Corridor {

    /** 一次最多清多少格；走廊比这还长就截断，让玩家把范围调小。 */
    public static final int MAX_BLOCKS = 20000;
    /** 走行线最多收多少格。64 格范围大约 130 格，留足余量也不至于让一个包变大。 */
    public static final int MAX_SPINE = 4096;
    /** 宽度上限（奇数）。 */
    public static final int MAX_WIDTH = 15;
    /** 轨面以上最多留多少格。 */
    public static final int MAX_UP = 32;
    /** 轨面以下最多挖多少格。 */
    public static final int MAX_DOWN = 16;

    private static final int XZ_OFF = 1 << 25;
    private static final int XZ_MASK = (1 << 26) - 1;
    private static final int Y_OFF = 512;
    private static final int Y_MASK = 1023;

    private final int[] xs;
    private final int[] ys;
    private final int[] zs;
    private final boolean truncated;

    private Corridor(int[] xs, int[] ys, int[] zs, boolean truncated) {
        this.xs = xs;
        this.ys = ys;
        this.zs = zs;
        this.truncated = truncated;
    }

    public int size() {
        return this.xs.length;
    }

    public int x(int i) {
        return this.xs[i];
    }

    public int y(int i) {
        return this.ys[i];
    }

    public int z(int i) {
        return this.zs[i];
    }

    /** 走廊超过 {@link #MAX_BLOCKS} 被截断了：这一份不完整，不该拿它去清。 */
    public boolean truncated() {
        return this.truncated;
    }

    public static Corridor empty() {
        return new Corridor(new int[0], new int[0], new int[0], false);
    }

    /** 宽度规整成奇数并夹在上限内：玩家输偶数时按「多一格」算。 */
    public static int fixWidth(int width) {
        int value = Math.max(1, Math.min(MAX_WIDTH, width));
        return (value & 1) == 1 ? value : Math.min(MAX_WIDTH, value + 1);
    }

    /** 三个数只要有一个不合法就返回 false，服务端拿它挡掉乱发的包。 */
    public static boolean validSize(int width, int up, int down) {
        return (width & 1) == 1 && width >= 1 && width <= MAX_WIDTH
                && up >= 0 && up <= MAX_UP
                && down >= 0 && down <= MAX_DOWN;
    }

    /**
     * 按走行线扫一遍走廊。
     *
     * @param sx 走行线每一格的 x
     * @param sy 走行线每一格的轨面 y（设计标高）
     * @param sz 走行线每一格的 z
     */
    public static Corridor of(int[] sx, int[] sy, int[] sz, int width, int up, int down) {
        if (sx == null || sy == null || sz == null) {
            return empty();
        }
        int n = Math.min(Math.min(sx.length, sy.length), sz.length);
        if (n == 0) {
            return empty();
        }
        int half = width / 2;
        // 斜着的一步会拆成两小步，最多 2n-1 格。
        int[] px = new int[2 * n];
        int[] py = new int[2 * n];
        int[] pz = new int[2 * n];
        int m = manhattan(sx, sy, sz, n, px, py, pz);

        LongSet set = new LongSet();
        boolean truncated = false;

        spread:
        for (int i = 0; i < m; i++) {
            // 横断面方向 = 行进方向转 90°。方向按「上一步」取：拆完台阶之后每一步都是一个轴，
            // 用前后两格的差反而会在每个拐角处算成 45°。
            int dx = 0;
            int dz = 0;
            if (i > 0) {
                dx = Integer.signum(px[i] - px[i - 1]);
                dz = Integer.signum(pz[i] - pz[i - 1]);
            }
            if (dx == 0 && dz == 0 && i + 1 < m) {
                dx = Integer.signum(px[i + 1] - px[i]);
                dz = Integer.signum(pz[i + 1] - pz[i]);
            }
            if (dx == 0 && dz == 0) {
                dx = 1;
            }
            int ox = -dz;
            int oz = dx;
            for (int k = -half; k <= half; k++) {
                int bx = px[i] + ox * k;
                int bz = pz[i] + oz * k;
                int top = py[i] + up;
                for (int y = py[i] - down; y <= top; y++) {
                    if (!set.add(key(bx, y, bz))) {
                        continue;
                    }
                    if (set.size() > MAX_BLOCKS) {
                        truncated = true;
                        break spread;
                    }
                }
            }
        }

        long[] keys = set.toArray(MAX_BLOCKS);
        int[] ox = new int[keys.length];
        int[] oy = new int[keys.length];
        int[] oz = new int[keys.length];
        for (int i = 0; i < keys.length; i++) {
            ox[i] = keyX(keys[i]);
            oy[i] = keyY(keys[i]);
            oz[i] = keyZ(keys[i]);
        }
        return new Corridor(ox, oy, oz, truncated);
    }

    /**
     * 把斜着走的每一步拆成两小步（先走 x 再走 z），得到一条只有轴向步的走行线。
     *
     * <p>拆出来的那一格用起点那一格的标高——差一步的纵断面最多差一格，横断面照样盖得住。</p>
     *
     * @return 拆完之后有多少格；结果写进 {@code outX/outY/outZ}
     */
    private static int manhattan(int[] sx, int[] sy, int[] sz, int n, int[] outX, int[] outY, int[] outZ) {
        int cap = outX.length;
        int m = 0;
        outX[m] = sx[0];
        outY[m] = sy[0];
        outZ[m] = sz[0];
        m++;
        for (int i = 1; i < n && m < cap; i++) {
            int dx = sx[i] - sx[i - 1];
            int dz = sz[i] - sz[i - 1];
            if (dx != 0 && dz != 0 && m < cap - 1) {
                // 斜着的一步：先沿 x 走一格，再落到目标格
                outX[m] = sx[i - 1] + Integer.signum(dx);
                outY[m] = sy[i - 1];
                outZ[m] = sz[i - 1];
                m++;
            }
            if (m >= cap) {
                break;
            }
            outX[m] = sx[i];
            outY[m] = sy[i];
            outZ[m] = sz[i];
            m++;
        }
        return m;
    }

    /**
     * 坐标压成一个 long（x/z 各 26 位、y 10 位），一路拿去去重和传输。
     * 世界边界 ±3000 万格，26 位装得下；y 只到 320，10 位绰绰有余。
     */
    public static long key(int x, int y, int z) {
        return (((long) (x + XZ_OFF) & XZ_MASK) << 36)
                | (((long) (z + XZ_OFF) & XZ_MASK) << 10)
                | ((long) (y + Y_OFF) & Y_MASK);
    }

    public static int keyX(long key) {
        return (int) ((key >>> 36) & XZ_MASK) - XZ_OFF;
    }

    public static int keyZ(long key) {
        return (int) ((key >>> 10) & XZ_MASK) - XZ_OFF;
    }

    public static int keyY(long key) {
        return (int) (key & Y_MASK) - Y_OFF;
    }

    /** 极简长整型集合（开放寻址，装载因子 2/3），免得给两万个坐标装箱。 */
    private static final class LongSet {

        private long[] keys = new long[1 << 12];
        private boolean[] used = new boolean[1 << 12];
        private int size;

        boolean add(long value) {
            if ((this.size + 1) * 3 >= this.keys.length * 2) {
                grow();
            }
            int mask = this.keys.length - 1;
            int i = spread(value) & mask;
            while (this.used[i]) {
                if (this.keys[i] == value) {
                    return false;
                }
                i = (i + 1) & mask;
            }
            this.used[i] = true;
            this.keys[i] = value;
            this.size++;
            return true;
        }

        int size() {
            return this.size;
        }

        long[] toArray(int limit) {
            long[] out = new long[Math.min(limit, this.size)];
            int n = 0;
            for (int i = 0; i < this.keys.length && n < out.length; i++) {
                if (this.used[i]) {
                    out[n++] = this.keys[i];
                }
            }
            return out;
        }

        private static int spread(long value) {
            long h = value * 0x9E3779B97F4A7C15L;
            return (int) (h >>> 32);
        }

        private void grow() {
            long[] oldKeys = this.keys;
            boolean[] oldUsed = this.used;
            this.keys = new long[oldKeys.length << 1];
            this.used = new boolean[this.keys.length];
            this.size = 0;
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldUsed[i]) {
                    add(oldKeys[i]);
                }
            }
        }
    }
}
