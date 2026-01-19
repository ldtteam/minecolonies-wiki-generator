package com.ldtteam.minecolonieswikigenerator.generators;

import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates JSON data for all registered items.
 */
public class ItemDataGenerator extends DataGenerator<ClientLevel>
{
    private static final Logger LOGGER = LogManager.getLogger();

    @Override
    public String getName()
    {
        return "Items Data";
    }

    @Override
    public Path getGeneratorOutputPath(final Path rootPath)
    {
        return rootPath.resolve("items");
    }

    @Override
    public CompletableFuture<Void> generate(final DataGeneratorOptions<ClientLevel> options)
    {
        return CompletableFuture.runAsync(() -> {
            LOGGER.info("Starting items data generation...");
            final AtomicInteger count = new AtomicInteger(0);

            BuiltInRegistries.ITEM.entrySet().forEach(entry -> {
                try
                {
                    generateItemData(options, entry.getKey().location(), entry.getValue());
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

    private void generateItemData(final DataGeneratorOptions options, final ResourceLocation id, final Item item) throws Exception
    {
        final JsonObject json = new JsonObject();
        json.addProperty("name", item.getDescription().getString());

        if (item instanceof BlockItem blockItem)
        {
            final ResourceLocation blockKey = BuiltInRegistries.BLOCK.getKeyOrNull(blockItem.getBlock());
            if (blockKey != null)
            {
                json.addProperty("block-id", blockKey.toString());
            }
        }

        options.saveJsonFile(id.getNamespace(), id.getPath(), json);
    }
}
