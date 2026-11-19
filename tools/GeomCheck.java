import com.phzt.railplanner.client.geom.*;
import java.util.*;

public class GeomCheck {
    static int fails = 0;
    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + "   " + detail);
        if (!ok) fails++;
    }
    public static void main(String[] a) {
        // 1. 直线
        Alignment line = Alignment.of(List.of(new Vec2(0.5, 0.5), new Vec2(100.5, 0.5)), 24);
        check("直线长度", Math.abs(line.length() - 100.0) < 1e-6, "len=" + line.length());
        BlockPath p1 = Rasterizer.rasterize(line);
        check("直线格数", p1.size() == 101, "cells=" + p1.size() + " (101 格 = 100 格长度 + 起点)");

        // 2. 90 度右转，R=20
        Alignment arc = Alignment.of(List.of(new Vec2(0.5, 0.5), new Vec2(100.5, 0.5), new Vec2(100.5, 100.5)), 20);
        double expectL = 80 + Math.PI / 2 * 20 + 80;
        check("90°曲线Δ", arc.corners().size() == 1 && Math.abs(Math.toDegrees(arc.corners().get(0).deflection) - 90) < 1e-3,
                "Δ=" + Math.toDegrees(arc.corners().get(0).deflection));
        check("切线长T", Math.abs(arc.corners().get(0).tangent - 20) < 1e-6, "T=" + arc.corners().get(0).tangent);
        check("曲线长L", Math.abs(arc.corners().get(0).arcLength - Math.PI / 2 * 20) < 1e-6, "L=" + arc.corners().get(0).arcLength);
        check("外距E", Math.abs(arc.corners().get(0).external - 20 * (Math.sqrt(2) - 1)) < 1e-6, "E=" + arc.corners().get(0).external);
        check("总长", Math.abs(arc.length() - expectL) < 1e-6, "len=" + arc.length() + " expect=" + expectL);

        // 3. 像素化连续性
        BlockPath p2 = Rasterizer.rasterize(arc);
        boolean contiguous = true;
        int maxStep = 0;
        for (int i = 1; i < p2.size(); i++) {
            int dx = Math.abs(p2.xs[i] - p2.xs[i - 1]);
            int dz = Math.abs(p2.zs[i] - p2.zs[i - 1]);
            maxStep = Math.max(maxStep, Math.max(dx, dz));
            if (dx > 1 || dz > 1) contiguous = false;
        }
        check("像素化8邻接", contiguous, "cells=" + p2.size() + " maxStep=" + maxStep);
        double pathLen = 0;
        for (int i = 1; i < p2.size(); i++) pathLen += Math.hypot(p2.xs[i] - p2.xs[i - 1], p2.zs[i] - p2.zs[i - 1]);
        check("像素化长度接近几何长度", Math.abs(pathLen - arc.length()) / arc.length() < 0.05,
                "path=" + String.format("%.2f", pathLen) + " geom=" + String.format("%.2f", arc.length()));

        // 4. 半径被线段长度钳制
        Alignment tight = Alignment.of(List.of(new Vec2(0.5, 0.5), new Vec2(20.5, 0.5), new Vec2(20.5, 20.5)), 100);
        check("半径被钳制", tight.corners().get(0).radius < 20 && tight.corners().get(0).radius > 0,
                "R=" + tight.corners().get(0).radius);

        // 5. 左转（对称）
        Alignment left = Alignment.of(List.of(new Vec2(0.5, 100.5), new Vec2(100.5, 100.5), new Vec2(100.5, 0.5)), 20);
        check("左转同样成立", left.corners().size() == 1 && Math.abs(left.length() - expectL) < 1e-6, "len=" + left.length());

        // 6. 三点几乎共线：不做曲线
        Alignment collinear = Alignment.of(List.of(new Vec2(0.5, 0.5), new Vec2(50.5, 0.5), new Vec2(100.5, 0.5)), 20);
        check("共线不出曲线", collinear.corners().isEmpty() && Math.abs(collinear.length() - 100) < 1e-6, "len=" + collinear.length());

        // 7. 起点终点落格
        check("起点格", p2.xs[0] == 0 && p2.zs[0] == 0, "(" + p2.xs[0] + "," + p2.zs[0] + ")");
        check("终点格", p2.xs[p2.size() - 1] == 100 && p2.zs[p2.size() - 1] == 100, "(" + p2.xs[p2.size() - 1] + "," + p2.zs[p2.size() - 1] + ")");

        // 8. 正交化：斜的一段 → 一个 90° 弯，端点不动
        OrthoRoute.Result o1 = OrthoRoute.build(List.of(new Vec2(0.5, 0.5), new Vec2(100.5, 40.5)), 24);
        check("正交顶点数", o1.points().size() == 3, "pts=" + o1.points().size());
        check("正交全是沿轴", axisOnly(o1.points()), "pts=" + o1.points().size());
        check("正交终点不动", Math.abs(o1.points().get(2).x - 100.5) < 1e-9
                && Math.abs(o1.points().get(2).z - 40.5) < 1e-9, "end=" + o1.points().get(2).x + "," + o1.points().get(2).z);
        check("正交不外推", o1.pushed() < 1e-9, "pushed=" + o1.pushed());
        Alignment a1 = Alignment.ofOrthogonal(o1.points(), 24);
        double expectO1 = (100 - 24) + Math.PI / 2 * 24 + (40 - 24);
        check("正交总长", Math.abs(a1.length() - expectO1) < 1e-6, "len=" + a1.length() + " expect=" + expectO1);
        check("正交半径不缩", Math.abs(a1.corners().get(0).radius - 24) < 1e-9, "R=" + a1.corners().get(0).radius);
        check("正交拐角都是90°", Math.abs(Math.toDegrees(a1.corners().get(0).deflection) - 90) < 1e-9,
                "Δ=" + Math.toDegrees(a1.corners().get(0).deflection));
        BlockPath p3 = Rasterizer.rasterize(a1);
        check("正交像素化8邻接", contiguous(p3), "cells=" + p3.size());

        // 9. 太短的一段：把端点往外推，半径照样是 24
        OrthoRoute.Result o2 = OrthoRoute.build(List.of(new Vec2(0.5, 0.5), new Vec2(10.5, 5.5)), 24);
        Alignment a2 = Alignment.ofOrthogonal(o2.points(), 24);
        check("正交外推后半径不缩", Math.abs(a2.corners().get(0).radius - 24) < 1e-9, "R=" + a2.corners().get(0).radius);
        check("正交外推有记录", o2.pushed() > 1, "pushed=" + String.format("%.1f", o2.pushed()));
        check("正交外推后全沿轴", axisOnly(o2.points()), "pts=" + o2.points().size());

        // 10. 本来就沿轴：不插拐点，长度等于折线长
        OrthoRoute.Result o3 = OrthoRoute.build(List.of(new Vec2(0.5, 0.5), new Vec2(100.5, 0.5), new Vec2(100.5, 100.5)), 24);
        check("正交不插多余拐点", o3.points().size() == 3 && o3.bends() == 1, "pts=" + o3.points().size());
        Alignment a3 = Alignment.ofOrthogonal(o3.points(), 24);
        double expectO3 = 2 * (100 - 24) + Math.PI / 2 * 24;
        check("正交单弯总长", Math.abs(a3.length() - expectO3) < 1e-6, "len=" + a3.length());

        // 11. 两个弯共用一截：两腿都够，半径都不缩
        OrthoRoute.Result o4 = OrthoRoute.build(List.of(new Vec2(0.5, 0.5), new Vec2(100.5, 60.5), new Vec2(220.5, 60.5)), 24);
        Alignment a4 = Alignment.ofOrthogonal(o4.points(), 24);
        boolean radiiOk = true;
        for (Alignment.Corner c : a4.corners()) radiiOk &= Math.abs(c.radius - 24) < 1e-9;
        check("正交两弯半径", a4.corners().size() == 2 && radiiOk, "corners=" + a4.corners().size());
        check("正交连续", contiguous(Rasterizer.rasterize(a4)), "cells=" + Rasterizer.rasterize(a4).size());

        // 12. 缓和曲线：90° 弯铺成 直线—回旋线—圆弧—回旋线—直线
        Alignment e1 = Alignment.of(List.of(new Vec2(0.5, 0.5), new Vec2(100.5, 0.5), new Vec2(100.5, 100.5)), 20, true);
        double ls = Alignment.easementWanted(20, Math.PI / 2);
        Alignment.Corner ec = e1.corners().get(0);
        check("缓和段长", Math.abs(ec.clothoid - ls) < 1e-6, "Ls=" + f(ec.clothoid) + " 期望 " + f(ls));
        check("线形是缓-圆-缓", "L,C,A,C,L".equals(kinds(e1)), kinds(e1));
        check("切线长按缓圆缓算", Math.abs(ec.tangent - Alignment.tangentFor(20, Math.PI / 2, ls)) < 1e-6,
                "T=" + f(ec.tangent) + " 期望 " + f(Alignment.tangentFor(20, Math.PI / 2, ls)));
        check("回旋线参数A", Math.abs(ec.clothoidParam() - Math.sqrt(20 * ls)) < 1e-9, "A=" + f(ec.clothoidParam()));
        double alphaE = ls / (2 * 20);
        double expectCurve = 2 * ls + 20 * (Math.PI / 2 - 2 * alphaE);
        check("曲线长=2Ls+R(Δ-2α)", Math.abs(ec.arcLength - expectCurve) < 1e-6,
                "L=" + f(ec.arcLength) + " 期望 " + f(expectCurve));
        double expectTotal = 2 * (100 - ec.tangent) + expectCurve;
        check("总长", Math.abs(e1.length() - expectTotal) < 2e-3, "len=" + f(e1.length()) + " 期望 " + f(expectTotal));
        check("缓和后端点仍落格", e1.pointAt(e1.length()).sub(new Vec2(100.5, 100.5)).length() < 1e-6,
                "end=" + f(e1.pointAt(e1.length()).x) + "," + f(e1.pointAt(e1.length()).z));
        check("缓和后位置连续", maxGap(e1) < 1e-4, "maxGap=" + maxGap(e1));
        check("缓和后切线连续", maxHeadingJump(e1) < 1e-4, "maxJump=" + maxHeadingJump(e1));

        // 13. 回旋线的位置跟独立积分对得上（Simpson 表 vs 20 万步中点法）
        Alignment.Piece cp = firstKind(e1, Alignment.Kind.CLOTHOID);
        Vec2 cpStart = e1.pointAt(cp.startS);
        Vec2 brute = integrate(cpStart.x, cpStart.z, cp.h0, cp.k0, cp.k1, cp.length);
        Vec2 got = e1.pointAt(cp.startS + cp.length);
        check("回旋线形状可信", brute.sub(got).length() < 1e-3, "偏差 " + f(brute.sub(got).length()) + " 格");

        // 14. 腿太短：缓和段自动缩短，圆弧还在（不能缩没）
        Alignment e2 = Alignment.of(List.of(new Vec2(0.5, 0.5), new Vec2(70.5, 0.5), new Vec2(70.5, 70.5)), 25, true);
        Alignment.Corner sc = e2.corners().get(0);
        double cap = 70 * 0.49;
        double want25 = Alignment.easementWanted(25, Math.PI / 2);
        check("余量不足时缓和段被夹", sc.clothoid > 1 && sc.clothoid < want25 - 0.5,
                "Ls=" + f(sc.clothoid) + " 想要 " + f(want25));
        check("短腿切线不超预算", sc.tangent <= cap + 1e-6, "T=" + f(sc.tangent) + " cap=" + f(cap));
        check("短腿仍是缓-圆-缓", "L,C,A,C,L".equals(kinds(e2)), kinds(e2));

        // 14b. 腿短到连半径都吃满：先缩半径，没有余量就不插缓和段（半径优先）
        Alignment e4 = Alignment.of(List.of(new Vec2(0.5, 0.5), new Vec2(30.5, 0.5), new Vec2(30.5, 30.5)), 20, true);
        Alignment.Corner clamped = e4.corners().get(0);
        check("吃满时半径先缩", Math.abs(clamped.radius - 14.7) < 1e-6, "R=" + f(clamped.radius));
        check("没余量就不插缓和段", clamped.clothoid == 0, "Ls=" + f(clamped.clothoid));
        check("没缓和段也能铺", !e4.pieces().isEmpty() && maxGap(e4) < 1e-3, "pieces=" + e4.pieces().size());

        // 15. 正交布线 + 缓和：半径 24，切线按缓圆缓加长
        OrthoRoute.Result o5 = OrthoRoute.build(List.of(new Vec2(0.5, 0.5), new Vec2(300.5, 300.5)), 24, true);
        Alignment a5 = Alignment.ofOrthogonal(o5.points(), 24, true);
        Alignment.Corner oc = a5.corners().get(0);
        double tol90 = Math.PI / 2;
        double ls90 = Alignment.easementWanted(24, tol90);
        check("正交缓和半径不变", Math.abs(oc.radius - 24) < 1e-9, "R=" + f(oc.radius));
        check("正交缓和段长", Math.abs(oc.clothoid - ls90) < 1e-6, "Ls=" + f(oc.clothoid) + " 期望 " + f(ls90));
        check("正交外推为零", o5.pushed() < 1e-9, "pushed=" + f(o5.pushed()));
        check("正交缓和端点落格", a5.pointAt(a5.length()).sub(new Vec2(300.5, 300.5)).length() < 1e-6,
                "end=" + f(a5.pointAt(a5.length()).x) + "," + f(a5.pointAt(a5.length()).z));
        check("正交缓和连续", maxGap(a5) < 1e-4 && maxHeadingJump(a5) < 1e-4,
                "gap=" + maxGap(a5) + " jump=" + maxHeadingJump(a5));
        BlockPath p5 = Rasterizer.rasterize(a5);
        check("正交缓和像素化8邻接", contiguous(p5), "cells=" + p5.size());
        double pathLen5 = 0;
        for (int i = 1; i < p5.size(); i++) pathLen5 += Math.hypot(p5.xs[i] - p5.xs[i - 1], p5.zs[i] - p5.zs[i - 1]);
        check("像素化长度接近", Math.abs(pathLen5 - a5.length()) / a5.length() < 0.05,
                "path=" + f(pathLen5) + " geom=" + f(a5.length()));

        // 16. 关掉缓和曲线 == 老行为（同一条线两种算法只差线形）
        Alignment e3 = Alignment.of(List.of(new Vec2(0.5, 0.5), new Vec2(100.5, 0.5), new Vec2(100.5, 100.5)), 20, false);
        check("关掉后没有回旋线", !"L,C,A,C,L".equals(kinds(e3)) && e3.corners().get(0).clothoid == 0,
                kinds(e3));

        System.out.println(fails == 0 ? "ALL PASS" : ("FAILURES: " + fails));
        if (fails > 0) System.exit(1);
    }

    static String f(double v) {
        return String.format("%.4f", v);
    }

    /** 线形的种类串：L 直线 / C 回旋线 / A 圆弧。 */
    static String kinds(Alignment al) {
        StringBuilder sb = new StringBuilder();
        for (Alignment.Piece p : al.pieces()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(p.kind == Alignment.Kind.LINE ? "L" : p.kind == Alignment.Kind.ARC ? "A" : "C");
        }
        return sb.toString();
    }

    static Alignment.Piece firstKind(Alignment al, Alignment.Kind kind) {
        for (Alignment.Piece p : al.pieces()) {
            if (p.kind == kind) return p;
        }
        throw new IllegalStateException("没有 " + kind);
    }

    /**
     * 接缝处的位置跳变：跨过接缝走 2e-4 格，两点就该正好隔 2e-4 格（单位速度参数化）。
     * 差得远就说明接缝断开或者里程跳变。
     */
    static double maxGap(Alignment al) {
        double worst = 0;
        for (int i = 1; i < al.pieces().size(); i++) {
            double s = al.pieces().get(i).startS;
            double d = al.pointAt(s - 1e-4).sub(al.pointAt(s + 1e-4)).length();
            worst = Math.max(worst, Math.abs(d - 2e-4));
        }
        return worst;
    }

    /** 相邻两段接缝处的最大走向跳变（C1 连续就该是 0）。 */
    static double maxHeadingJump(Alignment al) {
        double worst = 0;
        for (int i = 1; i < al.pieces().size(); i++) {
            double s = al.pieces().get(i).startS;
            worst = Math.max(worst, Math.abs(al.headingAt(s - 1e-4) - al.headingAt(s + 1e-4)));
        }
        return worst;
    }

    /** 独立的数值积分（20 万步中点法），用来校验回旋线采样表。 */
    static Vec2 integrate(double x0, double z0, double h0, double k0, double k1, double length) {
        int n = 200000;
        double h = length / n;
        double x = x0;
        double z = z0;
        for (int i = 0; i < n; i++) {
            double s = (i + 0.5) * h;
            double phi = h0 + k0 * s + (k1 - k0) * s * s / (2 * length);
            x += Math.cos(phi) * h;
            z += Math.sin(phi) * h;
        }
        return new Vec2(x, z);
    }

    /** 折线每一段是不是都严格沿 X 或 Z。 */
    static boolean axisOnly(List<Vec2> pts) {
        for (int i = 0; i + 1 < pts.size(); i++) {
            double dx = Math.abs(pts.get(i + 1).x - pts.get(i).x);
            double dz = Math.abs(pts.get(i + 1).z - pts.get(i).z);
            if (dx > 1e-6 && dz > 1e-6) return false;
        }
        return true;
    }

    /** 8 邻接：相邻两格不跳格。 */
    static boolean contiguous(BlockPath p) {
        for (int i = 1; i < p.size(); i++) {
            if (Math.abs(p.xs[i] - p.xs[i - 1]) > 1 || Math.abs(p.zs[i] - p.zs[i - 1]) > 1) return false;
        }
        return true;
    }
}
