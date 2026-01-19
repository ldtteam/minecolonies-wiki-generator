package com.ldtteam.minecolonieswikigenerator.generators;

import com.google.gson.Gson;
import com.google.gson.JsonElement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Options passed to data generators.
 *
 * @param <L> the level type (e.g., ClientLevel) - allows version-specific implementations
 *            to provide world access without the common module depending on Minecraft classes
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
     * Gets the level instance, useful for BlockEntity rendering that requires world access.
     *
     * @return the level instance
     */
    public L getLevel()
    {
        return level;
    }

    public Gson getGson()
    {
        return gson;
    }

    public void saveJsonFile(final String namespace, final String path, final JsonElement json) throws IOException
    {
        saveFile(namespace, path, "json", gson.toJson(json).getBytes(StandardCharsets.UTF_8));
    }

    public void saveFile(final String namespace, final String path, final String extension, final byte[] data) throws IOException
    {
        final Path filePath = outputPath.resolve(namespace).resolve(path + "." + extension);

        Files.createDirectories(filePath.getParent());
        Files.write(filePath, data);
    }
}
