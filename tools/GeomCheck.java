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

        System.out.println(fails == 0 ? "ALL PASS" : ("FAILURES: " + fails));
        if (fails > 0) System.exit(1);
    }
}
