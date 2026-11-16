package com.phzt.railplanner.client.world;

import com.phzt.railplanner.client.config.RailConfig;
import net.minecraft.gizmos.GizmoProperties;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;

/**
 * 把限界里「挡路的方块」画成红框。
 *
 * <p>和 3D 线一样走原版 gizmos：{@link Gizmos#cuboid} 塞一个方块大小的立方体，
 * 描边红、填充是半透明红，山体里的那些也看得见。</p>
 *
 * <p>一帧只画最近的一批（{@link #MAX_BOXES} 个）：整条线在范围里可能几千格，
 * 全画出来帧率会掉，而红框本来就是为了「一眼看到哪儿要清」，远处的画了也看不清。
 * 窗口里报的是全量数，不受这个上限影响。</p>
 */
public final class ClearanceGizmos {

    /** 一帧最多画多少个方块（近的先画）。 */
    private static final int MAX_BOXES = 600;
    /** 超过这个距离的不画，格。 */
    private static final double MAX_DIST = 128.0;

    /** 描边红 + 半透明红填充：单看方块轮廓是实心的，透过山体也认得出。 */
    private static final GizmoStyle STYLE = GizmoStyle.strokeAndFill(0xFFFF3B30, 1.0f, 0x33FF3B30);

    private ClearanceGizmos() {
    }

    public static void emit(double camX, double camY, double camZ) {
        if (!RailConfig.get().clearanceOn) {
            return;
        }
        int n = Clearance.count();
        if (n == 0) {
            return;
        }
        boolean onTop = RailConfig.get().worldXray;
        double range2 = MAX_DIST * MAX_DIST;
        int drawn = 0;
        for (int i = 0; i < n && drawn < MAX_BOXES; i++) {
            int x = Clearance.x(i);
            int y = Clearance.y(i);
            int z = Clearance.z(i);
            double dx = x + 0.5 - camX;
            double dz = z + 0.5 - camZ;
            if (dx * dx + dz * dz > range2) {
                continue;
            }
            GizmoProperties box = Gizmos.cuboid(new AABB(x, y, z, x + 1.0, y + 1.0, z + 1.0), STYLE);
            if (onTop) {
                box.setAlwaysOnTop();
            }
            drawn++;
        }
    }
}
