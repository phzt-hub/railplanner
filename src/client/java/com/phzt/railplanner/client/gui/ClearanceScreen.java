package com.phzt.railplanner.client.gui;

import com.phzt.railplanner.client.Loc;
import com.phzt.railplanner.client.config.RailConfig;
import com.phzt.railplanner.client.sync.ClearanceNet;
import com.phzt.railplanner.client.world.Clearance;
import com.phzt.railplanner.clearance.Corridor;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 「铁路限界」窗口：宽度/高度/深度/范围 + 标红开关 + 待清数量 + 一键清理。
 *
 * <p>数值都是输入框（宽度按奇数夹紧，输 4 会变成 5），改一个就立刻重扫、世界里马上重画。
 * 清理按钮能不能点由 {@link ClearanceNet#canClear()} 定——服务端没装、没装 WorldEdit、
 * 不是 OP、不是创造，四种情况分别在下面那行红字里说清楚。</p>
 */
public class ClearanceScreen extends Screen {

    private static final int ROW = 22;
    private static final int HEADER = 0xFF9AA4AE;
    private static final int TEXT = 0xFFE6E6E6;
    private static final int WARN = 0xFFFF8A80;
    private static final int OK = 0xFF69F0AE;

    private final Screen parent;

    private int left;
    private int statusTop;
    private int buttonTop;

    private Button toggle;
    private EditBox widthField;
    private EditBox upField;
    private EditBox downField;
    private EditBox rangeField;

    public ClearanceScreen(Screen parent) {
        super(Component.literal(Loc.s("铁路限界", "Rail clearance")));
        this.parent = parent;
    }

    @Override
    protected void init() {
        RailConfig cfg = RailConfig.get();
        int w = 300;
        this.left = this.width / 2 - w / 2;
        int y = Math.max(30, this.height / 2 - 96);

        this.toggle = this.addRenderableWidget(Button.builder(labelToggle(), b -> {
            cfg.clearanceOn = !cfg.clearanceOn;
            cfg.save();
            b.setMessage(labelToggle());
            Clearance.invalidate();
        }).bounds(this.left, y, w, 20).build());
        y += 24;

        this.widthField = field(y, String.valueOf(cfg.clearanceWidth), this::onWidth);
        y += ROW;
        this.upField = field(y, String.valueOf(cfg.clearanceUp), this::onUp);
        y += ROW;
        this.downField = field(y, String.valueOf(cfg.clearanceDown), this::onDown);
        y += ROW;
        this.rangeField = field(y, String.valueOf(cfg.clearanceRange), this::onRange);
        y += ROW;

        this.statusTop = y + 2;
        this.buttonTop = y + 44;

        int bw = (w - 8) / 3;
        this.addRenderableWidget(Button.builder(Component.literal(Loc.s("刷新", "Refresh")), b -> {
            Clearance.invalidate();
            ClearanceNet.ask();
        }).bounds(this.left, this.buttonTop, bw, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal(Loc.s("一键清理", "Clear now")), b -> confirm())
                .bounds(this.left + bw + 4, this.buttonTop, bw, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal(Loc.s("关闭", "Done")),
                b -> this.minecraft.gui.setScreen(this.parent))
                .bounds(this.left + 2 * (bw + 4), this.buttonTop, bw, 20).build());

        // 打开窗口就问一次：中途被给了 / 被撤了 OP，这里能立刻反映出来。
        ClearanceNet.ask();
        Clearance.invalidate();
    }

    private EditBox field(int y, String value, java.util.function.Consumer<String> responder) {
        EditBox box = new EditBox(this.font, this.left + 130, y + 1, 56, 18, Component.literal(value));
        box.setMaxLength(3);
        box.setValue(value);
        box.setResponder(responder);
        this.addRenderableWidget(box);
        return box;
    }

    private void confirm() {
        if (!ClearanceNet.canClear()) {
            return;
        }
        RailConfig cfg = RailConfig.get();
        Component message = Component.literal(Loc.s(
                "将清除范围内 " + Clearance.count() + " 个方块（限界 " + cfg.clearanceWidth + "×" + cfg.clearanceUp
                        + "+" + cfg.clearanceDown + "，半径 " + cfg.clearanceRange + " 格）。\n清完可以用 //undo 撤回。",
                "Will clear " + Clearance.count() + " blocks in range (clearance " + cfg.clearanceWidth + "×"
                        + cfg.clearanceUp + "+" + cfg.clearanceDown + ", radius " + cfg.clearanceRange
                        + " blocks).\nYou can //undo afterwards."));
        this.minecraft.gui.setScreen(new ConfirmScreen(ok -> {
            if (ok) {
                ClearanceNet.requestClear();
            }
            this.minecraft.gui.setScreen(this);
        }, Component.literal(Loc.s("清除限界内的方块？", "Clear inside the clearance?")), message));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(this.font, this.getTitle(), this.width / 2, 16, 0xFFFFFFFF);

        int labelY = this.statusTop - ROW * 4 + 4;
        g.text(this.font, Component.literal(Loc.s("宽度（奇数）", "Width (odd)")), this.left + 6, labelY, HEADER);
        g.text(this.font, Component.literal(Loc.s("向上", "Up")), this.left + 6, labelY + ROW, HEADER);
        g.text(this.font, Component.literal(Loc.s("向下", "Down")), this.left + 6, labelY + ROW * 2, HEADER);
        g.text(this.font, Component.literal(Loc.s("范围", "Range")), this.left + 6, labelY + ROW * 3, HEADER);
        for (int i = 0; i < 4; i++) {
            g.text(this.font, Component.literal(Loc.s("格", "blocks")), this.left + 192, labelY + ROW * i,
                    0xFF8A949E);
        }

        // 三行状态：服务端、权限、数量；底下再一行说「为什么点不了」或上一次的结果。
        int y = this.statusTop;
        boolean ok = ClearanceNet.canClear();
        String head = ClearanceNet.supported()
                ? Loc.s("服务端：Rail Planner " + ClearanceNet.server(), "Server: Rail Planner " + ClearanceNet.server())
                : Loc.s("服务端：没装 Rail Planner", "Server: no Rail Planner");
        g.text(this.font, Component.literal(head), this.left + 6, y, ClearanceNet.supported() ? TEXT : WARN);
        y += 11;

        boolean creative = this.minecraft.player != null && this.minecraft.player.isCreative();
        String caps = Loc.s("WorldEdit：", "WorldEdit: ")
                + (ClearanceNet.worldEdit() ? Loc.s("有", "yes") : Loc.s("没有", "no"))
                + Loc.s("　OP：", "  op: ") + (ClearanceNet.op() ? Loc.s("是", "yes") : Loc.s("否", "no"))
                + Loc.s("　创造：", "  creative: ") + (creative ? Loc.s("是", "yes") : Loc.s("否", "no"));
        g.text(this.font, Component.literal(caps), this.left + 6, y, TEXT);
        y += 11;

        String count = Clearance.count() > 0
                ? Loc.s("范围内待清 " + Clearance.count() + " 格（走廊 " + Clearance.corridorSize() + " 格）",
                        Clearance.count() + " blocks to clear (corridor " + Clearance.corridorSize() + ")")
                : Loc.s("范围内没有要清的方块", "Nothing to clear in range");
        g.text(this.font, Component.literal(count), this.left + 6, y, ok ? OK : TEXT);
        y += 11;

        if (!ok) {
            g.text(this.font, Component.literal(ClearanceNet.blockedReason()), this.left + 6, y, WARN);
        } else if (!ClearanceNet.result().isEmpty()) {
            g.text(this.font, Component.literal(ClearanceNet.result()), this.left + 6, y, HEADER);
        }
    }

    private static Component labelToggle() {
        boolean on = RailConfig.get().clearanceOn;
        return Component.literal(Loc.s("限界标红：", "Clearance highlight: ")
                + (on ? Loc.s("开", "on") : Loc.s("关", "off")));
    }

    private void onWidth(String text) {
        Integer v = parseInt(text);
        if (v != null) {
            RailConfig.get().clearanceWidth = Corridor.fixWidth(v);
            Clearance.invalidate();
        }
    }

    private void onUp(String text) {
        Integer v = parseInt(text);
        if (v != null) {
            RailConfig.get().clearanceUp = Math.max(0, Math.min(Corridor.MAX_UP, v));
            Clearance.invalidate();
        }
    }

    private void onDown(String text) {
        Integer v = parseInt(text);
        if (v != null) {
            RailConfig.get().clearanceDown = Math.max(0, Math.min(Corridor.MAX_DOWN, v));
            Clearance.invalidate();
        }
    }

    private void onRange(String text) {
        Integer v = parseInt(text);
        if (v != null) {
            RailConfig.get().clearanceRange = Math.max(16, Math.min(256, v));
            Clearance.invalidate();
        }
    }

    private static Integer parseInt(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 关窗口时把输入框按规整后的配置重写一遍（宽度输 4 会显示成 5），顺手落盘。 */
    @Override
    public void removed() {
        super.removed();
        RailConfig cfg = RailConfig.get();
        cfg.clearanceWidth = Corridor.fixWidth(cfg.clearanceWidth);
        cfg.save();
        if (this.widthField != null) {
            this.widthField.setValue(String.valueOf(cfg.clearanceWidth));
        }
        if (this.upField != null) {
            this.upField.setValue(String.valueOf(cfg.clearanceUp));
        }
        if (this.downField != null) {
            this.downField.setValue(String.valueOf(cfg.clearanceDown));
        }
        if (this.rangeField != null) {
            this.rangeField.setValue(String.valueOf(cfg.clearanceRange));
        }
        if (this.toggle != null) {
            this.toggle.setMessage(labelToggle());
        }
        Clearance.invalidate();
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }
}
