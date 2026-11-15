package com.phzt.railplanner.client.planner;

import com.phzt.railplanner.client.model.Anchor;
import com.phzt.railplanner.client.terrain.XaeroTerrain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import xaero.map.MapProcessor;

/** 用 Xaero 高度图做 A* 选线：坡度越陡、跨水越贵。 */
public final class AutoRouter {

    private static final int STEP = 8;
    private static final double SLOPE_COST = 9.0;
    private static final double WATER_PENALTY = 260.0;
    private static final int SEARCH_MARGIN = 224;
    private static final int MAX_NODES = 60000;

    private AutoRouter() {
    }

    public static List<Anchor> route(MapProcessor mp, Anchor start, Anchor goal) {
        int minX = Math.min(start.x(), goal.x()) - SEARCH_MARGIN;
        int maxX = Math.max(start.x(), goal.x()) + SEARCH_MARGIN;
        int minZ = Math.min(start.z(), goal.z()) - SEARCH_MARGIN;
        int maxZ = Math.max(start.z(), goal.z()) + SEARCH_MARGIN;
        int nx = (maxX - minX) / STEP + 2;
        int nz = (maxZ - minZ) / STEP + 2;
        if ((long) nx * nz > MAX_NODES * 4L) {
            return List.of();
        }

        int sx = toGrid(start.x(), minX);
        int sz = toGrid(start.z(), minZ);
        int gx = toGrid(goal.x(), minX);
        int gz = toGrid(goal.z(), minZ);

        Map<Long, Integer> heights = new HashMap<>();
        Map<Long, Double> best = new HashMap<>();
        Map<Long, Long> parent = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>();
        long startKey = key(sx, sz);
        best.put(startKey, 0.0);
        open.add(new Node(startKey, 0.0, heuristic(sx, sz, gx, gz)));

        long goalKey = key(gx, gz);
        int expanded = 0;
        long foundKey = -1;
        while (!open.isEmpty() && expanded++ < MAX_NODES) {
            Node node = open.poll();
            Double g = best.get(node.key);
            if (g == null || node.g > g + 1e-6) {
                continue;
            }
            if (node.key == goalKey) {
                foundKey = node.key;
                break;
            }
            int cx = keyX(node.key);
            int cz = keyZ(node.key);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    int tx = cx + dx;
                    int tz = cz + dz;
                    if (tx < 0 || tz < 0 || tx >= nx || tz >= nz) {
                        continue;
                    }
                    long tk = key(tx, tz);
                    int h0raw = height(mp, heights, minX + cx * STEP, minZ + cz * STEP);
                    int h1raw = height(mp, heights, minX + tx * STEP, minZ + tz * STEP);
                    boolean unknown = h0raw == XaeroTerrain.UNKNOWN || h1raw == XaeroTerrain.UNKNOWN;
                    int h0 = h0raw == XaeroTerrain.UNKNOWN ? 64 : h0raw;
                    int h1 = h1raw == XaeroTerrain.UNKNOWN ? 64 : h1raw;
                    double dist = Math.hypot(dx, dz) * STEP;
                    double cost = dist + SLOPE_COST * Math.abs(h1 - h0);
                    if (h1 <= 62 && h0 <= 62) {
                        cost += WATER_PENALTY;
                    }
                    if (unknown) {
                        cost += 60.0;
                    }
                    double ng = node.g + cost;
                    Double old = best.get(tk);
                    if (old == null || ng < old - 1e-6) {
                        best.put(tk, ng);
                        parent.put(tk, node.key);
                        open.add(new Node(tk, ng, heuristic(tx, tz, gx, gz)));
                    }
                }
            }
        }
        if (foundKey == -1) {
            return List.of();
        }

        List<Anchor> path = new ArrayList<>();
        long cur = foundKey;
        while (cur != startKey) {
            path.add(0, new Anchor(minX + keyX(cur) * STEP, minZ + keyZ(cur) * STEP));
            Long p = parent.get(cur);
            if (p == null) {
                break;
            }
            cur = p;
        }
        path.add(0, start);
        path = simplify(path, 18.0);
        if (!path.isEmpty()) {
            path.set(path.size() - 1, goal);
        }
        return path;
    }

    private static int height(MapProcessor mp, Map<Long, Integer> cache, int x, int z) {
        long k = ((long) x << 32) ^ (z & 0xFFFFFFFFL);
        Integer v = cache.get(k);
        if (v != null) {
            return v;
        }
        int h = XaeroTerrain.height(mp, x, z);
        cache.put(k, h);
        return h;
    }

    private static int toGrid(int v, int min) {
        return Math.round((v - min) / (float) STEP);
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static int keyX(long k) {
        return (int) (k >> 32);
    }

    private static int keyZ(long k) {
        return (int) k;
    }

    private static double heuristic(int x, int z, int tx, int tz) {
        return Math.hypot(tx - x, tz - z) * STEP;
    }

    /** Ramer–Douglas–Peucker 简化。 */
    static List<Anchor> simplify(List<Anchor> pts, double epsilon) {
        if (pts.size() < 3) {
            return new ArrayList<>(pts);
        }
        boolean[] keep = new boolean[pts.size()];
        keep[0] = true;
        keep[pts.size() - 1] = true;
        simplifySection(pts, 0, pts.size() - 1, epsilon, keep);
        List<Anchor> out = new ArrayList<>();
        for (int i = 0; i < pts.size(); i++) {
            if (keep[i]) {
                out.add(pts.get(i));
            }
        }
        return out;
    }

    private static void simplifySection(List<Anchor> pts, int lo, int hi, double eps, boolean[] keep) {
        if (hi <= lo + 1) {
            return;
        }
        Anchor a = pts.get(lo);
        Anchor b = pts.get(hi);
        double maxDist = -1;
        int idx = -1;
        for (int i = lo + 1; i < hi; i++) {
            double d = pointLineDistance(pts.get(i), a, b);
            if (d > maxDist) {
                maxDist = d;
                idx = i;
            }
        }
        if (maxDist > eps) {
            keep[idx] = true;
            simplifySection(pts, lo, idx, eps, keep);
            simplifySection(pts, idx, hi, eps, keep);
        }
    }

    private static double pointLineDistance(Anchor p, Anchor a, Anchor b) {
        double dx = b.x() - a.x();
        double dz = b.z() - a.z();
        double len = Math.hypot(dx, dz);
        if (len < 1e-9) {
            return Math.hypot(p.x() - a.x(), p.z() - a.z());
        }
        return Math.abs(dx * (p.z() - a.z()) - dz * (p.x() - a.x())) / len;
    }

    private record Node(long key, double g, double h) implements Comparable<Node> {
        @Override
        public int compareTo(Node o) {
            return Double.compare(this.g + this.h, o.g + o.h);
        }
    }
}
