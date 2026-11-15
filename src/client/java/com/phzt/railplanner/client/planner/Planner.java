package com.phzt.railplanner.client.planner;

import com.phzt.railplanner.client.RailPlannerClient;
import com.phzt.railplanner.client.config.RailConfig;
import com.phzt.railplanner.client.geom.Alignment;
import com.phzt.railplanner.client.model.Anchor;
import com.phzt.railplanner.client.model.RailRoute;
import com.phzt.railplanner.client.model.RouteStore;
import com.phzt.railplanner.client.render.MapView;
import com.phzt.railplanner.client.render.PlannerRenderer;
import com.phzt.railplanner.client.sync.SyncClient;
import com.phzt.railplanner.client.sync.SyncKeys;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.gui.GuiMap;

/** 规划状态与交互。 */
public final class Planner {

    private static final Logger LOG = LoggerFactory.getLogger("railplanner/planner");

    private static final int[] PALETTE = {
            0xFFFF5252, 0xFF40C4FF, 0xFFFFD740, 0xFF69F0AE, 0xFFFF80AB, 0xFFB388FF, 0xFFFFAB40
    };

    public static boolean active;
    public static String worldId = "default";
    public static final List<RailRoute> routes = new ArrayList<>();
    public static RailRoute current;
    /** 世界里的 3D 线与状态条显示哪条（current 空了之后还留着的「最后那条」）。 */
    private static RailRoute displayed;
    public static int dragIndex = -1;
    public static int hoverIndex = -1;

    /** 地图里那行提示的正文与到期时间。 */
    private static String notice = "";
    private static long noticeUntil;
    private static final long NOTICE_MS = 2500;

    private static boolean clickPending;
    private static double pressX, pressY;
    private static boolean dirty;
    private static int tickCounter;

    public static RouteStats stats;
    private static boolean statsDirty = true;
    private static String statsRouteId;
    public static double lastMouseX;
    public static double lastMouseY;

    private Planner() {
    }

    public static void toggle(Minecraft mc) {
        Screen screen = mc.gui.screen();
        if (screen instanceof GuiMap) {
            active = !active;
            return;
        }
        if (openMap(mc)) {
            active = true;
            return;
        }
        active = !active;
    }

    private static boolean openMap(Minecraft mc) {
        try {
            WorldMapSession session = WorldMapSession.getCurrentSession();
            if (session == null) {
                return false;
            }
            MapProcessor mp = session.getMapProcessor();
            if (mp == null || mc.getCameraEntity() == null) {
                return false;
            }
            ensureLoaded(mp);
            mc.gui.setScreen(new GuiMap(null, null, mp, mc.getCameraEntity()));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void ensureLoaded(MapProcessor mp) {
        // 世界身份按「主机名（不含端口）/ 存档名」算，同一个服务器的不同端口是同一份数据。
        String id;
        try {
            id = SyncKeys.of(mc());
        } catch (Throwable t) {
            id = "default";
        }
        if (id == null || id.isBlank()) {
            id = "default";
        }
        try {
            if (!java.nio.file.Files.isRegularFile(RouteStore.file(id))) {
                migrateLegacy(id, mp);
            }
        } catch (Throwable t) {
            LOG.warn("迁移旧存档失败：{}", t.toString());
        }
        if (!id.equals(worldId) || routes.isEmpty()) {
            if (!routes.isEmpty()) {
                RouteStore.save(worldId, routes);
            }
            worldId = id;
            routes.clear();
            routes.addAll(RouteStore.load(worldId));
            current = null;
            displayed = routes.isEmpty() ? null : routes.get(0);
            stats = null;
            statsDirty = true;
        }
    }

    /**
     * 0.5.0 及以前本地存档按 Xaero 的世界 id 命名，0.6.0 换成 {@code sp_/mp_} 身份。
     * 第一次用新版本进这个世界时，把老文件里的线并过来——否则玩家会以为自己的线丢了。
     *
     * <p>只在「新名字的文件还不存在」时跑一次，之后两边各走各的；老文件原样留着当备份。</p>
     */
    private static void migrateLegacy(String key, MapProcessor mp) {
        List<RailRoute> merged = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        try {
            String legacy = mp == null ? null : mp.getCurrentWorldId();
            if (legacy != null && !legacy.isBlank()) {
                List<RailRoute> old = RouteStore.load(legacy);
                if (!old.isEmpty()) {
                    merged.addAll(old);
                    sources.add(legacy);
                }
            }
        } catch (Throwable ignored) {
            // Xaero 没开或版本不对，靠下面的模糊匹配兜底
        }
        if (key.startsWith("mp_")) {
            String host = key.substring(3).replace('_', '.');
            for (RouteStore.Legacy legacy : RouteStore.scanLegacy()) {
                String world = legacy.world().toLowerCase();
                if (!world.startsWith("multiplayer_")) {
                    continue;
                }
                String flat = world.replaceAll("[^a-z0-9._-]", "_");
                if (!flat.contains(host) && !flat.contains(host.replace('.', '_'))) {
                    continue;
                }
                List<RailRoute> old = RouteStore.load(legacy.world());
                if (!old.isEmpty()) {
                    merged.addAll(old);
                    sources.add(legacy.world());
                }
            }
        }
        if (merged.isEmpty()) {
            return;
        }
        // 按 id 去重，老文件的顺序优先
        Map<String, RailRoute> byId = new LinkedHashMap<>();
        for (RailRoute r : merged) {
            byId.putIfAbsent(r.id, r);
        }
        RouteStore.save(key, new ArrayList<>(byId.values()));
        LOG.info("迁移旧存档 → {}：{} 条线（来自 {}）", key, byId.size(), String.join(" / ", sources));
    }

    public static void tick(Minecraft mc) {
        if (!dirty) {
            return;
        }
        if (++tickCounter % 100 == 0) {
            RouteStore.save(worldId, routes);
            dirty = false;
        }
    }

    /** 锁住/解锁已选的点：锁上之后左键按住不再拖动，免得误触把已经画好的线扯歪。 */
    public static void toggleLock() {
        RailConfig config = RailConfig.get();
        config.anchorLock = !config.anchorLock;
        RailConfig.save();
        if (config.anchorLock) {
            dragIndex = -1;
        }
        toast(config.anchorLock ? "点 → 已锁定，拖动不会再移动" : "点 → 解锁，可以拖动");
        LOG.info("按键 {}：点的锁定 → {}", RailPlannerClient.keyName(RailPlannerClient.LOCK),
                config.anchorLock ? "开" : "关");
    }

    /**
     * 一句话提示：世界里的走 HUD 上那行，地图里另外画一行——地图开着的时候 HUD 被挡在后面，
     * 「拖不动」这种反馈必须在地图里看得见，否则玩家会以为是自己没点中。
     */
    private static void toast(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui != null && mc.gui.hud != null) {
            mc.gui.hud.setOverlayMessage(Component.literal(text), false);
        }
        notice = text;
        noticeUntil = System.currentTimeMillis() + NOTICE_MS;
    }

    /** 地图里那行提示，过期了就是 null。 */
    public static String notice() {
        return System.currentTimeMillis() < noticeUntil ? notice : null;
    }

    public static void markDirty() {
        dirty = true;
        statsDirty = true;
        if (current != null) {
            current.invalidate();
        }
        SyncClient.markDirty();
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    /** 同步用的读数：这个世界的数据（界面开着就用内存里这份，没开就读盘）。 */
    public static List<RailRoute> routesFor(String key) {
        if (key != null && key.equals(worldId)) {
            return new ArrayList<>(routes);
        }
        return RouteStore.load(key);
    }

    /**
     * 把同步合并之后的结果装回本地：内存里那份就地改（正在编辑的那条线对象不能换掉），
     * 然后把同一个结果写盘。
     */
    public static void applySynced(String key, List<RailRoute> merged) {
        RouteStore.save(key, merged);
        if (key == null || !key.equals(worldId)) {
            return;
        }
        Map<String, RailRoute> want = new LinkedHashMap<>();
        for (RailRoute r : merged) {
            want.put(r.id, r);
        }
        List<RailRoute> out = new ArrayList<>();
        for (RailRoute existing : routes) {
            RailRoute fresh = want.remove(existing.id);
            if (fresh != null) {
                existing.name = fresh.name;
                existing.color = fresh.color;
                existing.radius = fresh.radius;
                existing.anchors.clear();
                existing.anchors.addAll(fresh.anchors);
                existing.invalidate();
                out.add(existing);
            } else if (existing == current && existing.anchors.isEmpty()) {
                // 正在画、还没落点的那条线不跟着删
                out.add(existing);
            }
        }
        out.addAll(want.values());
        routes.clear();
        routes.addAll(out);
        if (current != null && !routes.contains(current)) {
            current = null;
        }
        if (displayed == null || !routes.contains(displayed)) {
            displayed = routes.isEmpty() ? null : routes.get(0);
        }
        stats = null;
        statsDirty = true;
        dirty = false;
    }

    public static void saveNow() {
        RouteStore.save(worldId, routes);
        dirty = false;
        tickCounter = 0;
        SyncClient.markDirty();
    }

    public static RailRoute newRoute() {
        RailRoute r = new RailRoute(UUID.randomUUID().toString(), "线 " + (routes.size() + 1),
                PALETTE[routes.size() % PALETTE.length]);
        routes.add(r);
        current = r;
        displayed = r;
        statsDirty = true;
        return r;
    }

    public static void finishRoute() {
        if (current != null) {
            if (current.isEmpty()) {
                routes.remove(current);
                if (displayed == current) {
                    displayed = null;
                }
            } else {
                // 完成了也别从世界里消失——那条线正是要拿去修的。
                displayed = current;
            }
        }
        current = null;
        dragIndex = -1;
        hoverIndex = -1;
        stats = null;
        saveNow();
    }

    public static void addAnchor(MapView view, double gx, double gy) {
        int bx = (int) Math.floor(view.worldX(gx));
        int bz = (int) Math.floor(view.worldZ(gy));
        if (current == null) {
            newRoute();
        }
        current.anchors.add(new Anchor(bx, bz));
        markDirty();
    }

    public static void render(GuiGraphicsExtractor g, MapView view, int mouseX, int mouseY, MapProcessor mp) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        if (mp != null) {
            ensureLoaded(mp);
        }
        if (active && dragIndex >= 0 && current != null && dragIndex < current.anchors.size()
                && !RailConfig.get().anchorLock) {
            int bx = (int) Math.floor(view.worldX(mouseX));
            int bz = (int) Math.floor(view.worldZ(mouseY));
            Anchor old = current.anchors.get(dragIndex);
            if (old.x() != bx || old.z() != bz) {
                current.anchors.set(dragIndex, new Anchor(bx, bz));
                markDirty();
            }
        }
        hoverIndex = active && current != null ? hitVertex(view, current, mouseX, mouseY) : -1;

        if (statsDirty && mp != null) {
            ensureStats();
        }

        PlannerRenderer.render(g, view, mouseX, mouseY, routes, current, hoverIndex, dragIndex);
    }

    public static boolean onClick(MapView view, int button, double gx, double gy) {
        if (!active) {
            return false;
        }
        int hit = hitVertex(view, current, gx, gy);
        if (button == 1) {
            if (hit >= 0) {
                if (RailConfig.get().anchorLock) {
                    toast("点已锁定，拖不动（" + RailPlannerClient.keyName(RailPlannerClient.LOCK) + " 解锁）");
                    return true;
                }
                dragIndex = hit;
                return true;
            }
            clickPending = true;
            pressX = gx;
            pressY = gy;
            return false;
        }
        if (button == 3) {
            if (hit >= 0 && current != null) {
                current.anchors.remove(hit);
                markDirty();
                return true;
            }
        }
        return false;
    }

    public static boolean onRelease(MapView view, int button, double gx, double gy) {
        if (!active) {
            return false;
        }
        if (button != 1) {
            return false;
        }
        if (dragIndex >= 0) {
            dragIndex = -1;
            saveNow();
            return true;
        }
        if (clickPending) {
            clickPending = false;
            if (Math.hypot(gx - pressX, gy - pressY) < 5.0) {
                if (current == null) {
                    RailRoute hit = hitRoute(view, gx, gy);
                    if (hit != null) {
                        current = hit;
                        statsDirty = true;
                        return false;
                    }
                }
                addAnchor(view, gx, gy);
                return false;
            }
        }
        return false;
    }

    /** 点中已有线的 6 像素以内，就把那条线捡回来继续编辑。 */
    public static RailRoute hitRoute(MapView view, double gx, double gy) {
        for (RailRoute r : routes) {
            Alignment al = r.alignment();
            if (al.length() < 0.5) {
                continue;
            }
            double step = Math.max(0.5, 4.0 / Math.max(0.05, view.pixelsPerBlock()));
            for (double s = 0; s <= al.length(); s += step) {
                var p = al.pointAt(s);
                double x = view.guiX(p.x);
                double y = view.guiY(p.z);
                if (Math.hypot(x - gx, y - gy) < 6.0) {
                    return r;
                }
            }
        }
        return null;
    }

    /**
     * 地图里的按键全部走 {@link KeyMapping}：玩家在「按键绑定」里改过的键立刻生效，
     * 界面上显示的字（HUD 提示）也读同一份绑定。
     */
    public static boolean onKey(KeyEvent event, MapView view, MapProcessor mp) {
        if (!active) {
            // 地图里再按一次「规划模式」键可以重新进入
            if (RailPlannerClient.TOGGLE.matches(event)) {
                active = true;
                statsDirty = true;
                LOG.info("按键 {}：地图里重新进入规划模式", RailPlannerClient.keyName(RailPlannerClient.TOGGLE));
                return true;
            }
            return false;
        }
        boolean shift = event.hasShiftDown();
        if (RailPlannerClient.HUD_COLLAPSE.matches(event)) {
            RailConfig config = RailConfig.get();
            config.hudCollapsed = !config.hudCollapsed;
            RailConfig.save();
            LOG.info("按键 {}：信息面板 → {}", RailPlannerClient.keyName(RailPlannerClient.HUD_COLLAPSE),
                    config.hudCollapsed ? "折叠成一行" : "展开");
            return true;
        }
        if (RailPlannerClient.LOCK.matches(event)) {
            toggleLock();
            return true;
        }
        if (RailPlannerClient.FINISH.matches(event)) {
            finishRoute();
            LOG.info("按键 {}：完成并保存这条线", RailPlannerClient.keyName(RailPlannerClient.FINISH));
            return true;
        }
        if (RailPlannerClient.UNDO.matches(event)) {
            if (current != null && !current.anchors.isEmpty()) {
                current.anchors.remove(current.anchors.size() - 1);
                markDirty();
                LOG.info("按键 {}：撤销一个点，剩 {} 个", RailPlannerClient.keyName(RailPlannerClient.UNDO),
                        current.anchors.size());
            }
            return true;
        }
        if (RailPlannerClient.RADIUS.matches(event)) {
            if (current != null) {
                current.radius = Math.max(4.0, Math.min(200.0, current.radius * (shift ? 0.8 : 1.25)));
                markDirty();
                LOG.info("按键 {}：圆曲线半径 → {} 格", RailPlannerClient.keyName(RailPlannerClient.RADIUS),
                        Math.round(current.radius));
            }
            return true;
        }
        if (RailPlannerClient.AUTOROUTE.matches(event)) {
            autoRoute(mp, view);
            LOG.info("按键 {}：自动选线", RailPlannerClient.keyName(RailPlannerClient.AUTOROUTE));
            return true;
        }
        if (RailPlannerClient.GRADE_DOWN.matches(event)) {
            RouteStats.nudgeMaxDesignGrade(shift ? -5.0 : -1.0);
            markDirty();
            LOG.info("按键 {}：设计限坡 → {}%", RailPlannerClient.keyName(RailPlannerClient.GRADE_DOWN),
                    floatText(RouteStats.MAX_DESIGN_GRADE));
            return true;
        }
        if (RailPlannerClient.GRADE_UP.matches(event)) {
            RouteStats.nudgeMaxDesignGrade(shift ? 5.0 : 1.0);
            markDirty();
            LOG.info("按键 {}：设计限坡 → {}%", RailPlannerClient.keyName(RailPlannerClient.GRADE_UP),
                    floatText(RouteStats.MAX_DESIGN_GRADE));
            return true;
        }
        if (RailPlannerClient.TOGGLE.matches(event)) {
            active = false;
            LOG.info("按键 {}：退出规划模式（地图还开着）", RailPlannerClient.keyName(RailPlannerClient.TOGGLE));
            return true;
        }
        return false;
    }

    private static String floatText(double v) {
        return String.format("%.1f", v);
    }

    private static void autoRoute(MapProcessor mp, MapView view) {
        if (mp == null || current == null || current.anchors.isEmpty()) {
            return;
        }
        double gx = lastMouseX;
        double gy = lastMouseY;
        Anchor start = current.anchors.get(current.anchors.size() - 1);
        Anchor goal = new Anchor((int) Math.floor(view.worldX(gx)), (int) Math.floor(view.worldZ(gy)));
        if (start.x() == goal.x() && start.z() == goal.z()) {
            return;
        }
        List<Anchor> path = AutoRouter.route(mp, start, goal);
        if (path.size() < 2) {
            return;
        }
        current.anchors.remove(current.anchors.size() - 1);
        for (Anchor a : path) {
            current.anchors.add(a);
        }
        markDirty();
    }

    public static int hitVertex(MapView view, RailRoute route, double gx, double gy) {
        if (route == null) {
            return -1;
        }
        double best = 7.0 * 7.0;
        int hit = -1;
        for (int i = 0; i < route.anchors.size(); i++) {
            Anchor a = route.anchors.get(i);
            double sx = view.guiX(a.x() + 0.5);
            double sy = view.guiY(a.z() + 0.5);
            double d = (sx - gx) * (sx - gx) + (sy - gy) * (sy - gy);
            if (d < best) {
                best = d;
                hit = i;
            }
        }
        return hit;
    }

    public static Alignment currentAlignment() {
        return current == null ? null : current.alignment();
    }

    public static RouteStats stats() {
        return stats;
    }

    /** 当前在编辑的那条线。 */
    public static RailRoute current() {
        return current;
    }

    /**
     * 世界里的 3D 线与状态条看哪条：正在编辑的那条；没有就退回最后选中/载入的那条——
     * 否则按回车完成一条线之后它就从世界里消失了，而那正是要照着去修的那条。
     */
    public static RailRoute displayRoute() {
        return current != null ? current : displayed;
    }

    /**
     * 取当前线的统计，没算过就算一份。
     *
     * <p>地图里是 {@link #render} 顺手算的；世界里的 3D 线不开地图也得画，所以这里要能自己算：
     * 优先用 Xaero 的会话（全图已探明数据），会话没起来就退回真实区块的高度。
     */
    public static RouteStats ensureStats() {
        RailRoute route = displayRoute();
        if (route == null) {
            stats = null;
            statsRouteId = null;
            return null;
        }
        if (!statsDirty && stats != null && route.id.equals(statsRouteId)) {
            return stats;
        }
        if (route.path().size() > 20000) {
            stats = null;
            statsRouteId = null;
            statsDirty = false;
            return null;
        }
        stats = RouteStats.compute(route, mapProcessor());
        statsRouteId = route.id;
        statsDirty = false;
        return stats;
    }

    private static MapProcessor mapProcessor() {
        try {
            WorldMapSession session = WorldMapSession.getCurrentSession();
            return session == null ? null : session.getMapProcessor();
        } catch (Throwable t) {
            return null;
        }
    }

    public static String statsRouteId() {
        return statsRouteId;
    }

    public static boolean isActive() {
        return active;
    }
}
