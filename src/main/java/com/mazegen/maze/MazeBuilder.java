package com.mazegen.maze;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.VineBlock;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Traduce una {@link MazeGrid} a una lista de bloques reales y la manda a construir.
 * También coloca spawners, cofres de botín y el cofre especial de la salida, y registra
 * el laberinto en {@link MazeLiveManager} para que empiece a "removerse" solo cuando la
 * construcción inicial termine.
 */
public final class MazeBuilder {

    /** Celdas por lado por defecto si el comando no especifica tamaño. */
    public static final int DEFAULT_CELL_COUNT = 100;
    public static final int MIN_CELL_COUNT = 5;
    public static final int MAX_CELL_COUNT = 200;

    private static final int WALL_HEIGHT = 4;     // bloques de pared, de suelo+1 a suelo+WALL_HEIGHT
    private static final int TORCH_EVERY_CELLS = 3;
    private static final double VINE_CHANCE = 0.14;
    private static final int VINE_MIN_LEN = 1;
    private static final int VINE_MAX_LEN = 3;

    // 0=este,1=oeste,2=sur,3=norte -> Direction real de Minecraft
    private static final Direction[] DIR_TO_MC = {Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH};
    private static final int[] DX = {1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 1, -1};

    private MazeBuilder() {}

    public static void generate(ServerWorld world, BlockPos center, ServerCommandSource source) {
        generate(world, center, source, DEFAULT_CELL_COUNT, DEFAULT_CELL_COUNT);
    }

    public static void generate(ServerWorld world, BlockPos center, ServerCommandSource source,
                                 int cellCountX, int cellCountZ) {
        long seed = new Random().nextLong() ^ System.nanoTime();
        MazeGrid grid = new MazeGrid(cellCountX, cellCountZ, seed);

        int origX = center.getX() - grid.blockSizeX / 2;
        int origZ = center.getZ() - grid.blockSizeZ / 2;
        int baseY = center.getY();

        List<MazeBuildQueue.BlockJob> jobs = new ArrayList<>(grid.blockSizeX * grid.blockSizeZ * (WALL_HEIGHT + 2));
        Random rnd = new Random(seed ^ 0x9E3779B97F4A7C15L);

        // --- Suelo, paredes/aire y techo ---
        for (int x = 0; x < grid.blockSizeX; x++) {
            for (int z = 0; z < grid.blockSizeZ; z++) {
                boolean open = grid.open[x][z];
                BlockPos worldPos = new BlockPos(origX + x, baseY, origZ + z);

                BlockState floorState;
                if (grid.isEntranceFloorBlock(x, z)) {
                    floorState = Blocks.RED_CONCRETE.getDefaultState();
                } else if (grid.isExitFloorBlock(x, z)) {
                    floorState = Blocks.GREEN_CONCRETE.getDefaultState();
                } else {
                    floorState = pickFloorMaterial(rnd);
                }
                jobs.add(new MazeBuildQueue.BlockJob(world, worldPos, floorState));

                if (open) {
                    for (int y = 1; y <= WALL_HEIGHT; y++) {
                        jobs.add(new MazeBuildQueue.BlockJob(world, worldPos.up(y), Blocks.AIR.getDefaultState()));
                    }
                } else {
                    BlockState wallState = pickWallMaterial(rnd);
                    for (int y = 1; y <= WALL_HEIGHT; y++) {
                        jobs.add(new MazeBuildQueue.BlockJob(world, worldPos.up(y), wallState));
                    }
                }

                // techo de cristal sobre todo el recinto
                jobs.add(new MazeBuildQueue.BlockJob(world, worldPos.up(WALL_HEIGHT + 1), Blocks.GLASS.getDefaultState()));
            }
        }

        // --- Antorchas y enredaderas en caras de pared que dan a un pasillo ---
        for (int x = 0; x < grid.blockSizeX; x++) {
            for (int z = 0; z < grid.blockSizeZ; z++) {
                if (grid.open[x][z]) continue; // solo nos interesan columnas sólidas

                for (int d = 0; d < 4; d++) {
                    int nx = x + DX[d], nz = z + DZ[d];
                    if (nx < 0 || nz < 0 || nx >= grid.blockSizeX || nz >= grid.blockSizeZ) continue;
                    if (!grid.open[nx][nz]) continue; // el vecino tiene que ser pasillo

                    BlockPos neighborBase = new BlockPos(origX + nx, baseY, origZ + nz);
                    Direction facingIntoRoom = DIR_TO_MC[d];       // hacia dónde "mira" la antorcha
                    Direction attachSide = facingIntoRoom.getOpposite(); // cara de la enredadera pegada a la pared

                    // Antorchas: patrón periódico y determinista según la celda, no 100% aleatorio.
                    if (Math.floorMod(x + z, TORCH_EVERY_CELLS * 3) == 0) {
                        BlockPos torchPos = neighborBase.up(2);
                        jobs.add(new MazeBuildQueue.BlockJob(world, torchPos,
                                Blocks.WALL_TORCH.getDefaultState().with(Properties.HORIZONTAL_FACING, facingIntoRoom)));
                    }

                    // Enredaderas: solo estético, en algunos tramos cerca del techo, nunca cubriendo todo.
                    if (rnd.nextDouble() < VINE_CHANCE) {
                        int len = VINE_MIN_LEN + rnd.nextInt(VINE_MAX_LEN - VINE_MIN_LEN + 1);
                        BlockState vineState = Blocks.VINE.getDefaultState().with(vineProperty(attachSide), true);
                        for (int i = 0; i < len; i++) {
                            int y = WALL_HEIGHT - i;
                            if (y < 1) break;
                            jobs.add(new MazeBuildQueue.BlockJob(world, neighborBase.up(y), vineState));
                        }
                    }
                }
            }
        }

        // --- Puertas exteriores en entrada y salida ---
        carveExteriorOpening(jobs, world, grid, origX, origZ, baseY, grid.entranceCellX, grid.entranceCellZ, grid.entranceDir);
        carveExteriorOpening(jobs, world, grid, origX, origZ, baseY, grid.exitCellX, grid.exitCellZ, grid.exitDir);

        // --- Spawners y cofres de botín repartidos con rareza por el interior ---
        placeLootAndSpawners(jobs, world, grid, origX, origZ, baseY, rnd);

        // --- Cofre especial de la salida, con el botín "épico" ---
        placeExitChest(jobs, world, grid, origX, origZ, baseY);

        ServerPlayerEntity player = source.getEntity() instanceof ServerPlayerEntity p ? p : null;
        source.sendFeedback(() -> Text.literal(String.format(
                "§6[MazeGen] §fConstruyendo laberinto de §a%dx%d §fceldas en (%d, %d, %d)... (%d bloques)",
                grid.cellCountX, grid.cellCountZ, center.getX(), center.getY(), center.getZ(), jobs.size())), false);

        long startMillis = System.currentTimeMillis();
        int totalBlocks = jobs.size();

        MazeBuildQueue.submit(jobs, () -> {
            double seconds = (System.currentTimeMillis() - startMillis) / 1000.0;
            if (player != null && player.isAlive()) {
                player.sendMessage(
                        Text.literal(String.format(
                                "§6[MazeGen] §fLaberinto completado: §a%d §fbloques en §a%.1fs§f. §7El laberinto empezará a cambiar cada 30s.",
                                totalBlocks, seconds)),
                        false);
            }
            MazeLiveManager.register(new MazeInstance(world, grid, origX, origZ, baseY, WALL_HEIGHT, seed));
        });
    }

    /** Abre un hueco de FLOOR_W bloques de ancho en la pared exterior de una celda de borde, hacia fuera del recinto. */
    private static void carveExteriorOpening(List<MazeBuildQueue.BlockJob> jobs, ServerWorld world, MazeGrid grid,
                                              int origX, int origZ, int baseY, int cellX, int cellZ, int dir) {
        int[] b = grid.connectorBounds(cellX, cellZ, dir);
        for (int x = b[0]; x <= b[1]; x++) {
            for (int z = b[2]; z <= b[3]; z++) {
                BlockPos base = new BlockPos(origX + x, baseY, origZ + z);
                for (int y = 1; y <= WALL_HEIGHT; y++) {
                    jobs.add(new MazeBuildQueue.BlockJob(world, base.up(y), Blocks.AIR.getDefaultState()));
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Spawners y cofres de botín "normal"
    // ------------------------------------------------------------------

    private static void placeLootAndSpawners(List<MazeBuildQueue.BlockJob> jobs, ServerWorld world, MazeGrid grid,
                                              int origX, int origZ, int baseY, Random rnd) {
        List<int[]> candidates = new ArrayList<>();
        for (int cx = 0; cx < grid.cellCountX; cx++) {
            for (int cz = 0; cz < grid.cellCountZ; cz++) {
                if (cx == grid.entranceCellX && cz == grid.entranceCellZ) continue;
                if (cx == grid.exitCellX && cz == grid.exitCellZ) continue;
                candidates.add(new int[]{cx, cz});
            }
        }
        java.util.Collections.shuffle(candidates, rnd);

        int totalCells = grid.cellCountX * grid.cellCountZ;
        int spawnerCount = clamp(totalCells / 120, 3, 60);
        int chestCount = clamp(totalCells / 80, 4, 80);
        int needed = Math.min(candidates.size(), spawnerCount + chestCount);

        EntityType<?>[] spawnerMobs = {EntityType.ZOMBIE, EntityType.BLAZE, EntityType.WITHER_SKELETON};

        int idx = 0;
        for (int i = 0; i < needed; i++) {
            int[] cell = candidates.get(idx++);
            int bx = grid.cellBaseX(cell[0]) + MazeGrid.FLOOR_W / 2;
            int bz = grid.cellBaseZ(cell[1]) + MazeGrid.FLOOR_W / 2;
            BlockPos pos = new BlockPos(origX + bx, baseY + 1, origZ + bz);

            if (i < spawnerCount) {
                EntityType<?> mob = spawnerMobs[i % spawnerMobs.length];
                jobs.add(new MazeBuildQueue.BlockJob(world, pos, Blocks.SPAWNER.getDefaultState(),
                        (w, p) -> {
                            if (w.getBlockEntity(p) instanceof MobSpawnerBlockEntity spawner) {
                                NbtCompound entityNbt = new NbtCompound();
                                entityNbt.putString("id", EntityType.getId(mob).toString());
                                NbtCompound spawnData = new NbtCompound();
                                spawnData.put("entity", entityNbt);
                                NbtCompound spawnerNbt = new NbtCompound();
                                spawnerNbt.put("SpawnData", spawnData);
                                spawner.getLogic().readNbt(w, p, spawnerNbt);
                                spawner.markDirty();
                            }
                        }));
            } else {
                List<ItemStack> contents = rollChestContents(rnd);
                jobs.add(new MazeBuildQueue.BlockJob(world, pos, Blocks.CHEST.getDefaultState(),
                        (w, p) -> {
                            if (w.getBlockEntity(p) instanceof ChestBlockEntity chest) {
                                for (int slot = 0; slot < contents.size(); slot++) {
                                    chest.setStack(slot, contents.get(slot));
                                }
                                chest.markDirty();
                            }
                        }));
            }
        }
    }

    /** Chuletas siempre, 20% espada de diamante + escudo, 50% (independiente) 2 lingotes de netherite. */
    private static List<ItemStack> rollChestContents(Random rnd) {
        List<ItemStack> contents = new ArrayList<>();
        contents.add(new ItemStack(Items.COOKED_PORKCHOP, 2 + rnd.nextInt(5)));
        if (rnd.nextDouble() < 0.20) {
            contents.add(new ItemStack(Items.DIAMOND_SWORD));
            contents.add(new ItemStack(Items.SHIELD));
        }
        if (rnd.nextDouble() < 0.50) {
            contents.add(new ItemStack(Items.NETHERITE_INGOT, 2));
        }
        return contents;
    }

    // ------------------------------------------------------------------
    // Cofre especial de la salida
    // ------------------------------------------------------------------

    private static void placeExitChest(List<MazeBuildQueue.BlockJob> jobs, ServerWorld world, MazeGrid grid,
                                        int origX, int origZ, int baseY) {
        int bx = grid.cellBaseX(grid.exitCellX) + MazeGrid.FLOOR_W / 2;
        int bz = grid.cellBaseZ(grid.exitCellZ) + MazeGrid.FLOOR_W / 2;
        BlockPos pos = new BlockPos(origX + bx, baseY + 1, origZ + bz);

        RegistryWrapper.WrapperLookup lookup = world.getRegistryManager();
        List<ItemStack> contents = new ArrayList<>();
        contents.add(fullyEnchant(new ItemStack(Items.ELYTRA), lookup, elytraEnchants()));
        for (int i = 0; i < 3; i++) contents.add(fullyEnchant(new ItemStack(Items.MACE), lookup, maceEnchants()));
        for (int i = 0; i < 3; i++) contents.add(fullyEnchant(new ItemStack(Items.TRIDENT), lookup, tridentEnchants()));

        jobs.add(new MazeBuildQueue.BlockJob(world, pos, Blocks.CHEST.getDefaultState(),
                (w, p) -> {
                    if (w.getBlockEntity(p) instanceof ChestBlockEntity chest) {
                        for (int slot = 0; slot < contents.size(); slot++) {
                            chest.setStack(slot, contents.get(slot));
                        }
                        chest.markDirty();
                    }
                }));
    }

    private static Map<RegistryKey<Enchantment>, Integer> elytraEnchants() {
        Map<RegistryKey<Enchantment>, Integer> m = new LinkedHashMap<>();
        m.put(Enchantments.UNBREAKING, 3);
        m.put(Enchantments.MENDING, 1);
        return m;
    }

    private static Map<RegistryKey<Enchantment>, Integer> maceEnchants() {
        Map<RegistryKey<Enchantment>, Integer> m = new LinkedHashMap<>();
        m.put(Enchantments.DENSITY, 5);
        m.put(Enchantments.BREACH, 4);
        m.put(Enchantments.WIND_BURST, 3);
        m.put(Enchantments.UNBREAKING, 3);
        m.put(Enchantments.MENDING, 1);
        return m;
    }

    private static Map<RegistryKey<Enchantment>, Integer> tridentEnchants() {
        Map<RegistryKey<Enchantment>, Integer> m = new LinkedHashMap<>();
        m.put(Enchantments.IMPALING, 5);
        m.put(Enchantments.LOYALTY, 3);
        m.put(Enchantments.UNBREAKING, 3);
        m.put(Enchantments.MENDING, 1);
        return m;
    }

    private static ItemStack fullyEnchant(ItemStack stack, RegistryWrapper.WrapperLookup lookup,
                                           Map<RegistryKey<Enchantment>, Integer> enchants) {
        RegistryWrapper.Impl<Enchantment> registry = lookup.getWrapperOrThrow(RegistryKeys.ENCHANTMENT);
        ItemEnchantmentsComponent.Builder builder = new ItemEnchantmentsComponent.Builder(ItemEnchantmentsComponent.DEFAULT);
        for (Map.Entry<RegistryKey<Enchantment>, Integer> e : enchants.entrySet()) {
            registry.getOptional(e.getKey()).ifPresent(entry -> builder.add(entry, e.getValue()));
        }
        stack.set(DataComponentTypes.ENCHANTMENTS, builder.build());
        return stack;
    }

    // ------------------------------------------------------------------

    private static net.minecraft.state.property.BooleanProperty vineProperty(Direction side) {
        return switch (side) {
            case NORTH -> VineBlock.NORTH;
            case SOUTH -> VineBlock.SOUTH;
            case EAST -> VineBlock.EAST;
            case WEST -> VineBlock.WEST;
            default -> VineBlock.UP;
        };
    }

    private static BlockState pickWallMaterial(Random rnd) {
        double r = rnd.nextDouble();
        if (r < 0.40) return Blocks.STONE.getDefaultState();
        if (r < 0.75) return Blocks.COBBLESTONE.getDefaultState();
        return Blocks.MOSSY_COBBLESTONE.getDefaultState();
    }

    private static BlockState pickFloorMaterial(Random rnd) {
        double r = rnd.nextDouble();
        // "Pale Moss Block" no existe en 1.21.1 (llegó en 1.21.4); se sustituye por Calcita
        // para dar ese contraste de piedra clara/pálida entre el musgo.
        if (r < 0.35) return Blocks.MOSSY_COBBLESTONE.getDefaultState();
        if (r < 0.70) return Blocks.MOSS_BLOCK.getDefaultState();
        return Blocks.CALCITE.getDefaultState();
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
