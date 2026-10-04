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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

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
        final TagLoader<Holder<Item>> tagLoader = new TagLoader<>(BuiltInRegistries.ITEM::getHolder, Registries.tagsDirPath(BuiltInRegistries.ITEM.key()));
        final Map<ResourceLocation, Collection<Holder<Item>>> tags = tagLoader.loadAndBuild(Minecraft.getInstance().getSingleplayerServer().getResourceManager());
        return tags.keySet().stream()
            .map(id -> new GeneratorTarget(id.getNamespace(), id.getPath()))
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public CompletableFuture<Void> generate(final GeneratorTarget target, final DataGeneratorOptions<ClientLevel> options)
    {
        return CompletableFuture.runAsync(() -> {
            final ResourceLocation id = ResourceLocation.fromNamespaceAndPath(target.namespace(), target.path());
            final TagLoader<Holder<Item>> tagLoader = new TagLoader<>(BuiltInRegistries.ITEM::getHolder, Registries.tagsDirPath(BuiltInRegistries.ITEM.key()));
            final Map<ResourceLocation, Collection<Holder<Item>>> tags = tagLoader.loadAndBuild(Minecraft.getInstance().getSingleplayerServer().getResourceManager());
            final Collection<Holder<Item>> items = tags.get(id);
            if (items == null)
            {
                return;
            }
            try
            {
                generateItemTagData(options, id, items);
            }
            catch (Exception e)
            {
                LOGGER.error("Error generating data for item tag: {}", id, e);
            }
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