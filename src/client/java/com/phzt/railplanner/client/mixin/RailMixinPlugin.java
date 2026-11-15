package com.phzt.railplanner.client.mixin;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 装没装 Xaero、装的是哪个版本，决定要不要织地图那一层。
 *
 * <p>服务端也会加载这个 jar，而 Xaero 是纯客户端模组，所以它不能写成必须依赖；
 * 客户端没装 Xaero 时这个插件把 {@code GuiMapMixin} 跳过，游戏照常能进，
 * 只是没有地图上的规划界面。</p>
 *
 * <p>装了也要对版本：{@code GuiMapMixin} 织的是 Xaero 1.47.x 的内部方法，一行
 * {@code @Inject} 对不上就是「开游戏就崩」，而且崩在 mixin 层，玩家读不懂。
 * 所以只认织过并实测过的 1.47.x，别的版本一律跳过地图层并在日志里写清原因——
 * 地图上的规划界面没了，世界里那条 3D 线照常可用。</p>
 */
public final class RailMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOG = LoggerFactory.getLogger("railplanner/mixin");

    /** 织过并实测过的那条小版本线。 */
    private static final int TESTED_MAJOR = 1;
    private static final int TESTED_MINOR = 47;

    /** 版本号只取开头两段数字，Xaero 的写法是 1.47.2 这种。 */
    private static final Pattern LEADING_VERSION = Pattern.compile("^(\\d+)\\.(\\d+)");

    private static final boolean XAERO_OK = checkXaero();

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
            return XAERO_OK;
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

    /** 装了 Xaero，而且版本正落在织过的那条线上。 */
    private static boolean checkXaero() {
        Optional<ModContainer> mod = FabricLoader.getInstance().getModContainer("xaeroworldmap");
        if (mod.isEmpty()) {
            LOG.info("没装 Xaero 世界地图：跳过地图上的规划界面（世界里那条 3D 线仍然可用）");
            return false;
        }
        String raw = mod.get().getMetadata().getVersion().getFriendlyString();
        Matcher m = LEADING_VERSION.matcher(raw == null ? "" : raw.trim());
        if (!m.find()) {
            LOG.warn("认不出 Xaero 世界地图的版本号「{}」：跳过地图上的规划界面（世界里那条 3D 线仍然可用）", raw);
            return false;
        }
        int major = Integer.parseInt(m.group(1));
        int minor = Integer.parseInt(m.group(2));
        if (major != TESTED_MAJOR || minor != TESTED_MINOR) {
            LOG.warn("Xaero 世界地图 {} 不在织过并验证过的 {}.{}x 上：本次跳过地图上的规划界面"
                            + "（世界里那条 3D 线仍然可用）；换回 {}.{}x 的 Xaero 即可恢复地图上的规划界面。",
                    raw, TESTED_MAJOR, TESTED_MINOR, TESTED_MAJOR, TESTED_MINOR);
            return false;
        }
        LOG.info("Xaero 世界地图 {}：织入地图上的规划界面", raw);
        return true;
    }
}
