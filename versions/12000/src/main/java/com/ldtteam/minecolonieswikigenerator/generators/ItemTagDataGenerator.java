package com.ldtteam.minecolonieswikigenerator.generators;

import com.google.gson.JsonArray;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static net.minecraft.tags.TagManager.getTagDir;

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
    public Set<GeneratorTarget> listTargets(final ClientLevel level)
    {
        final TagLoader<Holder<Item>> tagLoader = new TagLoader<>(ForgeRegistries.ITEMS::getHolder, getTagDir(ForgeRegistries.ITEMS.getRegistryKey()));
        return tagLoader.loadAndBuild(Minecraft.getInstance().getSingleplayerServer().getResourceManager())
            .keySet().stream()
            .map(key -> new GeneratorTarget(key.getNamespace(), key.getPath()))
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public CompletableFuture<Void> generate(final GeneratorTarget target, final DataGeneratorOptions<ClientLevel> options)
    {
        return CompletableFuture.runAsync(() -> {
            final TagLoader<Holder<Item>> tagLoader = new TagLoader<>(ForgeRegistries.ITEMS::getHolder, getTagDir(ForgeRegistries.ITEMS.getRegistryKey()));
            final Map<ResourceLocation, Collection<Holder<Item>>> tagMap = tagLoader.loadAndBuild(Minecraft.getInstance().getSingleplayerServer().getResourceManager());
            final ResourceLocation tagId = new ResourceLocation(target.namespace(), target.path());
            final Collection<Holder<Item>> items = tagMap.get(tagId);
            if (items == null)
            {
                return;
            }
            try
            {
                final JsonArray array = new JsonArray();
                for (final Holder<Item> item : items)
                {
                    array.add(ForgeRegistries.ITEMS.getKey(item.value()).toString());
                }
                options.saveJsonFile(target.namespace(), target.path(), array);
            }
            catch (IOException e)
            {
                LOGGER.error("Error generating data for item tag: {}", target, e);
            }
        });
    }
}
