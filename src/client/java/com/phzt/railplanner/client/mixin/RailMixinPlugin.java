package com.phzt.railplanner.client.mixin;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * 装没装 Xaero 决定要不要织地图那一层。
 *
 * <p>服务端也会加载这个 jar，而 Xaero 是纯客户端模组，所以它不能写成必须依赖；
 * 客户端没装 Xaero 时这个插件把 {@code GuiMapMixin} 跳过，游戏照常能进，
 * 只是没有地图上的规划界面。</p>
 */
public final class RailMixinPlugin implements IMixinConfigPlugin {

    private static final boolean XAERO = FabricLoader.getInstance().isModLoaded("xaeroworldmap");

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith("GuiMapMixin")) {
            return XAERO;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
