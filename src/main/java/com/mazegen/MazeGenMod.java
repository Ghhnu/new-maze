package com.mazegen;

import com.mazegen.command.GenerateCommand;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MazeGenMod implements ModInitializer {

    public static final String MOD_ID = "mazegen";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[MazeGen] Inicializando MazeGen...");
        GenerateCommand.register();
    }
}
