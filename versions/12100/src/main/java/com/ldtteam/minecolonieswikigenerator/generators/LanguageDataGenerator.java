package com.ldtteam.minecolonieswikigenerator.generators;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.FileToIdConverter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Generates JSON data for all language files.
 */
public class LanguageDataGenerator extends DataGenerator<ClientLevel>
{
    private static final Logger LOGGER = LogManager.getLogger();

    @Override
    public String getName()
    {
        return "Language Data";
    }

    @Override
    public Path getGeneratorOutputPath(final Path rootPath)
    {
        return rootPath.resolve("lang");
    }

    @Override
    public CompletableFuture<Void> generate(final DataGeneratorOptions<ClientLevel> options)
    {
        return CompletableFuture.runAsync(() -> {
            LOGGER.info("Starting language data generation...");

            final FileToIdConverter languages = new FileToIdConverter("lang", ".json");

            final JsonObject allTranslations = new JsonObject();
            languages.listMatchingResources(Minecraft.getInstance().getResourceManager()).forEach((key, value) -> {
                try (final BufferedReader reader = value.openAsReader())
                {
                    final JsonObject object = options.getGson().fromJson(reader, JsonObject.class);
                    object.entrySet().forEach(entry -> {
                        allTranslations.add(entry.getKey(), entry.getValue());
                    });
                }
                catch (Exception e)
                {
                    LOGGER.error("Error generating data for languages: {}", key, e);
                }
            });

            try
            {
                options.saveJsonFile("", "en_us", allTranslations);
            }
            catch (IOException e)
            {
                LOGGER.error("Error saving final file for language generation", e);
            }

            LOGGER.info("Languages data generation complete.");
        });
    }
}
