package com.phzt.railplanner.client.sync;

import com.phzt.railplanner.client.config.RailConfig;
import com.phzt.railplanner.client.model.Anchor;
import com.phzt.railplanner.client.model.RailRoute;
import com.phzt.railplanner.sync.Dto;
import java.util.ArrayList;
import java.util.List;

/** 客户端模型 {@link RailRoute} / {@link RailConfig} 与传输模型 {@link Dto} 之间的转换。 */
public final class RouteCodec {

    private RouteCodec() {
    }

    public static Dto.Route toDto(RailRoute r) {
        Dto.Route d = new Dto.Route();
        d.id = r.id;
        d.name = r.name;
        d.color = r.color;
        d.radius = r.radius;
        d.updatedAt = System.currentTimeMillis();
        d.anchors = new ArrayList<>();
        for (Anchor a : r.anchors) {
            d.anchors.add(new int[]{a.x(), a.z()});
        }
        return d;
    }

    public static RailRoute toRoute(Dto.Route d) {
        RailRoute r = new RailRoute(d.id, d.name, d.color);
        r.radius = d.radius > 0 ? d.radius : 24.0;
        if (d.anchors != null) {
            for (int[] a : d.anchors) {
                if (a != null && a.length >= 2) {
                    r.anchors.add(new Anchor(a[0], a[1]));
                }
            }
        }
        return r;
    }

    public static List<Dto.Route> toDtoList(List<RailRoute> routes) {
        List<Dto.Route> out = new ArrayList<>(routes.size());
        for (RailRoute r : routes) {
            out.add(toDto(r));
        }
        return out;
    }

    public static List<RailRoute> toRouteList(List<Dto.Route> routes) {
        List<RailRoute> out = new ArrayList<>(routes == null ? 0 : routes.size());
        if (routes != null) {
            for (Dto.Route d : routes) {
                if (d != null && d.id != null) {
                    out.add(toRoute(d));
                }
            }
        }
        return out;
    }

    /** 同步过去的那部分设置。 */
    public static Dto.Settings settings() {
        RailConfig cfg = RailConfig.get();
        Dto.Settings s = new Dto.Settings();
        s.worldLines = cfg.worldLines;
        s.worldXray = cfg.worldXray;
        s.worldRange = cfg.worldRange;
        s.worldSurfaces = cfg.worldSurfaces;
        s.worldAnchors = cfg.worldAnchors;
        return s;
    }

    public static void applySettings(Dto.Settings s) {
        if (s == null) {
            return;
        }
        RailConfig cfg = RailConfig.get();
        boolean changed = cfg.worldLines != s.worldLines || cfg.worldXray != s.worldXray
                || Math.abs(cfg.worldRange - s.worldRange) > 1e-6
                || cfg.worldSurfaces != s.worldSurfaces || cfg.worldAnchors != s.worldAnchors;
        if (!changed) {
            return;
        }
        cfg.worldLines = s.worldLines;
        cfg.worldXray = s.worldXray;
        cfg.worldRange = s.worldRange;
        cfg.worldSurfaces = s.worldSurfaces;
        cfg.worldAnchors = s.worldAnchors;
        cfg.save();
    }
}
