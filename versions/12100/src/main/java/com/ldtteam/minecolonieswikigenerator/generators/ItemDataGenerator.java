package com.ldtteam.minecolonieswikigenerator.generators;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.minecolonies.api.items.IMinecoloniesFoodItem;
import com.minecolonies.core.blocks.MinecoloniesCropBlock;
import com.minecolonies.core.items.ItemCrop;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

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
    public Set<GeneratorTarget> listTargets(final ClientLevel level)
    {
        return BuiltInRegistries.ITEM.entrySet().stream()
            .map(e -> new GeneratorTarget(e.getKey().location().getNamespace(), e.getKey().location().getPath()))
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public CompletableFuture<Void> generate(final GeneratorTarget target, final DataGeneratorOptions<ClientLevel> options)
    {
        return CompletableFuture.runAsync(() -> {
            final ResourceLocation id = ResourceLocation.fromNamespaceAndPath(target.namespace(), target.path());
            final Item item = BuiltInRegistries.ITEM.get(id);
            try
            {
                generateItemData(options, id, item);
            }
            catch (Exception e)
            {
                LOGGER.error("Error generating data for item: {}", id, e);
            }
        });
    }

    private void generateItemData(final DataGeneratorOptions<ClientLevel> options, final ResourceLocation id, final Item item) throws Exception
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

        if (item instanceof IMinecoloniesFoodItem foodItem)
        {
            final FoodProperties foodProperties = item.getFoodProperties(item.getDefaultInstance(), null);
            if (foodProperties != null)
            {
                final JsonObject food = new JsonObject();
                food.addProperty("tier", foodItem.getTier());
                food.addProperty("saturation", foodProperties.nutrition());
                json.add("food", food);
            }
        }

        if (item instanceof ItemCrop cropItem)
        {
            final JsonObject crop = new JsonObject();
            try
            {
                final Field preferredBiomeField = ItemCrop.class.getDeclaredField("preferredBiome");
                preferredBiomeField.setAccessible(true);
                final TagKey<?> preferredBiome = (TagKey<?>) preferredBiomeField.get(cropItem);
                if (preferredBiome != null)
                {
                    crop.addProperty("biome-tag", preferredBiome.location().toString());
                }
            }
            catch (NoSuchFieldException | IllegalAccessException e)
            {
                LOGGER.error("Failed to read preferredBiome from ItemCrop via reflection", e);
            }

            if (cropItem.getBlock() instanceof MinecoloniesCropBlock cropBlock)
            {
                final JsonArray droppedFrom = new JsonArray();
                for (final Block block : cropBlock.getDroppedFrom())
                {
                    final ResourceLocation blockKey = BuiltInRegistries.BLOCK.getKeyOrNull(block);
                    if (blockKey != null)
                    {
                        droppedFrom.add(blockKey.toString());
                    }
                }
                if (!droppedFrom.isEmpty())
                {
                    crop.add("dropped-from", droppedFrom);
                }
            }

            json.add("crop", crop);
        }

        options.saveJsonFile(id.getNamespace(), id.getPath(), json);
    }
}