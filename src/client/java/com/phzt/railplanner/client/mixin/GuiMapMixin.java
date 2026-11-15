package com.phzt.railplanner.client.mixin;

import com.phzt.railplanner.client.planner.Planner;
import com.phzt.railplanner.client.render.MapView;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.MapProcessor;
import xaero.map.gui.GuiMap;

/** 挂进 Xaero 的世界地图：画线、点击、按键。 */
@Mixin(GuiMap.class)
public abstract class GuiMapMixin {

    @Shadow
    private double cameraX;

    @Shadow
    private double cameraZ;

    @Shadow
    private double scale;

    @Shadow
    private double screenScale;

    private MapView railplanner$view() {
        Screen self = (Screen) (Object) this;
        return new MapView(this.cameraX, this.cameraZ, this.scale, this.screenScale, self.width, self.height);
    }

    @Inject(method = "extractRenderState",
            at = @At(value = "INVOKE",
                    target = "Lxaero/lib/client/gui/ScreenBase;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
                    shift = At.Shift.BEFORE))
    private void railplanner$render(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        Planner.render(g, this.railplanner$view(), mouseX, mouseY, ((GuiMap) (Object) this).getMapProcessor());
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void railplanner$mouseClicked(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (Planner.onClick(this.railplanner$view(), event.button(), event.x(), event.y())) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void railplanner$mouseReleased(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (Planner.onRelease(this.railplanner$view(), event.button(), event.x(), event.y())) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "removed", at = @At("TAIL"))
    private void railplanner$removed(CallbackInfo ci) {
        Planner.saveNow();
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void railplanner$keyPressed(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        MapProcessor mp = ((GuiMap) (Object) this).getMapProcessor();
        if (Planner.onKey(event, this.railplanner$view(), mp)) {
            cir.setReturnValue(true);
        }
    }
}
