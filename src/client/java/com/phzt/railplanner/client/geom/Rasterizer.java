package com.phzt.railplanner.client.geom;

import java.util.ArrayList;
import java.util.List;

/** 把平曲线采样后落到方块网格上，得到连续（8 邻接）的轨道方块串。 */
public final class Rasterizer {

    private Rasterizer() {
    }

    public static BlockPath rasterize(Alignment alignment) {
        List<int[]> cells = new ArrayList<>();
        List<Float> chain = new ArrayList<>();
        if (alignment.length() < 0.5) {
            return new BlockPath(new int[0], new int[0], new float[0], new byte[0], 0.0);
        }
        double step = 0.5;
        int samples = (int) Math.ceil(alignment.length() / step);
        int lastX = Integer.MIN_VALUE;
        int lastZ = Integer.MIN_VALUE;
        for (int k = 0; k <= samples; k++) {
            double s = Math.min(alignment.length(), k * step);
            Vec2 p = alignment.pointAt(s);
            int bx = (int) Math.floor(p.x);
            int bz = (int) Math.floor(p.z);
            if (bx == lastX && bz == lastZ) {
                continue;
            }
            if (lastX == Integer.MIN_VALUE) {
                cells.add(new int[]{bx, bz});
                chain.add((float) s);
            } else {
                connect(cells, chain, lastX, lastZ, bx, bz, (float) s);
            }
            lastX = bx;
            lastZ = bz;
        }

        int n = cells.size();
        int[] xs = new int[n];
        int[] zs = new int[n];
        float[] ch = new float[n];
        byte[] dir = new byte[n];
        for (int i = 0; i < n; i++) {
            xs[i] = cells.get(i)[0];
            zs[i] = cells.get(i)[1];
            ch[i] = chain.get(i);
        }
        double len = 0.0;
        for (int i = 1; i < n; i++) {
            dir[i - 1] = directionOf(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
            len += Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
        }
        if (n > 0) {
            dir[n - 1] = n > 1 ? dir[n - 2] : 0;
        }
        return new BlockPath(xs, zs, ch, dir, len);
    }

    private static void connect(List<int[]> cells, List<Float> chain, int x0, int z0, int x1, int z1, float endS) {
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;
        int x = x0;
        int z = z0;
        int guard = 0;
        while ((x != x1 || z != z1) && guard++ < 10000) {
            int e2 = err * 2;
            if (e2 > -dz) {
                err -= dz;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                z += sz;
            }
            cells.add(new int[]{x, z});
            chain.add(endS);
        }
    }

    static byte directionOf(int dx, int dz) {
        int sx = Integer.signum(dx);
        int sz = Integer.signum(dz);
        if (sx > 0) {
            return (byte) (sz > 0 ? 1 : (sz < 0 ? 7 : 0));
        }
        if (sx < 0) {
            return (byte) (sz > 0 ? 3 : (sz < 0 ? 5 : 4));
        }
        return (byte) (sz > 0 ? 2 : 6);
    }
}
