package com.ldtteam.minecolonieswikigenerator.generators;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.ClientHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class ItemImageDataGenerator extends DataGenerator<ClientLevel>
{
    private static final Logger LOGGER = LogManager.getLogger();

    private static final int IMAGE_SIZE = 300;

    @Override
    public String getName()
    {
        return "Item Images";
    }

    @Override
    public Path getGeneratorOutputPath(final Path rootPath)
    {
        return rootPath.resolve("item_images");
    }

    @Override
    public Set<GeneratorTarget> listTargets(final ClientLevel level)
    {
        return BuiltInRegistries.ITEM.entrySet().stream()
            .map(e -> new GeneratorTarget(e.getKey().location().getNamespace(), e.getKey().location().getPath()))
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Integer batchSize()
    {
        return 10;
    }

    @Override
    public CompletableFuture<Void> generate(final GeneratorTarget target, final DataGeneratorOptions<ClientLevel> options)
    {
        final ResourceLocation itemId = ResourceLocation.fromNamespaceAndPath(target.namespace(), target.path());
        final Item item = BuiltInRegistries.ITEM.get(itemId);
        final ItemStack stack = new ItemStack(item);

        try
        {
            final byte[] imageData = renderItemToImage(stack, IMAGE_SIZE);
            if (imageData != null)
            {
                options.saveFile(target.namespace(), target.path(), "png", imageData);
            }
            else
            {
                LOGGER.warn("Failed to render image for item: {}", target);
            }
        }
        catch (Exception e)
        {
            LOGGER.error("Error generating image for item: {}", target, e);
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Renders an item to a PNG image using Minecraft's ItemRenderer.
     *
     * @param stack the item stack to render
     * @param size  the size of the output image
     * @return the rendered image as PNG byte data, or null if rendering failed
     */
    public static byte[] renderItemToImage(final ItemStack stack, final int size)
    {
        if (stack.isEmpty())
        {
            return null;
        }

        final Minecraft mc = Minecraft.getInstance();

        final TextureTarget renderTarget = new TextureTarget(size, size, true, Minecraft.ON_OSX);
        renderTarget.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        renderTarget.clear(Minecraft.ON_OSX);
        renderTarget.bindWrite(true);

        final Matrix4f matrix4f = new Matrix4f().setOrtho(0.0F, size, size, 0.0F, 1000.0F, ClientHooks.getGuiFarPlane());
        RenderSystem.setProjectionMatrix(matrix4f, VertexSorting.ORTHOGRAPHIC_Z);

        final Matrix4fStack matrix4fstack = RenderSystem.getModelViewStack();
        matrix4fstack.pushMatrix();
        matrix4fstack.translation(0.0F, 0.0F, 10000F - ClientHooks.getGuiFarPlane());
        RenderSystem.applyModelViewMatrix();
        Lighting.setupFor3DItems();

        final float scale = size / 16.0f;
        final GuiGraphics guiGraphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
        guiGraphics.pose().pushPose();
        guiGraphics.pose().scale(scale, scale, scale);
        guiGraphics.renderFakeItem(stack, 0, 0);
        guiGraphics.pose().popPose();
        guiGraphics.flush();

        try (final NativeImage image = new NativeImage(size, size, false))
        {
            RenderSystem.bindTexture(renderTarget.getColorTextureId());
            image.downloadTexture(0, false);
            image.flipY();

            return image.asByteArray();
        }
        catch (IOException e)
        {
            LOGGER.error("Error rendering item image", e);
            return null;
        }
        finally
        {
            matrix4fstack.popMatrix();
            RenderSystem.applyModelViewMatrix();
            renderTarget.unbindWrite();
            mc.getMainRenderTarget().bindWrite(true);
            renderTarget.destroyBuffers();
        }
    }
}
