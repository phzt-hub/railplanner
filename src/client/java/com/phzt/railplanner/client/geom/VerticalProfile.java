package com.phzt.railplanner.client.geom;

/**
 * 纵断面：把「每个点钉在自己的标高上」算成每一格的设计标高。
 *
 * <p>纯几何，不碰游戏，所以可以脱离客户端单测（见 {@code tools/VerticalCheck.java}）。</p>
 *
 * <p>点与点之间分两种情况：</p>
 * <ul>
 *   <li><b>高差放得进限坡</b>（|ΔY| ≤ 限坡 × 段长）：在「两头各自按限坡追出去」能到达的区间里
 *       贴着地形走。地形比限坡缓的时候就是原来那条贴地的线；地形起伏时被两头夹住，但不会超限。</li>
 *   <li><b>放不进限坡</b>：两头都是硬钉住的，本来就不可能同时满足限坡，于是按两点之间的直线走
 *       （这一段注定超限，面板上的设计坡会标红）。</li>
 * </ul>
 *
 * <p>没有地形基线（全都没探明）时，整条线都按点与点之间的直线算。</p>
 */
public final class VerticalProfile {

    private VerticalProfile() {
    }

    /**
     * @param cellChain 每一格在走行线上的里程（单调递增）
     * @param pinChain  每个点对应的里程（单调递增，长度 = 点数）
     * @param pinY      每个点要求的标高
     * @param base      地形基线（可为 null = 完全没数据）
     * @param maxGrade  限坡，小数（0.08 = 8%）
     * @param out       输出：每一格的设计标高
     */
    public static void apply(float[] cellChain, double[] pinChain, float[] pinY, float[] base,
                             double maxGrade, float[] out) {
        int n = Math.min(cellChain.length, out.length);
        int count = pinChain.length;
        if (n <= 0 || count == 0) {
            java.util.Arrays.fill(out, 0.0f);
            return;
        }
        if (count == 1) {
            java.util.Arrays.fill(out, pinY[0]);
            return;
        }
        double g = Math.max(1.0E-6, maxGrade);
        int seg = 0;
        for (int i = 0; i < n; i++) {
            double s = cellChain[i];
            while (seg + 2 < count && pinChain[seg + 1] <= s) {
                seg++;
            }
            double sA = pinChain[seg];
            double sB = pinChain[seg + 1];
            double yA = pinY[seg];
            double yB = pinY[seg + 1];
            double span = sB - sA;
            double k = span <= 1.0E-9 ? 0.0 : (s - sA) / span;
            k = Math.max(0.0, Math.min(1.0, k));
            boolean feasible = Math.abs(yB - yA) <= g * span + 1.0E-6;
            if (base == null || !feasible) {
                out[i] = (float) (yA + (yB - yA) * k);
            } else {
                double lo = Math.max(yA - g * (s - sA), yB - g * (sB - s));
                double hi = Math.min(yA + g * (s - sA), yB + g * (sB - s));
                out[i] = (float) Math.max(lo, Math.min(hi, base[i]));
            }
        }
    }
}
