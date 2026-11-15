package com.phzt.railplanner.client.geom;

/** 像素化到方块网格的轨道走行线。 */
public final class BlockPath {

    public final int[] xs;
    public final int[] zs;
    public final float[] chainage;
    public final byte[] direction; // 0=E 1=SE 2=S 3=SW 4=W 5=NW 6=N 7=NE
    public final double length;

    BlockPath(int[] xs, int[] zs, float[] chainage, byte[] direction, double length) {
        this.xs = xs;
        this.zs = zs;
        this.chainage = chainage;
        this.direction = direction;
        this.length = length;
    }

    public int size() {
        return this.xs.length;
    }
}
