package com.phzt.railplanner.client.diag;

import com.phzt.railplanner.client.Loc;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * <code>/railplanner diag ...</code>：不打开设置页也能开关诊断日志、看状态、留快照、复制摘要。
 *
 * <p>命令名同时挂 {@code /railplanner} 和 {@code /rp}。全部是客户端命令，不需要服务端装模组，
 * 也不会被服务端拒绝。</p>
 */
public final class DiagCommands {

    private DiagCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommands.literal("railplanner").then(diagNode()));
            dispatcher.register(ClientCommands.literal("rp").then(diagNode()));
        });
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> diagNode() {
        return ClientCommands.literal("diag")
                .executes(ctx -> status(ctx.getSource()))
                .then(ClientCommands.literal("on").executes(ctx -> {
                    Diag.setEnabled(true);
                    Diag.start();
                    ctx.getSource().sendFeedback(Component.literal(Loc.s(
                            "诊断日志已打开：" + Diag.pathText(),
                            "Diagnostic log on: " + Diag.pathText())));
                    return 1;
                }))
                .then(ClientCommands.literal("off").executes(ctx -> {
                    Diag.setEnabled(false);
                    ctx.getSource().sendFeedback(Component.literal(Loc.s(
                            "诊断日志已关闭（文件保留）", "Diagnostic log off (file kept)")));
                    return 1;
                }))
                .then(ClientCommands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(ClientCommands.literal("snapshot").executes(ctx -> {
                    Path out = Diag.snapshot("手动快照（/railplanner diag snapshot）", null);
                    ctx.getSource().sendFeedback(Component.literal(out == null
                            ? Loc.s("快照写不出来，看 latest.log", "Snapshot failed — see latest.log")
                            : Loc.s("已写快照：" + out, "Snapshot written: " + out)));
                    return 1;
                }))
                .then(ClientCommands.literal("report").executes(ctx -> {
                    String text = Diag.report();
                    String copied;
                    try {
                        Minecraft.getInstance().keyboardHandler.setClipboard(text);
                        copied = Loc.s("已复制到剪贴板（" + text.split("\n").length + " 行）",
                                "Copied to clipboard (" + text.split("\n").length + " lines)");
                    } catch (RuntimeException e) {
                        copied = Loc.s("复制失败：" + e, "Copy failed: " + e);
                    }
                    Diag.event("cmd", "生成诊断摘要（" + text.length() + " 字符）");
                    ctx.getSource().sendFeedback(Component.literal(copied));
                    return 1;
                }))
                .then(ClientCommands.literal("level")
                        .then(ClientCommands.literal("basic").executes(ctx -> level(ctx.getSource(), "basic")))
                        .then(ClientCommands.literal("verbose").executes(ctx -> level(ctx.getSource(), "verbose"))));
    }

    private static int level(FabricClientCommandSource source, String value) {
        Diag.setLevel(value);
        source.sendFeedback(Component.literal(Loc.s("诊断级别 → " + value, "Diagnostic level → " + value)));
        return 1;
    }

    private static int status(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal(Loc.s("诊断：" + Diag.statusText(), "Diagnostics: " + Diag.statusText())));
        return 1;
    }
}
