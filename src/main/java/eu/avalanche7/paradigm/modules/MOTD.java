package eu.avalanche7.paradigm.modules;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import net.minecraft.command.ICommand;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

import java.util.List;

public class MOTD implements ParadigmModule {

    private static final String NAME = "MOTD";
    private Services services;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isEnabled(Services services) {
        return services.getMainConfig().motdEnable.value;
    }

    @Override
    public void onLoad(FMLPreInitializationEvent event, Services services) {
        this.services = services;
        services.getDebugLogger().debugLog(NAME + " module loaded.");
    }

    @Override
    public void onServerStarting(FMLServerStartingEvent event, Services services) {
        services.getDebugLogger().debugLog(NAME + " module: Server starting.");
    }

    @Override
    public void onEnable(Services services) {
        services.getDebugLogger().debugLog(NAME + " module enabled.");
    }

    @Override
    public void onDisable(Services services) {
        services.getDebugLogger().debugLog(NAME + " module disabled.");
    }

    @Override
    public void onServerStopping(FMLServerStoppingEvent event, Services services) {
        services.getDebugLogger().debugLog(NAME + " module: Server stopping.");
    }

    @Override
    public ICommand getCommand() {
        return null; // This module does not have a command
    }

    @Override
    public void registerEventListeners(Services services) {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (this.services == null || !isEnabled(this.services) || !(event.player instanceof EntityPlayerMP)) {
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        ITextComponent motdMessage = createMOTDMessage(player, this.services);
        player.sendMessage(motdMessage);
        this.services.getDebugLogger().debugLog("Sent MOTD to " + player.getName());
    }

    private ITextComponent createMOTDMessage(EntityPlayerMP player, Services services) {
        if (services == null || services.getMotdConfig() == null) {
            if (services != null && services.getDebugLogger() != null) {
                services.getDebugLogger().debugLog("MOTDModule: Services or MOTDConfig is null in createMOTDMessage.");
            }
            return new TextComponentString("");
        }
        List<String> lines = services.getMotdConfig().motdLines.value;
        if (lines == null || lines.isEmpty()) {
            return new TextComponentString("");
        }

        ITextComponent motdMessage = new TextComponentString("");
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (services.getMessageParser() != null) {
                motdMessage.appendSibling(services.getMessageParser().parseMessage(line, player));
            } else {
                if(services.getDebugLogger() != null) {
                    services.getDebugLogger().debugLog("MOTDModule: MessageParser is null in createMOTDMessage loop.");
                }
                motdMessage.appendSibling(new TextComponentString(line));
            }

            if (i < lines.size() - 1) {
                motdMessage.appendSibling(new TextComponentString("\n"));
            }
        }
        return motdMessage;
    }
}
