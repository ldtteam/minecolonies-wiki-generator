package com.ldtteam.minecolonieswikigenerator.generators;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Base class for all wiki data generators.
 *
 * <p>Implementors declare what files they produce via {@link #listTargets}, and generate each file
 * individually via {@link #generate}. The manager handles namespace filtering, output cleanup, and
 * scheduling — generators should not do any of that themselves.
 *
 * @param <L> the level type (e.g. {@code ClientLevel}) — passed through so version-specific
 *            subclasses can access world state without the common module depending on Minecraft
 */
public abstract class DataGenerator<L>
{
    /**
     * A human-readable name for this generator, used in log output.
     */
    public abstract String getName();

    /**
     * Returns the directory under {@code rootPath} where this generator writes its output.
     * The folder name is also used to scope cleanup — only files inside this folder are considered.
     */
    public abstract Path getGeneratorOutputPath(final Path rootPath);

    /**
     * Whether the manager should delete files in the output folder that are not produced by this
     * generator before generation begins. Defaults to {@code true}; override to {@code false} for
     * generators that produce files alongside files owned by other generators.
     */
    public boolean shouldClearBeforeGeneration()
    {
        return true;
    }

    /**
     * Returns the complete set of {@link GeneratorTarget}s this generator intends to produce.
     * No namespace filtering should be applied here — the manager filters targets before calling
     * {@link #generate}, and uses the full set for output cleanup.
     *
     * @param level the current level, available for registry or world queries
     */
    public abstract Set<GeneratorTarget> listTargets(final L level);

    /**
     * The number of targets to process per render-thread tick, or {@code null} to run all targets
     * concurrently off the render thread via {@link CompletableFuture#runAsync}.
     *
     * <p>Return a non-null value for generators that require render-thread access (e.g. block/item
     * rendering). The manager will drain the target queue in batches of this size each tick.
     */
    public Integer batchSize()
    {
        return null;
    }

    /**
     * Generates the output file for a single target. Called once per target after namespace
     * filtering — implementations do not need to check whether the target's namespace is excluded.
     *
     * @param target  the specific file to produce, identified by namespace and path
     * @param options provides the output path, Gson instance, level, and file-saving helpers
     * @return a future that completes when the file has been written
     */
    public abstract CompletableFuture<Void> generate(final GeneratorTarget target, final DataGeneratorOptions<L> options);
}