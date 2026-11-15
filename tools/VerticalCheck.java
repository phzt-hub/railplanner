import com.phzt.railplanner.client.geom.VerticalProfile;
import java.util.*;

/** 纵断面自检：钉点、限坡、直线兜底，不启游戏。 */
public class VerticalCheck {
    static int fails = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + "   " + detail);
        if (!ok) fails++;
    }

    /** 第 i 格的坡（%），i>0。 */
    static double grade(float[] d, float[] chain, int i) {
        double dz = chain[i] - chain[i - 1];
        return dz <= 0 ? 0 : Math.abs(d[i] - d[i - 1]) / dz * 100.0;
    }

    static float[] chain(int n) {
        float[] c = new float[n];
        for (int i = 0; i < n; i++) {
            c[i] = i;
        }
        return c;
    }

    public static void main(String[] a) {
        float[] cell = chain(100);

        // 1. 两端 100 → 95（Δ=5 ≤ 8% × 99），地形平在 97：贴地形，两端精确，全程不超限
        float[] base = new float[100];
        Arrays.fill(base, 97.0f);
        float[] out = new float[100];
        VerticalProfile.apply(cell, new double[]{0, 99}, new float[]{100, 95}, base, 0.08, out);
        check("可行段端点起点", Math.abs(out[0] - 100) < 1e-3, "d[0]=" + out[0]);
        check("可行段端点终点", Math.abs(out[99] - 95) < 1e-3, "d[99]=" + out[99]);
        double maxG = 0;
        for (int i = 1; i < 100; i++) {
            maxG = Math.max(maxG, grade(out, cell, i));
        }
        check("可行段不超限坡", maxG <= 8.0 + 1e-3, String.format("max=%.3f%%", maxG));
        // 地形 97 只有在中段才落在两头按限坡追出来的区间里，两头要贴着限坡的边（"追上去"）
        boolean followsTerrain = true;
        for (int i = 40; i <= 70; i++) {
            if (Math.abs(out[i] - 97.0f) > 1e-3) {
                followsTerrain = false;
                break;
            }
        }
        check("可行段中段贴地形", followsTerrain, "d[50]=" + out[50]);
        check("可行段开头被限坡夹住", Math.abs(out[10] - 99.2f) < 1e-3, "d[10]=" + out[10] + "（期望 99.2）");

        // 2. 两端 120 → 67（Δ=53 > 8% × 99）：直线，坡恒定，两端精确
        VerticalProfile.apply(cell, new double[]{0, 99}, new float[]{120, 67}, base, 0.08, out);
        double chord = 53.0 / 99.0 * 100.0;
        boolean flat = true;
        for (int i = 1; i < 100; i++) {
            if (Math.abs(grade(out, cell, i) - chord) > 1e-2) {
                flat = false;
                break;
            }
        }
        check("超限段走直线", flat, String.format("每格 %.2f%%（期望 %.2f%%）", grade(out, cell, 1), chord));
        check("超限段端点", Math.abs(out[0] - 120) < 1e-3 && Math.abs(out[99] - 67) < 1e-3,
                "d[0]=" + out[0] + " d[99]=" + out[99]);

        // 3. 三个点：0/40/99，标高 100/97/94，段内都放得进限坡（3 ≤ 3.2、3 ≤ 4.72）
        VerticalProfile.apply(cell, new double[]{0, 40, 99}, new float[]{100, 97, 94}, base, 0.08, out);
        check("三点中间点命中", Math.abs(out[40] - 97) < 1e-3, "d[40]=" + out[40]);
        check("三点两端命中", Math.abs(out[0] - 100) < 1e-3 && Math.abs(out[99] - 94) < 1e-3,
                "d[0]=" + out[0] + " d[99]=" + out[99]);
        double maxG3 = 0;
        for (int i = 1; i < 100; i++) {
            maxG3 = Math.max(maxG3, grade(out, cell, i));
        }
        check("三点不超限坡", maxG3 <= 8.0 + 1e-3, String.format("max=%.3f%%", maxG3));

        // 4. 没有地形基线：全按直线
        VerticalProfile.apply(cell, new double[]{0, 99}, new float[]{100, 95}, null, 0.08, out);
        double expect = 100.0 - 5.0 * 50 / 99.0;
        check("无地形按直线", Math.abs(out[50] - expect) < 1e-3, "d[50]=" + out[50] + "（期望 " + expect + "）");

        // 5. 单点 / 空输入不炸
        VerticalProfile.apply(cell, new double[]{0}, new float[]{80}, base, 0.08, out);
        check("单点", out[0] == 80f && out[99] == 80f, "d=" + out[0]);
        VerticalProfile.apply(new float[0], new double[]{0}, new float[]{80}, base, 0.08, new float[0]);
        check("空输入", true, "no throw");

        System.out.println(fails == 0 ? "RESULT ok" : "RESULT " + fails + " failed");
        if (fails > 0) {
            System.exit(1);
        }
    }
}
