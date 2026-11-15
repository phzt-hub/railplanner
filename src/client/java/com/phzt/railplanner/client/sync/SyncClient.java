package com.phzt.railplanner.client.sync;

import com.phzt.railplanner.RailPlanner;
import com.phzt.railplanner.client.config.RailConfig;
import com.phzt.railplanner.client.model.RailRoute;
import com.phzt.railplanner.client.planner.Planner;
import com.phzt.railplanner.sync.Dto;
import com.phzt.railplanner.sync.Merge;
import com.phzt.railplanner.sync.Payloads;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端这一侧的同步状态机。
 *
 * <pre>
 * 进服 → HELLO（服务端有没有装、协议对不对）
 *      → PULL（服务端这个玩家、这个服务器的数据）
 *      → 三方合并（本地 / 基线 / 服务端）
 *          ├ 没冲突：本地装 adopt、服务端收 push
 *          └ 有冲突：弹窗让玩家逐条选，选完再按同样流程走一遍
 *      → PUSH → ACK → 把这次签收的内容写进基线
 * </pre>
 *
 * <p>之后每次本地改动（画点、删线、改设置）停手 3 秒就再走一遍 PULL→合并→PUSH。
 * 之所以每次先拉再推，是因为两台机器可能同时改：只有拿到服务端的最新状态才判得出冲突。</p>
 */
public final class SyncClient {

    public enum State {
        /** 还没进服。 */
        OFF,
        /** 关了同步开关。 */
        DISABLED,
        /** 等握手回包。 */
        HELLO,
        /** 等拉取回包。 */
        PULL,
        /** 等推送回包。 */
        PUSH,
        /** 同步完成，可以推改动。 */
        READY,
        /** 服务端没装（或版本不兼容），只用本地。 */
        ABSENT,
        /** 等玩家在冲突界面上选。 */
        CONFLICT
    }

    private static final Logger LOG = LoggerFactory.getLogger("railplanner/sync");

    private static final int HELLO_TIMEOUT = 100;
    private static final int PULL_TIMEOUT = 200;
    private static final int PUSH_TIMEOUT = 100;
    private static final int DEBOUNCE_TICKS = 60;

    private static State state = State.OFF;
    private static String key = "";
    private static int deadline;
    private static int debounce;
    private static boolean dirty;
    private static int retries;
    private static String detail = "";
    private static Merge.Plan plan;
    private static Map<String, String> pendingBase;
    private static int openConflictIn = -1;

    private SyncClient() {
    }

    public static State state() {
        return state;
    }

    public static String key() {
        return key;
    }

    // ---------------------------------------------------------------- 生命周期

    public static void onJoin(Minecraft mc) {
        key = SyncKeys.of(mc);
        state = State.OFF;
        dirty = false;
        debounce = 0;
        retries = 0;
        plan = null;
        pendingBase = null;
        openConflictIn = -1;
        detail = "";
        if (!RailConfig.get().syncEnabled) {
            state = State.DISABLED;
            LOG.info("同步：已关闭（{}）", key);
            return;
        }
        if (!canSend()) {
            absent(mc, "服务端没有装 Rail Planner");
            return;
        }
        Dto.Hello hello = new Dto.Hello();
        hello.client = RailPlanner.version();
        if (!send(Payloads.C2S_HELLO, hello)) {
            absent(mc, "服务端没有装 Rail Planner");
            return;
        }
        state = State.HELLO;
        deadline = HELLO_TIMEOUT;
        LOG.info("同步：向 {} 握手（客户端 {}）", key, RailPlanner.version());
    }

    public static void onDisconnect() {
        if (state == State.CONFLICT && plan != null) {
            LOG.info("同步：断开连接，未处理的冲突 {} 条先放着", plan.conflicts.size());
        }
        state = State.OFF;
        dirty = false;
        plan = null;
        pendingBase = null;
        openConflictIn = -1;
        detail = "";
    }

    /** 本地数据改了：停手 3 秒再走一轮同步。 */
    public static void markDirty() {
        if (state != State.READY) {
            return;
        }
        dirty = true;
        debounce = DEBOUNCE_TICKS;
    }

    public static void tick(Minecraft mc) {
        if (openConflictIn > 0 && --openConflictIn == 0) {
            openConflict(mc);
        }
        if (state == State.HELLO || state == State.PULL || state == State.PUSH) {
            if (--deadline <= 0) {
                timeout(mc);
            }
            return;
        }
        if (state == State.READY && dirty) {
            if (--debounce <= 0) {
                dirty = false;
                requestPull();
            }
        }
    }

    // ---------------------------------------------------------------- 收发

    private static boolean canSend() {
        try {
            return ClientPlayNetworking.canSend(Payloads.C2S.TYPE);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean send(int op, Object body) {
        try {
            if (!ClientPlayNetworking.canSend(Payloads.C2S.TYPE)) {
                return false;
            }
            ClientPlayNetworking.send(new Payloads.C2S(op, Dto.COMPACT.toJson(body)));
            return true;
        } catch (Throwable t) {
            LOG.warn("发送同步请求失败: {}", t.toString());
            return false;
        }
    }

    public static void handle(Payloads.S2C payload, ClientPlayNetworking.Context context) {
        Minecraft mc = context.client();
        mc.execute(() -> {
            try {
                switch (payload.op()) {
                    case Payloads.S2C_HELLO -> onHello(mc, payload.body());
                    case Payloads.S2C_STATE -> onState(mc, payload.body());
                    case Payloads.S2C_ACK -> onAck(payload.body());
                    default -> LOG.warn("未知同步回包 {}", payload.op());
                }
            } catch (Throwable t) {
                LOG.warn("处理同步回包失败: {}", t.toString());
            }
        });
    }

    private static void onHello(Minecraft mc, String body) {
        Dto.HelloAck ack = Dto.GSON.fromJson(body, Dto.HelloAck.class);
        if (ack == null || !ack.sync) {
            absent(mc, "服务端没有开启同步");
            return;
        }
        if (ack.proto != Dto.PROTO) {
            absent(mc, "服务端版本不兼容（协议 " + ack.proto + " ≠ " + Dto.PROTO + "）");
            return;
        }
        LOG.info("同步：握手成功（服务端 {}）", ack.server);
        requestPull();
    }

    private static void requestPull() {
        Dto.Pull pull = new Dto.Pull();
        pull.worldKey = key;
        if (!send(Payloads.C2S_PULL, pull)) {
            state = State.READY;
            return;
        }
        state = State.PULL;
        deadline = PULL_TIMEOUT;
    }

    private static void onState(Minecraft mc, String body) {
        Dto.State remote = Dto.GSON.fromJson(body, Dto.State.class);
        List<RailRoute> local = Planner.routesFor(key);
        Map<String, Dto.Route> localMap = new LinkedHashMap<>();
        for (Dto.Route d : RouteCodec.toDtoList(local)) {
            if (d.points() > 0) {
                localMap.put(d.id, d);
            }
        }
        SyncBase base = SyncBase.load(key);
        Merge.Plan p = Merge.plan(localMap, base.routes, remote, RouteCodec.settings(), base.settings);
        LOG.info("同步：{} 本地 {} 条，服务端 {} 条，要推 {} / 要删 {} / 要拉 {} / 冲突 {}",
                key, localMap.size(), remote == null || remote.routes == null ? 0 : remote.routes.size(),
                p.push.size(), p.pushDeletes.size(), p.adopt.size(), p.conflicts.size());
        if (p.hasConflicts()) {
            state = State.CONFLICT;
            plan = p;
            // 进服那几帧客户端还在铺地形/盖加载界面，这时开窗会被顶掉，所以下一拍再开。
            openConflictIn = 2;
            return;
        }
        finish(mc, p);
    }

    private static void openConflict(Minecraft mc) {
        Merge.Plan p = plan;
        if (p == null || p.conflicts.isEmpty()) {
            return;
        }
        openConflictIn = -1;
        mc.gui.setScreen(new SyncConflictScreen(mc.gui.screen(), p,
                (keepLocal, keepLocalSettings) -> finishConflicts(mc, keepLocal, keepLocalSettings)));
    }

    private static void finishConflicts(Minecraft mc, Map<String, Boolean> keepLocal, boolean keepLocalSettings) {
        Merge.Plan p = plan;
        plan = null;
        openConflictIn = -1;
        if (p == null) {
            state = State.READY;
            return;
        }
        p.choose(keepLocal, keepLocalSettings);
        finish(mc, p);
    }

    private static void finish(Minecraft mc, Merge.Plan p) {
        applyLocally(p);
        boolean needPush = !p.push.isEmpty() || !p.pushDeletes.isEmpty() || p.pushSettings != null;
        if (!needPush) {
            commitBase();
            state = State.READY;
            return;
        }
        Dto.Push push = new Dto.Push();
        push.worldKey = key;
        push.upserts = p.push;
        push.deletes = p.pushDeletes;
        push.settings = p.pushSettings;
        pendingBase = snapshot();
        if (!send(Payloads.C2S_PUSH, push)) {
            pendingBase = null;
            state = State.READY;
            return;
        }
        state = State.PUSH;
        deadline = PUSH_TIMEOUT;
        retries = 0;
        LOG.info("同步：推送 +{} -{} 设置{}", p.push.size(), p.pushDeletes.size(), p.pushSettings != null ? "+" : "-");
    }

    private static void onAck(String body) {
        Dto.Ack ack = Dto.GSON.fromJson(body, Dto.Ack.class);
        if (ack == null || !ack.ok) {
            LOG.warn("同步：服务端拒绝了这次推送");
            pendingBase = null;
            state = State.READY;
            return;
        }
        commitBase();
        state = State.READY;
        if (dirty) {
            debounce = DEBOUNCE_TICKS;
        }
        LOG.info("同步：完成，服务端版本 {}（{}）", ack.revision, key);
    }

    // ---------------------------------------------------------------- 本地落地

    private static void applyLocally(Merge.Plan p) {
        if (!p.adopt.isEmpty() || !p.removeLocal.isEmpty()) {
            List<RailRoute> merged = Planner.routesFor(key);
            Map<String, RailRoute> byId = new LinkedHashMap<>();
            for (RailRoute r : merged) {
                byId.put(r.id, r);
            }
            for (Dto.Route d : p.adopt.values()) {
                byId.put(d.id, RouteCodec.toRoute(d));
            }
            if (!p.removeLocal.isEmpty()) {
                byId.keySet().removeAll(p.removeLocal);
            }
            Planner.applySynced(key, new ArrayList<>(byId.values()));
        }
        if (p.adoptSettings != null) {
            RouteCodec.applySettings(p.adoptSettings);
            LOG.info("同步：世界显示设置按服务端那份更新");
        }
    }

    /** 推进完成后服务端将会拥有的那套内容指纹。 */
    private static Map<String, String> snapshot() {
        Map<String, String> out = new LinkedHashMap<>();
        for (RailRoute r : Planner.routesFor(key)) {
            Dto.Route d = RouteCodec.toDto(r);
            if (d.points() > 0) {
                out.put(d.id, d.hash());
            }
        }
        return out;
    }

    private static void commitBase() {
        SyncBase base = SyncBase.load(key);
        Map<String, String> fresh = pendingBase != null ? pendingBase : snapshot();
        pendingBase = null;
        Map<String, String> current = snapshot();
        base.routes = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : fresh.entrySet()) {
            // 推送在途时玩家又改了这条线：别把它记成「已同步」，下一轮会再推一次
            String now = current.get(e.getKey());
            if (now != null && now.equals(e.getValue())) {
                base.routes.put(e.getKey(), e.getValue());
            }
        }
        base.settings = RouteCodec.settings().hash();
        base.save();
    }

    // ---------------------------------------------------------------- 出错

    private static void timeout(Minecraft mc) {
        switch (state) {
            case HELLO -> absent(mc, "服务端没有回应（可能没装 Rail Planner）");
            case PULL -> {
                if (retries++ < 1) {
                    LOG.info("同步：拉取超时，重试一次");
                    requestPull();
                } else {
                    absent(mc, "拉取超时，这次先用本地数据");
                }
            }
            case PUSH -> {
                if (retries++ < 1) {
                    LOG.info("同步：推送超时，重试一次");
                    Dto.Push push = new Dto.Push();
                    push.worldKey = key;
                    if (send(Payloads.C2S_PUSH, push)) {
                        deadline = PUSH_TIMEOUT;
                    } else {
                        state = State.READY;
                    }
                } else {
                    LOG.warn("同步：推送两次都超时，等下次改动再试");
                    pendingBase = null;
                    state = State.READY;
                }
            }
            default -> state = State.READY;
        }
    }

    private static void absent(Minecraft mc, String reason) {
        if (state == State.ABSENT) {
            return;
        }
        state = State.ABSENT;
        detail = reason;
        LOG.info("同步：{} → 只用本地（{}）", reason, key);
        RailConfig cfg = RailConfig.get();
        if (!cfg.noModNotified.contains(key)) {
            cfg.noModNotified.add(key);
            cfg.save();
            if (mc.gui != null) {
                mc.gui.chatListener().handleSystemMessage(
                        Component.literal("[Rail Planner] " + reason + "，画好的线只保存在本地。")
                                .withStyle(ChatFormatting.GRAY), false);
            }
        }
    }

    /** HUD 上那行字。 */
    public static String hudText() {
        return switch (state) {
            case OFF -> "未连接";
            case DISABLED -> "同步已关（只用本地）";
            case HELLO, PULL, PUSH -> "同步中…";
            case READY -> "已同步";
            case ABSENT -> "服务端没装，只用本地";
            case CONFLICT -> "有冲突待选";
        };
    }

    public static String detail() {
        return detail;
    }
}
