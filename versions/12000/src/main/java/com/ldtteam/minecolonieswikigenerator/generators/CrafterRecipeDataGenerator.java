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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class CrafterRecipeDataGenerator extends DataGenerator<ClientLevel>
{
    private static final Logger LOGGER = LogManager.getLogger();

    private static final FileToIdConverter CRAFTER_RECIPES = FileToIdConverter.json("crafterrecipes");

    @Override
    public String getName()
    {
        return "Crafter Recipes Data";
    }

    @Override
    public Path getGeneratorOutputPath(final Path rootPath)
    {
        return rootPath.resolve("crafter_recipes");
    }

    @Override
    public Set<GeneratorTarget> listTargets(final ClientLevel level)
    {
        return CRAFTER_RECIPES.listMatchingResources(Minecraft.getInstance().getSingleplayerServer().getResourceManager())
            .keySet().stream()
            .map(key -> {
                final ResourceLocation id = CRAFTER_RECIPES.fileToId(key);
                return new GeneratorTarget(id.getNamespace(), id.getPath());
            })
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public CompletableFuture<Void> generate(final GeneratorTarget target, final DataGeneratorOptions<ClientLevel> options)
    {
        return CompletableFuture.runAsync(() -> {
            final ResourceLocation fileKey = CRAFTER_RECIPES.idToFile(new ResourceLocation(target.namespace(), target.path()));
            final Resource resource = CRAFTER_RECIPES.listMatchingResources(Minecraft.getInstance().getSingleplayerServer().getResourceManager()).get(fileKey);
            if (resource == null)
            {
                return;
            }
            try (final InputStream stream = resource.open())
            {
                options.saveFile(target.namespace(), target.path(), "json", stream.readAllBytes());
            }
            catch (IOException e)
            {
                LOGGER.error("Error generating data for crafter recipe: {}", target, e);
            }
        });
    }
}
