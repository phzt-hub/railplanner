package com.phzt.railplanner.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.phzt.railplanner.client.Loc;
import com.phzt.railplanner.client.model.Anchor;
import com.phzt.railplanner.client.model.PointKind;
import com.phzt.railplanner.client.model.RailRoute;
import com.phzt.railplanner.client.planner.Planner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 世界里拿触发物品右键点出来的点位编辑框：X、Z、Y、名字、类型、锁。
 *
 * <p>X / Z 也能手输：线是照着现实地图画的，坐标常常是抄过来的整数格，
 * 单靠鼠标在地图上拖只能拖到「差不多」，还得再挪。手输走同一条 {@link Planner#applyAnchorEdit}
 * 写入路径，夹值也夹在同一个地方。</p>
 *
 * <p>点本身没变，改的是 {@link Anchor} 里那几个值，落盘和同步走 {@link Planner#applyAnchorEdit}
 * ——和地图里改点走同一条路，不然两边迟早有一边忘存。</p>
 *
 * <p>{@code x / z} 在打开时就记住，保存时再对一遍：别的机器同步过来把这条线换了的时候，
 * 下标可能已经指向另一个点，宁可这次不写，也不能改错一个。</p>
 */
public class PointEditScreen extends Screen {

    private static final Logger LOG = LoggerFactory.getLogger("railplanner/planner");
    private static final int TITLE = 0xFFFFD740;
    private static final int DIM = 0xFF9AA4AE;
    private static final int WARN = 0xFFFF6E6E;
    /** 面板宽：左边的标签 + 右边的输入框。 */
    private static final int WIDTH = 300;
    private static final int LABEL = 44;
    /** 行距：18 高的输入框 + 4 的间隙。 */
    private static final int ROW = 22;

    private final Screen parent;
    private final RailRoute route;
    private final int index;
    /** 打开时这个点的位置，保存前拿来核对（同步把点挪走了就不写）。 */
    private final int x;
    private final int z;
    private PointKind kind;
    private boolean locked;
    private EditBox xField;
    private EditBox zField;
    private EditBox yField;
    private EditBox nameField;
    private Button kindButton;
    private Button lockButton;
    private String error = "";
    private int left;
    private int top;

    public PointEditScreen(Screen parent, RailRoute route, int index) {
        super(Component.literal(Loc.s("点位", "Point")));
        this.parent = parent;
        this.route = route;
        this.index = index;
        Anchor a = route.anchors.get(index);
        this.x = a.x();
        this.z = a.z();
        this.kind = a.kind();
        this.locked = a.locked();
    }

    /** 世界里命中一个点之后调它：下标越界就当没发生。 */
    public static void open(RailRoute route, int index) {
        if (route == null || index < 0 || index >= route.anchors.size()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.gui.setScreen(new PointEditScreen(null, route, index));
        LOG.info("世界里点开点位：点 {} · {} · ({}, {}, {})", index + 1,
                route.anchors.get(index).kind().label, route.anchors.get(index).x(),
                route.anchors.get(index).y(), route.anchors.get(index).z());
    }

    @Override
    protected void init() {
        this.left = this.width / 2 - WIDTH / 2;
        this.top = Math.max(30, this.height / 2 - 96);
        Anchor a = currentAnchor();
        if (a == null) {
            // 开框的这段时间里线被同步换掉了：不建输入框，只留一个「关闭」。
            error = Loc.s("这个点已经不在线上了", "This point is no longer on the line");
            this.addRenderableWidget(Button.builder(Component.literal(Loc.s("关闭", "Close")), b -> onClose())
                    .bounds(this.left, top + 40, 120, 20).build());
            return;
        }
        int fieldX = this.left + LABEL;
        int fieldW = WIDTH - LABEL;

        this.xField = field("X", fieldX, top + 20, fieldW, 12, String.valueOf(a.x()));
        this.zField = field("Z", fieldX, top + 20 + ROW, fieldW, 12, String.valueOf(a.z()));
        this.yField = field("Y", fieldX, top + 20 + 2 * ROW, fieldW, 8, String.valueOf(a.y()));

        this.nameField = field(Loc.s("名字", "Name"), fieldX, top + 20 + 3 * ROW, fieldW, 24, a.name());

        this.kindButton = this.addRenderableWidget(Button.builder(kindLabel(), b -> {
            this.kind = this.kind.shift(1);
            b.setMessage(kindLabel());
        }).bounds(fieldX, top + 20 + 4 * ROW, fieldW, 20).build());

        this.lockButton = this.addRenderableWidget(Button.builder(lockLabel(), b -> {
            this.locked = !this.locked;
            b.setMessage(lockLabel());
        }).bounds(fieldX, top + 20 + 5 * ROW, fieldW, 20).build());

        int buttons = top + 20 + 6 * ROW + 4;
        this.addRenderableWidget(Button.builder(Component.literal(Loc.s("保存", "Save")), b -> save())
                .bounds(this.left, buttons, 120, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal(Loc.s("取消", "Cancel")), b -> onClose())
                .bounds(this.left + WIDTH - 120, buttons, 120, 20).build());

        this.setInitialFocus(this.xField);
    }

    /** 一行输入框：位置、长度上限、初值。{@code addRenderableWidget} 是 protected，所以这里只能是实例方法。 */
    private EditBox field(String hint, int x, int y, int w, int max, String value) {
        EditBox box = new EditBox(this.font, x, y, w, 18, Component.literal(hint));
        box.setMaxLength(max);
        box.setValue(value);
        this.addRenderableWidget(box);
        return box;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        String head = Loc.s("点 ", "Point ") + (index + 1) + " · " + kind.label();
        g.text(this.font, head, left, top, TITLE);
        if (xField == null) {
            // 点已经不在线上了：上面只剩一行错误和那个关闭按钮。
            g.text(this.font, Component.literal(error), left, top + 18, WARN);
            return;
        }
        int row = top + 20;
        g.text(this.font, "X", left, row + 5, DIM);
        g.text(this.font, "Z", left, row + ROW + 5, DIM);
        g.text(this.font, "Y", left, row + 2 * ROW + 5, DIM);
        g.text(this.font, Component.literal(Loc.s("名字", "Name")), left, row + 3 * ROW + 5, DIM);
        g.text(this.font, Component.literal(Loc.s("类型", "Type")), left, row + 4 * ROW + 5, DIM);
        g.text(this.font, Component.literal(Loc.s("锁", "Lock")), left, row + 5 * ROW + 6, DIM);

        int info = top + 20 + 6 * ROW + 30;
        if (!error.isEmpty()) {
            g.text(this.font, Component.literal(error), left, info, WARN);
        } else {
            g.text(this.font, Component.literal(Loc.s("X / Z 手输 -30000000 到 30000000，Y 填 -1024 到 1024",
                    "X / Z: -30000000 to 30000000, Y: -1024 to 1024")), left, info, DIM);
        }
        g.text(this.font, Component.literal(Loc.s("回车保存 · Esc 取消", "Enter saves · Esc cancels")),
                left, info + 12, DIM);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_RETURN) {
            save();
            return true;
        }
        return super.keyPressed(event);
    }

    /** 框里那几样写回这个点。 */
    private void save() {
        Anchor a = currentAnchor();
        if (a == null) {
            error = Loc.s("这个点已经不在线上了", "This point is no longer on the line");
            return;
        }
        if (a.x() != x || a.z() != z) {
            error = Loc.s("点位被同步改过，这次修改没有写入", "The point moved (synced); nothing was written");
            return;
        }
        int nx;
        int nz;
        int ny;
        try {
            nx = Integer.parseInt(xField.getValue().trim());
            nz = Integer.parseInt(zField.getValue().trim());
            ny = Integer.parseInt(yField.getValue().trim());
        } catch (NumberFormatException e) {
            error = Loc.s("X / Z / Y 都要填整数", "X / Z / Y must all be integers");
            return;
        }
        if (Math.abs(nx) > Anchor.MAX_XZ || Math.abs(nz) > Anchor.MAX_XZ) {
            error = Loc.s("X / Z 超出 ±30000000", "X / Z outside ±30000000");
            return;
        }
        if (ny < -1024 || ny > 1024) {
            error = Loc.s("Y 超出 -1024 到 1024", "Y is outside -1024 to 1024");
            return;
        }
        boolean changed = Planner.applyAnchorEdit(route, index, nx, nz, ny, nameField.getValue(), kind, locked);
        if (changed) {
            Planner.saveNow();
            LOG.info("世界里的点位编辑框：点 {} 改成 ({}, {}, {}) · 名字「{}」· {} · {}", index + 1, nx, ny, nz,
                    nameField.getValue().trim(), kind.label, locked ? "锁着" : "不锁");
        }
        onClose();
    }

    private Anchor currentAnchor() {
        if (route == null || index < 0 || index >= route.anchors.size()) {
            return null;
        }
        return route.anchors.get(index);
    }

    private Component kindLabel() {
        return Component.literal("◀ " + kind.label() + " ▶");
    }

    private Component lockLabel() {
        return Component.literal(locked
                ? Loc.s("锁：开（拖不动）", "Lock: on (drag disabled)")
                : Loc.s("锁：关（可以拖）", "Lock: off (draggable)"));
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }
}
