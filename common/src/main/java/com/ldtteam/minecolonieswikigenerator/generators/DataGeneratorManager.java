package com.ldtteam.minecolonieswikigenerator.generators;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.ldtteam.minecolonieswikigenerator.RootEntrypoint;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Drives all registered {@link DataGenerator}s for a single generation run.
 *
 * <p>The manager is ticked each game tick via {@link #tick()}. On the first tick it starts every
 * active generator: it resolves their full target sets, deletes stale output files, filters out
 * excluded namespaces, then either fires all targets concurrently (async generators) or enqueues
 * them for batched render-thread processing (batched generators). Once all generators report
 * completion, it shuts down the entrypoint.
 *
 * @param <L> the level type passed through to generators and their options
 */
public final class DataGeneratorManager<L>
{
    private static final Logger LOGGER = LogManager.getLogger();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String ENV_EXCLUDED_NAMESPACES = "EXCLUDED_NAMESPACES";

    private final RootEntrypoint<L> entrypoint;

    private final RootEntrypoint.DataGenerators<L> generators;

    private final Set<String> excludedNamespaces;

    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean completed   = new AtomicBoolean(false);

    private CompletableFuture<Void> allGeneratorsFuture;

    private final Deque<BatchedGeneratorState<L>> batchedGenerators = new ArrayDeque<>();

    private static Set<String> readExcludedNamespaces()
    {
        final String env = System.getenv(ENV_EXCLUDED_NAMESPACES);
        if (env == null || env.isBlank())
        {
            return Set.of();
        }
        return Arrays.stream(env.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    public DataGeneratorManager(final RootEntrypoint<L> entrypoint)
    {
        this.entrypoint = entrypoint;
        this.generators = entrypoint.getGenerators();
        this.excludedNamespaces = readExcludedNamespaces();
        if (!excludedNamespaces.isEmpty())
        {
            LOGGER.info("Excluding namespaces from generation: {}", excludedNamespaces);
        }
    }

    /**
     * Called once per game tick. Initializes generation on the first tick, advances any active
     * batched generator, and shuts down once everything is complete.
     */
    public void tick()
    {
        if (!initialized.getAndSet(true))
        {
            LOGGER.info("Starting data generation with {} generators...", generators.activeGenerators().size());
            if (!generators.inactiveGenerators().isEmpty())
            {
                LOGGER.info("{} disabled generators will be skipped.", generators.inactiveGenerators().size());
            }
            startAllGenerators();
        }

        if (!batchedGenerators.isEmpty())
        {
            final BatchedGeneratorState<L> active = batchedGenerators.getFirst();
            if (active.isDrained())
            {
                active.complete();
                batchedGenerators.pop();
            }
            else
            {
                active.processBatch();
            }
        }

        if (allGeneratorsFuture != null && allGeneratorsFuture.isDone() && !completed.getAndSet(true))
        {
            LOGGER.info("All data generation complete!");
            LOGGER.info("Shutting down...");
            this.entrypoint.shutdown();
        }
    }

    private void startAllGenerators()
    {
        final List<CompletableFuture<Void>> futures = new ArrayList<>();
        final L level = entrypoint.getLevel();
        final Path rootPath = this.entrypoint.getOutputPath();

        for (final DataGenerator<L> generator : generators.activeGenerators())
        {
            LOGGER.info("Starting generator: {}", generator.getName());

            final Path generatorOutputPath = generator.getGeneratorOutputPath(rootPath);
            final Set<GeneratorTarget> allTargets = generator.listTargets(level);

            if (generator.shouldClearBeforeGeneration())
            {
                deleteUnindexedFiles(generatorOutputPath, allTargets);
            }

            final List<GeneratorTarget> targets = allTargets.stream().filter(t -> !excludedNamespaces.contains(t.namespace())).toList();

            LOGGER.info("{}: generating {}/{} targets ({} excluded namespaces)", generator.getName(), targets.size(), allTargets.size(), allTargets.size() - targets.size());

            final DataGeneratorOptions<L> options = new DataGeneratorOptions<>(generatorOutputPath, GSON, level);

            final Integer batchSize = generator.batchSize();
            if (batchSize != null)
            {
                final CompletableFuture<Void> batchFuture = new CompletableFuture<>();
                batchedGenerators.add(new BatchedGeneratorState<>(generator, targets, options, batchSize, batchFuture));
                futures.add(batchFuture);
            }
            else
            {
                final List<CompletableFuture<Void>> targetFutures = targets.stream().map(target -> generator.generate(target, options).whenComplete((result, throwable) -> {
                    if (throwable != null)
                    {
                        LOGGER.error("Generator '{}' failed for target '{}':", generator.getName(), target, throwable);
                    }
                })).toList();
                futures.add(CompletableFuture.allOf(targetFutures.toArray(new CompletableFuture[0])));
            }
        }

        allGeneratorsFuture = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void deleteUnindexedFiles(final Path outputPath, final Set<GeneratorTarget> allTargets)
    {
        if (!Files.exists(outputPath))
        {
            return;
        }
        try (Stream<Path> walk = Files.walk(outputPath))
        {
            walk.filter(p -> !Files.isDirectory(p)).filter(p -> {
                final String relative = outputPath.relativize(p).toString().replace('\\', '/');
                final int dot = relative.lastIndexOf('.');
                final String stripped = dot >= 0 ? relative.substring(0, dot) : relative;
                final int slash = stripped.indexOf('/');
                if (slash < 0)
                {
                    return true;
                }
                final String namespace = stripped.substring(0, slash);
                final String filePath = stripped.substring(slash + 1);
                return !allTargets.contains(new GeneratorTarget(namespace, filePath));
            }).map(Path::toFile).forEach(File::delete);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    private static final class BatchedGeneratorState<L>
    {
        private final DataGenerator<L>        generator;
        private final Deque<GeneratorTarget>  queue;
        private final DataGeneratorOptions<L> options;
        private final int                     batchSize;
        private final CompletableFuture<Void> completionFuture;
        private final int                     total;
        private final AtomicInteger           done = new AtomicInteger(0);

        BatchedGeneratorState(
            final DataGenerator<L> generator,
            final List<GeneratorTarget> targets,
            final DataGeneratorOptions<L> options,
            final int batchSize,
            final CompletableFuture<Void> completionFuture)
        {
            this.generator = generator;
            this.queue = new ArrayDeque<>(targets);
            this.options = options;
            this.batchSize = batchSize;
            this.completionFuture = completionFuture;
            this.total = targets.size();
        }

        boolean isDrained()
        {
            return queue.isEmpty();
        }

        void complete()
        {
            completionFuture.complete(null);
        }

        void processBatch()
        {
            for (int i = 0; i < batchSize && !queue.isEmpty(); i++)
            {
                final GeneratorTarget target = queue.poll();
                try
                {
                    generator.generate(target, options).join();
                    final int count = done.incrementAndGet();
                    if (count % 100 == 0)
                    {
                        LOGGER.info("{}: {}/{}", generator.getName(), count, total);
                    }
                }
                catch (Exception e)
                {
                    LOGGER.error("{}: error generating target '{}'", generator.getName(), target, e);
                }
            }
        }
    }
}
