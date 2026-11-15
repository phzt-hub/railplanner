package com.phzt.railplanner.client.render;

/** 世界地图当前视野：把世界坐标和 GUI 像素互相换算。 */
public record MapView(double cameraX, double cameraZ, double scale, double screenScale, int width, int height) {

    public double guiX(double worldX) {
        return this.width / 2.0 + (worldX - this.cameraX) * this.scale / this.screenScale;
    }

    public double guiY(double worldZ) {
        return this.height / 2.0 + (worldZ - this.cameraZ) * this.scale / this.screenScale;
    }

    public double worldX(double guiX) {
        return (guiX - this.width / 2.0) * this.screenScale / this.scale + this.cameraX;
    }

    public double worldZ(double guiY) {
        return (guiY - this.height / 2.0) * this.screenScale / this.scale + this.cameraZ;
    }

    /** 一个方块在屏幕上占多少像素。 */
    public double pixelsPerBlock() {
        return this.scale / this.screenScale;
    }
}
