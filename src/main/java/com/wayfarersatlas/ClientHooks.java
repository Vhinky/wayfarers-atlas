package com.wayfarersatlas;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Kept separate so the common Item / Block classes never touch client-only classes directly. */
public final class ClientHooks {
    public static void openMap() {
        Minecraft.getInstance().setScreen(new MapScreen());
    }

    public static void openTable(BlockPos pos) {
        Minecraft.getInstance().setScreen(new TableScreen(pos));
    }

    private ClientHooks() {}
}
