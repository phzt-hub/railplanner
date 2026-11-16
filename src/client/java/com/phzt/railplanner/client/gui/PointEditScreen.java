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
 * 世界里拿触发物品右键点出来的点位编辑框：Y、名字、类型三样，和地图里双击顶点那个框同口径。
 *
 * <p>点本身没变，改的是 {@link Anchor} 里那三个值，落盘和同步走 {@link Planner#applyAnchorEdit}
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

    private final Screen parent;
    private final RailRoute route;
    private final int index;
    /** 打开时这个点的位置，保存前拿来核对。 */
    private final int x;
    private final int z;
    private PointKind kind;
    private EditBox yField;
    private EditBox nameField;
    private Button kindButton;
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
    }

    /** 世界里命中一个点之后调它：下标越界就当没发生。 */
    public static void open(RailRoute route, int index) {
        if (route == null || index < 0 || index >= route.anchors.size()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.gui.setScreen(new PointEditScreen(null, route, index));
        LOG.info("世界里点开点位：点 {} · {} · Y {}", index + 1, route.anchors.get(index).kind().label,
                route.anchors.get(index).y());
    }

    @Override
    protected void init() {
        this.left = this.width / 2 - WIDTH / 2;
        this.top = Math.max(36, this.height / 2 - 84);
        Anchor a = currentAnchor();
        if (a == null) {
            // 开框的这段时间里线被同步换掉了：不建输入框，只留一个「关闭」。
            error = Loc.s("这个点已经不在线上了", "This point is no longer on the line");
            this.addRenderableWidget(Button.builder(Component.literal(Loc.s("关闭", "Close")), b -> onClose())
                    .bounds(this.left, top + 112, 120, 20).build());
            return;
        }
        int fieldX = this.left + LABEL;
        int fieldW = WIDTH - LABEL;

        this.yField = new EditBox(this.font, fieldX, top + 22, fieldW, 18, Component.literal("Y"));
        this.yField.setMaxLength(8);
        this.yField.setValue(String.valueOf(a.y()));
        this.addRenderableWidget(this.yField);

        this.nameField = new EditBox(this.font, fieldX, top + 48, fieldW, 18,
                Component.literal(Loc.s("名字", "Name")));
        this.nameField.setMaxLength(24);
        this.nameField.setValue(a.name());
        this.addRenderableWidget(this.nameField);

        this.kindButton = this.addRenderableWidget(Button.builder(kindLabel(), b -> {
            this.kind = this.kind.shift(1);
            b.setMessage(kindLabel());
        }).bounds(fieldX, top + 74, fieldW, 20).build());

        int buttons = top + 112;
        this.addRenderableWidget(Button.builder(Component.literal(Loc.s("保存", "Save")), b -> save())
                .bounds(this.left, buttons, 120, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal(Loc.s("取消", "Cancel")), b -> onClose())
                .bounds(this.left + WIDTH - 120, buttons, 120, 20).build());

        this.setInitialFocus(this.yField);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        String head = Loc.s("点 ", "Point ") + (index + 1) + " · " + kind.label();
        g.text(this.font, head, left, top, TITLE);
        g.text(this.font, "Y", left, top + 27, DIM);
        g.text(this.font, Component.literal(Loc.s("名字", "Name")), left, top + 53, DIM);
        g.text(this.font, Component.literal(Loc.s("类型", "Type")), left, top + 79, DIM);
        if (!error.isEmpty()) {
            g.text(this.font, Component.literal(error), left, top + 139, WARN);
        } else {
            g.text(this.font, Component.literal("x " + x + " · z " + z), left, top + 139, DIM);
        }
        g.text(this.font, Component.literal(Loc.s("回车保存 · Esc 取消", "Enter saves · Esc cancels")),
                left, top + 151, DIM);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_RETURN) {
            save();
            return true;
        }
        return super.keyPressed(event);
    }

    /** 框里那三样写回这个点。 */
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
        int y;
        try {
            y = Integer.parseInt(yField.getValue().trim());
        } catch (NumberFormatException e) {
            error = Loc.s("Y 要填整数（-1024 到 1024）", "Y must be an integer (-1024 to 1024)");
            return;
        }
        if (y < -1024 || y > 1024) {
            error = Loc.s("Y 超出 -1024 到 1024", "Y is outside -1024 to 1024");
            return;
        }
        boolean changed = Planner.applyAnchorEdit(route, index, y, nameField.getValue(), kind);
        if (changed) {
            Planner.saveNow();
            LOG.info("世界里的点位编辑框：点 {} 改成 Y {} · 名字「{}」· {}", index + 1, y,
                    nameField.getValue().trim(), kind.label);
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

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }
}
