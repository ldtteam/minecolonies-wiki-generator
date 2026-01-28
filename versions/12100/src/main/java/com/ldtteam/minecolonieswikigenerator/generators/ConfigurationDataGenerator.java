package com.ldtteam.minecolonieswikigenerator.generators;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.GsonHelper;
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforgespi.language.IModInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates JSON data for all configuration options.
 */
public class ConfigurationDataGenerator extends DataGenerator<ClientLevel>
{
    private static final Logger LOGGER = LogManager.getLogger();

    private static final String VALUE_CHILD_TYPE_CATEGORY = "category";
    private static final String VALUE_CHILD_TYPE_CONFIG   = "config";

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
    public CompletableFuture<Void> generate(final DataGeneratorOptions<ClientLevel> options)
    {
        return CompletableFuture.runAsync(() -> {
            LOGGER.info("Starting configuration data generation...");
            final AtomicInteger count = new AtomicInteger(0);

            final Map<String, Map<ModConfig.Type, Map<LinkedList<String>, ModConfigSpec.ValueSpec>>> fullConfiguration = new HashMap<>();

            ModList.get().getMods().stream().flatMap(m -> ModConfigs.getModConfigs(m.getModId()).stream()).filter(config -> !config.getModId().equals("neoforge") && !config.getModId().equals("jei")).forEach(config -> {
                if (config.getSpec() instanceof ModConfigSpec configSpec)
                {
                    collectConfig(new LinkedList<>(), configSpec.getSpec(), (key, valueSpec) -> {
                        final Map<ModConfig.Type, Map<LinkedList<String>, ModConfigSpec.ValueSpec>> modMap =
                            fullConfiguration.computeIfAbsent(config.getModId(), a -> new HashMap<>());
                        final Map<LinkedList<String>, ModConfigSpec.ValueSpec> modTypeMap = modMap.computeIfAbsent(config.getType(), a -> new HashMap<>());
                        modTypeMap.put(key, valueSpec);
                    });
                }
            });

            for (final Map.Entry<String, Map<ModConfig.Type, Map<LinkedList<String>, ModConfigSpec.ValueSpec>>> modConfiguration : fullConfiguration.entrySet())
            {
                final String modId = modConfiguration.getKey();
                final Map<ModConfig.Type, Map<LinkedList<String>, ModConfigSpec.ValueSpec>> configurationTypes = modConfiguration.getValue();
                try
                {
                    final JsonObject configurationJson = new JsonObject();
                    configurationJson.addProperty("name",
                        ModList.get().getMods().stream().filter(f -> f.getModId().equals(modId)).findFirst().map(IModInfo::getDisplayName).orElse(modId));

                    final JsonArray configurationTypesJson = new JsonArray();
                    for (final Map.Entry<ModConfig.Type, Map<LinkedList<String>, ModConfigSpec.ValueSpec>> configurationType : configurationTypes.entrySet())
                    {
                        final JsonObject configurationTypeJson = new JsonObject();
                        configurationTypeJson.addProperty("type", configurationType.getKey().toString());

                        final JsonArray configurationTypeValuesJson = new JsonArray();
                        for (final Map.Entry<LinkedList<String>, ModConfigSpec.ValueSpec> configurationTypeValue : configurationType.getValue().entrySet())
                        {
                            collectValuesJson(configurationTypeValue.getKey(), configurationTypeValue.getValue(), configurationTypeValuesJson);
                        }
                        configurationTypeJson.add("values", configurationTypeValuesJson);

                        configurationTypesJson.add(configurationTypeJson);
                    }

                    configurationJson.add("types", configurationTypesJson);

                    options.saveJsonFile(modId, "configuration", configurationJson);
                    count.incrementAndGet();
                }
                catch (IOException e)
                {
                    LOGGER.error("Error generating data for configuration: {}", modId, e);
                }
            }

            LOGGER.info("Configuration generation complete. Generated {} files.", count.get());
        });
    }

    private void collectConfig(final LinkedList<String> path, final UnmodifiableConfig config, final BiConsumer<LinkedList<String>, ModConfigSpec.ValueSpec> consumer)
    {
        config.entrySet().forEach(entry -> {
            path.addLast(entry.getKey());
            if (entry.getValue() instanceof UnmodifiableConfig subConfig)
            {
                collectConfig(path, subConfig, consumer);
            }
            else if (entry.getValue() instanceof ModConfigSpec.ValueSpec valueSpec)
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

    private void collectValuesJson(final LinkedList<String> key, final ModConfigSpec.ValueSpec valueSpec, final JsonArray collector)
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
            final String[] comment = valueSpec.getComment().replaceAll("\\[Default: .+?]", "").trim().split("\n", 2);
            newConfigJson.addProperty("comment", comment[0]);
            if (comment.length > 1)
            {
                parseSpecialConditions(newConfigJson, comment[1]);
            }
        }
        newConfigJson.addProperty("needsRestart", !valueSpec.restartType().equals(ModConfigSpec.RestartType.NONE));

        childArray.add(newConfigJson);
    }

    @NotNull
    private JsonArray getOrCreateChildArray(final JsonArray parent, final String childName)
    {
        for (final JsonElement child : parent)
        {
            if (child instanceof JsonObject childObject && GsonHelper.getAsString(childObject, "type").equals(VALUE_CHILD_TYPE_CATEGORY) && GsonHelper.getAsString(childObject,
                "name").equals(childName))
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
            final Double rangeMin = Double.parseDouble(rangeMatcher.group(1));
            final Double rangeMax = Double.parseDouble(rangeMatcher.group(2));
            configJson.addProperty("min", rangeMin);
            configJson.addProperty("max", rangeMax);
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
            final List<String> enumValues = Arrays.stream(enumMatcher.group(1).split(",")).map(String::trim).toList();
            final JsonArray enumValuesJson = new JsonArray();
            for (final String enumValue : enumValues)
            {
                enumValuesJson.add(enumValue);
            }
            configJson.add("enum", enumValuesJson);
            return;
        }

        throw new IllegalArgumentException("Comment line '" + commentLine + "' is not any valid format.");
    }
}
