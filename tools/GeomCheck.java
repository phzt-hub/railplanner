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

        System.out.println(fails == 0 ? "ALL PASS" : ("FAILURES: " + fails));
        if (fails > 0) System.exit(1);
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
