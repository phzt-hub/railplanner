package com.phzt.railplanner.client.compat;

import com.phzt.railplanner.client.config.RailConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Mod Menu 的配置入口。没装 Mod Menu 时这个类不会被加载。 */
public final class RailPlannerModMenu implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return RailConfigScreen::new;
    }
}
