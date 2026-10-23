package com.wayfarersatlas;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only part: keys, minimap layer, map screens. */
@Mod(value = WayfarersAtlas.MODID, dist = Dist.CLIENT)
public class WayfarersAtlasClient {
    public WayfarersAtlasClient(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, AtlasConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new); // Mods -> Config
        modBus.addListener(Keys::register);
        modBus.addListener(MinimapLayer::register);
        NeoForge.EVENT_BUS.addListener(ClientEvents::onTick);
        NeoForge.EVENT_BUS.addListener(ClientEvents::onLogin);
        NeoForge.EVENT_BUS.addListener(ClientEvents::onLogout);
        NeoForge.EVENT_BUS.addListener(ClientEvents::onChat);
        NeoForge.EVENT_BUS.addListener(ClientEvents::onClientCommands);
    }
}
