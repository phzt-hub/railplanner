package com.phzt.railplanner.sync;

import com.phzt.railplanner.RailPlanner;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/**
 * 服务端三个处理器：握手、拉取、推送。
 *
 * <p>全部在服务端主线程上跑（Fabric 的 play 通道就是这样派发的），落盘文件很小，
 * 不值得为它开线程池——开线程反而要和存档保存抢锁。</p>
 */
public final class ServerSync {

    private ServerSync() {
    }

    public static void handle(Payloads.C2S payload, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        try {
            switch (payload.op()) {
                case Payloads.C2S_HELLO -> hello(context, player);
                case Payloads.C2S_PULL -> pull(context, player, payload.body());
                case Payloads.C2S_PUSH -> push(context, player, payload.body());
                case Payloads.C2S_CLEARINFO, Payloads.C2S_CLEAR ->
                        com.phzt.railplanner.clearance.ClearanceServer.handle(context, player, payload.op(),
                                payload.body());
                default -> RailPlanner.LOG.warn("收到未知同步操作 {}（来自 {}）", payload.op(), player.getName().getString());
            }
        } catch (Throwable t) {
            RailPlanner.LOG.warn("处理同步请求失败（{}）: {}", player.getName().getString(), t.toString());
            Dto.Ack ack = new Dto.Ack();
            ack.ok = false;
            reply(player, Payloads.S2C_ACK, ack);
        }
    }

    private static void hello(ServerPlayNetworking.Context context, ServerPlayer player) {
        Dto.HelloAck ack = new Dto.HelloAck();
        ack.sync = true;
        ack.proto = Dto.PROTO;
        ack.server = RailPlanner.version();
        reply(player, Payloads.S2C_HELLO, ack);
        RailPlanner.LOG.info("{} 握手：客户端 {} 协议 {}", player.getName().getString(),
                context.server().getServerVersion(), Dto.PROTO);
    }

    private static void pull(ServerPlayNetworking.Context context, ServerPlayer player, String body) {
        Dto.Pull pull = Dto.GSON.fromJson(body, Dto.Pull.class);
        String key = PlayerStore.cleanKey(pull == null ? null : pull.worldKey);
        Dto.State state = PlayerStore.load(context.server(), player.getUUID(), key);
        state.worldKey = key;
        reply(player, Payloads.S2C_STATE, state);
        RailPlanner.LOG.info("{} 拉取 {}：{} 条线（版本 {}）", player.getName().getString(), key,
                state.routes.size(), state.revision);
    }

    private static void push(ServerPlayNetworking.Context context, ServerPlayer player, String body) {
        Dto.Push push = Dto.GSON.fromJson(body, Dto.Push.class);
        if (push == null) {
            Dto.Ack ack = new Dto.Ack();
            ack.ok = false;
            reply(player, Payloads.S2C_ACK, ack);
            return;
        }
        int revision = PlayerStore.apply(context.server(), player.getUUID(), push);
        Dto.Ack ack = new Dto.Ack();
        ack.worldKey = PlayerStore.cleanKey(push.worldKey);
        ack.revision = revision;
        reply(player, Payloads.S2C_ACK, ack);
    }

    /** 回一个包。限界那条路也用这个，所以是 public。 */
    public static void reply(ServerPlayer player, int op, Object body) {
        if (!ServerPlayNetworking.canSend(player, Payloads.S2C.TYPE)) {
            return;
        }
        ServerPlayNetworking.send(player, new Payloads.S2C(op, Dto.COMPACT.toJson(body)));
    }
}
