package com.phzt.railplanner.clearance;

import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 限界里哪一格算「要清」。
 *
 * <p>口径是「实心方块才标」：挡路的才算，草、花、藤、火把、铁轨、告示牌这些本来就
 * 不占满一格，清不清都一样，标出来只会糊满屏幕。</p>
 *
 * <p>几类永远不动：</p>
 * <ul>
 *   <li>空气、水、岩浆——水和岩浆不是满方块（{@code isSolidRender()} 为假），
 *       本来就在下一档里；真把它们挖掉也不会变成空气，反而把水抽干、把岩浆弄流；</li>
 *   <li>不满一格的方块——{@code isSolidRender()} 为假，草花火把铁轨链条都在这一档；</li>
 *   <li>带方块实体的——箱子、熔炉、刷怪笼、告示牌，里面可能有东西，误清代价太大；</li>
 *   <li>道砟（砂砾）和红石块——自己铺的，清掉等于白铺。</li>
 * </ul>
 */
public final class ClearRules {

    /** 自己铺的东西：道砟和红石块。铁轨本身不满一格，已经在上面那一档里排除了。 */
    private static final Set<String> KEEP = Set.of("gravel", "redstone_block");

    private ClearRules() {
    }

    public static boolean clearable(BlockState state) {
        if (state == null || state.isAir()) {
            return false;
        }
        if (!state.isSolidRender()) {
            return false;
        }
        if (state.hasBlockEntity()) {
            return false;
        }
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if (path.contains("rail")) {
            return false;
        }
        return !KEEP.contains(path);
    }
}
