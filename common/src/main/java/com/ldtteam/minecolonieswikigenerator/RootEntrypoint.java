package com.ldtteam.minecolonieswikigenerator;

import com.ldtteam.minecolonieswikigenerator.generators.DataGenerator;
import com.ldtteam.minecolonieswikigenerator.generators.DataGeneratorManager;

import java.nio.file.Path;
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

    public abstract List<? extends DataGenerator<L>> getGenerators();

    public abstract void shutdown();

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
}
