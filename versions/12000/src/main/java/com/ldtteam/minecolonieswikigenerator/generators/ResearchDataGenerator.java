package com.ldtteam.minecolonieswikigenerator.generators;

import com.google.gson.JsonObject;
import com.ldtteam.minecolonieswikigenerator.research.ResearchObjectType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static com.minecolonies.core.datalistener.ResearchListener.EFFECT_PROP;
import static com.minecolonies.core.research.GlobalResearchBranch.*;

/**
 * Generates JSON data for all research.
 */
public class ResearchDataGenerator extends DataGenerator<ClientLevel>
{
    private static final Logger LOGGER = LogManager.getLogger();

    private final ResearchObjectType type;

    public ResearchDataGenerator(final ResearchObjectType type)
    {
        this.type = type;
    }

    @Override
    public String getName()
    {
        return "Research Data - " + type.getDisplayName();
    }

    @Override
    public Path getGeneratorOutputPath(final Path rootPath)
    {
        return rootPath.resolve(type.getFolder());
    }

    @Override
    public CompletableFuture<Void> generate(final DataGeneratorOptions<ClientLevel> options)
    {
        return CompletableFuture.runAsync(() -> {
            LOGGER.info("Starting research data generation...");
            final AtomicInteger count = new AtomicInteger(0);

            final FileToIdConverter research = FileToIdConverter.json("researches");
            research.listMatchingResources(Minecraft.getInstance().getSingleplayerServer().getResourceManager()).forEach((key, value) -> {
                try
                {
                    final boolean isValidFile = generateResearchData(options, research.fileToId(key), value);
                    if (isValidFile)
                    {
                        count.incrementAndGet();
                    }
                }
                catch (Exception e)
                {
                    LOGGER.error("Error generating data for research: {}", key, e);
                }
            });

            LOGGER.info("Research data generation complete. Generated {} files.", count.get());
        });
    }

    private boolean generateResearchData(final DataGeneratorOptions<ClientLevel> options, final ResourceLocation researchId, final Resource resource) throws IOException
    {
        try (final BufferedReader reader = resource.openAsReader())
        {
            final JsonObject json = options.getGson().fromJson(reader, JsonObject.class);
            if (getType(json).equals(type))
            {
                options.saveJsonFile(researchId.getNamespace(), researchId.getPath().replaceAll(".*/", ""), json);
                return true;
            }
        }
        return false;
    }

    private ResearchObjectType getType(final JsonObject object)
    {
        if (object.has(EFFECT_PROP))
        {
            return ResearchObjectType.RESEARCH_EFFECT;
        }
        else if (object.has(RESEARCH_BRANCH_NAME_PROP) || object.has(RESEARCH_BASE_TIME_PROP) || object.has(RESEARCH_BRANCH_TYPE_PROP))
        {
            return ResearchObjectType.RESEARCH_TREE;
        }
        else
        {
            return ResearchObjectType.RESEARCH;
        }
    }
}
