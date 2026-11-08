package com.ldtteam.minecolonieswikigenerator.generators;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.GsonHelper;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.config.ConfigTracker;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.forgespi.language.IModInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ConfigurationDataGenerator extends DataGenerator<ClientLevel>
{
    private static final Logger LOGGER = LogManager.getLogger();

    private static final String VALUE_CHILD_TYPE_CATEGORY = "category";
    private static final String VALUE_CHILD_TYPE_CONFIG   = "config";

    private static final Set<String> ALWAYS_EXCLUDED_MODS = Set.of(
        "minecraft", "forge", "neoforge", "jei", "fml"
    );

    private static final Pattern PATTERN_NUMBER_RANGE       = Pattern.compile("^.*Range: ([\\d\\-,.]+) ~ ([\\d\\-,.]+).*$", Pattern.DOTALL);
    private static final Pattern PATTERN_NUMBER_BOUND_RANGE = Pattern.compile("^.*Range: ([<>]) ([\\d\\-,.]+).*$", Pattern.DOTALL);
    private static final Pattern PATTERN_ENUM_VALUES        = Pattern.compile("^.*Allowed Values: (.*)$");

    @Override
    public String getName()
    {
        return "Configuration Data";
    }

    @Override
    public Path getGeneratorOutputPath(final Path rootPath)
    {
        return rootPath.resolve("config");
    }

    @Override
    public Set<GeneratorTarget> listTargets(final ClientLevel level)
    {
        return ConfigTracker.INSTANCE.configSets().values().stream()
            .flatMap(Collection::stream)
            .map(ModConfig::getModId)
            .filter(modId -> !ALWAYS_EXCLUDED_MODS.contains(modId))
            .distinct()
            .map(modId -> new GeneratorTarget(modId, "configuration"))
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public CompletableFuture<Void> generate(final GeneratorTarget target, final DataGeneratorOptions<ClientLevel> options)
    {
        return CompletableFuture.runAsync(() -> {
            final String modId = target.namespace();
            final Map<ModConfig.Type, Map<LinkedList<String>, ForgeConfigSpec.ValueSpec>> configurationTypes = new LinkedHashMap<>();

            ConfigTracker.INSTANCE.configSets().values().stream()
                .flatMap(Collection::stream)
                .filter(config -> config.getModId().equals(modId))
                .forEach(config -> {
                    if (config.getSpec() instanceof ForgeConfigSpec configSpec)
                    {
                        collectConfig(new LinkedList<>(), configSpec.getSpec(), (key, valueSpec) -> {
                            configurationTypes.computeIfAbsent(config.getType(), a -> new LinkedHashMap<>()).put(key, valueSpec);
                        });
                    }
                });

            try
            {
                final JsonObject configurationJson = new JsonObject();
                configurationJson.addProperty("name",
                    ModList.get().getMods().stream().filter(f -> f.getModId().equals(modId)).findFirst().map(IModInfo::getDisplayName).orElse(modId));

                final JsonArray configurationTypesJson = new JsonArray();
                for (final Map.Entry<ModConfig.Type, Map<LinkedList<String>, ForgeConfigSpec.ValueSpec>> entry : configurationTypes.entrySet())
                {
                    final JsonObject configurationTypeJson = new JsonObject();
                    configurationTypeJson.addProperty("type", entry.getKey().toString());

                    final JsonArray configurationTypeValuesJson = new JsonArray();
                    for (final Map.Entry<LinkedList<String>, ForgeConfigSpec.ValueSpec> valueEntry : entry.getValue().entrySet())
                    {
                        collectValuesJson(valueEntry.getKey(), valueEntry.getValue(), configurationTypeValuesJson);
                    }
                    configurationTypeJson.add("values", configurationTypeValuesJson);
                    configurationTypesJson.add(configurationTypeJson);
                }

                configurationJson.add("types", configurationTypesJson);
                options.saveJsonFile(modId, "configuration", configurationJson);
            }
            catch (IOException e)
            {
                LOGGER.error("Error generating data for configuration: {}", modId, e);
            }
        });
    }

    private void collectConfig(final LinkedList<String> path, final UnmodifiableConfig config, final BiConsumer<LinkedList<String>, ForgeConfigSpec.ValueSpec> consumer)
    {
        config.entrySet().forEach(entry -> {
            path.addLast(entry.getKey());
            if (entry.getValue() instanceof UnmodifiableConfig subConfig)
            {
                collectConfig(path, subConfig, consumer);
            }
            else if (entry.getValue() instanceof ForgeConfigSpec.ValueSpec valueSpec)
            {
                consumer.accept(new LinkedList<>(path), valueSpec);
            }
            else
            {
                LOGGER.info("Unknown object in config at path: {}, key: {}. Object was {}, class {}",
                    String.join("/", path),
                    entry.getKey(),
                    entry.getValue(),
                    entry.getValue().getClass());
            }
            path.removeLast();
        });
    }

    private void collectValuesJson(final LinkedList<String> key, final ForgeConfigSpec.ValueSpec valueSpec, final JsonArray collector)
    {
        final LinkedList<String> keyCopy = new LinkedList<>(key);
        final String configName = keyCopy.removeLast();

        JsonArray childArray = collector;
        for (final String section : keyCopy)
        {
            childArray = getOrCreateChildArray(childArray, section);
        }

        final JsonObject newConfigJson = new JsonObject();
        newConfigJson.addProperty("type", VALUE_CHILD_TYPE_CONFIG);
        newConfigJson.addProperty("name", configName);

        final Object defaultValue = valueSpec.getDefault();
        switch (defaultValue)
        {
            case Boolean boolValue ->
            {
                newConfigJson.addProperty("value-type", "boolean");
                newConfigJson.addProperty("defaultValue", boolValue);
            }
            case Number numberValue ->
            {
                newConfigJson.addProperty("value-type", "number");
                newConfigJson.addProperty("defaultValue", numberValue);
            }
            case Enum<?> enumValue ->
            {
                newConfigJson.addProperty("value-type", "enum");
                newConfigJson.addProperty("defaultValue", enumValue.name());
            }
            default ->
            {
                newConfigJson.addProperty("value-type", "string");
                newConfigJson.addProperty("defaultValue", defaultValue.toString());
            }
        }

        newConfigJson.addProperty("description", valueSpec.getTranslationKey());
        if (valueSpec.getComment() != null)
        {
            final String[] comment = valueSpec.getComment().split("\n", 2);
            newConfigJson.addProperty("comment", comment[0].replaceAll("\\[Default: .+]", "").trim());
            if (comment.length > 1)
            {
                parseSpecialConditions(newConfigJson, comment[1]);
            }
        }
        newConfigJson.addProperty("needsRestart", valueSpec.needsWorldRestart());

        childArray.add(newConfigJson);
    }

    @NotNull
    private JsonArray getOrCreateChildArray(final JsonArray parent, final String childName)
    {
        for (final JsonElement child : parent)
        {
            if (child instanceof JsonObject childObject
                && GsonHelper.getAsString(childObject, "type").equals(VALUE_CHILD_TYPE_CATEGORY)
                && GsonHelper.getAsString(childObject, "name").equals(childName))
            {
                return childObject.getAsJsonArray("children");
            }
        }

        final JsonObject newCategoryJson = new JsonObject();
        newCategoryJson.addProperty("type", VALUE_CHILD_TYPE_CATEGORY);
        newCategoryJson.addProperty("name", childName);

        final JsonArray newCategoryChildrenJson = new JsonArray();
        newCategoryJson.add("children", newCategoryChildrenJson);
        parent.add(newCategoryJson);

        return newCategoryChildrenJson;
    }

    private void parseSpecialConditions(final JsonObject configJson, final String commentLine)
    {
        final Matcher rangeMatcher = PATTERN_NUMBER_RANGE.matcher(commentLine);
        if (rangeMatcher.matches())
        {
            configJson.addProperty("min", Double.parseDouble(rangeMatcher.group(1)));
            configJson.addProperty("max", Double.parseDouble(rangeMatcher.group(2)));
            return;
        }

        final Matcher rangeBoundMatcher = PATTERN_NUMBER_BOUND_RANGE.matcher(commentLine);
        if (rangeBoundMatcher.matches())
        {
            final String bound = rangeBoundMatcher.group(1);
            final Double rangeValue = Double.parseDouble(rangeBoundMatcher.group(2));
            if (Objects.equals(bound, ">"))
            {
                configJson.addProperty("min", rangeValue);
            }
            else if (Objects.equals(bound, "<"))
            {
                configJson.addProperty("max", rangeValue);
            }
            return;
        }

        final Matcher enumMatcher = PATTERN_ENUM_VALUES.matcher(commentLine);
        if (enumMatcher.matches())
        {
            final JsonArray enumValuesJson = new JsonArray();
            Arrays.stream(enumMatcher.group(1).split(",")).map(String::trim).forEach(enumValuesJson::add);
            configJson.add("enum", enumValuesJson);
            return;
        }

        throw new IllegalArgumentException("Comment line '" + commentLine + "' is not any valid format.");
    }
}
