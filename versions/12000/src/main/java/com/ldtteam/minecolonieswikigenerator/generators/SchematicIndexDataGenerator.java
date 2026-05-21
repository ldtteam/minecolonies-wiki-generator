package com.ldtteam.minecolonieswikigenerator.generators;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.blueprints.v1.BlueprintUtil;
import com.ldtteam.structurize.storage.StructurePacks;
import com.minecolonies.api.blocks.AbstractBlockHut;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Generates a JSON index per structure pack listing metadata for every blueprint.
 * Output: schematic_images/{packId}/index.json
 */
public class SchematicIndexDataGenerator extends DataGenerator<ClientLevel>
{
    private static final Logger LOGGER = LogManager.getLogger();

    @Override
    public String getName()
    {
        return "Schematic Index";
    }

    @Override
    public Path getGeneratorOutputPath(final Path rootPath)
    {
        return rootPath.resolve("schematic_index");
    }

    @Override
    public CompletableFuture<Void> generate(final DataGeneratorOptions<ClientLevel> options)
    {
        return CompletableFuture.runAsync(() -> {
            for (final var packMeta : StructurePacks.getPackMetas())
            {
                final String packName = packMeta.getName();
                final Path packRoot = packMeta.getPath();
                final String packId = packRoot.getFileName().toString();
                final List<String> authors = packMeta.getAuthors();

                final List<JsonObject> schematics = new ArrayList<>();

                try (final Stream<Path> walk = Files.walk(packRoot))
                {
                    for (final Path blueprintPath : (Iterable<Path>) walk.filter(p -> !Files.isDirectory(p) && p.toString().endsWith(".blueprint"))::iterator)
                    {
                        final Blueprint blueprint = loadBlueprint(blueprintPath);
                        if (blueprint == null)
                        {
                            continue;
                        }

                        // Relative path of the containing directory within the pack, using forward slashes
                        final String subDir = packRoot.relativize(blueprintPath.getParent()).toString().replace('\\', '/');
                        final String fileName = blueprintPath.getFileName().toString().replace(".blueprint", "");

                        final JsonObject schematic = new JsonObject();
                        schematic.addProperty("id", fileName);

                        // Detect building type by checking if the anchor block is a MineColonies hut block
                        final AbstractBlockHut<?> hutBlock = getHutBlock(blueprint);
                        if (hutBlock != null)
                        {
                            schematic.addProperty("type", "building");
                            schematic.addProperty("building", hutBlock.getBlueprintName().toLowerCase());
                        }
                        else
                        {
                            schematic.addProperty("type", "decoration");
                        }

                        // path is the subdir within the pack (empty string if at pack root)
                        schematic.addProperty("path", subDir);

                        final JsonObject size = new JsonObject();
                        size.addProperty("x", blueprint.getSizeX());
                        size.addProperty("y", blueprint.getSizeY());
                        size.addProperty("z", blueprint.getSizeZ());
                        schematic.add("size", size);

                        final String imagePath = subDir.isEmpty() ? fileName : subDir + "/" + fileName;
                        final JsonObject images = new JsonObject();
                        for (final String variant : new String[]{"clean", "full", "placed"})
                        {
                            final JsonArray variantImages = new JsonArray();
                            variantImages.add(imagePath + "_" + variant + "_front.png");
                            variantImages.add(imagePath + "_" + variant + "_back.png");
                            images.add(variant, variantImages);
                        }
                        schematic.add("images", images);

                        schematics.add(schematic);
                    }
                }
                catch (final IOException e)
                {
                    LOGGER.error("Could not walk blueprint pack '{}': {}", packId, e.getMessage());
                    continue;
                }

                // Build pack-level JSON object
                final JsonObject packJson = new JsonObject();
                packJson.addProperty("id", packId);
                packJson.addProperty("displayName", packName);

                final JsonArray authorsArray = new JsonArray();
                for (final String author : authors)
                {
                    authorsArray.add(author);
                }
                packJson.add("authors", authorsArray);

                final JsonArray schematicsArray = new JsonArray();
                schematics.forEach(schematicsArray::add);
                packJson.add("schematics", schematicsArray);

                try
                {
                    options.saveFile(packId, "index", "json", options.getGson().toJson(packJson).getBytes(StandardCharsets.UTF_8));
                }
                catch (final IOException e)
                {
                    LOGGER.error("Failed to write schematic index for pack '{}'", packId, e);
                }
            }
        });
    }

    /**
     * Returns the {@link AbstractBlockHut} at the blueprint's primary anchor position, or null if none.
     */
    private static AbstractBlockHut<?> getHutBlock(final Blueprint blueprint)
    {
        final BlockPos anchor = blueprint.getPrimaryBlockOffset();
        return blueprint.getBlockInfoAsList().stream()
            .filter(info -> info.getPos().equals(anchor) && info.getState() != null)
            .map(info -> info.getState().getBlock())
            .filter(b -> b instanceof AbstractBlockHut<?>)
            .map(b -> (AbstractBlockHut<?>) b)
            .findFirst()
            .orElse(null);
    }

    private static Blueprint loadBlueprint(final Path path)
    {
        try
        {
            final CompoundTag nbt = NbtIo.readCompressed(new ByteArrayInputStream(Files.readAllBytes(path)));
            return BlueprintUtil.readBlueprintFromNBT(nbt);
        }
        catch (final IOException e)
        {
            return null;
        }
    }
}