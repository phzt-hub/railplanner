package com.phzt.railplanner.client.planner;

import com.mojang.blaze3d.platform.InputConstants;
import com.phzt.railplanner.client.RailPlannerClient;
import com.phzt.railplanner.client.config.RailConfig;
import com.phzt.railplanner.client.geom.Alignment;
import com.phzt.railplanner.client.geom.Vec2;
import com.phzt.railplanner.client.model.Anchor;
import com.phzt.railplanner.client.model.PointKind;
import com.phzt.railplanner.client.model.RailRoute;
import com.phzt.railplanner.client.model.RouteStore;
import com.phzt.railplanner.client.render.MapView;
import com.phzt.railplanner.client.render.PlannerRenderer;
import com.phzt.railplanner.client.sync.SyncClient;
import com.phzt.railplanner.client.sync.SyncKeys;
import com.phzt.railplanner.client.terrain.XaeroTerrain;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.ChatFormatting;
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

    /** 点位编辑框：-1 表示没开。 */
    private static int editIndex = -1;
    /** 点位编辑框停在那一行：0 = Y，1 = 名字，2 = 类型。 */
    private static int editRow;
    private static String editY = "";
    private static String editName = "";
    private static PointKind editKind = PointKind.PLAIN;
    /** Y 行刚打开时是「全选」状态：敲第一个数字直接换掉原来的值。 */
    private static boolean editReplace;

    /** 线路编辑框（名字 + 颜色）：行 0 = 名字，1 = 色板，2 = RGB 输入。 */
    private static boolean routeEditOpen;
    private static RailRoute routeEditTarget;
    private static String routeEditName = "";
    private static int routeEditRow;
    /** RGB 模式里三个通道各自的字。 */
    private static final String[] routeChannels = {"255", "255", "255"};
    private static int routeChannel;
    /** 编辑框里正在用的颜色（0xRRGGBB）。 */
    private static int routeColor = 0x4FC3F7;

    /** 色板上那 12 个预设色（0xRRGGBB）。 */
    public static final int[] COLOR_PRESETS = {
            0xFF5252, 0xFF8A65, 0xFFD740, 0xD4E157, 0x69F0AE, 0x40C4FF,
            0x448AFF, 0x7C4DFF, 0xFF80AB, 0xFFFFFF, 0xB0BEC5, 0x212121
    };
    /** 这一版的按键是 SDL 扫描码：数字行 1..9 是 30..38、0 是 39；小键盘 1..9 是 89..97、0 是 98，回车 88。 */
    private static final int SCAN_1 = 30;
    private static final int SCAN_9 = 38;
    private static final int SCAN_KP_1 = 89;
    private static final int SCAN_KP_9 = 97;
    private static final int SCAN_KP_0 = 98;
    private static final int SCAN_KP_ENTER = 88;

    /** 每 40 帧查一次「还没探明的点」，探到了就把回落值换掉。 */
    private static int resolveTick = 39;

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
        RailRoute r = new RailRoute(UUID.randomUUID().toString(),
                com.phzt.railplanner.client.Loc.s("线 ", "Line ") + (routes.size() + 1),
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
        Anchor added = anchorAt(mapProcessor(), bx, bz);
        current.anchors.add(added);
        LOG.info("加点 {},{} → Y {}（地图 {} / 区块 {}{}）", added.x(), added.z(), added.y(),
                mapHeight(bx, bz), RouteStats.columnHeight(bx, bz),
                added.manual() ? "，手填" : (added.resolved() ? "" : "，地面没探明先用 64"));
        markDirty();
    }

    public static void render(GuiGraphicsExtractor g, MapView view, int mouseX, int mouseY, MapProcessor mp) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        if (mp != null) {
            ensureLoaded(mp);
            if (++resolveTick % 40 == 0) {
                resolveAnchors(mp);
            }
        }
        if (active && dragIndex >= 0 && current != null && dragIndex < current.anchors.size()
                && !RailConfig.get().anchorLock) {
            int bx = (int) Math.floor(view.worldX(mouseX));
            int bz = (int) Math.floor(view.worldZ(mouseY));
            Anchor old = current.anchors.get(dragIndex);
            if (old.x() != bx || old.z() != bz) {
                // 手填过 Y 的点只换位置、标高留着；其余的点在新的一列上重新取地面高度。
                current.anchors.set(dragIndex, old.manual()
                        ? old.at(bx, bz)
                        : anchorAt(mp, bx, bz).withName(old.name()).withKind(old.kind()));
                markDirty();
            }
        }
        hoverIndex = active && current != null ? hitVertex(view, current, mouseX, mouseY) : -1;

        if (statsDirty && mp != null) {
            ensureStats();
        }

        PlannerRenderer.render(g, view, mouseX, mouseY, routes, current, hoverIndex, dragIndex);
    }

    public static boolean onClick(MapView view, int button, double gx, double gy, boolean doubleClick,
            boolean ctrl) {
        if (RailConfig.get().hotkeysOff || !active) {
            return false;
        }
        if (isEditing()) {
            // 编辑框开着时点地图 = 确认框里这些，别顺手又加一个点。
            commitPointEditor();
            return true;
        }
        if (routeEditOpen) {
            if (button == 1) {
                int swatch = PlannerRenderer.hitSwatch(gx, gy);
                if (swatch >= 0) {
                    pickPreset(swatch);
                    return true;
                }
                double hue = PlannerRenderer.hitHue(gx, gy);
                if (hue >= 0) {
                    setHue(hue);
                    return true;
                }
            }
            commitRouteEditor();
            return true;
        }
        if (button == 1) {
            if (PlannerRenderer.hitName(gx, gy)) {
                // 第一下就吃掉：不然双击线名会先在地图上加一个点。
                if (doubleClick) {
                    RailRoute named = PlannerRenderer.nameRoute();
                    if (named != null) {
                        openRouteEditor(named);
                    }
                }
                return true;
            }
            if (ctrl && insertOnLine(view, gx, gy)) {
                return true;
            }
        }
        int hit = hitVertex(view, current, gx, gy);
        if (button == 1) {
            if (doubleClick && hit >= 0) {
                openPointEditor(hit);
                return true;
            }
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
        if (RailConfig.get().hotkeysOff || !active) {
            return false;
        }
        if (button != 1) {
            return false;
        }
        if (isEditing() || routeEditOpen) {
            dragIndex = -1;
            clickPending = false;
            return true;
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
        if (RailConfig.get().hotkeysOff) {
            return false;
        }
        if (isEditing()) {
            return pointEditKey(event);
        }
        if (routeEditOpen) {
            return routeEditKey(event);
        }
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

    /**
     * 设置页里拨「停用全部快捷键」时调一次：收掉规划模式和编辑框，并在聊天栏说一句。
     *
     * <p>停用期间只剩「打开设置页」那个键还活着——不然玩家就再也进不去设置页把它关回来了。
     * 那是唯一的例外，其余按键和鼠标操作一起停。</p>
     */
    public static void notifyHotkeysOff() {
        boolean off = RailConfig.get().hotkeysOff;
        if (off) {
            closePointEditor();
            closeRouteEditor();
            active = false;
            dragIndex = -1;
            hoverIndex = -1;
            clickPending = false;
        }
        Minecraft mc = mc();
        if (mc.gui != null) {
            mc.gui.chatListener().handleSystemMessage(Component.literal(off
                    ? "[Rail Planner] 已停用本模组的全部快捷键与鼠标操作。要改回来：打开设置页（"
                            + RailPlannerClient.keyName(RailPlannerClient.SETTINGS) + "）把开关关掉。"
                    : "[Rail Planner] 快捷键已恢复。").withStyle(ChatFormatting.GRAY), false);
        }
        LOG.info("停用全部快捷键 → {}", off ? "开" : "关");
    }

    // ---------------------------------------------------------------- 点的 Y

    /**
     * 按 (x, z) 现取一个点：那一列最高方块的高度（不含树叶的地表）就是它的默认标高。
     * 地图数据还没探到、区块也没加载，就先放回落值 64，等探明了由 {@link #resolveAnchors} 换上。
     */
    public static Anchor anchorAt(MapProcessor mp, int x, int z) {
        int h = RouteStats.height(mp, x, z);
        return h == XaeroTerrain.UNKNOWN ? new Anchor(x, z) : Anchor.auto(x, z, h);
    }

    /** Xaero 地图数据里这一列的高度，没探过就是 {@link XaeroTerrain#UNKNOWN}（只给日志用）。 */
    private static int mapHeight(int x, int z) {
        MapProcessor mp = mapProcessor();
        return mp == null ? XaeroTerrain.UNKNOWN : XaeroTerrain.height(mp, x, z);
    }

    /**
     * 「存下来时还没探明」的点：等地图数据到了，把回落值换成那一列真实的地面高度。
     * 手填过的点不动；已经解析过、标高也讲得通的点不动。
     *
     * <p>标高讲不通的点（比世界最底层还低、或者高过顶）也一并重算——那是 0.7.1 及以前
     * 客户端拿空白高度图问高度留下的 -64，老存档里已经存下了。</p>
     */
    public static void resolveAnchors(MapProcessor mp) {
        if (mp == null) {
            return;
        }
        ClientLevel level = mc().level;
        boolean changed = false;
        for (RailRoute route : routes) {
            for (int i = 0; i < route.anchors.size(); i++) {
                Anchor a = route.anchors.get(i);
                if (a.manual() || (a.resolved() && RouteStats.plausibleY(level, a.y()))) {
                    continue;
                }
                int h = RouteStats.height(mp, a.x(), a.z());
                Anchor fixed = h == XaeroTerrain.UNKNOWN ? new Anchor(a.x(), a.z()) : Anchor.auto(a.x(), a.z(), h);
                if (fixed.equals(a)) {
                    continue;
                }
                route.anchors.set(i, fixed);
                route.invalidate();
                changed = true;
            }
        }
        if (changed) {
            markDirty();
            LOG.info("点的 Y：探明之后把回落值换成了地面高度");
        }
    }

    // ---------------------------------------------------------------- 编辑框

    /** 点位编辑框开没开。 */
    public static boolean isEditing() {
        return editIndex >= 0 && current != null && editIndex < current.anchors.size();
    }

    /** 点位编辑框正在改的那个点，没开就是 -1。 */
    public static int editIndex() {
        return isEditing() ? editIndex : -1;
    }

    /** 点位编辑框停在哪一行：0 = Y，1 = 名字，2 = 类型。 */
    public static int editRow() {
        return editRow;
    }

    public static String editYText() {
        return editY;
    }

    public static String editNameText() {
        return editName;
    }

    public static PointKind editKind() {
        return editKind;
    }

    /** 线路编辑框开没开。 */
    public static boolean isEditingRoute() {
        return routeEditOpen;
    }

    /** 线路编辑框停在哪一行：0 = 名字，1 = 色板，2 = RGB。 */
    public static int routeRow() {
        return routeEditRow;
    }

    public static String routeNameText() {
        return routeEditName;
    }

    public static int routeColor() {
        return routeColor;
    }

    public static int routeChannel() {
        return routeChannel;
    }

    public static String routeChannelText(int i) {
        return i >= 0 && i < routeChannels.length ? routeChannels[i] : "";
    }

    private static void openPointEditor(int index) {
        if (current == null || index < 0 || index >= current.anchors.size()) {
            return;
        }
        closeRouteEditor();
        Anchor a = current.anchors.get(index);
        editIndex = index;
        editRow = 0;
        editY = String.valueOf(a.y());
        editName = a.name();
        editKind = a.kind();
        editReplace = true;
    }

    private static void closePointEditor() {
        editIndex = -1;
        editRow = 0;
        editY = "";
        editName = "";
        editKind = PointKind.PLAIN;
        editReplace = false;
    }

    /** 回车 / 点地图别处：把框里的东西写进这个点。 */
    private static void commitPointEditor() {
        int index = editIndex;
        String yText = editY;
        String name = editName;
        PointKind kind = editKind;
        closePointEditor();
        if (current == null || index < 0 || index >= current.anchors.size()) {
            return;
        }
        Anchor a = current.anchors.get(index);
        int y = a.y();
        String text = yText.trim();
        if (!text.isEmpty() && !"-".equals(text)) {
            try {
                y = Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                // 不是整数就当没填
            }
        }
        applyAnchorEdit(current, index, y, name, kind);
    }

    /**
     * 改一个点的 Y / 名字 / 类型。地图里的点位框和世界里的编辑框都走这一条，
     * 免得两处各写一套夹值、trim 和日志。
     *
     * @return 真改动了才 true（值全一样就不落盘、不推同步）
     */
    public static boolean applyAnchorEdit(RailRoute route, int index, int y, String name, PointKind kind) {
        if (route == null || index < 0 || index >= route.anchors.size()) {
            return false;
        }
        Anchor a = route.anchors.get(index);
        Anchor next = a;
        int value = Math.max(-1024, Math.min(1024, y));
        if (value != a.y()) {
            next = next.withY(value);
        }
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.length() > 24) {
            trimmed = trimmed.substring(0, 24);
        }
        if (!trimmed.equals(a.name())) {
            next = next.withName(trimmed);
        }
        if (kind != null && kind != a.kind()) {
            next = next.withKind(kind);
        }
        if (next.equals(a)) {
            return false;
        }
        route.anchors.set(index, next);
        route.invalidate();
        markDirty();
        LOG.info("点 {}：Y {} · 名字「{}」· {}（原 {} / {} / {}）", index + 1, next.y(), next.name(),
                next.kind().label, a.y(), a.name().isEmpty() ? "没名字" : a.name(), a.kind().label);
        return true;
    }

    /** 双击面板上那行线名：改名字和颜色。 */
    private static void openRouteEditor(RailRoute route) {
        if (route == null) {
            return;
        }
        closePointEditor();
        routeEditOpen = true;
        routeEditTarget = route;
        routeEditName = route.name == null ? "" : route.name;
        routeEditRow = 0;
        routeColor = route.color & 0xFFFFFF;
        routeChannel = 0;
        syncChannelsFromColor();
        LOG.info("线路属性框：{}", route.name);
    }

    private static void closeRouteEditor() {
        routeEditOpen = false;
        routeEditTarget = null;
        routeEditRow = 0;
    }

    private static void commitRouteEditor() {
        RailRoute route = routeEditTarget;
        String name = routeEditName.trim();
        int color = routeColor & 0xFFFFFF;
        closeRouteEditor();
        if (route == null) {
            return;
        }
        boolean changed = false;
        if (!name.isEmpty() && !name.equals(route.name)) {
            route.name = name;
            changed = true;
        }
        int argb = 0xFF000000 | color;
        if (argb != route.color) {
            route.color = argb;
            changed = true;
        }
        if (changed) {
            markDirty();
            LOG.info("线路属性：名字「{}」颜色 #{}", route.name, hex(color));
        }
    }

    /** 色板上点一个（也接受数字键 1–9、0、-、=）。 */
    public static void pickPreset(int index) {
        if (index >= 0 && index < COLOR_PRESETS.length) {
            routeColor = COLOR_PRESETS[index];
            syncChannelsFromColor();
        }
    }

    /** 色相滑条拖到 0..1 的哪个位置：色相换掉，饱和度与明度留着。 */
    public static void setHue(double fraction) {
        float[] hsb = toHsb(routeColor);
        routeColor = fromHsb((float) Math.max(0, Math.min(1, fraction)), Math.max(0.25f, hsb[1]),
                Math.max(0.35f, hsb[2]));
        syncChannelsFromColor();
    }

    private static void syncChannelsFromColor() {
        routeChannels[0] = String.valueOf((routeColor >> 16) & 0xFF);
        routeChannels[1] = String.valueOf((routeColor >> 8) & 0xFF);
        routeChannels[2] = String.valueOf(routeColor & 0xFF);
    }

    /** 把 RGB 行里那个通道的字读回颜色（空着当 0，超过 255 夹住）。 */
    private static void applyChannel() {
        int value;
        try {
            value = Integer.parseInt(routeChannels[routeChannel].trim());
        } catch (NumberFormatException e) {
            value = 0;
        }
        value = Math.max(0, Math.min(255, value));
        routeChannels[routeChannel] = String.valueOf(value);
        int shift = 16 - routeChannel * 8;
        routeColor = (routeColor & ~(0xFF << shift)) | (value << shift);
    }

    private static void shiftHue(int degrees) {
        float[] hsb = toHsb(routeColor);
        float hue = hsb[0] + degrees / 360.0f;
        routeColor = fromHsb(hue, Math.max(0.25f, hsb[1]), Math.max(0.35f, hsb[2]));
        syncChannelsFromColor();
    }

    private static float[] toHsb(int rgb) {
        float r = ((rgb >> 16) & 0xFF) / 255.0f;
        float g = ((rgb >> 8) & 0xFF) / 255.0f;
        float b = (rgb & 0xFF) / 255.0f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float d = max - min;
        float hue = 0;
        if (d > 0.0001f) {
            if (max == r) {
                hue = ((g - b) / d) % 6.0f;
            } else if (max == g) {
                hue = (b - r) / d + 2.0f;
            } else {
                hue = (r - g) / d + 4.0f;
            }
            hue /= 6.0f;
        }
        if (hue < 0) {
            hue += 1.0f;
        }
        return new float[]{hue % 1.0f, max <= 0 ? 0 : d / max, max};
    }

    private static int fromHsb(float hue, float sat, float bri) {
        float h = (hue - (float) Math.floor(hue)) * 6.0f;
        int i = (int) Math.floor(h);
        float f = h - i;
        float p = bri * (1 - sat);
        float q = bri * (1 - sat * f);
        float t = bri * (1 - sat * (1 - f));
        float r;
        float g;
        float b;
        switch (i % 6) {
            case 0 -> {
                r = bri;
                g = t;
                b = p;
            }
            case 1 -> {
                r = q;
                g = bri;
                b = p;
            }
            case 2 -> {
                r = p;
                g = bri;
                b = t;
            }
            case 3 -> {
                r = p;
                g = q;
                b = bri;
            }
            case 4 -> {
                r = t;
                g = p;
                b = bri;
            }
            default -> {
                r = bri;
                g = p;
                b = q;
            }
        }
        return (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
    }

    private static int clamp255(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255.0f)));
    }

    private static String hex(int rgb) {
        return String.format("%06X", rgb & 0xFFFFFF);
    }

    /** 点位编辑框里的按键：Tab/上下换行，方向键换类型，回车保存，Esc 取消。 */
    private static boolean pointEditKey(KeyEvent event) {
        int key = event.key();
        if (key == InputConstants.KEY_ESCAPE) {
            closePointEditor();
            LOG.info("点位编辑框：取消");
            return true;
        }
        if (key == InputConstants.KEY_RETURN || key == SCAN_KP_ENTER) {
            commitPointEditor();
            return true;
        }
        if (key == InputConstants.KEY_TAB || key == InputConstants.KEY_DOWN || key == InputConstants.KEY_UP) {
            boolean back = key == InputConstants.KEY_UP
                    || (key == InputConstants.KEY_TAB && event.hasShiftDown());
            editRow = (editRow + (back ? 2 : 1)) % 3;
            editReplace = editRow == 0;
            return true;
        }
        if (editRow == 2) {
            if (key == InputConstants.KEY_LEFT) {
                editKind = editKind.shift(-1);
                return true;
            }
            if (key == InputConstants.KEY_RIGHT) {
                editKind = editKind.shift(1);
                return true;
            }
        }
        if (key == InputConstants.KEY_BACKSPACE) {
            if (editRow == 0) {
                editY = editReplace ? "" : dropLast(editY);
                editReplace = false;
            } else if (editRow == 1) {
                editName = dropLast(editName);
            }
            return true;
        }
        if (event.hasControlDown() && key == InputConstants.KEY_V && editRow == 1) {
            editName = cutText(editName + clipboard());
            return true;
        }
        char c = ascii(event);
        if (c == 0) {
            return true;
        }
        if (editRow == 0) {
            if ((c >= '0' && c <= '9') || c == '-') {
                editY = editReplace ? String.valueOf(c) : cutText(editY + c);
                editReplace = false;
            }
        } else if (editRow == 1) {
            editName = cutText(editName + c);
        }
        return true;
    }

    /** 线路编辑框里的按键：Tab 在三行之间转，色板行用 ←→ 调色相，RGB 行用 ←→ 选通道。 */
    private static boolean routeEditKey(KeyEvent event) {
        int key = event.key();
        if (key == InputConstants.KEY_ESCAPE) {
            closeRouteEditor();
            LOG.info("线路属性框：取消");
            return true;
        }
        if (key == InputConstants.KEY_RETURN || key == SCAN_KP_ENTER) {
            commitRouteEditor();
            return true;
        }
        if (key == InputConstants.KEY_TAB) {
            routeEditRow = (routeEditRow + (event.hasShiftDown() ? 2 : 1)) % 3;
            if (routeEditRow == 2) {
                applyChannel();
                routeChannel = 0;
                routeChannels[0] = "";
            }
            return true;
        }
        if (key == InputConstants.KEY_DOWN) {
            routeEditRow = (routeEditRow + 1) % 3;
            if (routeEditRow == 2) {
                applyChannel();
                routeChannel = 0;
                routeChannels[0] = "";
            }
            return true;
        }
        if (key == InputConstants.KEY_UP) {
            routeEditRow = (routeEditRow + 2) % 3;
            if (routeEditRow == 2) {
                applyChannel();
                routeChannel = 0;
                routeChannels[0] = "";
            }
            return true;
        }
        if (routeEditRow == 2) {
            if (key == InputConstants.KEY_LEFT || key == InputConstants.KEY_RIGHT) {
                applyChannel();
                routeChannel = (routeChannel + (key == InputConstants.KEY_LEFT ? 2 : 1)) % 3;
                routeChannels[routeChannel] = "";
                return true;
            }
            if (key == InputConstants.KEY_BACKSPACE) {
                routeChannels[routeChannel] = dropLast(routeChannels[routeChannel]);
                applyChannel();
                return true;
            }
            char c = ascii(event);
            if (c >= '0' && c <= '9' && routeChannels[routeChannel].length() < 3) {
                routeChannels[routeChannel] = routeChannels[routeChannel] + c;
                applyChannel();
            }
            return true;
        }
        if (routeEditRow == 1) {
            if (key == InputConstants.KEY_LEFT) {
                shiftHue(-5);
                return true;
            }
            if (key == InputConstants.KEY_RIGHT) {
                shiftHue(5);
                return true;
            }
            char c = ascii(event);
            if (c >= '1' && c <= '9') {
                pickPreset(c - '1');
            } else if (c == '0') {
                pickPreset(9);
            } else if (c == '-') {
                pickPreset(10);
            } else if (c == '=') {
                pickPreset(11);
            }
            return true;
        }
        if (key == InputConstants.KEY_BACKSPACE) {
            routeEditName = dropLast(routeEditName);
            return true;
        }
        if (event.hasControlDown() && key == InputConstants.KEY_V) {
            routeEditName = cutText(routeEditName + clipboard());
            return true;
        }
        char c = ascii(event);
        if (c != 0) {
            routeEditName = cutText(routeEditName + c);
        }
        return true;
    }

    /**
     * 字符输入：能拿到的可打印字符走按键的扫描码（这一版字符事件在按键之后派发，
     * 按键一吞就收不到），这里只补非 ASCII 的那部分——输入法提交上来的汉字、粘贴进来的字。
     */
    public static boolean onChar(int codepoint) {
        if (codepoint < 32 || codepoint == 127) {
            return false;
        }
        if (codepoint < 128) {
            return false;
        }
        String text = new String(Character.toChars(codepoint));
        if (isEditing() && editRow == 1) {
            editName = cutText(editName + text);
            return true;
        }
        if (routeEditOpen && routeEditRow == 0) {
            routeEditName = cutText(routeEditName + text);
            return true;
        }
        return false;
    }

    private static char ascii(KeyEvent event) {
        int chr = event.keycode();
        int key = event.key();
        boolean shift = event.hasShiftDown();
        // 字母按扫描码算，Shift 才出得来大写（字符码那边给的是没按 Shift 的那个）。
        if (key >= InputConstants.KEY_A && key <= InputConstants.KEY_Z) {
            char c = (char) ('a' + (key - InputConstants.KEY_A));
            return shift ? Character.toUpperCase(c) : c;
        }
        if (chr >= 32 && chr < 127) {
            return (char) chr;
        }
        if (key >= SCAN_1 && key <= SCAN_9) {
            return (char) ('1' + (key - SCAN_1));
        }
        if (key == InputConstants.KEY_0) {
            return '0';
        }
        if (key >= SCAN_KP_1 && key <= SCAN_KP_9) {
            return (char) ('1' + (key - SCAN_KP_1));
        }
        if (key == SCAN_KP_0) {
            return '0';
        }
        if (key == InputConstants.KEY_SPACE) {
            return ' ';
        }
        if (key == InputConstants.KEY_MINUS) {
            return shift ? '_' : '-';
        }
        if (key == InputConstants.KEY_PERIOD) {
            return '.';
        }
        if (key == InputConstants.KEY_COMMA) {
            return ',';
        }
        return 0;
    }

    private static String dropLast(String text) {
        return text == null || text.isEmpty() ? "" : text.substring(0, text.length() - 1);
    }

    private static String cutText(String text) {
        return text.length() <= 24 ? text : text.substring(0, 24);
    }

    private static String clipboard() {
        try {
            Minecraft mc = mc();
            return mc.keyboardHandler == null ? "" : mc.keyboardHandler.getClipboard();
        } catch (Throwable t) {
            return "";
        }
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
            // 自动选线生出来的点也得带 Y：起点沿用原来那个点，其余按新的一列取地面高度。
            current.anchors.add(a.equals(start) ? start : (a.manual() ? a : anchorAt(mp, a.x(), a.z())));
        }
        markDirty();
    }

    /**
     * Ctrl + 左键点在线上：把新点插进那一段中间，位置吸附到线上最近的一点（线形不乱）。
     *
     * <p>只认正在画的那条和刚画完的那条（{@link #displayRoute()}）：别的线先点一下把它捡回来。</p>
     */
    private static boolean insertOnLine(MapView view, double gx, double gy) {
        RailRoute target = null;
        double bestD = Double.MAX_VALUE;
        double bestS = 0;
        RailRoute second = displayed == current ? null : displayed;
        for (int k = 0; k < 2; k++) {
            RailRoute route = k == 0 ? current : second;
            if (route == null || route.anchors.size() < 2) {
                continue;
            }
            Alignment al = route.alignment();
            if (al.length() < 1.0) {
                continue;
            }
            double s = nearestS(al, view, gx, gy);
            if (s < 0) {
                continue;
            }
            Vec2 p = al.pointAt(s);
            double d = Math.hypot(view.guiX(p.x) - gx, view.guiY(p.z) - gy);
            if (d < bestD) {
                bestD = d;
                bestS = s;
                target = route;
            }
        }
        if (target == null || bestD > 7.0) {
            return false;
        }
        Vec2 p = target.alignment().pointAt(bestS);
        int bx = (int) Math.floor(p.x);
        int bz = (int) Math.floor(p.z);
        for (Anchor a : target.anchors) {
            if (a.x() == bx && a.z() == bz) {
                return true;
            }
        }
        int at = segmentAfter(target, p.x, p.z);
        target.anchors.add(at, anchorAt(mapProcessor(), bx, bz));
        target.invalidate();
        markDirty();
        toast("插在第 " + at + " 与第 " + (at + 1) + " 点之间（共 " + target.anchors.size() + " 点）");
        LOG.info("插点：线「{}」第 {} 位 → ({}, {})", target.name, at + 1, bx, bz);
        return true;
    }

    /** 沿走行线找离鼠标最近的那个里程（屏幕距离，两种像素都算上了）。 */
    private static double nearestS(Alignment al, MapView view, double gx, double gy) {
        double len = al.length();
        if (len < 0.5) {
            return -1;
        }
        double step = Math.max(0.5, 2.0 / Math.max(0.05, view.pixelsPerBlock()));
        double bestS = -1;
        double bestD = Double.MAX_VALUE;
        for (double s = 0; s <= len; s += step) {
            Vec2 p = al.pointAt(s);
            double d = Math.hypot(view.guiX(p.x) - gx, view.guiY(p.z) - gy);
            if (d < bestD) {
                bestD = d;
                bestS = s;
            }
        }
        if (bestS < 0) {
            return -1;
        }
        double lo = Math.max(0, bestS - step);
        double hi = Math.min(len, bestS + step);
        for (double s = lo; s <= hi; s += step / 8.0) {
            Vec2 p = al.pointAt(s);
            double d = Math.hypot(view.guiX(p.x) - gx, view.guiY(p.z) - gy);
            if (d < bestD) {
                bestD = d;
                bestS = s;
            }
        }
        return bestS;
    }

    /** 这个位置插在第几个点后面：折线每一段投影一遍，挑最近那段。 */
    private static int segmentAfter(RailRoute route, double px, double pz) {
        int bestAt = route.anchors.size() - 1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i + 1 < route.anchors.size(); i++) {
            Anchor a = route.anchors.get(i);
            Anchor b = route.anchors.get(i + 1);
            double dx = b.x() - a.x();
            double dz = b.z() - a.z();
            double len2 = dx * dx + dz * dz;
            double t = len2 <= 0.0001 ? 0 : ((px - a.x()) * dx + (pz - a.z()) * dz) / len2;
            t = Math.max(0, Math.min(1, t));
            double ex = a.x() + dx * t - px;
            double ez = a.z() + dz * t - pz;
            double d = ex * ex + ez * ez;
            if (d < bestD) {
                bestD = d;
                bestAt = i + 1;
            }
        }
        return bestAt;
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
