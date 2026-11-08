package brama;

import brama.command.GateCommand;
import brama.siege.GateWatch;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge-bus game events. Separate from {@code brama.client.ClientSetup}, which is mod-bus and
 * client-only and so cannot carry these.
 */
@Mod.EventBusSubscriber(modid = Brama.MODID)
public final class ModEvents {

    private ModEvents() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        GateCommand.register(event.getDispatcher());
    }

    /**
     * Drives the siege watch. End phase, so the gates react to where everyone has actually moved to
     * this tick rather than to where they were before it.
     */
    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.level instanceof ServerLevel level) {
            GateWatch.tick(level);
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            GateWatch.levelUnloaded(level);
        }
    }

    /**
     * The gate register and the chosen claim provider are static, and a client can open one
     * single-player world after another in the same process, so both are cleared between servers.
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        GateWatch.serverStopped();
    }
}
