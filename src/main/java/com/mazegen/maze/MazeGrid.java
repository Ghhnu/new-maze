package com.mazegen.maze;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * Genera la "planta" lógica de un laberinto perfecto (sin bucles, siempre resoluble)
 * usando el algoritmo de backtracking aleatorio (randomized DFS) sobre una rejilla de celdas.
 * <p>
 * Cada celda ocupa {@link #FLOOR_W} bloques de pasillo transitable, separada de sus vecinas
 * por un muro de {@link #WALL_W} bloques de grosor. Al abrir una conexión entre dos celdas se
 * talla un hueco de {@code FLOOR_W} de ancho a través de ese muro.
 * <p>
 * {@link #open} es la rejilla a nivel de bloque: true = suelo transitable, false = pared sólida.
 * Soporta rejillas no cuadradas (cellCountX distinto de cellCountZ).
 */
public class MazeGrid {

    /** Ancho de pasillo, en bloques. */
    public static final int FLOOR_W = 3;
    /** Grosor de las paredes (incluido el borde exterior), en bloques. */
    public static final int WALL_W = 3;
    /** Bloques que ocupa una celda + su muro siguiente. */
    public static final int STEP = FLOOR_W + WALL_W;

    private static final int[] DX = {1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 1, -1};
    // dir 0 = este (+X), 1 = oeste (-X), 2 = sur (+Z), 3 = norte (-Z)

    public final int cellCountX, cellCountZ;   // celdas por lado (X y Z pueden diferir)
    public final int blockSizeX, blockSizeZ;   // cellCount * STEP + WALL_W
    public final boolean[][] open;             // [x][z] a nivel de bloque

    public int entranceCellX, entranceCellZ, entranceDir;
    public int exitCellX, exitCellZ, exitDir;

    public MazeGrid(int cellCountX, int cellCountZ, long seed) {
        this.cellCountX = cellCountX;
        this.cellCountZ = cellCountZ;
        this.blockSizeX = cellCountX * STEP + WALL_W;
        this.blockSizeZ = cellCountZ * STEP + WALL_W;
        this.open = new boolean[blockSizeX][blockSizeZ];
        generate(seed);
    }

    private void generate(long seed) {
        Random rnd = new Random(seed);
        boolean[][] visited = new boolean[cellCountX][cellCountZ];
        Deque<int[]> stack = new ArrayDeque<>();

        visited[0][0] = true;
        carveCellFloor(0, 0);
        stack.push(new int[]{0, 0});

        while (!stack.isEmpty()) {
            int[] cur = stack.peek();
            int cx = cur[0], cz = cur[1];

            List<Integer> dirs = new ArrayList<>(List.of(0, 1, 2, 3));
            Collections.shuffle(dirs, rnd);

            boolean carved = false;
            for (int d : dirs) {
                int nx = cx + DX[d];
                int nz = cz + DZ[d];
                if (nx < 0 || nz < 0 || nx >= cellCountX || nz >= cellCountZ) continue;
                if (visited[nx][nz]) continue;

                visited[nx][nz] = true;
                carveCellFloor(nx, nz);
                setConnectorRegion(cx, cz, d, true);
                stack.push(new int[]{nx, nz});
                carved = true;
                break;
            }
            if (!carved) stack.pop();
        }

        pickEntranceAndExit(rnd);
    }

    public int cellBaseX(int cx) {
        return cx * STEP + WALL_W;
    }

    public int cellBaseZ(int cz) {
        return cz * STEP + WALL_W;
    }

    // Offset +WALL_W: la banda de borde queda siempre reservada como muro perimetral
    // (nunca se talla), así el recinto queda cerrado por los 4 lados salvo en los
    // huecos explícitos de entrada y salida.
    private void carveCellFloor(int cx, int cz) {
        int bx = cellBaseX(cx), bz = cellBaseZ(cz);
        for (int x = bx; x < bx + FLOOR_W; x++) {
            for (int z = bz; z < bz + FLOOR_W; z++) {
                open[x][z] = true;
            }
        }
    }

    /**
     * Devuelve {x0, x1, z0, z1} (inclusive) del bloque de bloques que conecta la celda
     * (cx, cz) con su vecina en la dirección dir, atravesando el muro de WALL_W de grosor.
     * También sirve para calcular el hueco de salida al exterior en un borde del recinto,
     * ya que la banda perimetral tiene exactamente WALL_W de grosor por construcción.
     */
    public int[] connectorBounds(int cx, int cz, int dir) {
        int bx = cellBaseX(cx), bz = cellBaseZ(cz);
        return switch (dir) {
            case 0 -> new int[]{bx + FLOOR_W, bx + FLOOR_W + WALL_W - 1, bz, bz + FLOOR_W - 1};          // este
            case 1 -> new int[]{bx - WALL_W, bx - 1, bz, bz + FLOOR_W - 1};                               // oeste
            case 2 -> new int[]{bx, bx + FLOOR_W - 1, bz + FLOOR_W, bz + FLOOR_W + WALL_W - 1};           // sur
            default -> new int[]{bx, bx + FLOOR_W - 1, bz - WALL_W, bz - 1};                              // norte
        };
    }

    public void setConnectorRegion(int cx, int cz, int dir, boolean value) {
        int[] b = connectorBounds(cx, cz, dir);
        for (int x = b[0]; x <= b[1]; x++) {
            for (int z = b[2]; z <= b[3]; z++) {
                open[x][z] = value;
            }
        }
    }

    public boolean cellsConnected(int cx, int cz, int dir) {
        int[] b = connectorBounds(cx, cz, dir);
        return open[b[0]][b[2]];
    }

    /** Todas las aristas interiores (celda-celda) del laberinto, cada una representada una vez. */
    public List<int[]> interiorEdges() {
        List<int[]> edges = new ArrayList<>();
        for (int cx = 0; cx < cellCountX; cx++) {
            for (int cz = 0; cz < cellCountZ; cz++) {
                if (cx + 1 < cellCountX) edges.add(new int[]{cx, cz, 0});
                if (cz + 1 < cellCountZ) edges.add(new int[]{cx, cz, 2});
            }
        }
        return edges;
    }

    private int[][] bfsDistances(int sx, int sz) {
        int[][] dist = new int[cellCountX][cellCountZ];
        for (int[] row : dist) Arrays.fill(row, -1);
        Deque<int[]> q = new ArrayDeque<>();
        dist[sx][sz] = 0;
        q.add(new int[]{sx, sz});
        while (!q.isEmpty()) {
            int[] c = q.poll();
            int cx = c[0], cz = c[1];
            for (int d = 0; d < 4; d++) {
                int nx = cx + DX[d], nz = cz + DZ[d];
                if (nx < 0 || nz < 0 || nx >= cellCountX || nz >= cellCountZ) continue;
                if (dist[nx][nz] != -1) continue;
                if (!cellsConnected(cx, cz, d)) continue;
                dist[nx][nz] = dist[cx][cz] + 1;
                q.add(new int[]{nx, nz});
            }
        }
        return dist;
    }

    /** La entrada siempre es la celda (0,0) con apertura hacia el norte del recinto. */
    private void pickEntranceAndExit(Random rnd) {
        entranceCellX = 0;
        entranceCellZ = 0;
        entranceDir = 3; // norte

        int[][] dist = bfsDistances(0, 0);
        int bestX = 0, bestZ = 0, bestDist = -1, bestDir = 2;

        for (int i = 0; i < cellCountX; i++) {
            for (int j = 0; j < cellCountZ; j++) {
                boolean edge = (i == 0 || j == 0 || i == cellCountX - 1 || j == cellCountZ - 1);
                if (!edge) continue;
                if (i == entranceCellX && j == entranceCellZ) continue;
                if (dist[i][j] > bestDist) {
                    bestDist = dist[i][j];
                    bestX = i;
                    bestZ = j;
                    if (i == 0) bestDir = 1;
                    else if (i == cellCountX - 1) bestDir = 0;
                    else if (j == 0) bestDir = 3;
                    else bestDir = 2;
                }
            }
        }
        exitCellX = bestX;
        exitCellZ = bestZ;
        exitDir = bestDir;
    }

    public boolean isEntranceFloorBlock(int x, int z) {
        return isCellFloorBlock(x, z, entranceCellX, entranceCellZ);
    }

    public boolean isExitFloorBlock(int x, int z) {
        return isCellFloorBlock(x, z, exitCellX, exitCellZ);
    }

    private boolean isCellFloorBlock(int x, int z, int cx, int cz) {
        int bx = cellBaseX(cx), bz = cellBaseZ(cz);
        return x >= bx && x < bx + FLOOR_W && z >= bz && z < bz + FLOOR_W;
    }
}
