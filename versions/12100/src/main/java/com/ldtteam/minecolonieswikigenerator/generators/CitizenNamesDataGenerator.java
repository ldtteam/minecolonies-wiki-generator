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
 * Generates JSON data for all citizen names.
 */
public class CitizenNamesDataGenerator extends DataGenerator<ClientLevel>
{
    private static final Logger LOGGER = LogManager.getLogger();

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
    public CompletableFuture<Void> generate(final DataGeneratorOptions<ClientLevel> options)
    {
        return CompletableFuture.runAsync(() -> {
            LOGGER.info("Starting citizen names data generation...");
            final AtomicInteger count = new AtomicInteger(0);

            final FileToIdConverter citizenNames = FileToIdConverter.json("citizennames");
            citizenNames.listMatchingResources(Minecraft.getInstance().getSingleplayerServer().getResourceManager()).forEach((key, value) -> {
                try
                {
                    generateCitizenNamesData(options, citizenNames.fileToId(key), value);
                    count.incrementAndGet();
                }
                catch (Exception e)
                {
                    LOGGER.error("Error generating data for citizen names: {}", key, e);
                }
            });

            LOGGER.info("Citizen names data generation complete. Generated {} files.", count.get());
        });
    }

    private void generateCitizenNamesData(final DataGeneratorOptions<ClientLevel> options, final ResourceLocation citizenNameId, final Resource resource) throws IOException
    {
        try (final InputStream stream = resource.open())
        {
            options.saveFile(citizenNameId.getNamespace(), citizenNameId.getPath(), "json", stream.readAllBytes());
        }
    }
}
