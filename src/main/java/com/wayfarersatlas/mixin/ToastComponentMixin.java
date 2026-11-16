package com.wayfarersatlas.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pop-ups in the corner (the tutorial hints like "Punch a tree", advancements, recipes) are not drawn while a screen of
 * the Atlas is open: they would cover its buttons. They show again once it is closed.
 */
@Mixin(ToastComponent.class)
public class ToastComponentMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void wayfarersAtlas$hideOverAtlas(GuiGraphics g, CallbackInfo ci) {
        var screen = Minecraft.getInstance().screen;
        if (screen != null && screen.getClass().getName().startsWith("com.wayfarersatlas.")) ci.cancel();
    }
}
