package com.phzt.railplanner.client.model;

import com.phzt.railplanner.client.geom.Alignment;
import com.phzt.railplanner.client.geom.BlockPath;
import com.phzt.railplanner.client.geom.Rasterizer;
import com.phzt.railplanner.client.geom.Vec2;
import java.util.ArrayList;
import java.util.List;

/** 一条规划中的铁路线。 */
public final class RailRoute {

    public String id;
    public String name;
    public int color;
    public double radius = 24.0;
    public final List<Anchor> anchors = new ArrayList<>();

    private Alignment alignment;
    private BlockPath path;

    public RailRoute() {
    }

    public RailRoute(String id, String name, int color) {
        this.id = id;
        this.name = name;
        this.color = color;
    }

    public void invalidate() {
        this.alignment = null;
        this.path = null;
    }

    public Alignment alignment() {
        if (this.alignment == null) {
            List<Vec2> pts = new ArrayList<>(this.anchors.size());
            for (Anchor a : this.anchors) {
                pts.add(new Vec2(a.x() + 0.5, a.z() + 0.5));
            }
            this.alignment = Alignment.of(pts, Math.max(2.0, this.radius));
        }
        return this.alignment;
    }

    public BlockPath path() {
        if (this.path == null) {
            this.path = Rasterizer.rasterize(this.alignment());
        }
        return this.path;
    }

    public boolean isEmpty() {
        return this.anchors.isEmpty();
    }
}
