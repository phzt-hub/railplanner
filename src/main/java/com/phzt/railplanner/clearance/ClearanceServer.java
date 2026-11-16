package com.phzt.railplanner.clearance;

import com.phzt.railplanner.RailPlanner;
import com.phzt.railplanner.sync.Dto;
import com.phzt.railplanner.sync.Payloads;
import com.phzt.railplanner.sync.ServerSync;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/**
 * 服务端这一侧：报告「这儿能不能清」，以及在玩家满足条件时真的去清。
 *
 * <p>客户端的「一键清理」按钮只负责发一个请求；<b>所有判据都在服务端重算一遍</b>：
 * 创造模式、OP 权限、装了 WorldEdit、尺寸合法、走廊没超上限。客户端说能清不算数。</p>
 *
 * <p>走行线由客户端发过来（那是它自己画的线），但方块坐标一律服务端用 {@link Corridor}
 * 重算，客户端给的坐标表一概不认。</p>
 */
public final class ClearanceServer {

    private ClearanceServer() {
    }

    public static void handle(ServerPlayNetworking.Context context, ServerPlayer player, int op, String body) {
        switch (op) {
            case Payloads.C2S_CLEARINFO -> info(player);
            case Payloads.C2S_CLEAR -> clear(player, body);
            default -> RailPlanner.LOG.warn("收到未知的限界请求 {}（来自 {}）", op,
                    player.getName().getString());
        }
    }

    /** 服务端装没装 WorldEdit。装没装是启动时就定死的，不用每次问。 */
    public static boolean worldEditLoaded() {
        return FabricLoader.getInstance().isModLoaded("worldedit");
    }

    /** OP 判据用「游戏管理员」这一档（原版 op 等级 2），和 WorldEdit 自己的默认权限门槛一致。 */
    public static boolean isOp(ServerPlayer player) {
        return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    private static void info(ServerPlayer player) {
        Dto.ClearInfo info = new Dto.ClearInfo();
        info.worldedit = worldEditLoaded();
        info.op = isOp(player);
        info.creative = player.isCreative();
        info.maxBlocks = Corridor.MAX_BLOCKS;
        info.server = RailPlanner.version();
        ServerSync.reply(player, Payloads.S2C_CLEARINFO, info);
    }

    private static void clear(ServerPlayer player, String body) {
        String who = player.getName().getString();
        Dto.ClearAck ack = new Dto.ClearAck();

        Dto.Clear req;
        try {
            req = Dto.GSON.fromJson(body, Dto.Clear.class);
        } catch (RuntimeException e) {
            req = null;
        }
        if (req == null || req.xs == null || req.ys == null || req.zs == null) {
            fail(player, ack, "bad_request");
            return;
        }

        // 判据顺序跟着「最容易说清楚的那一条」走：先说是模式不对，再说权限，再说没装插件。
        if (!player.isCreative()) {
            fail(player, ack, "not_creative");
            return;
        }
        if (!isOp(player)) {
            fail(player, ack, "not_op");
            return;
        }
        if (!worldEditLoaded()) {
            fail(player, ack, "no_worldedit");
            return;
        }

        int n = Math.min(Math.min(req.xs.length, req.ys.length), req.zs.length);
        if (n < 2 || n > Corridor.MAX_SPINE || !Corridor.validSize(req.width, req.up, req.down)) {
            fail(player, ack, "bad_request");
            return;
        }

        Corridor corridor = Corridor.of(req.xs, req.ys, req.zs, req.width, req.up, req.down);
        if (corridor.truncated()) {
            // 超上限的走廊不截断着清：说清楚让玩家把范围调小，免得清一半。
            fail(player, ack, "too_many");
            return;
        }

        ServerLevel level = player.level();
        long[] keys = new long[corridor.size()];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int found = 0;
        for (int i = 0; i < corridor.size(); i++) {
            pos.set(corridor.x(i), corridor.y(i), corridor.z(i));
            if (ClearRules.clearable(level.getBlockState(pos))) {
                keys[found++] = Corridor.key(corridor.x(i), corridor.y(i), corridor.z(i));
            }
        }
        ack.found = found;
        if (found == 0) {
            ack.ok = true;
            ack.cleared = 0;
            ServerSync.reply(player, Payloads.S2C_CLEAR, ack);
            RailPlanner.LOG.info("{} 限界清理：{}，走廊 {} 格，没有需要清的方块", who, size(req), corridor.size());
            return;
        }

        int cleared;
        try {
            cleared = WorldEditClear.clear(level, player, keys, found);
        } catch (Throwable t) {
            RailPlanner.LOG.warn("{} 限界清理失败（WorldEdit）：{}", who, t.toString());
            fail(player, ack, "worldedit_failed");
            return;
        }

        ack.ok = true;
        ack.cleared = cleared;
        ServerSync.reply(player, Payloads.S2C_CLEAR, ack);
        RailPlanner.LOG.info("{} 限界清理：{}，走廊 {} 格，命中 {} 格，清掉 {} 格（可 //undo）",
                who, size(req), corridor.size(), found, cleared);
    }

    private static void fail(ServerPlayer player, Dto.ClearAck ack, String reason) {
        ack.ok = false;
        ack.reason = reason;
        ServerSync.reply(player, Payloads.S2C_CLEAR, ack);
        RailPlanner.LOG.info("{} 限界清理被拒：{}", player.getName().getString(), reason);
    }

    private static String size(Dto.Clear req) {
        return req.width + "×" + req.up + "+" + req.down;
    }
}
