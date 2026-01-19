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
import java.util.stream.Stream;

/**
 * Manages client-side data generators.
 *
 * @param <L> the level type (e.g., ClientLevel) for version-specific world access
 */
public final class DataGeneratorManager<L>
{
    private static final Logger LOGGER = LogManager.getLogger();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final RootEntrypoint<L> entrypoint;

    private final RootEntrypoint.DataGenerators<L> generators;

    private final Deque<LongRunningDataGenerator<L>> longRunningGenerators;

    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean completed   = new AtomicBoolean(false);

    private CompletableFuture<Void> allGeneratorsFuture;

    public DataGeneratorManager(final RootEntrypoint<L> entrypoint)
    {
        this.entrypoint = entrypoint;
        this.generators = entrypoint.getGenerators();
        this.longRunningGenerators =
            new ArrayDeque<>(generators.activeGenerators().stream().filter(LongRunningDataGenerator.class::isInstance).map(g -> (LongRunningDataGenerator<L>) g).toList());
    }

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

        if (!longRunningGenerators.isEmpty())
        {
            final LongRunningDataGenerator<L> activeGenerator = longRunningGenerators.getFirst();
            if (activeGenerator.isComplete())
            {
                longRunningGenerators.pop();
            }
            else
            {
                activeGenerator.processTasks(LOGGER);
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

        for (final DataGenerator<L> generator : generators.activeGenerators())
        {
            LOGGER.info("Starting generator: {}", generator.getName());

            final Path generatorOutputPath = generator.getGeneratorOutputPath(this.entrypoint.getOutputPath());
            deletePath(generatorOutputPath);

            final DataGeneratorOptions<L> options = new DataGeneratorOptions<>(generatorOutputPath, GSON, level);
            futures.add(generator.generate(options));
        }

        allGeneratorsFuture = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void deletePath(final Path path)
    {
        if (Files.exists(path))
        {
            try (Stream<Path> walk = Files.walk(path))
            {
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }
    }
}
