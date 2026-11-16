package com.phzt.railplanner.client.mixin;

import com.phzt.railplanner.client.world.ClearanceGizmos;
import com.phzt.railplanner.client.world.RouteGizmos;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.debug.DebugRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 借原版收集 gizmos 的时机把铁路线塞进同一批。
 *
 * <p>{@code DebugRenderer.emitGizmos} 由 {@code LevelExtractor.extract} 每帧调用一次，那时
 * {@code Minecraft} 已经用 {@code Gizmos$TemporaryCollection} 装好收集器，往 {@code Gizmos.line}
 * 里塞的线会跟原版调试线一起提交、一起画。直接放在 {@code GameRenderer} 里调用会没有收集器。
 */
@Mixin(DebugRenderer.class)
public abstract class DebugRendererMixin {

    @Inject(method = "emitGizmos", at = @At("TAIL"))
    private void railplanner$emitGizmos(Frustum frustum, double camX, double camY, double camZ,
                                        float partialTick, CallbackInfo ci) {
        RouteGizmos.emit(camX, camY, camZ);
        // 限界红框跟 3D 线共用同一个收集时机，但开关是各自的：关掉 3D 线照样能看限界。
        ClearanceGizmos.emit(camX, camY, camZ);
    }
}
