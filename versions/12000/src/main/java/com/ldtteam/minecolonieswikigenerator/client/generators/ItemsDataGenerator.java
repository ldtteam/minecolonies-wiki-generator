package com.ldtteam.minecolonieswikigenerator.client.generators;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates JSON data for all registered items.
 */
public class ItemsDataGenerator implements IClientDataGenerator
{
    private static final Logger LOGGER = LogManager.getLogger();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Override
    public String getName()
    {
        return "Items Data";
    }

    @Override
    public CompletableFuture<Void> generate(final Path outputPath)
    {
        return CompletableFuture.runAsync(() -> {
            LOGGER.info("Starting items data generation...");
            final AtomicInteger count = new AtomicInteger(0);

            ForgeRegistries.ITEMS.getEntries().forEach(entry -> {
                try
                {
                    generateItemData(outputPath, entry.getKey().location(), entry.getValue());
                    count.incrementAndGet();
                }
                catch (Exception e)
                {
                    LOGGER.error("Error generating data for item: {}", entry.getKey().location(), e);
                }
            });

            LOGGER.info("Items data generation complete. Generated {} files.", count.get());
        });
    }

    private void generateItemData(final Path outputPath, final ResourceLocation id, final Item item)
    {
        final JsonObject json = new JsonObject();
        json.addProperty("name", item.getDescription().getString());

        if (item instanceof BlockItem blockItem)
        {
            final ResourceLocation blockKey = ForgeRegistries.BLOCKS.getKey(blockItem.getBlock());
            if (blockKey != null)
            {
                json.addProperty("block-id", blockKey.toString());
            }
        }

        saveJsonFile(outputPath, id, json);
    }

    private void saveJsonFile(final Path outputPath, final ResourceLocation id, final JsonObject json)
    {
        final Path filePath = outputPath.resolve("items").resolve(id.getNamespace()).resolve(id.getPath() + ".json");

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
