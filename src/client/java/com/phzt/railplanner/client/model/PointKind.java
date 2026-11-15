package com.phzt.railplanner.client.model;

/**
 * 点的类型：地图上和世界里画成不同的形状，一眼分得出车站、弯道控制点这些。
 *
 * <p>id 落在锚点数组 flags 的高位（bit 8 起），所以 0.7.x 的老存档读出来一律是
 * {@link #PLAIN}，不需要单独迁移。</p>
 */
public enum PointKind {

    PLAIN(0, "普通点", "Plain", 0xFFCFD8DC),
    STATION(1, "车站", "Station", 0xFF4FC3F7),
    CURVE(2, "弯道控制点", "Curve control point", 0xFFB39DDB),
    TUNNEL(3, "隧道口", "Tunnel portal", 0xFFFFB74D),
    BRIDGE(4, "桥梁", "Bridge", 0xFF4DB6AC),
    SIGNAL(5, "信号机", "Signal", 0xFFFF6E6E);

    public final int id;
    public final String label;
    /** 英文名，给非中文客户端用（见 {@link com.phzt.railplanner.client.Loc}）。 */
    public final String labelEn;
    /** 图标主色（地图上画形状用）。 */
    public final int color;

    PointKind(int id, String label, String labelEn, int color) {
        this.id = id;
        this.label = label;
        this.labelEn = labelEn;
        this.color = color;
    }

    public static PointKind byId(int id) {
        for (PointKind k : values()) {
            if (k.id == id) {
                return k;
            }
        }
        return PLAIN;
    }

    /** 往前后挪一格，用来在编辑框里用方向键换类型。 */
    public PointKind shift(int delta) {
        PointKind[] all = values();
        int i = (ordinal() + delta) % all.length;
        if (i < 0) {
            i += all.length;
        }
        return all[i];
    }

    public String label() {
        return com.phzt.railplanner.client.Loc.s(label, labelEn);
    }
}
