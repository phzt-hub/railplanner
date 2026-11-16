package com.phzt.railplanner.clearance;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.coremc.CoreMcAdapter;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 走 WorldEdit 的那一侧：把限界里的方块切成空气，并记进这个玩家自己的历史（{@code //undo} 能撤回）。
 *
 * <p>这个类只有在服务端装了 WorldEdit 时才会被加载——{@link ClearanceServer} 先用
 * {@code FabricLoader.isModLoaded("worldedit")} 挡住，再进来，所以没装 WorldEdit 的
 * 服务端不会因为这里缺类而崩。</p>
 *
 * <p>用的是 {@link EditSession}：一次编辑一个会话，改完 {@code close()} 落盘，
 * 再 {@code remember()} 交给玩家会话，这样 {@code //undo} 撤的就是这一整次清理。</p>
 */
final class WorldEditClear {

    private WorldEditClear() {
    }

    /**
     * @return 真正改掉的格数（跟传进来的数量可能有差：已经是空气的那些 setBlock 返回 false）
     */
    static int clear(ServerLevel level, ServerPlayer player, long[] keys, int count) {
        CoreMcAdapter adapter = FabricAdapter.get();
        // 先建好玩家那一侧的会话对象：EditSession 要它来记「是谁改的」，历史也挂在它上面。
        com.sk89q.worldedit.entity.Player wePlayer = adapter.fromNativePlayer(player);
        LocalSession local = WorldEdit.getInstance().getSessionManager().get(wePlayer);

        EditSession session = WorldEdit.getInstance().newEditSessionBuilder()
                .world(adapter.fromNativeWorld(level))
                .actor(wePlayer)
                // 留一点余量：上限比要改的多几个，免得刚好卡在边界上抛 MaxChangedBlocks。
                .maxBlocks(count + 64)
                .build();

        int done = 0;
        try {
            for (int i = 0; i < count; i++) {
                BlockVector3 at = BlockVector3.at(
                        Corridor.keyX(keys[i]), Corridor.keyY(keys[i]), Corridor.keyZ(keys[i]));
                if (session.setBlock(at, BlockTypes.AIR.getDefaultState())) {
                    done++;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            // 先落盘再记历史：顺序反过来的话 //undo 拿到的可能是还没写完的会话。
            session.close();
            local.remember(session);
        }
        return done;
    }
}
