package com.wayfarersatlas;

import net.minecraft.client.Minecraft;

/** Kept separate so the common Item class never touches client-only classes directly. */
public final class ClientHooks {
    public static void openMap() {
        Minecraft.getInstance().setScreen(new MapScreen());
    }

    private ClientHooks() {}
}
