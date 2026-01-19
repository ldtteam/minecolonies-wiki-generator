package com.ldtteam.minecolonieswikigenerator.generators;

import com.google.gson.JsonArray;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.item.Item;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates JSON data for all item tags.
 */
public class ItemTagDataGenerator extends DataGenerator<ClientLevel>
{
    private static final Logger LOGGER = LogManager.getLogger();

    @Override
    public String getName()
    {
        return "Item Tags Data";
    }

    @Override
    public Path getGeneratorOutputPath(final Path rootPath)
    {
        return rootPath.resolve("item_tags");
    }

    @Override
    public CompletableFuture<Void> generate(final DataGeneratorOptions<ClientLevel> options)
    {
        return CompletableFuture.runAsync(() -> {
            LOGGER.info("Starting item tags data generation...");
            final AtomicInteger count = new AtomicInteger(0);

            final TagLoader<Holder<Item>> tagloader = new TagLoader<>(BuiltInRegistries.ITEM::getHolder, Registries.tagsDirPath(BuiltInRegistries.ITEM.key()));
            tagloader.loadAndBuild(Minecraft.getInstance().getSingleplayerServer().getResourceManager()).forEach((key, value) -> {
                try
                {
                    generateItemTagData(options, key, value);
                    count.incrementAndGet();
                }
                catch (Exception e)
                {
                    LOGGER.error("Error generating data for item tags: {}", key, e);
                }
            });

            LOGGER.info("Item tags data generation complete. Generated {} files.", count.get());
        });
    }

    private void generateItemTagData(final DataGeneratorOptions<ClientLevel> options, final ResourceLocation itemTagId, final Collection<Holder<Item>> itemCollection)
        throws IOException
    {
        final JsonArray array = new JsonArray();
        for (final Holder<Item> item : itemCollection)
        {
            array.add(BuiltInRegistries.ITEM.getKey(item.value()).toString());
        }

        options.saveJsonFile(itemTagId.getNamespace(), itemTagId.getPath(), array);
    }
}
