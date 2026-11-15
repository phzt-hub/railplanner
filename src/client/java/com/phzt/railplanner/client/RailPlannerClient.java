package com.phzt.railplanner.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.phzt.railplanner.client.planner.Planner;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

public final class RailPlannerClient implements ClientModInitializer {

    public static final KeyMapping TOGGLE = new KeyMapping(
            "key.railplanner.toggle",
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_B,
            KeyMapping.Category.MISC);

    @Override
    public void onInitializeClient() {
        KeyMappingHelper.registerKeyMapping(TOGGLE);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (TOGGLE.consumeClick()) {
                Planner.toggle(client);
            }
            Planner.tick(client);
        });
    }
}
