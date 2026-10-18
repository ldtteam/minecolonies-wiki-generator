package com.ldtteam.minecolonieswikigenerator.generators;

/**
 * Identifies a single output file a generator intends to produce.
 *
 * <p>Mirrors the structure of a Minecraft {@code ResourceLocation} (namespace and path), but lives in
 * the common module which has no Minecraft dependency, allowing generators to declare their targets
 * without coupling the common module to any version-specific classes.
 *
 * @param namespace the mod or data namespace, e.g. {@code "minecolonies"}
 * @param path      the file path within that namespace, without extension, e.g. {@code "blocks/stone"}
 */
public record GeneratorTarget(
    String namespace,
    String path)
{
    @Override
    public String toString()
    {
        return namespace + ":" + path;
    }
}
