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
 *
 * <p>名字和类型都是给人看的：名字可以空着，类型决定地图上/世界里画成什么形状。</p>
 */
public record Anchor(int x, int z, int y, boolean manual, boolean resolved, String name, PointKind kind) {

    /** 探不到地形时的回落标高（海平面那一层）。 */
    public static final int FALLBACK_Y = 64;

    public Anchor {
        name = name == null ? "" : name;
        kind = kind == null ? PointKind.PLAIN : kind;
    }

    /** 还没探明的点：先按回落值放着，等地图数据到了再解析。 */
    public Anchor(int x, int z) {
        this(x, z, FALLBACK_Y, false, false, "", PointKind.PLAIN);
    }

    /** 从地面高度算出来的点：值已经存死，不用再解析。 */
    public static Anchor auto(int x, int z, int height) {
        return new Anchor(x, z, height, false, true, "", PointKind.PLAIN);
    }

    /** 手填的点。 */
    public static Anchor manual(int x, int z, int y) {
        return new Anchor(x, z, y, true, true, "", PointKind.PLAIN);
    }

    public Anchor withName(String value) {
        return new Anchor(x, z, y, manual, resolved, value, kind);
    }

    public Anchor withKind(PointKind value) {
        return new Anchor(x, z, y, manual, resolved, name, value);
    }

    /** 手填一个新的 Y，名字和类型保持不动。 */
    public Anchor withY(int value) {
        return new Anchor(x, z, value, true, true, name, kind);
    }

    /** 换个位置（拖动时用），名字、类型、Y 全都不动。 */
    public Anchor at(int nx, int nz) {
        return new Anchor(nx, nz, y, manual, resolved, name, kind);
    }

    /** 存档 / 同步用：[x, z, y, 标记位]。标记位 bit0 = 手填，bit1 = 已解析，bit8 起是类型。 */
    public int[] toArray() {
        int flags = (this.manual ? 1 : 0) | (this.resolved ? 2 : 0) | (kind.id << 8);
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
        PointKind kind = v.length >= 4 ? PointKind.byId((v[3] >> 8) & 0xFF) : PointKind.PLAIN;
        return new Anchor(v[0], v[1], v[2], manual, resolved, "", kind);
    }
}
