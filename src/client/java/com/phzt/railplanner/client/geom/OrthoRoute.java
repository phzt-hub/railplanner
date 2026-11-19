package com.phzt.railplanner.client.geom;

import java.util.ArrayList;
import java.util.List;

/**
 * 正交化：把玩家点的折线改成「沿 X 或 Z 的直段 + 90° 弯」。
 *
 * <p>斜着的一段先沿较长的那个轴走到交点、再拐 90° 走另一个轴：只多一个弯，两个端点的
 * X/Z 都不动。90° 弯的切线长 T 正好等于半径 R，所以弯两边那截直段都得有 R 那么长，
 * 两头都连着弯的那截要 2R；不够长的地方把端点沿那条腿往外推（推了多少格记在
 * {@link Result#pushed()}），半径一格不缩。</p>
 *
 * <p>这里只出折线顶点，圆弧由 {@link Alignment#ofOrthogonal} 按 90° 定点铺。</p>
 */
public final class OrthoRoute {

    /** 正交化的结果。 */
    public static final class Result {

        private final List<Vec2> points;
        private final int bends;
        private final double pushed;

        Result(List<Vec2> points, int bends, double pushed) {
            this.points = points;
            this.bends = bends;
            this.pushed = pushed;
        }

        /** 正交折线的顶点：首尾是端点，中间每个都是 90° 拐点。 */
        public List<Vec2> points() {
            return this.points;
        }

        /** 拐点（90° 弯）的数量。 */
        public int bends() {
            return this.bends;
        }

        /** 端点为了让每截直段够长而被外推的总格数。 */
        public double pushed() {
            return this.pushed;
        }
    }

    private static final double EPS = 1.0e-6;
    /** 外推最多跑几轮：每轮推最缺的那一条腿，正常线一两轮就收敛。 */
    private static final int MAX_PASSES = 12;

    /** 折线上的一个顶点：拐点是我们插的，{@code anchor} 是 -1。 */
    private record Vertex(Vec2 p, int anchor) {
    }

    private OrthoRoute() {
    }

    /**
     * 把折线正交化。
     *
     * @param raw    玩家点的折线（世界坐标，已经 +0.5 到格心也行）
     * @param radius 设计半径，也就是 90° 弯的切线长
     */
    public static Result build(List<Vec2> raw, double radius) {
        return build(raw, radius, false);
    }

    /**
     * 同上，额外告诉它这一段要不要插缓和曲线——插了之后 90° 弯的切线长是 T 而不是 R，
     * 两条腿都得按 T 去要长度，否则拐角会被挤成没有缓和段的急弯。
     */
    public static Result build(List<Vec2> raw, double radius, boolean easement) {
        List<Vec2> anchors = dedupe(raw);
        if (anchors.size() < 2) {
            return new Result(anchors, 0, 0.0);
        }
        double r = Math.max(2.0, radius);
        double pushed = 0.0;
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            List<Vertex> verts = layout(anchors);
            int leg = -1;
            double deficit = 0.0;
            for (int i = 0; i + 1 < verts.size(); i++) {
                double need = need(verts, i, r, easement);
                double have = verts.get(i + 1).p().sub(verts.get(i).p()).length();
                if (need - have > deficit + EPS) {
                    deficit = need - have;
                    leg = i;
                }
            }
            if (leg < 0) {
                return finish(verts, pushed);
            }
            if (!pushOnce(anchors, verts, leg, deficit)) {
                // 这一截两头都是我们插的拐点，没有端点可推：交给圆弧按半段夹
                return finish(verts, pushed);
            }
            pushed += deficit;
        }
        return finish(layout(anchors), pushed);
    }

    /** 铺折线：斜的那段岔出一个 90° 拐点，先走较长的那个轴。 */
    private static List<Vertex> layout(List<Vec2> anchors) {
        List<Vertex> out = new ArrayList<>();
        out.add(new Vertex(anchors.get(0), 0));
        for (int i = 1; i < anchors.size(); i++) {
            Vec2 from = out.get(out.size() - 1).p();
            Vec2 to = anchors.get(i);
            double dx = to.x - from.x;
            double dz = to.z - from.z;
            if (Math.abs(dx) < EPS && Math.abs(dz) < EPS) {
                continue;                       // 跟上一个点重合：不算一段
            }
            if (Math.abs(dx) < EPS || Math.abs(dz) < EPS) {
                out.add(new Vertex(to, i));     // 本来就沿着轴，直接走
                continue;
            }
            Vec2 elbow = Math.abs(dx) >= Math.abs(dz)
                    ? new Vec2(to.x, from.z)        // 先沿 X
                    : new Vec2(from.x, to.z);       // 先沿 Z
            out.add(new Vertex(elbow, -1));
            out.add(new Vertex(to, i));
        }
        return out;
    }

    /** 90° 弯在给定半径下要吃多长的切线：有缓和段按缓圆缓算，没有就是 R。 */
    static double bendTangent(double r, boolean easement) {
        if (!easement) {
            return r;
        }
        double delta = Math.PI / 2.0;
        return Alignment.tangentFor(r, delta, Alignment.easementWanted(r, delta));
    }

    /** 这一截直段最短要多少：两头都是弯要 2T，一头是弯要 T，两头都不是随便。 */
    private static double need(List<Vertex> verts, int leg, double r, boolean easement) {
        boolean head = isBend(verts, leg);
        boolean tail = isBend(verts, leg + 1);
        if (!head && !tail) {
            return 0.0;
        }
        double t = bendTangent(r, easement);
        return (head && tail) ? 2.0 * t : t;
    }

    /** 这个顶点是不是 90° 拐点（同向直着过去、掉头都不算）。 */
    private static boolean isBend(List<Vertex> verts, int index) {
        if (index <= 0 || index >= verts.size() - 1) {
            return false;
        }
        Vec2 in = verts.get(index).p().sub(verts.get(index - 1).p());
        Vec2 out = verts.get(index + 1).p().sub(verts.get(index).p());
        if (in.length() < EPS || out.length() < EPS) {
            return false;
        }
        return Math.abs(in.normalize().dot(out.normalize())) < 0.5;
    }

    /** 把这条腿末端（或起点）那个端点往外推 {@code deficit} 格，让它够长。 */
    private static boolean pushOnce(List<Vec2> anchors, List<Vertex> verts, int leg, double deficit) {
        Vec2 a = verts.get(leg).p();
        Vec2 b = verts.get(leg + 1).p();
        Vec2 dir = b.sub(a).normalize();
        if (dir.length() < 0.5) {
            return false;
        }
        int endAnchor = verts.get(leg + 1).anchor();
        if (endAnchor >= 0) {
            anchors.set(endAnchor, anchors.get(endAnchor).add(dir.x * deficit, dir.z * deficit));
            return true;
        }
        int startAnchor = verts.get(leg).anchor();
        if (startAnchor >= 0) {
            anchors.set(startAnchor, anchors.get(startAnchor).sub(dir.x * deficit, dir.z * deficit));
            return true;
        }
        return false;
    }

    private static Result finish(List<Vertex> verts, double pushed) {
        List<Vec2> pts = new ArrayList<>(verts.size());
        for (Vertex v : verts) {
            if (!pts.isEmpty() && pts.get(pts.size() - 1).sub(v.p()).length() < EPS) {
                continue;
            }
            pts.add(v.p());
        }
        return new Result(pts, Math.max(0, pts.size() - 2), pushed);
    }

    private static List<Vec2> dedupe(List<Vec2> raw) {
        List<Vec2> out = new ArrayList<>(raw.size());
        for (Vec2 p : raw) {
            if (p == null) {
                continue;
            }
            if (!out.isEmpty() && out.get(out.size() - 1).sub(p).length() < EPS) {
                continue;
            }
            out.add(p);
        }
        return out;
    }
}
