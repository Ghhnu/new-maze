package com.mazegen.maze;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import java.util.ArrayList;
import java.util.List;

/**
 * Mantiene la lista de laberintos ya construidos que deben "removerse" solos, tipo Maze
 * Runner, y dispara {@link MazeInstance#shuffleSector()} cada {@link #SHIFT_INTERVAL_TICKS}.
 */
public final class MazeLiveManager {

    /** 30 segundos a 20 ticks/segundo. */
    private static final int SHIFT_INTERVAL_TICKS = 30 * 20;

    private static final List<MazeInstance> active = new ArrayList<>();
    private static boolean registered = false;
    private static int tickCounter = 0;

    private MazeLiveManager() {}

    public static void register(MazeInstance instance) {
        ensureRegistered();
        active.add(instance);
    }

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> tick());
    }

    private static void tick() {
        if (active.isEmpty()) return;
        tickCounter++;
        if (tickCounter < SHIFT_INTERVAL_TICKS) return;
        tickCounter = 0;

        for (MazeInstance instance : active) {
            if (!instance.world().getServer().isRunning()) continue;
            instance.shuffleSector();
        }
    }
}
