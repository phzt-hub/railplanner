package com.phzt.railplanner.client.world;

import com.phzt.railplanner.client.config.RailConfig;
import com.phzt.railplanner.client.gui.PointEditScreen;
import com.phzt.railplanner.client.model.Anchor;
import com.phzt.railplanner.client.model.RailRoute;
import com.phzt.railplanner.client.planner.Planner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 世界里「拿着指定物品右键点一个点」：准星射线扫过当前那条线上的锚点，命中了就照手里的物品办事——
 * 铁轨（{@code poiEditItem}）弹编辑框，锁链（{@code poiLockItem}）把这一点的锁翻过来。
 *
 * <p>锚点在世界里不是实体也不是方块，原版的 {@code pick} 根本看不到它，所以这里自己算：
 * 每个点在世界里是一根竖针（和 {@link RouteGizmos#emitAnchors} 画的是同一个东西），
 * 取视线到这根针的最近距离，横向在 {@link #RADIUS} 格以内、沿视线不超过 {@link #REACH}
 * 就算点中，几个点都在准星附近时取沿视线最近的那个。</p>
 *
 * <p>命中时要「吞掉」这次右键：两个 Fabric 回调都是 {@code MultiPlayerGameMode} 里
 * 调用的（客户端），返回 {@link InteractionResult#FAIL} 之后原版就不会再发
 * {@code ServerboundUseItemOnPacket}，铁轨也就不会放下去、锁链也不会挂上去；没命中一律返回
 * {@link InteractionResult#PASS}，右键还是原版那个右键。</p>
 *
 * <p>两个物品万一配成同一个 id，锁链优先——先按「切锁」理解，不然配错一次就再也解不开锁了。</p>
 */
public final class PointPick {

    /** 伸手距离，格：和原版放方块一个口径，免得出现隔着半张地图改线。 */
    public static final double REACH = 6.0;
    /** 准星到点那根竖针的横向容差，格。针只有一格宽，太严就点不中。 */
    public static final double RADIUS = 0.8;
    /** 竖针从轨面往下留的一段，格。 */
    private static final double DOWN = 1.0;
    /** 竖针从轨面往上留的一段，格。 */
    private static final double UP = 2.0;

    private PointPick() {
    }

    /**
     * 右键回调的总入口：主手拿着触发物品、屏幕没开、命中了点，就按物品办事并吞掉这次交互。
     *
     * <p>客户端和服务端都会走到这里（同一个 API），服务端那次直接放行——编辑是纯客户端的事，
     * 改完的数据照原来的路径同步。</p>
     */
    public static InteractionResult onUse(Level level, Player player, InteractionHand hand) {
        if (level == null || !level.isClientSide() || hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        if (!(player instanceof LocalPlayer local)) {
            return InteractionResult.PASS;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != local || mc.gui.screen() != null) {
            return InteractionResult.PASS;
        }
        RailConfig config = RailConfig.get();
        // 「显示已选的点」关掉之后点根本不画出来，就别还能点到它了。
        if (!config.worldAnchors || config.hotkeysOff) {
            return InteractionResult.PASS;
        }
        // 锁链不受「拿物品右键改点」那个开关管：关掉改点是为了别手滑开了编辑框，
        // 但要还能把已经锁住的点解开，所以切锁这条路一直留着。
        boolean chain = holds(local, config.lockItemId());
        if (!chain && (!config.poiEdit || !holds(local, config.poiItemId()))) {
            return InteractionResult.PASS;
        }
        RailRoute route = Planner.displayRoute();
        int index = pick(route, local);
        if (index < 0) {
            return InteractionResult.PASS;
        }
        if (chain) {
            Planner.toggleAnchorLock(route, index);
            return InteractionResult.FAIL;
        }
        PointEditScreen.open(route, index);
        return InteractionResult.FAIL;
    }

    /** 准星当下指着的那个点（世界里画高亮用），没指着就是 -1。 */
    public static int aimed(RailRoute route) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gui.screen() != null) {
            return -1;
        }
        RailConfig config = RailConfig.get();
        if (!config.worldAnchors || config.hotkeysOff || !holdingTrigger(config, mc.player)) {
            return -1;
        }
        return pick(route, mc.player);
    }

    /** 手里拿的是不是两个触发物品之一（编辑框那个看 poiEdit 开没开）。 */
    private static boolean holdingTrigger(RailConfig config, Player player) {
        return holds(player, config.lockItemId())
                || (config.poiEdit && holds(player, config.poiItemId()));
    }

    /** 手里那份是不是指定物品 id。 */
    private static boolean holds(Player player, String itemId) {
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) {
            return false;
        }
        String id = String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        return id.equals(itemId);
    }

    /** 视线命中的点，没有就 -1。 */
    public static int pick(RailRoute route, Player player) {
        if (route == null || route.anchors.isEmpty()) {
            return -1;
        }
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 view = player.getViewVector(1.0f).normalize();
        int best = -1;
        double bestAlong = Double.MAX_VALUE;
        for (int i = 0; i < route.anchors.size(); i++) {
            Anchor a = route.anchors.get(i);
            // 轨面在方块顶面之上，和 3D 里画线同一个口径：标高 + 1。
            double x = a.x() + 0.5;
            double z = a.z() + 0.5;
            double y0 = a.y() + 1.0 - DOWN;
            double y1 = a.y() + 1.0 + UP;
            double[] hit = nearest(eye, view, x, y0, z, y1);
            if (hit == null) {
                continue;
            }
            if (hit[0] > REACH || hit[1] > RADIUS) {
                continue;
            }
            if (hit[0] < bestAlong) {
                bestAlong = hit[0];
                best = i;
            }
        }
        return best;
    }

    /**
     * 视线与一根竖针的最近点：{沿视线的距离, 两点间的距离}。
     *
     * <p>解的是「射线 P + s·d」和「线段 Q + t·(0,1,0)」这一对的最短距离，先把 s 卡在
     * [0, {@link #REACH}]、t 卡在 [0, 针长]，再量两个点之间的距离——够用，也不用引入
     * 整套 AABB 求交。</p>
     */
    private static double[] nearest(Vec3 eye, Vec3 dir, double x, double y0, double z, double y1) {
        double len = y1 - y0;
        if (len <= 1e-6) {
            return null;
        }
        double rx = eye.x - x;
        double ry = eye.y - y0;
        double rz = eye.z - z;
        // 竖针方向是 (0,1,0)，所以 d·e 就是 dir.y，e·r 就是 ry。
        double b = dir.y;
        double dd = dir.x * rx + dir.y * ry + dir.z * rz;
        double ee = ry;
        double denom = 1.0 - b * b;
        double s = denom < 1e-6 ? 0.0 : (ee * b - dd) / denom;
        if (s < 0) {
            // 在身后：不算点中，否则站着不动时脚下的点会一直挡在准星上。
            return null;
        }
        double along = Math.min(s, REACH);
        double t = ee + along * b;
        if (t < 0) {
            t = 0;
        } else if (t > len) {
            t = len;
        }
        double px = eye.x + dir.x * along;
        double py = eye.y + dir.y * along;
        double pz = eye.z + dir.z * along;
        double qy = y0 + t;
        double dx = px - x;
        double dy = py - qy;
        double dz = pz - z;
        return new double[]{along, Math.sqrt(dx * dx + dy * dy + dz * dz)};
    }
}
