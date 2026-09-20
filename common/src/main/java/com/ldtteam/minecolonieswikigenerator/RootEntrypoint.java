package com.ldtteam.minecolonieswikigenerator;

import com.ldtteam.minecolonieswikigenerator.generators.DataGenerator;
import com.ldtteam.minecolonieswikigenerator.generators.DataGeneratorManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Base entrypoint class for the wiki generator.
 *
 * @param <L> the level type (e.g., ClientLevel) for version-specific world access
 */
public abstract class RootEntrypoint<L>
{
    private DataGeneratorManager<L> generator;

    protected RootEntrypoint()
    {
    }

    public abstract L getLevel();

    public abstract Path getOutputPath();

    protected abstract void getGenerators(final DataGeneratorCollector<L> collector);

    public abstract void shutdown();

    public final DataGenerators<L> getGenerators()
    {
        final List<DataGenerator<L>> activeGenerators = new ArrayList<>();
        final List<DataGenerator<L>> inactiveGenerators = new ArrayList<>();

        getGenerators((active, generator) -> {
            if (active)
            {
                activeGenerators.add(generator);
            }
            else
            {
                inactiveGenerators.add(generator);
            }
        });

        return new DataGenerators<>(activeGenerators, inactiveGenerators);
    }

    protected final void initialize()
    {
        this.generator = new DataGeneratorManager<>(this);
    }

    protected final void tick()
    {
        if (this.generator != null)
        {
            this.generator.tick();
        }
    }

    public record DataGenerators<L>(
        List<DataGenerator<L>> activeGenerators,
        List<DataGenerator<L>> inactiveGenerators)
    {
    }

    @FunctionalInterface
    protected interface DataGeneratorCollector<L>
    {
        void add(final boolean active, final DataGenerator<L> generator);
    }
}
