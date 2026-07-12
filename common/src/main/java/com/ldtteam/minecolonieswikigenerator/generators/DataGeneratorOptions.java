package com.ldtteam.minecolonieswikigenerator.generators;

import com.google.gson.Gson;
import com.google.gson.JsonElement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Contextual options passed to a generator for a single generation run.
 *
 * <p>Provides access to the output path, a shared {@link Gson} instance, the current level, and
 * helpers for writing output files. Namespace filtering is handled entirely by the manager before
 * {@link DataGenerator#generate} is called — generators do not need to check it here.
 *
 * @param <L> the level type (e.g. {@code ClientLevel})
 */
public class DataGeneratorOptions<L>
{
    private final Path outputPath;

    private final Gson gson;

    private final L level;

    DataGeneratorOptions(final Path outputPath, final Gson gson, final L level)
    {
        this.outputPath = outputPath;
        this.gson = gson;
        this.level = level;
    }

    /**
     * The root output directory for this generator, i.e., the folder that files are written into.
     */
    public Path getOutputPath()
    {
        return outputPath;
    }

    /**
     * The current level instance, useful for generators that need world or registry access beyond
     * what {@link DataGenerator#listTargets} already resolved.
     */
    public L getLevel()
    {
        return level;
    }

    /**
     * A shared {@link Gson} instance configured with pretty-printing.
     */
    public Gson getGson()
    {
        return gson;
    }

    /**
     * Serialises {@code json} and writes it to {@code <outputPath>/<namespace>/<path>.json},
     * creating parent directories as needed.
     */
    public void saveJsonFile(final String namespace, final String path, final JsonElement json) throws IOException
    {
        saveFile(namespace, path, "json", gson.toJson(json).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Writes raw {@code data} to {@code <outputPath>/<namespace>/<path>.<extension>},
     * creating parent directories as needed.
     */
    public void saveFile(final String namespace, final String path, final String extension, final byte[] data) throws IOException
    {
        final Path filePath = outputPath.resolve(namespace).resolve(path + "." + extension);

        Files.createDirectories(filePath.getParent());
        Files.write(filePath, data);
    }
}
