package com.phzt.railplanner.client.geom;

import java.util.ArrayList;
import java.util.List;

/**
 * 铁路导线：由折线锚点生成「切线—圆曲线—切线」的平曲线。
 * 每个折点按给定半径做圆角（半径受相邻线段长度限制），圆弧参数按铁路习惯给出：
 * 偏角 Δ、半径 R、切线长 T = R·tan(Δ/2)、曲线长 L = R·Δ、外距 E = R·(sec(Δ/2) − 1)。
 */
public final class Alignment {

    public enum Kind { LINE, ARC }

    /** 一段线形：直线或圆弧。 */
    public static final class Piece {
        public final Kind kind;
        public final double startS;
        public final double length;
        public final int corner;        // 对应锚点下标，直线为 -1
        public final double deflection; // 偏角（弧度），直线为 0

        // 直线
        public final double x0, z0, dx, dz;
        // 圆弧
        public final double cx, cz, radius, startAngle, sweep;

        private Piece(Kind kind, double startS, double length, int corner, double deflection,
                      double x0, double z0, double dx, double dz,
                      double cx, double cz, double radius, double startAngle, double sweep) {
            this.kind = kind;
            this.startS = startS;
            this.length = length;
            this.corner = corner;
            this.deflection = deflection;
            this.x0 = x0;
            this.z0 = z0;
            this.dx = dx;
            this.dz = dz;
            this.cx = cx;
            this.cz = cz;
            this.radius = radius;
            this.startAngle = startAngle;
            this.sweep = sweep;
        }
    }

    /** 一个折点的圆曲线结果，给 HUD 用。 */
    public static final class Corner {
        public final int anchorIndex;
        public final double x, z;      // 交点（原折点）
        public final double radius;
        public final double deflection; // 弧度
        public final double tangent;
        public final double arcLength;
        public final double external;

        Corner(int anchorIndex, double x, double z, double radius, double deflection,
               double tangent, double arcLength, double external) {
            this.anchorIndex = anchorIndex;
            this.x = x;
            this.z = z;
            this.radius = radius;
            this.deflection = deflection;
            this.tangent = tangent;
            this.arcLength = arcLength;
            this.external = external;
        }

        public double deflectionDegrees() {
            return Math.toDegrees(this.deflection);
        }
    }

    private static final double EPS = 1.0E-6;

    private final List<Piece> pieces;
    private final List<Corner> corners;
    private final double totalLength;

    private Alignment(List<Piece> pieces, List<Corner> corners) {
        this.pieces = pieces;
        this.corners = corners;
        double len = 0.0;
        for (Piece p : pieces) {
            len += p.length;
        }
        this.totalLength = len;
    }

    public static Alignment of(List<Vec2> points, double wantedRadius) {
        List<Piece> pieces = new ArrayList<>();
        List<Corner> corners = new ArrayList<>();
        int n = points.size();
        if (n < 2) {
            return new Alignment(pieces, corners);
        }

        double[] t1x = new double[n];
        double[] t1z = new double[n];
        double[] t2x = new double[n];
        double[] t2z = new double[n];
        boolean[] has = new boolean[n];
        double[] rad = new double[n];
        double[] defl = new double[n];
        double[] cxx = new double[n];
        double[] czz = new double[n];
        double[] a0 = new double[n];
        double[] swp = new double[n];

        for (int i = 1; i < n - 1; i++) {
            Vec2 p0 = points.get(i - 1);
            Vec2 p1 = points.get(i);
            Vec2 p2 = points.get(i + 1);
            Vec2 d1 = p1.sub(p0).normalize();
            Vec2 d2 = p2.sub(p1).normalize();
            double l1 = p1.sub(p0).length();
            double l2 = p2.sub(p1).length();
            if (l1 < EPS || l2 < EPS) {
                continue;
            }
            double cross = d1.cross(d2);
            double dot = Math.max(-1.0, Math.min(1.0, d1.dot(d2)));
            double delta = Math.atan2(Math.abs(cross), dot);
            // 折角太小（几乎直线）或几乎掉头：不做圆曲线
            if (delta < 0.002 || delta > Math.PI - 0.002) {
                continue;
            }
            double tanHalf = Math.tan(delta / 2.0);
            double t = Math.min(wantedRadius * tanHalf, Math.min(l1, l2) * 0.49);
            double r = t / tanHalf;
            if (r < 0.5) {
                continue;
            }
            Vec2 a = p1.sub(d1.x * t, d1.z * t); // 入切线点 T1
            Vec2 b = p1.add(d2.x * t, d2.z * t); // 出切线点 T2

            // 圆心：从 T1 沿 d1 的垂线偏 R，验证圆弧扫角等于偏角
            Vec2 n1 = new Vec2(-d1.z, d1.x);
            Vec2 n2 = new Vec2(d1.z, -d1.x);
            Vec2 center = null;
            double startAng = 0;
            double sweep = 0;
            for (int k = 0; k < 2 && center == null; k++) {
                Vec2 nn = (k == 0) ? n1 : n2;
                Vec2 c = a.add(nn.x * r, nn.z * r);
                double ang1 = Math.atan2(a.z - c.z, a.x - c.x);
                double ang2 = Math.atan2(b.z - c.z, b.x - c.x);
                double sw = wrapPi(ang2 - ang1);
                if (Math.abs(Math.abs(sw) - delta) < 1.0E-3) {
                    center = c;
                    startAng = ang1;
                    sweep = sw;
                }
            }
            if (center == null) {
                continue;
            }
            has[i] = true;
            rad[i] = r;
            defl[i] = delta;
            cxx[i] = center.x;
            czz[i] = center.z;
            a0[i] = startAng;
            swp[i] = sweep;
            t1x[i] = a.x;
            t1z[i] = a.z;
            t2x[i] = b.x;
            t2z[i] = b.z;
            corners.add(new Corner(i, p1.x, p1.z, r, delta, t, r * delta,
                    r * (1.0 / Math.cos(delta / 2.0) - 1.0)));
        }

        double s = 0.0;
        Vec2 cur = points.get(0);
        for (int i = 1; i < n; i++) {
            if (has[i]) {
                Vec2 t1 = new Vec2(t1x[i], t1z[i]);
                Vec2 t2 = new Vec2(t2x[i], t2z[i]);
                double lineLen = t1.sub(cur).length();
                if (lineLen > EPS) {
                    Vec2 d = t1.sub(cur).normalize();
                    pieces.add(new Piece(Kind.LINE, s, lineLen, -1, 0.0,
                            cur.x, cur.z, d.x, d.z, 0, 0, 0, 0, 0));
                    s += lineLen;
                }
                double arcLen = rad[i] * defl[i];
                pieces.add(new Piece(Kind.ARC, s, arcLen, i, defl[i],
                        0, 0, 0, 0, cxx[i], czz[i], rad[i], a0[i], swp[i]));
                s += arcLen;
                cur = t2;
            }
        }
        Vec2 end = points.get(n - 1);
        double lastLen = end.sub(cur).length();
        if (lastLen > EPS) {
            Vec2 d = end.sub(cur).normalize();
            pieces.add(new Piece(Kind.LINE, s, lastLen, -1, 0.0,
                    cur.x, cur.z, d.x, d.z, 0, 0, 0, 0, 0));
        }
        return new Alignment(pieces, corners);
    }

    /**
     * 正交折线（每段都沿 X 或 Z）的导线：拐角一律 90°，按设计半径铺「直线—圆弧—直线」。
     *
     * <p>跟 {@link #of} 的区别是圆角不靠试算：90° 的切线长 T 就等于半径 R，每个拐点吃 R，
     * 两个弯夹着的那截直段要 2R（{@link OrthoRoute} 已经把腿推到够长）。段短到放不下时
     * 按半段夹，半径只减不增，绝不会让两条切线叠在一起。</p>
     *
     * @param vertices 正交折线的顶点，首尾是端点、中间是拐点
     * @param radius   设计半径
     */
    public static Alignment ofOrthogonal(List<Vec2> vertices, double radius) {
        List<Piece> pieces = new ArrayList<>();
        List<Corner> corners = new ArrayList<>();
        int n = vertices.size();
        if (n < 2) {
            return new Alignment(pieces, corners);
        }
        double[] len = new double[n - 1];
        double[] ux = new double[n - 1];
        double[] uz = new double[n - 1];
        for (int i = 0; i < n - 1; i++) {
            Vec2 d = vertices.get(i + 1).sub(vertices.get(i));
            len[i] = d.length();
            Vec2 u = d.normalize();
            ux[i] = u.x;
            uz[i] = u.z;
        }
        double wanted = Math.max(0.5, radius);
        boolean[] bend = new boolean[n];
        for (int k = 1; k < n - 1; k++) {
            if (len[k - 1] < EPS || len[k] < EPS) {
                continue;
            }
            double dot = ux[k - 1] * ux[k] + uz[k - 1] * uz[k];
            // 同向直着走 / 掉头：都不做圆曲线
            bend[k] = Math.abs(dot) < 0.5;
        }
        double[] tangent = new double[n];
        for (int k = 1; k < n - 1; k++) {
            if (!bend[k]) {
                continue;
            }
            // 一截直段要是两头都是弯，两个弯各吃一半；只有一头是弯，那个弯可以吃满。
            double capA = bend[k - 1] ? len[k - 1] * 0.5 : len[k - 1];
            double capB = bend[k + 1] ? len[k] * 0.5 : len[k];
            tangent[k] = Math.min(wanted, Math.min(capA, capB));
        }

        double s = 0.0;
        Vec2 cur = vertices.get(0);
        for (int k = 1; k < n - 1; k++) {
            Vec2 v = vertices.get(k);
            if (!bend[k]) {
                double dot = len[k - 1] < EPS || len[k] < EPS
                        ? 1.0 : ux[k - 1] * ux[k] + uz[k - 1] * uz[k];
                if (dot < -0.5) {
                    // 掉头：折回来，不画圆弧，但直段不能越过这个点
                    double straight = v.sub(cur).length();
                    if (straight > EPS) {
                        Vec2 d = v.sub(cur).normalize();
                        pieces.add(new Piece(Kind.LINE, s, straight, -1, 0.0,
                                cur.x, cur.z, d.x, d.z, 0, 0, 0, 0, 0));
                        s += straight;
                    }
                    cur = v;
                }
                continue;
            }
            double t = tangent[k];
            Vec2 t1 = v.sub(ux[k - 1] * t, uz[k - 1] * t);
            Vec2 t2 = v.add(ux[k] * t, uz[k] * t);
            Vec2 center = t1.add(ux[k] * t, uz[k] * t);
            double a0 = Math.atan2(t1.z - center.z, t1.x - center.x);
            double a1 = Math.atan2(t2.z - center.z, t2.x - center.x);
            double sweep = wrapPi(a1 - a0);
            double straight = t1.sub(cur).length();
            if (straight > EPS) {
                Vec2 d = t1.sub(cur).normalize();
                pieces.add(new Piece(Kind.LINE, s, straight, -1, 0.0,
                        cur.x, cur.z, d.x, d.z, 0, 0, 0, 0, 0));
                s += straight;
            }
            double arcLen = t * Math.abs(sweep);
            if (arcLen > EPS) {
                pieces.add(new Piece(Kind.ARC, s, arcLen, k, Math.abs(sweep), 0, 0, 0, 0,
                        center.x, center.z, t, a0, sweep));
                s += arcLen;
            }
            double deflection = Math.abs(sweep);
            corners.add(new Corner(-1, v.x, v.z, t, deflection, t, arcLen,
                    t * (1.0 / Math.cos(deflection / 2.0) - 1.0)));
            cur = t2;
        }
        Vec2 end = vertices.get(n - 1);
        double last = end.sub(cur).length();
        if (last > EPS) {
            Vec2 d = end.sub(cur).normalize();
            pieces.add(new Piece(Kind.LINE, s, last, -1, 0.0,
                    cur.x, cur.z, d.x, d.z, 0, 0, 0, 0, 0));
        }
        return new Alignment(pieces, corners);
    }

    private static double wrapPi(double a) {
        while (a > Math.PI) {
            a -= Math.PI * 2.0;
        }
        while (a < -Math.PI) {
            a += Math.PI * 2.0;
        }
        return a;
    }

    public List<Piece> pieces() {
        return this.pieces;
    }

    public List<Corner> corners() {
        return this.corners;
    }

    public double length() {
        return this.totalLength;
    }

    /** 沿导线里程 s 处的点。 */
    public Vec2 pointAt(double s) {
        if (this.pieces.isEmpty()) {
            return new Vec2(0, 0);
        }
        double ss = Math.max(0.0, Math.min(this.totalLength, s));
        for (Piece p : this.pieces) {
            if (ss <= p.startS + p.length + EPS) {
                double t = ss - p.startS;
                if (p.kind == Kind.LINE) {
                    return new Vec2(p.x0 + p.dx * t, p.z0 + p.dz * t);
                }
                double ang = p.startAngle + p.sweep * (t / Math.max(EPS, p.length));
                return new Vec2(p.cx + Math.cos(ang) * p.radius, p.cz + Math.sin(ang) * p.radius);
            }
        }
        Piece last = this.pieces.get(this.pieces.size() - 1);
        if (last.kind == Kind.LINE) {
            return new Vec2(last.x0 + last.dx * last.length, last.z0 + last.dz * last.length);
        }
        double ang = last.startAngle + last.sweep;
        return new Vec2(last.cx + Math.cos(ang) * last.radius, last.cz + Math.sin(ang) * last.radius);
    }

    /** 沿导线里程 s 处的航向角（弧度，x 轴为 0，逆时针为负）。 */
    public double headingAt(double s) {
        if (this.pieces.isEmpty()) {
            return 0.0;
        }
        double ss = Math.max(0.0, Math.min(this.totalLength, s));
        for (Piece p : this.pieces) {
            if (ss <= p.startS + p.length + EPS) {
                if (p.kind == Kind.LINE) {
                    return Math.atan2(p.dz, p.dx);
                }
                double t = ss - p.startS;
                double ang = p.startAngle + p.sweep * (t / Math.max(EPS, p.length));
                return ang + (p.sweep > 0 ? Math.PI / 2.0 : -Math.PI / 2.0);
            }
        }
        return 0.0;
    }
}
