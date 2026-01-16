package com.ldtteam.minecolonieswikigenerator.generators;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates JSON data for all block states.
 */
public class BlockStatesDataGenerator extends DataGenerator<ClientLevel>
{
    private static final Logger LOGGER = LogManager.getLogger();

    @Override
    public String getName()
    {
        return "Block States Data";
    }

    @Override
    public Path getGeneratorOutputPath(final Path rootPath)
    {
        return rootPath.resolve("block_states");
    }

    @Override
    public CompletableFuture<Void> generate(final DataGeneratorOptions<ClientLevel> options)
    {
        return CompletableFuture.runAsync(() -> {
            LOGGER.info("Starting block states data generation...");
            final AtomicInteger count = new AtomicInteger(0);

            BuiltInRegistries.BLOCK.entrySet().forEach(entry -> {
                try
                {
                    generateBlockStatesData(options, entry.getKey().location(), entry.getValue());
                    count.incrementAndGet();
                }
                catch (Exception e)
                {
                    LOGGER.error("Error generating data for block states: {}", entry.getKey().location(), e);
                }
            });

            LOGGER.info("Block states data generation complete. Generated {} files.", count.get());
        });
    }

    private void generateBlockStatesData(final DataGeneratorOptions options, final ResourceLocation id, final Block block) throws Exception
    {
        final JsonObject json = new JsonObject();

        // Properties
        final JsonArray propertiesJson = new JsonArray();
        block.getStateDefinition().getProperties().forEach(property -> {
            final JsonObject propertyJson = new JsonObject();
            propertyJson.addProperty("property", property.getName());
            propertyJson.addProperty("type", getPropertyType(property));

            final JsonArray propertyValuesJson = new JsonArray();
            property.getPossibleValues().forEach(value -> propertyValuesJson.add(value.toString()));
            propertyJson.add("values", propertyValuesJson);
            propertiesJson.add(propertyJson);
        });
        json.add("properties", propertiesJson);

        // States
        final JsonArray statesJson = new JsonArray();
        block.getStateDefinition().getPossibleStates().forEach(state -> {
            final JsonObject stateJson = new JsonObject();
            final JsonArray statePropertiesJson = new JsonArray();
            state.getProperties().forEach(property -> {
                final JsonObject statePropertyJson = new JsonObject();
                statePropertyJson.addProperty("property", property.getName());
                statePropertyJson.addProperty("value", state.getValue(property).toString());
                statePropertiesJson.add(statePropertyJson);
            });
            stateJson.add("values", statePropertiesJson);
            stateJson.addProperty("imageid", getBlockStateIdentifier(state));
            statesJson.add(stateJson);
        });
        json.add("blockstates", statesJson);

        options.saveJsonFile(id.getNamespace(), id.getPath(), json);
    }

    private String getPropertyType(final Property<?> property)
    {
        if (property instanceof IntegerProperty)
        {
            return "integer";
        }
        else if (property instanceof BooleanProperty)
        {
            return "boolean";
        }
        else if (property instanceof EnumProperty<?>)
        {
            return "enum";
        }
        return property.getClass().getSimpleName();
    }

    /**
     * Gets a unique identifier for a block state based on its model location.
     */
    public static String getBlockStateIdentifier(final BlockState blockState)
    {
        return String.valueOf(BlockModelShaper.stateToModelLocation(blockState).hashCode());
    }
}
