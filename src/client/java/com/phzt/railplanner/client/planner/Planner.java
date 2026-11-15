package com.phzt.railplanner.client.planner;

import com.phzt.railplanner.client.geom.Alignment;
import com.phzt.railplanner.client.model.Anchor;
import com.phzt.railplanner.client.model.RailRoute;
import com.phzt.railplanner.client.model.RouteStore;
import com.phzt.railplanner.client.render.MapView;
import com.phzt.railplanner.client.render.PlannerRenderer;
import java.util.ArrayList;
import java.util.List;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.gui.GuiMap;

/** 规划状态与交互。 */
public final class Planner {

    private static final int[] PALETTE = {
            0xFFFF5252, 0xFF40C4FF, 0xFFFFD740, 0xFF69F0AE, 0xFFFF80AB, 0xFFB388FF, 0xFFFFAB40
    };

    public static boolean active;
    public static String worldId = "default";
    public static final List<RailRoute> routes = new ArrayList<>();
    public static RailRoute current;
    public static int dragIndex = -1;
    public static int hoverIndex = -1;

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
        String id;
        try {
            id = mp.getCurrentWorldId();
        } catch (Throwable t) {
            id = null;
        }
        if (id == null || id.isBlank()) {
            id = "default";
        }
        if (!id.equals(worldId) || routes.isEmpty()) {
            if (!routes.isEmpty()) {
                RouteStore.save(worldId, routes);
            }
            worldId = id;
            routes.clear();
            routes.addAll(RouteStore.load(worldId));
            current = null;
            stats = null;
            statsDirty = true;
        }
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

    public static void markDirty() {
        dirty = true;
        statsDirty = true;
        if (current != null) {
            current.invalidate();
        }
    }

    public static void saveNow() {
        RouteStore.save(worldId, routes);
        dirty = false;
        tickCounter = 0;
    }

    public static RailRoute newRoute() {
        RailRoute r = new RailRoute(UUID.randomUUID().toString(), "线 " + (routes.size() + 1),
                PALETTE[routes.size() % PALETTE.length]);
        routes.add(r);
        current = r;
        statsDirty = true;
        return r;
    }

    public static void finishRoute() {
        if (current != null && current.isEmpty()) {
            routes.remove(current);
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
        if (active && dragIndex >= 0 && current != null && dragIndex < current.anchors.size()) {
            int bx = (int) Math.floor(view.worldX(mouseX));
            int bz = (int) Math.floor(view.worldZ(mouseY));
            Anchor old = current.anchors.get(dragIndex);
            if (old.x() != bx || old.z() != bz) {
                current.anchors.set(dragIndex, new Anchor(bx, bz));
                markDirty();
            }
        }
        hoverIndex = active && current != null ? hitVertex(view, current, mouseX, mouseY) : -1;

        if (statsDirty && current != null && mp != null && current.path().size() <= 20000) {
            stats = RouteStats.compute(current, mp);
            statsRouteId = current.id;
            statsDirty = false;
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

    public static boolean onKey(KeyEvent event, MapView view, MapProcessor mp) {
        int key = event.key();
        if (!active) {
            // 地图里再按一次 B 可以重新进入规划模式
            if (key == InputConstants.KEY_B) {
                active = true;
                statsDirty = true;
                return true;
            }
            return false;
        }
        boolean shift = event.hasShiftDown();
        switch (key) {
            case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
                finishRoute();
                return true;
            }
            case InputConstants.KEY_DELETE, InputConstants.KEY_BACKSPACE -> {
                if (current != null && !current.anchors.isEmpty()) {
                    current.anchors.remove(current.anchors.size() - 1);
                    markDirty();
                }
                return true;
            }
            case InputConstants.KEY_R -> {
                if (current != null) {
                    current.radius = Math.max(4.0, Math.min(200.0, current.radius * (shift ? 0.8 : 1.25)));
                    markDirty();
                }
                return true;
            }
            case InputConstants.KEY_G -> {
                autoRoute(mp, view);
                return true;
            }
            case InputConstants.KEY_LBRACKET -> {
                RouteStats.nudgeMaxDesignGrade(shift ? -5.0 : -1.0);
                markDirty();
                return true;
            }
            case InputConstants.KEY_RBRACKET -> {
                RouteStats.nudgeMaxDesignGrade(shift ? 5.0 : 1.0);
                markDirty();
                return true;
            }
            case InputConstants.KEY_B -> {
                active = false;
                return true;
            }
            default -> {
                return false;
            }
        }
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

    public static String statsRouteId() {
        return statsRouteId;
    }

    public static boolean isActive() {
        return active;
    }
}
