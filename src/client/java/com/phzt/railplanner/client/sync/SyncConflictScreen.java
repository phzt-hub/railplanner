package com.phzt.railplanner.client.sync;

import com.phzt.railplanner.sync.Merge;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 冲突界面：两边都动过的线路，一条一条问「留哪边」。
 *
 * <p>每条用一个按钮来回切，按钮上的字永远是「点了会怎样」，不是状态。选完全部按「应用」。
 * 默认立场是「保留服务端」（共享那份），想留本地得自己点一下。</p>
 */
public class SyncConflictScreen extends Screen {

    /** 选完之后交给同步器去做。 */
    public interface Handler {
        void done(Map<String, Boolean> keepLocal, boolean keepLocalSettings);
    }

    private static final int ROW = 24;
    private static final int PAGE = 6;

    private final Screen parent;
    private final Merge.Plan plan;
    private final Handler handler;
    private final Map<String, Boolean> keepLocal = new LinkedHashMap<>();
    private final Map<String, Button> rowButtons = new LinkedHashMap<>();
    private int page;

    public SyncConflictScreen(Screen parent, Merge.Plan plan, Handler handler) {
        super(Component.literal("同步冲突"));
        this.parent = parent;
        this.plan = plan;
        this.handler = handler;
        for (Merge.Conflict c : plan.conflicts) {
            keepLocal.put(c.id, Boolean.FALSE);
        }
    }

    @Override
    protected void init() {
        rowButtons.clear();
        int width = Math.min(360, this.width - 40);
        int x = this.width / 2 - width / 2;
        int top = this.height / 2 - 70;
        int from = page * PAGE;
        int to = Math.min(plan.conflicts.size(), from + PAGE);
        for (int i = from; i < to; i++) {
            Merge.Conflict c = plan.conflicts.get(i);
            int y = top + (i - from) * ROW;
            Button b = Button.builder(labelFor(c), btn -> {
                keepLocal.put(c.id, !Boolean.TRUE.equals(keepLocal.get(c.id)));
                btn.setMessage(labelFor(c));
            }).bounds(x, y, width, 20).build();
            rowButtons.put(c.id, b);
            this.addRenderableWidget(b);
        }

        int footer = top + PAGE * ROW + 8;
        int half = width / 2;
        if (plan.conflicts.size() > PAGE) {
            this.addRenderableWidget(Button.builder(Component.literal("上一页"), b -> {
                page = Math.max(0, page - 1);
                rebuild();
            }).bounds(x, footer, half - 2, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal("下一页"), b -> {
                page = Math.min((plan.conflicts.size() - 1) / PAGE, page + 1);
                rebuild();
            }).bounds(x + half + 2, footer, half - 2, 20).build());
            footer += ROW;
        }

        this.addRenderableWidget(Button.builder(Component.literal("全部保留本地"), b -> {
            for (Merge.Conflict c : plan.conflicts) {
                keepLocal.put(c.id, Boolean.TRUE);
            }
            rebuild();
        }).bounds(x, footer, half - 2, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("全部保留服务端"), b -> {
            for (Merge.Conflict c : plan.conflicts) {
                keepLocal.put(c.id, Boolean.FALSE);
            }
            rebuild();
        }).bounds(x + half + 2, footer, half - 2, 20).build());
        footer += ROW;

        this.addRenderableWidget(Button.builder(Component.literal("应用并同步"), b -> {
            this.minecraft.gui.setScreen(parent);
            handler.done(keepLocal, Boolean.TRUE.equals(keepLocal.get(Merge.SETTINGS_ID)));
        }).bounds(x, footer, half - 2, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("这次先不管"), b -> {
            this.minecraft.gui.setScreen(parent);
        }).bounds(x + half + 2, footer, half - 2, 20).build());
    }

    private void rebuild() {
        this.clearWidgets();
        this.init();
    }

    private Component labelFor(Merge.Conflict c) {
        boolean local = Boolean.TRUE.equals(keepLocal.get(c.id));
        String head = c.label + "（" + c.kindText() + "）  本地 " + c.localText() + " / 服务端 " + c.remoteText();
        return Component.literal(head + "  → " + (local ? "保留本地" : "保留服务端"));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(this.font, this.getTitle(), this.width / 2, this.height / 2 - 100, 0xFFFFFFFF);
        g.centeredText(this.font,
                Component.literal("这些线路两边都改过，点一下切换保留哪边（默认留服务端）"),
                this.width / 2, this.height / 2 - 88, 0xFF9AA4AE);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
