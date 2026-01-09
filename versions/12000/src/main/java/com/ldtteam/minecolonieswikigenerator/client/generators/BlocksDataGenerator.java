package com.ldtteam.minecolonieswikigenerator.client.generators;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates JSON data for all registered blocks.
 */
public class BlocksDataGenerator implements IClientDataGenerator
{
    private static final Logger LOGGER = LogManager.getLogger();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Override
    public String getName()
    {
        return "Blocks Data";
    }

    @Override
    public CompletableFuture<Void> generate(final Path outputPath)
    {
        return CompletableFuture.runAsync(() -> {
            LOGGER.info("Starting blocks data generation...");
            final AtomicInteger count = new AtomicInteger(0);

            ForgeRegistries.BLOCKS.getEntries().forEach(entry -> {
                try
                {
                    generateBlockData(outputPath, entry.getKey().location(), entry.getValue());
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

    private void generateBlockData(final Path outputPath, final ResourceLocation id, final Block block)
    {
        final JsonObject json = new JsonObject();
        json.addProperty("name", block.getName().getString());

        final Map<Integer, Integer> statesAndCodes = new HashMap<>();
        block.getStateDefinition().getPossibleStates().forEach(state -> statesAndCodes.put(state.hashCode(), block.getStateDefinition().getPossibleStates().indexOf(state)));
        json.addProperty("defaultstate", statesAndCodes.get(block.defaultBlockState().hashCode()));

        saveJsonFile(outputPath, id, json);
    }

    private void saveJsonFile(final Path outputPath, final ResourceLocation id, final JsonObject json)
    {
        final Path filePath = outputPath.resolve("blocks").resolve(id.getNamespace()).resolve(id.getPath() + ".json");

        try
        {
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, GSON.toJson(json), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            LOGGER.error("Failed to save file: {}", filePath, e);
        }
    }
}
