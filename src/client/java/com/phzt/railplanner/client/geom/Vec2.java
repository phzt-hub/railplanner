package com.phzt.railplanner.client.geom;

/** 平面上的一个点（世界方块坐标）。 */
public final class Vec2 {
    public final double x;
    public final double z;

    public Vec2(double x, double z) {
        this.x = x;
        this.z = z;
    }

    public Vec2 add(double dx, double dz) {
        return new Vec2(this.x + dx, this.z + dz);
    }

    public Vec2 sub(Vec2 o) {
        return new Vec2(this.x - o.x, this.z - o.z);
    }

    public Vec2 sub(double dx, double dz) {
        return new Vec2(this.x - dx, this.z - dz);
    }

    public double length() {
        return Math.sqrt(this.x * this.x + this.z * this.z);
    }

    public Vec2 normalize() {
        double l = this.length();
        return l < 1.0E-9 ? new Vec2(0, 0) : new Vec2(this.x / l, this.z / l);
    }

    public double dot(Vec2 o) {
        return this.x * o.x + this.z * o.z;
    }

    public double cross(Vec2 o) {
        return this.x * o.z - this.z * o.x;
    }
}
