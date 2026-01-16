package com.ldtteam.minecolonieswikigenerator.generators;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Base class for data generators that are long-running and require to be run on the render thread.
 *
 * @param <L> the level type (e.g., ClientLevel) for version-specific world access
 */
public abstract class LongRunningDataGenerator<L> extends DataGenerator<L>
{
    private static final Logger LOGGER = LogManager.getLogger();

    private final Deque<Runnable> taskQueue = new ArrayDeque<>();

    private final AtomicInteger totalTasks = new AtomicInteger(0);

    private final AtomicInteger completedTasks = new AtomicInteger(0);

    private final int batchSize;

    private CompletableFuture<Void> completionFuture;

    protected LongRunningDataGenerator(final int batchSize)
    {
        this.batchSize = batchSize;
    }

    @Override
    public final CompletableFuture<Void> generate(final DataGeneratorOptions<L> options)
    {
        this.completionFuture = new CompletableFuture<>();

        LOGGER.info("{}: Queueing tasks...", getName());
        queueTasks(runnable -> {
            taskQueue.add(runnable);
            totalTasks.incrementAndGet();
        }, options);
        LOGGER.info("{}: Queued {} tasks", getName(), totalTasks.get());

        return completionFuture;
    }

    protected abstract void queueTasks(final Consumer<Runnable> register, final DataGeneratorOptions<L> options);

    public final boolean isComplete()
    {
        return completionFuture != null && completionFuture.isDone();
    }

    public final void processTasks(final Logger logger)
    {
        if (taskQueue.isEmpty())
        {
            if (!completionFuture.isDone())
            {
                logger.info("{}: Generation complete. Processed {} tasks.", getName(), completedTasks.get());
                completionFuture.complete(null);
            }
            return;
        }

        for (int i = 0; i < batchSize && !taskQueue.isEmpty(); i++)
        {
            final Runnable task = taskQueue.poll();
            if (task != null)
            {
                try
                {
                    task.run();
                    final int completed = completedTasks.incrementAndGet();
                    if (completed % 100 == 0)
                    {
                        logger.info("{}: Task queue progress: {}/{}", getName(), completed, totalTasks.get());
                    }
                }
                catch (Exception e)
                {
                    logger.error("{}: Error executing task", getName(), e);
                }
            }
        }
    }
}
