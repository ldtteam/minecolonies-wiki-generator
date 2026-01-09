package com.ldtteam.minecolonieswikigenerator.client.generators;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for client-side data generators.
 */
public interface IClientDataGenerator
{
    /**
     * Gets the name of this generator for logging purposes.
     */
    String getName();

    /**
     * Runs the data generation asynchronously.
     *
     * @param outputPath the base output path for generated files
     * @return a CompletableFuture that completes when generation is done
     */
    CompletableFuture<Void> generate(Path outputPath);
}