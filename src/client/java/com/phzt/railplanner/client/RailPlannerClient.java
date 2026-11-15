package com.phzt.railplanner.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.phzt.railplanner.client.config.RailConfig;
import com.phzt.railplanner.client.config.RailConfigScreen;
import com.phzt.railplanner.client.planner.Planner;
import com.phzt.railplanner.client.render.WorldHud;
import com.phzt.railplanner.client.sync.SyncClient;
import com.phzt.railplanner.client.update.UpdateChecker;
import com.phzt.railplanner.sync.Payloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RailPlannerClient implements ClientModInitializer {

    private static final Logger LOG = LoggerFactory.getLogger("railplanner");

    /** 选项→控制→按键绑定里的分类。 */
    public static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("railplanner", "planner"));

    public static final KeyMapping TOGGLE =
            new KeyMapping("key.railplanner.toggle", InputConstants.Type.KEYBOARD, InputConstants.KEY_B, CATEGORY);
    public static final KeyMapping FINISH =
            new KeyMapping("key.railplanner.finish", InputConstants.Type.KEYBOARD, InputConstants.KEY_RETURN, CATEGORY);
    public static final KeyMapping UNDO =
            new KeyMapping("key.railplanner.undo", InputConstants.Type.KEYBOARD, InputConstants.KEY_DELETE, CATEGORY);
    public static final KeyMapping RADIUS =
            new KeyMapping("key.railplanner.radius", InputConstants.Type.KEYBOARD, InputConstants.KEY_R, CATEGORY);
    /** J：原版没占用（G 被原版「快捷动作」占了）。 */
    public static final KeyMapping AUTOROUTE =
            new KeyMapping("key.railplanner.autoroute", InputConstants.Type.KEYBOARD, InputConstants.KEY_J, CATEGORY);
    public static final KeyMapping GRADE_DOWN =
            new KeyMapping("key.railplanner.grade_down", InputConstants.Type.KEYBOARD, InputConstants.KEY_LBRACKET, CATEGORY);
    public static final KeyMapping GRADE_UP =
            new KeyMapping("key.railplanner.grade_up", InputConstants.Type.KEYBOARD, InputConstants.KEY_RBRACKET, CATEGORY);
    /** 没装 Mod Menu 就靠它打开设置页。U 原版没占用（O 被原版「好友列表」占了）。 */
    /** 折叠/展开地图上的信息面板。H 与原版 debug.showAdvancedTooltips 同键，但那只在 F3 里有作用。 */
    public static final KeyMapping HUD_COLLAPSE =
            new KeyMapping("key.railplanner.hud_collapse", InputConstants.Type.KEYBOARD,
                    InputConstants.KEY_H, CATEGORY);

    public static final KeyMapping SETTINGS =
            new KeyMapping("key.railplanner.settings", InputConstants.Type.KEYBOARD,
                    InputConstants.KEY_U, CATEGORY);

    /** V：世界里那条 3D 线的总开关（跟规划模式分开，截图前可以一键关掉）。 */
    public static final KeyMapping WORLD_TOGGLE =
            new KeyMapping("key.railplanner.world_toggle", InputConstants.Type.KEYBOARD,
                    InputConstants.KEY_V, CATEGORY);
    /** X：3D 线穿山可见（画在所有东西之上）。 */
    public static final KeyMapping WORLD_XRAY =
            new KeyMapping("key.railplanner.world_xray", InputConstants.Type.KEYBOARD,
                    InputConstants.KEY_X, CATEGORY);
    /** L：锁住已选的点，免得按住左键时把点拖歪（地图里、世界里都能按）。 */
    public static final KeyMapping LOCK =
            new KeyMapping("key.railplanner.lock", InputConstants.Type.KEYBOARD,
                    InputConstants.KEY_L, CATEGORY);

    private static final KeyMapping[] ALL = {
            TOGGLE, FINISH, UNDO, RADIUS, AUTOROUTE, GRADE_DOWN, GRADE_UP, HUD_COLLAPSE, SETTINGS,
            WORLD_TOGGLE, WORLD_XRAY, LOCK
    };

    @Override
    public void onInitializeClient() {
        for (KeyMapping mapping : ALL) {
            KeyMappingHelper.registerKeyMapping(mapping);
        }
        RailConfig.load();
        UpdateChecker.init();
        // 同步：服务端回包的接收器 + 进退服两个钩子
        ClientPlayNetworking.registerGlobalReceiver(Payloads.S2C.TYPE, SyncClient::handle);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> SyncClient.onJoin(client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> SyncClient.onDisconnect());
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("railplanner", "status"),
                (graphics, deltaTracker) -> WorldHud.render(graphics));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // 有界面开着时不吃这两个全局键：地图里的按键由 GuiMapMixin 自己接。
            int toggles = drain(TOGGLE);
            int settings = drain(SETTINGS);
            int worldToggles = drain(WORLD_TOGGLE);
            int worldXrays = drain(WORLD_XRAY);
            int lockToggles = drain(LOCK);
            if (client.gui.screen() == null) {
                if (lockToggles > 0) {
                    Planner.toggleLock();
                }
                if (worldToggles > 0) {
                    RailConfig config = RailConfig.get();
                    config.worldLines = !config.worldLines;
                    RailConfig.save();
                    client.gui.hud.setOverlayMessage(
                            Component.literal("世界里的 3D 线 → " + (config.worldLines ? "开" : "关")), false);
                    LOG.info("按键 {}：世界里的 3D 线 → {}", keyName(WORLD_TOGGLE), config.worldLines ? "开" : "关");
                }
                if (worldXrays > 0) {
                    RailConfig config = RailConfig.get();
                    config.worldXray = !config.worldXray;
                    RailConfig.save();
                    client.gui.hud.setOverlayMessage(
                            Component.literal("3D 线穿山可见 → " + (config.worldXray ? "开" : "关")), false);
                    LOG.info("按键 {}：3D 线穿山可见 → {}", keyName(WORLD_XRAY), config.worldXray ? "开" : "关");
                }
                if (toggles > 0) {
                    Planner.toggle(client);
                    LOG.info("规划模式 → {}（按键 {}）", Planner.isActive() ? "开" : "关", keyName(TOGGLE));
                }
                if (settings > 0) {
                    LOG.info("打开设置页（按键 {}）", keyName(SETTINGS));
                    client.gui.setScreen(new RailConfigScreen(null));
                }
            }
            Planner.tick(client);
            SyncClient.tick(client);
            UpdateChecker.tick(client);
        });
    }

    /** 当前绑定的按键名，界面里用来显示「按 X 做什么」。 */
    public static String keyName(KeyMapping mapping) {
        return mapping == null ? "?" : mapping.getTranslatedKeyMessage().getString();
    }

    private static int drain(KeyMapping mapping) {
        int count = 0;
        while (mapping.consumeClick()) {
            count++;
        }
        return count;
    }
}
