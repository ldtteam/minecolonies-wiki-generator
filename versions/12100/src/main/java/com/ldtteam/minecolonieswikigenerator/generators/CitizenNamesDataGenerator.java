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

public class CitizenNamesDataGenerator extends DataGenerator<ClientLevel>
{
    private static final Logger LOGGER = LogManager.getLogger();

    private static final FileToIdConverter CITIZEN_NAMES = FileToIdConverter.json("citizennames");

    @Override
    public String getName()
    {
        return "Citizen Names Data";
    }

    @Override
    public Path getGeneratorOutputPath(final Path rootPath)
    {
        return rootPath.resolve("citizen_names");
    }

    @Override
    public Set<GeneratorTarget> listTargets(final ClientLevel level)
    {
        return CITIZEN_NAMES.listMatchingResources(Minecraft.getInstance().getSingleplayerServer().getResourceManager())
            .keySet().stream()
            .map(key -> {
                final ResourceLocation id = CITIZEN_NAMES.fileToId(key);
                return new GeneratorTarget(id.getNamespace(), id.getPath());
            })
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public CompletableFuture<Void> generate(final GeneratorTarget target, final DataGeneratorOptions<ClientLevel> options)
    {
        return CompletableFuture.runAsync(() -> {
            final ResourceLocation fileKey = CITIZEN_NAMES.idToFile(ResourceLocation.fromNamespaceAndPath(target.namespace(), target.path()));
            final Resource resource = CITIZEN_NAMES.listMatchingResources(Minecraft.getInstance().getSingleplayerServer().getResourceManager()).get(fileKey);
            if (resource == null)
            {
                return;
            }
            try
            {
                generateCitizenNamesData(options, target, resource);
            }
            catch (Exception e)
            {
                LOGGER.error("Error generating data for citizen names: {}", target, e);
            }
        });
    }

    private void generateCitizenNamesData(final DataGeneratorOptions<ClientLevel> options, final GeneratorTarget target, final Resource resource) throws IOException
    {
        try (final InputStream stream = resource.open())
        {
            options.saveFile(target.namespace(), target.path(), "json", stream.readAllBytes());
        }
    }
}
