package com.phzt.railplanner.client.diag;

import com.phzt.railplanner.client.Loc;
import com.phzt.railplanner.client.config.RailConfig;
import com.phzt.railplanner.client.model.RailRoute;
import com.phzt.railplanner.client.planner.Planner;
import com.phzt.railplanner.client.update.UpdateChecker;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

/**
 * 诊断日志：<code>logs/railplanner-diag.log</code>。
 *
 * <p>这个模组平时的 INFO 只覆盖「开关」和「加载」，画线本身是静默的——线一闪一闪地消失时，
 * latest.log 里什么都看不到。诊断日志补的就是这一段：每个渲染/加载/同步/配置环节在做什么、
 * 为什么没做，以及每 10 秒一行的心跳（帧率、线数、内存）。</p>
 *
 * <p>默认开关跟构建走：版本号带 {@code -debug} 的调试包默认开，正式包默认关（玩家可以自己在
 * 设置页或 {@code /railplanner diag on} 打开）。关着的时候<b>不写文件</b>，只保留最近几百条
 * 关键事件在内存里——真崩了也能从快照看出它最后在干什么。</p>
 *
 * <p>滚动：单文件 1MB，保留 3 份（{@code .1} / {@code .2}），写满就换。所有写入口都是同步的，
 * 网络线程与渲染线程同时记也不会串行。</p>
 */
public final class Diag {

    private static final Logger LOG = LoggerFactory.getLogger("railplanner/diag");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** 同一个 tag 内容没变时，最多这么久写一行（warn 也用它，防止每帧的异常刷屏）。 */
    private static final long ONCE_GAP_MS = 1000L;
    /** basic 级别下，同一句话重复写的最短间隔：状态没变就少写，一变立刻写。 */
    private static final long REPEAT_GAP_MS = 10_000L;
    /** 心跳间隔。 */
    private static final long HEARTBEAT_MS = 10_000L;
    /** 单份文件上限 1MB。 */
    private static final long MAX_BYTES = 1024L * 1024L;
    /** 除当前文件外再留 2 份旧档，合计 3 份。 */
    private static final int BACKUPS = 2;
    /** 内存里留多少条，给崩溃快照用。 */
    private static final int RING = 400;

    private static final Map<String, String> LAST_TEXT = new HashMap<>();
    private static final Map<String, Long> LAST_AT = new HashMap<>();
    private static final Deque<String> RING_LINES = new ArrayDeque<>(RING);

    private static Path dir;
    private static Path file;
    private static Writer out;
    private static long bytes;
    private static long written;
    private static String broken;
    private static long lastHeartbeat;
    private static long lastSnapshotAt;
    private static boolean started;
    private static boolean crashHook;

    private Diag() {
    }

    // ------------------------------------------------------------------ 开关与级别

    /** 现在要不要写文件：配置优先，没配过就按构建类型（debug 包开、正式包关）。 */
    public static boolean enabled() {
        return RailConfig.get().diagOn();
    }

    public static String level() {
        return RailConfig.get().diagLevel == null ? "basic" : RailConfig.get().diagLevel;
    }

    /** 高频细节（每次地形查询、每帧重建面片之类）只在 verbose 下写。 */
    public static boolean verbose() {
        return "verbose".equalsIgnoreCase(level());
    }

    public static void setLevel(String newLevel) {
        RailConfig.get().diagLevel = "verbose".equalsIgnoreCase(newLevel) ? "verbose" : "basic";
        RailConfig.save();
        event("cfg", "诊断级别 → " + RailConfig.get().diagLevel);
    }

    public static void setEnabled(boolean on) {
        RailConfig.get().diag = on;
        RailConfig.save();
        if (on) {
            start();
            event("cfg", "诊断日志 → 开（" + level() + "），文件 " + pathText());
        } else {
            event("cfg", "诊断日志 → 关");
        }
    }

    public static String pathText() {
        return file == null ? "(未初始化)" : file.toString();
    }

    /** 当前状态的一行话，给设置页/命令用。 */
    public static String statusText() {
        String state = enabled() ? Loc.s("开", "on") : Loc.s("关", "off");
        String head = state + (Loc.isChinese() ? "（" : " (") + level() + (Loc.isChinese() ? "）" : ")");
        String lines = Loc.s("本次 " + written + " 行", written + " lines this run");
        String tail = broken == null ? "" : Loc.s("；文件写入有问题：" + broken, "; write problem: " + broken);
        return head + " · " + lines + " · " + pathText() + tail;
    }

    // ------------------------------------------------------------------ 生命周期

    /** 客户端初始化时调一次：建目录、开文件、写头部。 */
    public static synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        try {
            dir = FabricLoader.getInstance().getGameDir().resolve("logs");
            Files.createDirectories(dir);
            file = dir.resolve("railplanner-diag.log");
            openFile();
            rotateIfNeeded();
            for (String line : headerLines()) {
                writeRaw(line);
            }
        } catch (IOException | RuntimeException e) {
            broken = e.toString();
            LOG.warn("诊断日志文件打不开（{}），退回 latest.log：{}", file, broken);
        }
    }

    public static synchronized void flush() {
        if (out == null) {
            return;
        }
        try {
            out.flush();
        } catch (IOException e) {
            broken = e.toString();
        }
    }

    public static synchronized void close() {
        if (out == null) {
            return;
        }
        try {
            out.close();
        } catch (IOException ignored) {
            // 关不上就算了，下次启动会重新打开
        }
        out = null;
    }

    private static void openFile() throws IOException {
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // 换文件时关不掉旧的，不影响新的
            }
            out = null;
        }
        bytes = Files.isRegularFile(file) ? Files.size(file) : 0L;
        out = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND),
                StandardCharsets.UTF_8));
    }

    /** 超过 1MB 就把当前文件挪成 .1，.1 挪成 .2，.2 删掉。 */
    private static void rotateIfNeeded() {
        if (out == null || bytes < MAX_BYTES) {
            return;
        }
        close();
        try {
            Path second = dir.resolve("railplanner-diag.2.log");
            Path first = dir.resolve("railplanner-diag.1.log");
            Files.deleteIfExists(second);
            if (Files.isRegularFile(first)) {
                Files.move(first, second, StandardCopyOption.REPLACE_EXISTING);
            }
            if (Files.isRegularFile(file)) {
                Files.move(file, first, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            broken = e.toString();
            LOG.warn("诊断日志滚动失败：{}", e.toString());
        }
        try {
            openFile();
        } catch (IOException e) {
            broken = e.toString();
        }
    }

    // ------------------------------------------------------------------ 写

    private static String stamp(String kind, String tag) {
        return FULL.format(LocalDateTime.now()) + "  " + kind + "  " + (tag.isEmpty() ? "" : "[" + tag + "] ");
    }

    private static void remember(String line) {
        if (RING_LINES.size() >= RING) {
            RING_LINES.removeFirst();
        }
        RING_LINES.addLast(line);
    }

    private static void writeRaw(String line) {
        if (out == null) {
            return;
        }
        try {
            out.write(line);
            out.write('\n');
            out.flush();
            bytes += line.getBytes(StandardCharsets.UTF_8).length + 1L;
            written++;
        } catch (IOException e) {
            broken = e.toString();
        }
    }

    private static void emit(String kind, String tag, String message, boolean keepWhenOff) {
        String line = stamp(kind, tag) + message;
        if (keepWhenOff) {
            remember(line);
        } else if (enabled()) {
            remember(line);
        }
        if (!enabled()) {
            return;
        }
        rotateIfNeeded();
        writeRaw(line);
    }

    /** 同一 tag：内容一变立刻写，没变就最多每秒一行（渲染路径每帧都会调这个）。 */
    public static synchronized void once(String tag, String message) {
        if (!enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long at = LAST_AT.get(tag);
        String prev = LAST_TEXT.get(tag);
        long gap = verbose() ? ONCE_GAP_MS : REPEAT_GAP_MS;
        if (message.equals(prev) && at != null && now - at < gap) {
            return;
        }
        LAST_TEXT.put(tag, message);
        LAST_AT.put(tag, now);
        String line = stamp("I", tag) + message;
        remember(line);
        rotateIfNeeded();
        writeRaw(line);
    }

    /** 低频事件：每次调用都写（数据变动、加载、同步这类，一次就是一次）。 */
    public static synchronized void event(String tag, String message) {
        emit("I", tag, message, true);
    }

    /** 旧接口：一串自带头部的消息。 */
    public static synchronized void info(String message) {
        emit("I", "", message, true);
    }

    /**
     * 出错：写进文件、原样转给 latest.log。
     *
     * <p>同一句一秒内只记一次——渲染路径上的异常是每帧都来的，不限流一行能把文件写满。
     * 另外一分钟最多留一份崩溃快照，免得一个每帧抛的异常刷出一堆文件。</p>
     */
    public static synchronized void warn(String message, Throwable t) {
        long now = System.currentTimeMillis();
        String prev = LAST_TEXT.get("warn");
        Long at = LAST_AT.get("warn");
        if (message.equals(prev) && at != null && now - at < ONCE_GAP_MS) {
            return;
        }
        LAST_TEXT.put("warn", message);
        LAST_AT.put("warn", now);

        String line = stamp("W", "") + message;
        remember(line);
        if (enabled()) {
            rotateIfNeeded();
            writeRaw(line);
            if (t != null) {
                for (String traceLine : stack(t).split("\n")) {
                    writeRaw("    " + traceLine);
                }
            }
        }
        if (t == null) {
            LOG.warn(message);
        } else {
            LOG.warn(message, t);
        }
        if (now - lastSnapshotAt > 60_000L) {
            lastSnapshotAt = now;
            snapshot("异常：" + message, t);
        }
    }

    // ------------------------------------------------------------------ 心跳

    /** 每 tick 调一次；内部按 10 秒节流。 */
    public static synchronized void tick(Minecraft client) {
        if (!started || !enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastHeartbeat < HEARTBEAT_MS) {
            return;
        }
        lastHeartbeat = now;
        event("hb", heartbeatText(client));
    }

    private static String heartbeatText(Minecraft client) {
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) >> 20;
        long max = rt.maxMemory() >> 20;
        RailRoute route = Planner.displayRoute();
        String routeId = "null";
        String cells = "?";
        if (route != null) {
            routeId = shortId(route.id);
            try {
                cells = route.path().size() + "格";
            } catch (RuntimeException e) {
                cells = "取不到：" + e.getMessage();
            }
        }
        return "fps=" + fps(client)
                + " 线=" + Planner.routes.size()
                + " current=" + routeId
                + " 路径=" + cells
                + " 统计=" + statsText()
                + " 内存=" + used + "/" + max + "MB"
                + " 维度=" + dimension(client)
                + (verbose() ? " 坐标=" + coords(client) : "");
    }

    private static String statsText() {
        var st = Planner.stats();
        return st == null ? "无" : st.cells + "格/地面已知" + st.knownCells;
    }

    // ------------------------------------------------------------------ 报告与快照

    /** 环境摘要（多行）：版本、系统、渲染器、线路状态、诊断开关。 */
    public static synchronized String header() {
        StringBuilder sb = new StringBuilder();
        for (String line : headerLines()) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static String[] headerLines() {
        Minecraft client = Minecraft.getInstance();
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) >> 20;
        long max = rt.maxMemory() >> 20;
        return new String[] {
                "========================================================================",
                "Rail Planner 诊断日志  " + FULL.format(LocalDateTime.now()),
                "构建　　：" + UpdateChecker.currentVersion()
                        + (UpdateChecker.debugBuild() ? "（debug 构建）" : "（正式构建）"),
                "系统　　：" + System.getProperty("os.name") + " " + System.getProperty("os.version")
                        + " / " + System.getProperty("os.arch"),
                "Java　　：" + System.getProperty("java.version") + "（" + System.getProperty("java.vendor") + "）",
                "Minecraft：" + minecraftVersion(),
                "分辨率　：" + screen(client) + " · 帧率 " + fps(client) + " · 内存 " + used + "/" + max + " MB",
                "世界　　：" + dimension(client) + " · " + server(client),
                "诊断　　：" + (enabled() ? "开（" + level() + "）" : "关")
                        + " · 文件 " + pathText() + (broken == null ? "" : " · 写入异常 " + broken),
                "================================================================================",
        };
    }

    /** 可直接粘贴给开发者的那一段。 */
    public static synchronized String report() {
        StringBuilder sb = new StringBuilder();
        sb.append(header());
        sb.append(lineState()).append('\n');
        sb.append("最近 ").append(Math.min(RING_LINES.size(), 30)).append(" 条：").append('\n');
        int skip = Math.max(0, RING_LINES.size() - 30);
        int i = 0;
        for (String line : RING_LINES) {
            if (i++ < skip) {
                continue;
            }
            sb.append("  ").append(line).append('\n');
        }
        return sb.toString();
    }

    private static String lineState() {
        RailRoute route = Planner.displayRoute();
        RailConfig cfg = RailConfig.get();
        StringBuilder sb = new StringBuilder();
        sb.append("线路　　：").append(Planner.routes.size()).append(" 条");
        if (route != null) {
            sb.append(" · 当前 ").append(shortId(route.id))
                    .append(" · ").append(route.anchors.size()).append(" 个点");
            try {
                sb.append(" · ").append(route.path().size()).append(" 格");
            } catch (RuntimeException ignored) {
                sb.append(" · 路径取不到");
            }
        } else {
            sb.append(" · 当前：无");
        }
        sb.append('\n');
        sb.append("开关　　：规划模式 ").append(Planner.isActive() ? "开" : "关")
                .append(" · 3D 线 ").append(cfg.worldLines ? "开" : "关")
                .append(" · 穿山 ").append(cfg.worldXray ? "开" : "关")
                .append(" · 面片 ").append(cfg.worldSurfaces ? "开" : "关")
                .append(" · 同步 ").append(cfg.syncEnabled ? "开" : "关");
        return sb.toString();
    }

    /**
     * 崩溃快照：环境 + 最近 200 条 + 堆栈，落到 <code>logs/railplanner-crash-时间.txt</code>。
     *
     * @return 写出的文件；写不出来返回 null
     */
    public static synchronized Path snapshot(String context, Throwable t) {
        if (dir == null) {
            start();
        }
        if (dir == null) {
            return null;
        }
        Path target = dir.resolve("railplanner-crash-" + STAMP.format(LocalDateTime.now()) + ".txt");
        StringBuilder sb = new StringBuilder();
        sb.append("Rail Planner 崩溃快照 —— ").append(context).append('\n');
        sb.append(header());
        sb.append(lineState()).append('\n');
        if (t != null) {
            sb.append("---- 堆栈 ----").append('\n').append(stack(t)).append('\n');
        }
        sb.append("---- 最近 ").append(Math.min(RING_LINES.size(), 200)).append(" 条诊断 ----").append('\n');
        int skip = Math.max(0, RING_LINES.size() - 200);
        int i = 0;
        for (String line : RING_LINES) {
            if (i++ < skip) {
                continue;
            }
            sb.append(line).append('\n');
        }
        try {
            Files.writeString(target, sb.toString(), StandardCharsets.UTF_8);
            LOG.warn("已写崩溃快照 {}", target);
            return target;
        } catch (IOException | RuntimeException e) {
            LOG.warn("崩溃快照写不出来：{}", e.toString());
            return null;
        }
    }

    /** 装上全局未捕获异常钩子：先留快照，再把异常交还给原来的处理器。 */
    public static synchronized void installCrashHook() {
        if (crashHook) {
            return;
        }
        crashHook = true;
        Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                snapshot("未捕获异常（线程 " + thread.getName() + "）", error);
            } catch (Throwable ignored) {
                // 快照自己出错不能再拦住原始异常
            }
            if (old != null) {
                old.uncaughtException(thread, error);
            } else {
                LOG.error("未捕获异常（线程 {}）", thread.getName(), error);
            }
        });
    }

    // ------------------------------------------------------------------ 小工具

    public static String shortId(String id) {
        return id == null || id.length() < 8 ? String.valueOf(id) : id.substring(0, 8);
    }

    private static String stack(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static String fps(Minecraft client) {
        try {
            return String.valueOf(client.getFps());
        } catch (RuntimeException e) {
            return "?";
        }
    }

    private static String screen(Minecraft client) {
        try {
            return client.getWindow().getWidth() + "x" + client.getWindow().getHeight();
        } catch (RuntimeException e) {
            return "?";
        }
    }

    private static String coords(Minecraft client) {
        try {
            if (client.player == null) {
                return "-";
            }
            return Math.round(client.player.getX()) + "," + Math.round(client.player.getY()) + ","
                    + Math.round(client.player.getZ());
        } catch (RuntimeException e) {
            return "?";
        }
    }

    private static String dimension(Minecraft client) {
        try {
            return client.level == null ? "-" : client.level.dimension().identifier().toString();
        } catch (RuntimeException e) {
            return "-";
        }
    }

    private static String server(Minecraft client) {
        try {
            ServerData data = client.getCurrentServer();
            return data == null ? "单人游戏" : data.ip;
        } catch (RuntimeException e) {
            return "?";
        }
    }

    private static String minecraftVersion() {
        try {
            return FabricLoader.getInstance().getModContainer("minecraft")
                    .map(m -> m.getMetadata().getVersion().getFriendlyString())
                    .orElse("?");
        } catch (RuntimeException e) {
            return "?";
        }
    }
}
