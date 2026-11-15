package com.phzt.railplanner.client.model;

/**
 * 一个采样点，方块坐标（整数格）。
 *
 * <p>二维地图上只点得到 (x, z)，标高 y 只能另外记：它既是这个点要求的设计标高，
 * 也是整条线纵断面必须经过的位置——线路走到这里，轨面就在这个高度上。</p>
 *
 * <p>y 只有两个来源：</p>
 * <ul>
 *   <li>{@code manual}：玩家双击点进去手填的。填多少就是多少，地形再变也不动；</li>
 *   <li>不是手填的：点下去那一刻取该列最高方块的高度（不含树叶的地表）存死。
 *       那一刻地图数据还没探到、区块也没加载，就先放 {@link #FALLBACK_Y}，
 *       等探明了再自动换成真实高度（{@code resolved} 记着这一步做没做）。</li>
 * </ul>
 */
public record Anchor(int x, int z, int y, boolean manual, boolean resolved) {

    /** 探不到地形时的回落标高（海平面那一层）。 */
    public static final int FALLBACK_Y = 64;

    /** 还没探明的点：先按回落值放着，等地图数据到了再解析。 */
    public Anchor(int x, int z) {
        this(x, z, FALLBACK_Y, false, false);
    }

    /** 从地面高度算出来的点：值已经存死，不用再解析。 */
    public static Anchor auto(int x, int z, int height) {
        return new Anchor(x, z, height, false, true);
    }

    /** 手填的点。 */
    public static Anchor manual(int x, int z, int y) {
        return new Anchor(x, z, y, true, true);
    }

    /** 存档 / 同步用：[x, z, y, 标记位]。标记位 bit0 = 手填，bit1 = 已解析。 */
    public int[] toArray() {
        int flags = (this.manual ? 1 : 0) | (this.resolved ? 2 : 0);
        return new int[]{this.x, this.z, this.y, flags};
    }

    /**
     * 读回来。老存档（0.6.x 及以前）和弦上只有 [x, z]，没有 y——
     * 这种点当成「还没解析」，地图一开就会被换成真实地面高度。
     */
    public static Anchor fromArray(int[] v) {
        if (v == null || v.length < 2) {
            return null;
        }
        if (v.length < 3) {
            return new Anchor(v[0], v[1]);
        }
        boolean manual = v.length >= 4 && (v[3] & 1) != 0;
        boolean resolved = v.length >= 4 && (v[3] & 2) != 0;
        return new Anchor(v[0], v[1], v[2], manual, resolved);
    }
}
