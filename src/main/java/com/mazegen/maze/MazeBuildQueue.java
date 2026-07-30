package com.mazegen.maze;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Coloca bloques repartidos en varios ticks del servidor para no congelarlo con cientos de
 * miles de colocaciones de golpe en el mismo tick. Se usa tanto para la construcción inicial
 * de un laberinto como para los pequeños "retoques" periódicos de {@link MazeLiveManager}.
 * <p>
 * Los trabajos se agrupan en {@link Batch}: cuando todos los bloques de un lote están
 * colocados se ejecuta su callback (mensaje de progreso, registrar el laberinto para que
 * empiece a cambiar solo, etc.). Varios lotes (incluso de mundos distintos) pueden convivir
 * en la misma cola; se procesan en orden de llegada.
 */
public final class MazeBuildQueue {

    /** Callback opcional que se ejecuta justo después de colocar un bloque concreto. */
    @FunctionalInterface
    public interface PostPlace {
        void apply(ServerWorld world, BlockPos pos);
    }

    public static final class Batch {
        final int total;
        int placed = 0;
        final Runnable onComplete;

        public Batch(int total, Runnable onComplete) {
            this.total = total;
            this.onComplete = onComplete;
        }
    }

    public record BlockJob(ServerWorld world, BlockPos pos, BlockState state, PostPlace postPlace, Batch batch) {
        public BlockJob(ServerWorld world, BlockPos pos, BlockState state) {
            this(world, pos, state, null, null);
        }

        public BlockJob(ServerWorld world, BlockPos pos, BlockState state, PostPlace postPlace) {
            this(world, pos, state, postPlace, null);
        }

        BlockJob withBatch(Batch batch) {
            return new BlockJob(world, pos, state, postPlace, batch);
        }
    }

    private static final int BLOCKS_PER_TICK = 8000;

    private static final Deque<BlockJob> queue = new ArrayDeque<>();
    private static boolean registered = false;

    private MazeBuildQueue() {}

    /** Encola una lista de trabajos como un único lote silencioso (sin callback de finalización). */
    public static void submit(List<BlockJob> jobs) {
        submit(jobs, null);
    }

    /** Encola una lista de trabajos como un único lote; onComplete se ejecuta cuando el último se coloca. */
    public static void submit(List<BlockJob> jobs, Runnable onComplete) {
        ensureRegistered();
        if (jobs.isEmpty()) {
            if (onComplete != null) onComplete.run();
            return;
        }
        Batch batch = new Batch(jobs.size(), onComplete);
        for (BlockJob job : jobs) {
            queue.add(job.withBatch(batch));
        }
    }

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> tick());
    }

    private static void tick() {
        if (queue.isEmpty()) return;

        int placedThisTick = 0;
        while (!queue.isEmpty() && placedThisTick < BLOCKS_PER_TICK) {
            BlockJob job = queue.poll();
            job.world().setBlockState(job.pos(), job.state(), 3);
            if (job.postPlace() != null) {
                job.postPlace().apply(job.world(), job.pos());
            }
            placedThisTick++;

            Batch batch = job.batch();
            if (batch != null) {
                batch.placed++;
                if (batch.placed >= batch.total && batch.onComplete != null) {
                    batch.onComplete.run();
                }
            }
        }
    }
}
