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
import java.util.function.Consumer;

/**
 * Generates PNG images for all items using Minecraft's ItemRenderer.
 * This handles both 2D item sprites and 3D block models correctly.
 * This generator must run on the render thread since it requires OpenGL context.
 */
public class ItemImageDataGenerator extends LongRunningDataGenerator<ClientLevel>
{
    private static final Logger LOGGER = LogManager.getLogger();

    private static final int IMAGE_SIZE = 300;
    private static final int BATCH_SIZE = 10;

    public ItemImageDataGenerator()
    {
        super(BATCH_SIZE);
    }

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
    protected void queueTasks(final Consumer<Runnable> register, final DataGeneratorOptions<ClientLevel> options)
    {
        BuiltInRegistries.ITEM.entrySet().forEach(entry -> {
            final Item item = entry.getValue();
            final ResourceLocation itemId = entry.getKey().location();
            register.accept(() -> generateItemImage(options, itemId, item));
        });
    }

    private void generateItemImage(final DataGeneratorOptions<ClientLevel> options, final ResourceLocation itemId, final Item item)
    {
        try
        {
            final ItemStack stack = new ItemStack(item);
            final byte[] imageData = renderItemToImage(stack, IMAGE_SIZE);
            if (imageData != null)
            {
                options.saveFile(itemId.getNamespace(), itemId.getPath(), "png", imageData);
            }
            else
            {
                LOGGER.warn("Failed to render image for item: {}", itemId);
            }
        }
        catch (Exception e)
        {
            LOGGER.error("Error generating image for item: {}", itemId, e);
        }
    }

    /**
     * Renders an item to a PNG image using Minecraft's ItemRenderer.
     * This mimics GuiGraphics.renderItem() but renders to a framebuffer.
     *
     * @param stack the item stack to render
     * @param size  the maximum size of the output image (width and height)
     * @return the rendered image as PNG byte data, or null if rendering failed
     */
    public static byte[] renderItemToImage(final ItemStack stack, final int size)
    {
        if (stack.isEmpty())
        {
            return null;
        }

        final Minecraft mc = Minecraft.getInstance();

        // Create framebuffer
        final TextureTarget renderTarget = new TextureTarget(size, size, true, Minecraft.ON_OSX);
        renderTarget.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        renderTarget.clear(Minecraft.ON_OSX);
        renderTarget.bindWrite(true);

        // Set up projection
        final Matrix4f matrix4f = new Matrix4f().setOrtho(0.0F, size, size, 0.0F, 1000.0F, ClientHooks.getGuiFarPlane());
        RenderSystem.setProjectionMatrix(matrix4f, VertexSorting.ORTHOGRAPHIC_Z);

        final Matrix4fStack matrix4fstack = RenderSystem.getModelViewStack();
        matrix4fstack.pushMatrix();
        matrix4fstack.translation(0.0F, 0.0F, 10000F - ClientHooks.getGuiFarPlane());
        RenderSystem.applyModelViewMatrix();
        Lighting.setupFor3DItems();

        // Render - scale up from 16x16 to fill the image
        final float scale = size / 16.0f;
        final GuiGraphics guiGraphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
        guiGraphics.pose().pushPose();
        guiGraphics.pose().scale(scale, scale, scale);
        guiGraphics.renderFakeItem(stack, 0, 0);
        guiGraphics.pose().popPose();
        guiGraphics.flush();

        // Pop the model view stack
        matrix4fstack.popMatrix();
        RenderSystem.applyModelViewMatrix();

        // Read pixels
        try (final NativeImage image = new NativeImage(size, size, false))
        {
            RenderSystem.bindTexture(renderTarget.getColorTextureId());
            image.downloadTexture(0, false);
            image.flipY();

            renderTarget.unbindWrite();
            mc.getMainRenderTarget().bindWrite(true);
            renderTarget.destroyBuffers();

            return image.asByteArray();
        }
        catch (IOException e)
        {
            LOGGER.error("Error rendering item image", e);
            renderTarget.unbindWrite();
            mc.getMainRenderTarget().bindWrite(true);
            renderTarget.destroyBuffers();
            return null;
        }
    }
}
