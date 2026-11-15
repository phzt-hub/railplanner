package com.phzt.railplanner;

import com.phzt.railplanner.sync.Payloads;
import com.phzt.railplanner.sync.ServerSync;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 双端入口：客户端和服务端都会加载这个类。
 *
 * <p>它只做两件事——把两条自定义通道登记进网络层、把服务端的三个处理器挂上。
 * 服务端不认识路线怎么画，它只负责按 UUID 存和取。</p>
 */
public final class RailPlanner implements ModInitializer {

    public static final String MOD_ID = "railplanner";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    public static String version() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("0.0.0");
    }

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.serverboundPlay().registerLarge(Payloads.C2S.TYPE, Payloads.C2S.CODEC, Payloads.MAX);
        PayloadTypeRegistry.clientboundPlay().registerLarge(Payloads.S2C.TYPE, Payloads.S2C.CODEC, Payloads.MAX);
        ServerPlayNetworking.registerGlobalReceiver(Payloads.C2S.TYPE, (payload, context) -> ServerSync.handle(payload, context));
        LOG.info("Rail Planner {} 已加载（{}，通道 {} / {}）", version(),
                FabricLoader.getInstance().getEnvironmentType(), Payloads.C2S.TYPE.id(), Payloads.S2C.TYPE.id());
    }
}
