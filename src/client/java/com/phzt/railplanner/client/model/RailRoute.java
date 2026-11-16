package com.phzt.railplanner.client.model;

import com.phzt.railplanner.client.geom.Alignment;
import com.phzt.railplanner.client.geom.BlockPath;
import com.phzt.railplanner.client.geom.OrthoRoute;
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
    /**
     * 正交布线：把玩家点的折线拆成「沿 X 或 Z 的直段 + 90° 弯」（{@link OrthoRoute}）。
     * 新线按设置页的默认值，老存档没有这一列读出来就是 false——打开之后再点面板上的
     * 「正交化」才转过来，不会自己动别人画好的线。
     */
    public boolean orthogonal;

    private Alignment alignment;
    private BlockPath path;
    private OrthoRoute.Result ortho;

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
        this.ortho = null;
    }

    public Alignment alignment() {
        if (this.alignment == null) {
            List<Vec2> pts = new ArrayList<>(this.anchors.size());
            for (Anchor a : this.anchors) {
                pts.add(new Vec2(a.x() + 0.5, a.z() + 0.5));
            }
            double r = Math.max(2.0, this.radius);
            if (this.orthogonal) {
                this.ortho = OrthoRoute.build(pts, r);
                this.alignment = Alignment.ofOrthogonal(this.ortho.points(), r);
            } else {
                this.ortho = null;
                this.alignment = Alignment.of(pts, r);
            }
        }
        return this.alignment;
    }

    /** 正交化的统计（90° 弯几个、端点被外推了多少格）；没开正交时是 null。 */
    public OrthoRoute.Result ortho() {
        alignment();
        return this.ortho;
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
