package com.phzt.railplanner.client.geom;

import java.util.ArrayList;
import java.util.List;

/**
 * 铁路导线：由折线锚点生成「直线—缓和曲线—圆曲线—缓和曲线—直线」的平曲线。
 *
 * <p>不开缓和曲线时就是老的「切线—圆曲线—切线」，圆弧参数按铁路习惯给出：
 * 偏角 Δ、半径 R、切线长 T = R·tan(Δ/2)、曲线长 L = R·Δ、外距 E = R·(sec(Δ/2) − 1)。</p>
 *
 * <p>开缓和曲线时两端各插一段回旋线（A² = R·Ls，曲率从 0 线性长到 1/R），默认每端吃掉
 * 偏角的 1/4、中间圆弧留一半。切线长变成 T = (R + p)·tan(Δ/2) + q，其中 p、q 由回旋线
 * 端点积分算出（见 {@link #shift}）；外距 E = (R + p)·sec(Δ/2) − R。
 * 回旋线部分没有初等原函数，位置按 Simpson 积分采样成一张表，沿线取值走线性插值。</p>
 */
public final class Alignment {

    public enum Kind { LINE, ARC, CLOTHOID }

    /** 每端缓和段想要的偏角份额：各 1/4，圆弧留一半。 */
    private static final double EASEMENT_SHARE = 0.25;
    /** 缓和段再长也不能把圆弧吃光：单侧最多 3/8 的偏角，圆弧至少还剩 1/4。 */
    private static final double EASEMENT_MAX_SHARE = 0.375;
    /** 短于这么多格的缓和段没意义，直接退回纯圆曲线。 */
    private static final double MIN_EASEMENT = 2.0;
    /** 回旋线采样步长，格（插值误差在 1e-4 格量级，肉眼看不出）。 */
    private static final double CLOTHOID_STEP = 0.1;

    /** 一段线形：直线、圆弧或回旋线（缓和曲线）。 */
    public static final class Piece {
        public final Kind kind;
        public final double startS;
        public final double length;
        public final int corner;
        /** 这一段转过的角（带符号，弧度）；直线为 0。 */
        public final double deflection;

        // 直线
        public final double x0, z0, dx, dz;
        // 圆弧
        public final double cx, cz, radius, startAngle, sweep;
        // 回旋线
        public final double h0, k0, k1;
        /** 回旋线的采样表：x、z 交替，沿长度等距的 N+1 个点。 */
        final double[] table;

        private Piece(Kind kind, double startS, double length, int corner, double deflection,
                      double x0, double z0, double dx, double dz,
                      double cx, double cz, double radius, double startAngle, double sweep,
                      double h0, double k0, double k1, double[] table) {
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
            this.h0 = h0;
            this.k0 = k0;
            this.k1 = k1;
            this.table = table;
        }

        static Piece line(double startS, double length, Vec2 a, Vec2 b, int corner) {
            Vec2 d = b.sub(a).normalize();
            return new Piece(Kind.LINE, startS, length, corner, 0.0,
                    a.x, a.z, d.x, d.z, 0, 0, 0, 0, 0, 0, 0, 0, null);
        }

        static Piece arc(double startS, int corner, double deflection, Vec2 center, double radius,
                         double startAngle, double sweep) {
            return new Piece(Kind.ARC, startS, radius * Math.abs(sweep), corner, deflection,
                    0, 0, 0, 0, center.x, center.z, radius, startAngle, sweep, 0, 0, 0, null);
        }

        /** 回旋线：曲率从 k0 线性变到 k1，起点 {@code start}、起点走向 {@code heading}。 */
        static Piece clothoid(double startS, int corner, double turn, Vec2 start, double heading,
                              double k0, double k1, double length) {
            double[] table = clothoidTable(start.x, start.z, heading, k0, k1, length);
            return new Piece(Kind.CLOTHOID, startS, length, corner, turn,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, heading, k0, k1, table);
        }

        /** 回旋线在里程 s 处的走向：φ(s) = h0 + k0·s + (k1 − k0)·s²/(2L)。 */
        private static double clothoidHeading(double h0, double k0, double k1, double length, double s) {
            return h0 + k0 * s + (k1 - k0) * s * s / (2.0 * length);
        }

        /** 回旋线的位置表：每个小区间用 Simpson 公式积分 cos/sin，累积成 N+1 个点。 */
        private static double[] clothoidTable(double x0, double z0, double h0, double k0, double k1,
                                              double length) {
            int n = Math.max(8, Math.min(512, (int) Math.ceil(length / CLOTHOID_STEP)));
            double h = length / n;
            double[] tab = new double[2 * (n + 1)];
            tab[0] = x0;
            tab[1] = z0;
            double x = x0;
            double z = z0;
            for (int i = 1; i <= n; i++) {
                double a = (i - 1) * h;
                double b = i * h;
                double m = 0.5 * (a + b);
                double ca = Math.cos(clothoidHeading(h0, k0, k1, length, a));
                double cm = Math.cos(clothoidHeading(h0, k0, k1, length, m));
                double cb = Math.cos(clothoidHeading(h0, k0, k1, length, b));
                double sa = Math.sin(clothoidHeading(h0, k0, k1, length, a));
                double sm = Math.sin(clothoidHeading(h0, k0, k1, length, m));
                double sb = Math.sin(clothoidHeading(h0, k0, k1, length, b));
                x += h * (ca + 4.0 * cm + cb) / 6.0;
                z += h * (sa + 4.0 * sm + sb) / 6.0;
                tab[2 * i] = x;
                tab[2 * i + 1] = z;
            }
            return tab;
        }

        /** 这一段的终点。 */
        Vec2 end() {
            if (kind == Kind.LINE) {
                return new Vec2(x0 + dx * length, z0 + dz * length);
            }
            if (kind == Kind.ARC) {
                double a = startAngle + sweep;
                return new Vec2(cx + Math.cos(a) * radius, cz + Math.sin(a) * radius);
            }
            int n = table.length / 2 - 1;
            return new Vec2(table[2 * n], table[2 * n + 1]);
        }

        /** 段内里程 t（0..length）处的点。 */
        Vec2 pointAtLocal(double t) {
            if (kind == Kind.LINE) {
                return new Vec2(x0 + dx * t, z0 + dz * t);
            }
            if (kind == Kind.ARC) {
                double a = startAngle + sweep * (t / Math.max(1.0E-6, length));
                return new Vec2(cx + Math.cos(a) * radius, cz + Math.sin(a) * radius);
            }
            int n = table.length / 2 - 1;
            double u = Math.max(0.0, Math.min(n, t / Math.max(1.0E-6, length) * n));
            int i = (int) u;
            if (i >= n) {
                return new Vec2(table[2 * n], table[2 * n + 1]);
            }
            double f = u - i;
            return new Vec2(table[2 * i] + (table[2 * i + 2] - table[2 * i]) * f,
                    table[2 * i + 1] + (table[2 * i + 3] - table[2 * i + 1]) * f);
        }

        /** 段内里程 t 处的走向（弧度，x 轴为 0）。 */
        double headingAtLocal(double t) {
            if (kind == Kind.LINE) {
                return Math.atan2(dz, dx);
            }
            if (kind == Kind.ARC) {
                double a = startAngle + sweep * (t / Math.max(1.0E-6, length));
                return a + (sweep > 0 ? Math.PI / 2.0 : -Math.PI / 2.0);
            }
            return clothoidHeading(h0, k0, k1, length, t);
        }
    }

    /** 一个折点的圆曲线结果，给 HUD 用。 */
    public static final class Corner {
        public final int anchorIndex;
        public final double x, z;      // 交点（原折点）
        public final double radius;
        public final double deflection; // 弧度
        public final double tangent;
        /** 整段曲线长（含两端的缓和段）。 */
        public final double arcLength;
        public final double external;
        /** 单侧缓和段长 Ls，0 表示这个弯没有缓和曲线。 */
        public final double clothoid;

        Corner(int anchorIndex, double x, double z, double radius, double deflection,
               double tangent, double arcLength, double external, double clothoid) {
            this.anchorIndex = anchorIndex;
            this.x = x;
            this.z = z;
            this.radius = radius;
            this.deflection = deflection;
            this.tangent = tangent;
            this.arcLength = arcLength;
            this.external = external;
            this.clothoid = clothoid;
        }

        public double deflectionDegrees() {
            return Math.toDegrees(this.deflection);
        }

        /** 缓和曲线参数 A = √(R·Ls)；没缓和段时是 0。 */
        public double clothoidParam() {
            return this.clothoid <= 0 ? 0 : Math.sqrt(this.radius * this.clothoid);
        }
    }

    /** 一个折点算好的线形参数。 */
    private static final class Bend {
        final Vec2 v, t1, t2;
        final int anchor;
        final double heading;   // 入切线走向
        final double sign;      // +1 / −1，转弯方向
        final double radius;
        final double delta;     // 偏角
        final double ls;        // 单侧缓和段长，0 = 纯圆曲线
        final double alpha;     // 单侧缓和段转角
        final double beta;      // 圆曲线转角
        final double tangent;
        final double external;

        Bend(Vec2 v, Vec2 t1, Vec2 t2, int anchor, double heading, double sign, double radius,
             double delta, double ls, double alpha, double beta, double tangent, double external) {
            this.v = v;
            this.t1 = t1;
            this.t2 = t2;
            this.anchor = anchor;
            this.heading = heading;
            this.sign = sign;
            this.radius = radius;
            this.delta = delta;
            this.ls = ls;
            this.alpha = alpha;
            this.beta = beta;
            this.tangent = tangent;
            this.external = external;
        }

        double curveLength() {
            return 2.0 * this.ls + this.radius * this.beta;
        }

        Corner corner() {
            return new Corner(this.anchor, this.v.x, this.v.z, this.radius, this.delta, this.tangent,
                    curveLength(), this.external, this.ls);
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

    /**
     * 单侧缓和段想要的长度：每端吃掉偏角的 1/4，即 Ls = Δ·R/2。
     *
     * <p>不跟半径成固定倍数，而是跟「这个弯要转多少」挂钩：缓弯短、急弯长，
     * 中间那段圆弧永远留下一半偏角。</p>
     */
    public static double easementWanted(double radius, double deflection) {
        return 0.5 * deflection * radius;
    }

    /**
     * 有缓和段时这个弯要吃多长的切线：
     * T = (R + p)·tan(Δ/2) + q，p = Ls²/(24R)，q = Ls/2 − Ls³/(240R²)。
     *
     * <p>T 随 Ls 单调增，所以「腿不够长」时二分找最长的 Ls 就行。</p>
     */
    public static double tangentFor(double radius, double deflection, double clothoidLength) {
        double ls = Math.max(0.0, clothoidLength);
        double tanHalf = Math.tan(deflection / 2.0);
        if (ls <= 0 || radius <= 0) {
            return radius * tanHalf;
        }
        double[] pq = shift(radius, ls);
        return (radius + pq[0]) * tanHalf + pq[1];
    }

    /** 曲率 0→1/R、长 Ls 的回旋线从原点沿 +x 出发时的终点：{沿切线, 垂直}。 */
    private static double[] clothoidEnd(double radius, double ls) {
        int n = Math.max(8, Math.min(512, (int) Math.ceil(ls / CLOTHOID_STEP)));
        double h = ls / n;
        double x = 0.0;
        double y = 0.0;
        for (int i = 1; i <= n; i++) {
            double a = (i - 1) * h;
            double b = i * h;
            double m = 0.5 * (a + b);
            x += h * (Math.cos(phi(a, radius, ls)) + 4.0 * Math.cos(phi(m, radius, ls))
                    + Math.cos(phi(b, radius, ls))) / 6.0;
            y += h * (Math.sin(phi(a, radius, ls)) + 4.0 * Math.sin(phi(m, radius, ls))
                    + Math.sin(phi(b, radius, ls))) / 6.0;
        }
        return new double[]{x, y};
    }

    /** 回旋线那段走向：φ(s) = s²/(2·R·Ls)（曲率从 0 线性长到 1/R 的积分）。 */
    private static double phi(double s, double radius, double ls) {
        return s * s / (2.0 * radius * ls);
    }

    /**
     * 缓和段把后面的圆弧往里推的距离 p、顺切线方向缩的量 q。
     *
     * <p>教科书上写成 p = Ls²/(24R) − Ls⁴/(2688R³)、q = Ls/2 − Ls³/(240R²)，都是截断级数；
     * 这里直接用回旋线端点的积分值算，跟铺出来的线一格不差（级数那版差千分之几格，
     * 累积到线尾就是「总长对不上」）。</p>
     */
    private static double[] shift(double radius, double ls) {
        double alpha = ls / (2.0 * radius);
        double[] end = clothoidEnd(radius, ls);
        double p = end[1] - radius * (1.0 - Math.cos(alpha));
        double q = end[0] - radius * Math.sin(alpha);
        return new double[]{p, q};
    }

    /** 线形里有没有缓和段（给界面判断用）。 */
    public boolean hasClothoid() {
        for (Piece p : this.pieces) {
            if (p.kind == Kind.CLOTHOID) {
                return true;
            }
        }
        return false;
    }

    public static Alignment of(List<Vec2> points, double wantedRadius) {
        return of(points, wantedRadius, false);
    }

    /**
     * 自由折线的导线。
     *
     * @param easement 每个弯两端插不插缓和曲线
     */
    public static Alignment of(List<Vec2> points, double wantedRadius, boolean easement) {
        List<Piece> pieces = new ArrayList<>();
        List<Corner> corners = new ArrayList<>();
        int n = points.size();
        if (n < 2) {
            return new Alignment(pieces, corners);
        }

        Bend[] bends = new Bend[n];
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
            // 折角太小（几乎直线）或几乎掉头：不做曲线
            if (delta < 0.002 || delta > Math.PI - 0.002) {
                continue;
            }
            // 切线最多吃掉较短那条边的 49%
            double cap = Math.min(l1, l2) * 0.49;
            Bend b = plan(p1, d1, d2, delta, wantedRadius, cap, easement, i);
            if (b == null) {
                continue;
            }
            bends[i] = b;
        }

        double s = 0.0;
        Vec2 cur = points.get(0);
        for (int i = 1; i < n; i++) {
            Bend b = bends[i];
            if (b == null) {
                continue;
            }
            double lineLen = b.t1.sub(cur).length();
            if (lineLen > EPS) {
                pieces.add(Piece.line(s, lineLen, cur, b.t1, -1));
                s += lineLen;
            }
            cur = appendCurve(pieces, s, b);
            s += b.curveLength();
            corners.add(b.corner());
        }
        Vec2 end = points.get(n - 1);
        double lastLen = end.sub(cur).length();
        if (lastLen > EPS) {
            pieces.add(Piece.line(s, lastLen, cur, end, -1));
        }
        return new Alignment(pieces, corners);
    }

    /**
     * 正交折线（每段都沿 X 或 Z）的导线：拐角一律 90°，按设计半径铺
     * 「直线—缓和曲线—圆弧—缓和曲线—直线」。
     *
     * <p>圆角不靠试算：90° 的切线长在没缓和段时就是半径 R，插了缓和段变成
     * T = R + p + q；两个弯夹着的那截直段要 2T（{@link OrthoRoute} 已经把腿推到够长）。
     * 段短到放不下时按半段夹，先缩缓和段、再缩半径，绝不会让两条切线叠在一起。</p>
     *
     * @param vertices 正交折线的顶点，首尾是端点、中间是拐点
     * @param radius   设计半径
     * @param easement 90° 弯两端插不插缓和曲线
     */
    public static Alignment ofOrthogonal(List<Vec2> vertices, double radius) {
        return ofOrthogonal(vertices, radius, false);
    }

    public static Alignment ofOrthogonal(List<Vec2> vertices, double radius, boolean easement) {
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
        Bend[] bends = new Bend[n];
        for (int k = 1; k < n - 1; k++) {
            if (!bend[k]) {
                continue;
            }
            // 一截直段要是两头都是弯，两个弯各吃一半；只有一头是弯，那个弯可以吃满。
            double capA = bend[k - 1] ? len[k - 1] * 0.5 : len[k - 1];
            double capB = bend[k + 1] ? len[k] * 0.5 : len[k];
            Vec2 d1 = new Vec2(ux[k - 1], uz[k - 1]);
            Vec2 d2 = new Vec2(ux[k], uz[k]);
            double cross = d1.cross(d2);
            double dot = Math.max(-1.0, Math.min(1.0, d1.dot(d2)));
            double delta = Math.atan2(Math.abs(cross), dot);
            if (delta < 0.002) {
                continue;
            }
            bends[k] = plan(vertices.get(k), d1, d2, delta, wanted, Math.min(capA, capB), easement, -1);
        }

        double s = 0.0;
        Vec2 cur = vertices.get(0);
        for (int k = 1; k < n - 1; k++) {
            Vec2 v = vertices.get(k);
            Bend b = bends[k];
            if (b == null) {
                if (!bend[k]) {
                    double dot = len[k - 1] < EPS || len[k] < EPS
                            ? 1.0 : ux[k - 1] * ux[k] + uz[k - 1] * uz[k];
                    if (dot < -0.5) {
                        // 掉头：折回来，不画圆弧，但直段不能越过这个点
                        double straight = v.sub(cur).length();
                        if (straight > EPS) {
                            pieces.add(Piece.line(s, straight, cur, v, -1));
                            s += straight;
                        }
                        cur = v;
                    }
                }
                continue;
            }
            double lineLen = b.t1.sub(cur).length();
            if (lineLen > EPS) {
                pieces.add(Piece.line(s, lineLen, cur, b.t1, -1));
                s += lineLen;
            }
            cur = appendCurve(pieces, s, b);
            s += b.curveLength();
            corners.add(b.corner());
        }
        Vec2 end = vertices.get(n - 1);
        double last = end.sub(cur).length();
        if (last > EPS) {
            pieces.add(Piece.line(s, last, cur, end, -1));
        }
        return new Alignment(pieces, corners);
    }

    /** 算一个折点的线形；放不下（半径 < 0.5）返回 null。 */
    private static Bend plan(Vec2 v, Vec2 d1, Vec2 d2, double delta, double wantedRadius, double cap,
                             boolean easement, int anchor) {
        double tanHalf = Math.tan(delta / 2.0);
        if (!(tanHalf > 1.0E-9) || cap <= EPS) {
            return null;
        }
        double r = Math.min(wantedRadius, cap / tanHalf);
        if (r < 0.5) {
            return null;
        }
        double sign = Math.signum(d1.cross(d2));
        if (sign == 0) {
            sign = 1.0;
        }
        double ls = 0.0;
        if (easement) {
            ls = Math.min(easementWanted(r, delta), 2.0 * EASEMENT_MAX_SHARE * delta * r);
            if (tangentFor(r, delta, ls) > cap) {
                // 腿不够长：二分找最长的缓和段（T 随 Ls 单调增）
                double lo = 0.0;
                double hi = ls;
                for (int i = 0; i < 40; i++) {
                    double mid = 0.5 * (lo + hi);
                    if (tangentFor(r, delta, mid) <= cap) {
                        lo = mid;
                    } else {
                        hi = mid;
                    }
                }
                ls = lo;
            }
            if (ls < MIN_EASEMENT) {
                ls = 0.0;
            }
        }
        double alpha = ls / (2.0 * r);
        double beta = delta - 2.0 * alpha;
        if (beta <= 1.0E-4) {
            ls = 0.0;
            alpha = 0.0;
            beta = delta;
        }
        double tangent = ls > 0 ? tangentFor(r, delta, ls) : r * tanHalf;
        double p = ls > 0 ? shift(r, ls)[0] : 0.0;
        double external = (r + p) / Math.cos(delta / 2.0) - r;
        Vec2 t1 = v.sub(d1.x * tangent, d1.z * tangent);
        Vec2 t2 = v.add(d2.x * tangent, d2.z * tangent);
        return new Bend(v, t1, t2, anchor, Math.atan2(d1.z, d1.x), sign, r, delta, ls, alpha, beta,
                tangent, external);
    }

    /** 铺一个弯：缓和曲线—圆弧—缓和曲线，返回曲线终点。 */
    private static Vec2 appendCurve(List<Piece> pieces, double s0, Bend b) {
        double s = s0;
        Vec2 p = b.t1;
        double heading = b.heading;
        if (b.ls > EPS) {
            double k = b.sign / b.radius;
            Piece c1 = Piece.clothoid(s, b.anchor, b.alpha * b.sign, p, heading, 0, k, b.ls);
            pieces.add(c1);
            s += b.ls;
            p = c1.end();
            heading += b.alpha * b.sign;
        }
        Vec2 center = p.add(-Math.sin(heading) * b.sign * b.radius,
                Math.cos(heading) * b.sign * b.radius);
        double a0 = Math.atan2(p.z - center.z, p.x - center.x);
        double sweep = b.beta * b.sign;
        Piece arc = Piece.arc(s, b.anchor, sweep, center, b.radius, a0, sweep);
        pieces.add(arc);
        s += b.radius * b.beta;
        p = arc.end();
        heading += sweep;
        if (b.ls > EPS) {
            double k = b.sign / b.radius;
            Piece c2 = Piece.clothoid(s, b.anchor, b.alpha * b.sign, p, heading, k, 0, b.ls);
            pieces.add(c2);
            p = c2.end();
        }
        return p;
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
                return p.pointAtLocal(ss - p.startS);
            }
        }
        Piece last = this.pieces.get(this.pieces.size() - 1);
        return last.end();
    }

    /** 沿导线里程 s 处的航向角（弧度，x 轴为 0，逆时针为负）。 */
    public double headingAt(double s) {
        if (this.pieces.isEmpty()) {
            return 0.0;
        }
        double ss = Math.max(0.0, Math.min(this.totalLength, s));
        for (Piece p : this.pieces) {
            if (ss <= p.startS + p.length + EPS) {
                return p.headingAtLocal(ss - p.startS);
            }
        }
        Piece last = this.pieces.get(this.pieces.size() - 1);
        return last.headingAtLocal(last.length);
    }
}
