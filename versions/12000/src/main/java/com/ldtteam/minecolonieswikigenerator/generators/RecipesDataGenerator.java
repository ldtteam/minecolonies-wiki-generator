package com.ldtteam.minecolonieswikigenerator.generators;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates JSON data for all recipes.
 */
public class RecipesDataGenerator extends DataGenerator<ClientLevel>
{
    private static final Logger LOGGER = LogManager.getLogger();

    @Override
    public String getName()
    {
        return "Recipes Data";
    }

    @Override
    public Path getGeneratorOutputPath(final Path rootPath)
    {
        return rootPath.resolve("recipes");
    }

    @Override
    public CompletableFuture<Void> generate(final DataGeneratorOptions<ClientLevel> options)
    {
        return CompletableFuture.runAsync(() -> {
            LOGGER.info("Starting recipes data generation...");
            final AtomicInteger count = new AtomicInteger(0);

            final FileToIdConverter recipes = FileToIdConverter.json("recipes");
            recipes.listMatchingResources(Minecraft.getInstance().getSingleplayerServer().getResourceManager()).forEach((key, value) -> {
                try
                {
                    generateRecipeData(options, recipes.fileToId(key), value);
                    count.incrementAndGet();
                }
                catch (Exception e)
                {
                    LOGGER.error("Error generating data for recipes: {}", key, e);
                }
            });

            LOGGER.info("Recipes data generation complete. Generated {} files.", count.get());
        });
    }

    private void generateRecipeData(final DataGeneratorOptions<ClientLevel> options, final ResourceLocation recipeId, final Resource resource) throws IOException
    {
        try (final InputStream stream = resource.open())
        {
            options.saveFile(recipeId.getNamespace(), recipeId.getPath().replaceAll(".*/", ""), "json", stream.readAllBytes());
        }
    }
}
