package com.mazegen.maze;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Representa un laberinto ya construido en el mundo y sabe "removerse" un poco cada
 * cierto tiempo (ver {@link MazeLiveManager}): abre y cierra tramos de pasillo interiores,
 * al estilo Maze Runner. La entrada, la salida y todo el borde perimetral nunca se tocan.
 */
public final class MazeInstance {

    private final ServerWorld world;
    private final MazeGrid grid;
    private final int origX, origZ, baseY;
    private final int wallHeight;
    private final long shuffleSeed;
    private int shuffleCount = 0;

    public MazeInstance(ServerWorld world, MazeGrid grid, int origX, int origZ, int baseY,
                         int wallHeight, long seed) {
        this.world = world;
        this.grid = grid;
        this.origX = origX;
        this.origZ = origZ;
        this.baseY = baseY;
        this.wallHeight = wallHeight;
        this.shuffleSeed = seed ^ 0xA5A5A5A5L;
    }

    public ServerWorld world() {
        return world;
    }

    /** Cambia un puñado de tramos interiores de pasillo: algunos se abren, otros se cierran. */
    public void shuffleSector() {
        Random rnd = new Random(shuffleSeed + (shuffleCount++));

        List<int[]> edges = new ArrayList<>(grid.interiorEdges());
        // Nunca tocamos las aristas que salen directamente de la celda de entrada o de salida,
        // para que el acceso inicial a ambas nunca quede bloqueado por el propio cambio.
        edges.removeIf(e -> touchesCell(e, grid.entranceCellX, grid.entranceCellZ)
                || touchesCell(e, grid.exitCellX, grid.exitCellZ));
        if (edges.isEmpty()) return;

        Collections.shuffle(edges, rnd);

        int totalCells = grid.cellCountX * grid.cellCountZ;
        int amount = clamp(totalCells / 35, 6, 140);
        amount = Math.min(amount, edges.size());

        List<MazeBuildQueue.BlockJob> jobs = new ArrayList<>();
        for (int i = 0; i < amount; i++) {
            int[] e = edges.get(i);
            int cx = e[0], cz = e[1], dir = e[2];
            boolean currentlyOpen = grid.cellsConnected(cx, cz, dir);
            boolean newState = !currentlyOpen;
            grid.setConnectorRegion(cx, cz, dir, newState);
            queueConnectorBlocks(jobs, cx, cz, dir, newState, rnd);
        }

        MazeBuildQueue.submit(jobs);
    }

    private boolean touchesCell(int[] edge, int cx, int cz) {
        int ex = edge[0], ez = edge[1], dir = edge[2];
        if (ex == cx && ez == cz) return true;
        int nx = ex + (dir == 0 ? 1 : dir == 1 ? -1 : 0);
        int nz = ez + (dir == 2 ? 1 : dir == 3 ? -1 : 0);
        return nx == cx && nz == cz;
    }

    private void queueConnectorBlocks(List<MazeBuildQueue.BlockJob> jobs, int cx, int cz, int dir,
                                       boolean openState, Random rnd) {
        int[] b = grid.connectorBounds(cx, cz, dir);
        for (int x = b[0]; x <= b[1]; x++) {
            for (int z = b[2]; z <= b[3]; z++) {
                BlockPos worldPos = new BlockPos(origX + x, baseY, origZ + z);
                if (openState) {
                    jobs.add(new MazeBuildQueue.BlockJob(world, worldPos, pickFloorMaterial(rnd)));
                    for (int y = 1; y <= wallHeight; y++) {
                        jobs.add(new MazeBuildQueue.BlockJob(world, worldPos.up(y), Blocks.AIR.getDefaultState()));
                    }
                } else {
                    BlockState wallState = pickWallMaterial(rnd);
                    for (int y = 1; y <= wallHeight; y++) {
                        jobs.add(new MazeBuildQueue.BlockJob(world, worldPos.up(y), wallState));
                    }
                }
            }
        }
    }

    private static BlockState pickWallMaterial(Random rnd) {
        double r = rnd.nextDouble();
        if (r < 0.40) return Blocks.STONE.getDefaultState();
        if (r < 0.75) return Blocks.COBBLESTONE.getDefaultState();
        return Blocks.MOSSY_COBBLESTONE.getDefaultState();
    }

    private static BlockState pickFloorMaterial(Random rnd) {
        double r = rnd.nextDouble();
        if (r < 0.35) return Blocks.MOSSY_COBBLESTONE.getDefaultState();
        if (r < 0.70) return Blocks.MOSS_BLOCK.getDefaultState();
        return Blocks.CALCITE.getDefaultState();
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
