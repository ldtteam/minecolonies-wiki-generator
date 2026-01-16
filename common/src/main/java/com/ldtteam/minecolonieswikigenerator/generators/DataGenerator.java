package com.ldtteam.minecolonieswikigenerator.generators;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Base class for data generators.
 *
 * @param <L> the level type (e.g., ClientLevel) for version-specific world access
 */
public abstract class DataGenerator<L>
{
    /**
     * Gets the name of this generator for logging purposes.
     */
    public abstract String getName();

    /**
     * Returns the output path for this generator instance.
     *
     * @param rootPath the original root path where all files are stored.
     * @return the new resolved subpath.
     */
    public abstract Path getGeneratorOutputPath(final Path rootPath);

    /**
     * Runs the data generation asynchronously.
     *
     * @return a CompletableFuture that completes when generation is done
     */
    public abstract CompletableFuture<Void> generate(final DataGeneratorOptions<L> options);
}
