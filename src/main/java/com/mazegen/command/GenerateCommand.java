package com.mazegen.command;

import com.mazegen.maze.MazeBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

public final class GenerateCommand {

    private GenerateCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("generate")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.literal("maze")
                                .then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
                                        .executes(GenerateCommand::runDefault)
                                        .then(CommandManager.literal("coordenadas")
                                                .then(CommandManager.argument("sizeX",
                                                                IntegerArgumentType.integer(MazeBuilder.MIN_CELL_COUNT, MazeBuilder.MAX_CELL_COUNT))
                                                        .then(CommandManager.argument("sizeZ",
                                                                        IntegerArgumentType.integer(MazeBuilder.MIN_CELL_COUNT, MazeBuilder.MAX_CELL_COUNT))
                                                                .executes(GenerateCommand::runSized))))))));
    }

    private static int runDefault(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
        return build(ctx, MazeBuilder.DEFAULT_CELL_COUNT, MazeBuilder.DEFAULT_CELL_COUNT);
    }

    private static int runSized(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
        int sizeX = IntegerArgumentType.getInteger(ctx, "sizeX");
        int sizeZ = IntegerArgumentType.getInteger(ctx, "sizeZ");
        return build(ctx, sizeX, sizeZ);
    }

    private static int build(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx, int sizeX, int sizeZ) {
        ServerCommandSource source = ctx.getSource();
        BlockPos center = BlockPosArgumentType.getBlockPos(ctx, "pos");

        if (!(source.getWorld() instanceof ServerWorld world)) {
            source.sendError(Text.literal("No se pudo obtener el mundo del servidor."));
            return 0;
        }

        MazeBuilder.generate(world, center, source, sizeX, sizeZ);
        return 1;
    }
}
