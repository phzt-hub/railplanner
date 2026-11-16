package com.phzt.railplanner.client.sync;

import com.phzt.railplanner.client.Loc;
import com.phzt.railplanner.client.config.RailConfig;
import com.phzt.railplanner.client.world.Clearance;
import com.phzt.railplanner.sync.Dto;
import com.phzt.railplanner.sync.Payloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一键清理的客户端这一侧：问服务端能不能清、把请求发出去、把结果说给玩家听。
 *
 * <p>跟同步状态机（{@link SyncClient}）分开：清不清限界跟「要不要同步画好的线」是两件事，
 * 关掉同步也照样能清。所以它自己进服时问一次能力，不依赖同步有没有握手成功。</p>
 */
public final class ClearanceNet {

    private static final Logger LOG = LoggerFactory.getLogger("railplanner/clearance");

    /** 服务端回过包：说明它装了本模组。 */
    private static boolean heard;
    private static Dto.ClearInfo info = new Dto.ClearInfo();
    /** 上一次的结果，显示在窗口里。 */
    private static String result = "";

    private ClearanceNet() {
    }

    public static void onJoin(Minecraft mc) {
        heard = false;
        info = new Dto.ClearInfo();
        result = "";
        ask();
    }

    public static void onDisconnect() {
        heard = false;
        info = new Dto.ClearInfo();
        result = "";
    }

    /** 问一次服务端的能力。进服时问、打开窗口时再问（中途可能被给/被撤了 OP）。 */
    public static void ask() {
        if (!ClientPlayNetworking.canSend(Payloads.C2S.TYPE)) {
            return;
        }
        ClientPlayNetworking.send(new Payloads.C2S(Payloads.C2S_CLEARINFO, "{}"));
    }

    public static void handleInfo(Dto.ClearInfo value) {
        heard = true;
        info = value == null ? new Dto.ClearInfo() : value;
    }

    public static void handleAck(Dto.ClearAck ack) {
        Minecraft mc = Minecraft.getInstance();
        if (ack == null) {
            result = Loc.s("清理失败：回包是空的", "Clear failed: empty reply");
        } else if (ack.ok) {
            result = Loc.s("清掉 " + ack.cleared + " 格", "Cleared " + ack.cleared + " blocks");
        } else {
            result = reasonText(ack.reason);
        }
        LOG.info("限界清理结果：{}", result);
        if (mc.gui != null && mc.gui.hud != null) {
            mc.gui.hud.setOverlayMessage(Component.literal(Loc.s("限界：", "Clearance: ") + result), false);
        }
    }

    /** 服务端给了个代号，这里翻成一句人话。 */
    private static String reasonText(String reason) {
        String code = reason == null ? "" : reason;
        return switch (code) {
            case "not_creative" -> Loc.s("要在创造模式下才能清", "Switch to creative mode first");
            case "not_op" -> Loc.s("你不是这个服的 OP（需要 op 等级 2）", "You are not op on this server (level 2 required)");
            case "no_worldedit" -> Loc.s("服务端没装 WorldEdit", "The server has no WorldEdit");
            case "too_many" -> Loc.s("一次要清超过 " + com.phzt.railplanner.clearance.Corridor.MAX_BLOCKS
                    + " 格，把范围调小", "More than " + com.phzt.railplanner.clearance.Corridor.MAX_BLOCKS
                    + " blocks — shrink the range");
            case "bad_request" -> Loc.s("请求不合法（线或者尺寸有问题）", "Bad request (line or size rejected)");
            case "worldedit_failed" -> Loc.s("WorldEdit 那边报错了，看服务端日志", "WorldEdit failed — check the server log");
            default -> Loc.s("失败了（", "Failed (") + code + ")";
        };
    }

    /** 服务端装了本模组（能回包）。 */
    public static boolean supported() {
        return heard;
    }

    public static boolean worldEdit() {
        return info.worldedit;
    }

    public static boolean op() {
        return info.op;
    }

    public static int maxBlocks() {
        return info.maxBlocks <= 0 ? 20000 : info.maxBlocks;
    }

    public static String server() {
        return info.server == null ? "" : info.server;
    }

    public static String result() {
        return result;
    }

    /**
     * 现在能不能点「一键清理」。
     *
     * <p>三个条件缺一不可：服务端装了本模组且装了 WorldEdit、玩家在服务端是 OP、
     * 客户端这边也确实是创造模式（服务端还会再验一遍，这里只是别让按钮看着能点）。</p>
     */
    public static boolean canClear() {
        Minecraft mc = Minecraft.getInstance();
        boolean creative = mc.player != null && mc.player.isCreative();
        return heard && info.worldedit && info.op && creative && Clearance.count() > 0;
    }

    /** 不能清的时候，说清楚缺哪一条。 */
    public static String blockedReason() {
        Minecraft mc = Minecraft.getInstance();
        if (!heard) {
            return Loc.s("服务端没装 Rail Planner（或没回包）", "Server has no Rail Planner");
        }
        if (!info.worldedit) {
            return Loc.s("服务端没装 WorldEdit", "Server has no WorldEdit");
        }
        if (!info.op) {
            return Loc.s("你不是 OP（需要 op 等级 2）", "You are not op (level 2)");
        }
        if (mc.player == null || !mc.player.isCreative()) {
            return Loc.s("你不在创造模式", "You are not in creative mode");
        }
        if (Clearance.count() == 0) {
            return Clearance.why().isEmpty()
                    ? Loc.s("范围内没有要清的方块", "Nothing to clear in range")
                    : Clearance.why();
        }
        return "";
    }

    /** 把当前这条线的走行线 + 尺寸发给服务端。方块由服务端自己算。 */
    public static void requestClear() {
        if (!ClientPlayNetworking.canSend(Payloads.C2S.TYPE)) {
            return;
        }
        Dto.Clear req = new Dto.Clear();
        RailConfig cfg = RailConfig.get();
        req.width = cfg.clearanceWidth;
        req.up = cfg.clearanceUp;
        req.down = cfg.clearanceDown;
        req.xs = Clearance.spineX();
        req.ys = Clearance.spineY();
        req.zs = Clearance.spineZ();
        if (req.xs.length < 2) {
            result = Loc.s("范围内没有线，没东西可清", "No line in range");
            return;
        }
        LOG.info("请求清理：{}×{}+{}，走行线 {} 格", req.width, req.up, req.down, req.xs.length);
        ClientPlayNetworking.send(new Payloads.C2S(Payloads.C2S_CLEAR, Dto.COMPACT.toJson(req)));
        result = Loc.s("已发送，等服务端…", "Sent, waiting for the server...");
    }

}
