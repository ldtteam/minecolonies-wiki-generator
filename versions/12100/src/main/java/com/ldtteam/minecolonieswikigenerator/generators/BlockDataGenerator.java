package com.ldtteam.minecolonieswikigenerator.generators;

import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class BlockDataGenerator extends DataGenerator<ClientLevel>
{
    private static final Logger LOGGER = LogManager.getLogger();

    @Override
    public String getName()
    {
        return "Blocks Data";
    }

    @Override
    public Path getGeneratorOutputPath(final Path rootPath)
    {
        return rootPath.resolve("blocks");
    }

    @Override
    public Set<GeneratorTarget> listTargets(final ClientLevel level)
    {
        return BuiltInRegistries.BLOCK.entrySet()
            .stream()
            .map(e -> new GeneratorTarget(e.getKey().location().getNamespace(), e.getKey().location().getPath()))
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public CompletableFuture<Void> generate(final GeneratorTarget target, final DataGeneratorOptions<ClientLevel> options)
    {
        return CompletableFuture.runAsync(() -> {
            final ResourceLocation id = ResourceLocation.fromNamespaceAndPath(target.namespace(), target.path());
            final Block block = BuiltInRegistries.BLOCK.get(id);
            try
            {
                final JsonObject json = new JsonObject();
                json.addProperty("name", block.getName().getString());

                final Map<Integer, Integer> statesAndCodes = new HashMap<>();
                block.getStateDefinition()
                    .getPossibleStates()
                    .forEach(state -> statesAndCodes.put(state.hashCode(), block.getStateDefinition().getPossibleStates().indexOf(state)));
                json.addProperty("defaultstate", statesAndCodes.get(block.defaultBlockState().hashCode()));

                options.saveJsonFile(target.namespace(), target.path(), json);
            }
            catch (Exception e)
            {
                LOGGER.error("Error generating data for block: {}", id, e);
            }
        });
    }
}
