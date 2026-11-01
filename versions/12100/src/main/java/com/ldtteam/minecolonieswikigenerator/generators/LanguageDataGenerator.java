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
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class LanguageDataGenerator extends DataGenerator<ClientLevel>
{
    private static final Logger LOGGER = LogManager.getLogger();

    private static final FileToIdConverter LANGUAGES = new FileToIdConverter("lang", "en_us.json");

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
    public Set<GeneratorTarget> listTargets(final ClientLevel level)
    {
        return Set.of(new GeneratorTarget("", "en_us"));
    }

    @Override
    public CompletableFuture<Void> generate(final GeneratorTarget target, final DataGeneratorOptions<ClientLevel> options)
    {
        return CompletableFuture.runAsync(() -> {
            final JsonObject allTranslations = new JsonObject();
            LANGUAGES.listMatchingResources(Minecraft.getInstance().getResourceManager()).forEach((key, value) -> {
                try (final BufferedReader reader = value.openAsReader())
                {
                    final JsonObject object = options.getGson().fromJson(reader, JsonObject.class);
                    object.entrySet().forEach(entry -> allTranslations.add(entry.getKey(), entry.getValue()));
                }
                catch (Exception e)
                {
                    LOGGER.error("Error reading language file: {}", key, e);
                }
            });

            try
            {
                options.saveJsonFile(target.namespace(), target.path(), allTranslations);
            }
            catch (IOException e)
            {
                LOGGER.error("Error saving final file for language generation", e);
            }
        });
    }
}
