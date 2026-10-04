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

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static com.minecolonies.core.datalistener.ResearchListener.EFFECT_PROP;
import static com.minecolonies.core.research.GlobalResearchBranch.*;

public class ResearchDataGenerator extends DataGenerator<ClientLevel>
{
    private static final Logger LOGGER = LogManager.getLogger();

    private static final FileToIdConverter RESEARCH = FileToIdConverter.json("researches");

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
    public Set<GeneratorTarget> listTargets(final ClientLevel level)
    {
        return RESEARCH.listMatchingResources(Minecraft.getInstance().getSingleplayerServer().getResourceManager())
            .keySet().stream()
            .map(key -> {
                final ResourceLocation id = RESEARCH.fileToId(key);
                return new GeneratorTarget(id.getNamespace(), id.getPath());
            })
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public CompletableFuture<Void> generate(final GeneratorTarget target, final DataGeneratorOptions<ClientLevel> options)
    {
        return CompletableFuture.runAsync(() -> {
            final ResourceLocation fileKey = RESEARCH.idToFile(ResourceLocation.fromNamespaceAndPath(target.namespace(), target.path()));
            final Resource resource = RESEARCH.listMatchingResources(Minecraft.getInstance().getSingleplayerServer().getResourceManager()).get(fileKey);
            if (resource == null)
            {
                return;
            }
            try (final InputStream stream = resource.open())
            {
                final JsonObject json = options.getGson().fromJson(new String(stream.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
                if (getType(json).equals(type))
                {
                    options.saveJsonFile(target.namespace(), target.path(), json);
                }
            }
            catch (Exception e)
            {
                LOGGER.error("Error generating data for research: {}", target, e);
            }
        });
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
