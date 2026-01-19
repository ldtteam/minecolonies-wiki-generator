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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates JSON data for all registered blocks.
 */
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
    public CompletableFuture<Void> generate(final DataGeneratorOptions<ClientLevel> options)
    {
        return CompletableFuture.runAsync(() -> {
            LOGGER.info("Starting blocks data generation...");
            final AtomicInteger count = new AtomicInteger(0);

            BuiltInRegistries.BLOCK.entrySet().forEach(entry -> {
                try
                {
                    generateBlockData(options, entry.getKey().location(), entry.getValue());
                    count.incrementAndGet();
                }
                catch (Exception e)
                {
                    LOGGER.error("Error generating data for block: {}", entry.getKey().location(), e);
                }
            });

            LOGGER.info("Blocks data generation complete. Generated {} files.", count.get());
        });
    }

    private void generateBlockData(final DataGeneratorOptions options, final ResourceLocation id, final Block block) throws Exception
    {
        final JsonObject json = new JsonObject();
        json.addProperty("name", block.getName().getString());

        final Map<Integer, Integer> statesAndCodes = new HashMap<>();
        block.getStateDefinition().getPossibleStates().forEach(state -> statesAndCodes.put(state.hashCode(), block.getStateDefinition().getPossibleStates().indexOf(state)));
        json.addProperty("defaultstate", statesAndCodes.get(block.defaultBlockState().hashCode()));

        options.saveJsonFile(id.getNamespace(), id.getPath(), json);
    }
}
