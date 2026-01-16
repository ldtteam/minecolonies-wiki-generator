package com.ldtteam.minecolonieswikigenerator.generators;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Generates PNG images for all items using Minecraft's ItemRenderer.
 * This handles both 2D item sprites and 3D block models correctly.
 * This generator must run on the render thread since it requires OpenGL context.
 */
public class ItemImagesGenerator extends LongRunningDataGenerator<ClientLevel>
{
    private static final Logger LOGGER = LogManager.getLogger();

    private static final int IMAGE_SIZE = 300;
    private static final int BATCH_SIZE = 10;

    public ItemImagesGenerator()
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

            // Skip BlockItems - they are handled by BlockImagesGenerator
            if (item instanceof BlockItem)
            {
                return;
            }

            register.accept(() -> generateItemImage(options, itemId, item));
        });
    }

    private void generateItemImage(final DataGeneratorOptions options, final ResourceLocation itemId, final Item item)
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
     * @param stack   the item stack to render
     * @param maxSize the maximum size of the output image (width and height)
     * @return the rendered image as PNG byte data, or null if rendering failed
     */
    public static byte[] renderItemToImage(final ItemStack stack, final int maxSize)
    {
        if (stack.isEmpty())
        {
            return null;
        }

        // Render at 4x resolution for better anti-aliasing through supersampling
        final int renderSize = maxSize * 4;

        final Minecraft mc = Minecraft.getInstance();
        final ItemRenderer itemRenderer = mc.getItemRenderer();
        final BakedModel model = itemRenderer.getModel(stack, null, null, 0);

        // Create a framebuffer to render into
        final TextureTarget renderTarget = new TextureTarget(renderSize, renderSize, true, Minecraft.ON_OSX);
        renderTarget.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        renderTarget.clear(Minecraft.ON_OSX);
        renderTarget.bindWrite(true);

        // Set up orthographic projection matching GUI rendering
        // The GUI renders items in a 16x16 space, we scale up to fill our render target
        final float scale = renderSize / 16.0f;
        final Matrix4f projectionMatrix = new Matrix4f().setOrtho(
            0, renderSize,
            renderSize, 0,  // Flipped Y for GUI coordinates
            -150.0f, 150.0f
        );
        RenderSystem.setProjectionMatrix(projectionMatrix, VertexSorting.ORTHOGRAPHIC_Z);

        // Set up the pose stack - mimicking GuiGraphics.renderItem()
        final PoseStack poseStack = new PoseStack();
        // Translate to center and add the +8 offset that GUI rendering uses
        poseStack.translate(renderSize / 2.0f, renderSize / 2.0f, 0);
        // Scale by 16 (item size) * our scale factor, with -Y for GUI coordinates
        poseStack.scale(16.0f * scale, -16.0f * scale, 16.0f * scale);

        // Set up lighting based on model type (same as GuiGraphics.renderItem)
        final boolean useFlatLighting = !model.usesBlockLight();
        if (useFlatLighting)
        {
            Lighting.setupForFlatItems();
        }
        else
        {
            Lighting.setupFor3DItems();
        }

        // Create buffer source for rendering
        final MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        try
        {
            // Use ItemRenderer.render() - this handles everything correctly
            // including 2D sprites, 3D blocks, enchantment glint, etc.
            itemRenderer.render(
                stack,
                ItemDisplayContext.GUI,
                false,
                poseStack,
                bufferSource,
                15728880,  // Full bright light
                OverlayTexture.NO_OVERLAY,
                model
            );

            // Flush the buffer
            bufferSource.endBatch();

            // Restore lighting
            if (useFlatLighting)
            {
                Lighting.setupFor3DItems();
            }

            // Read the pixels from the framebuffer
            try (final NativeImage fullImage = new NativeImage(renderSize, renderSize, false))
            {
                RenderSystem.bindTexture(renderTarget.getColorTextureId());
                fullImage.downloadTexture(0, false);
                fullImage.flipY();

                // Find the content bounds (non-transparent pixels)
                int minX = renderSize, minY = renderSize, maxX = 0, maxY = 0;
                for (int y = 0; y < renderSize; y++)
                {
                    for (int x = 0; x < renderSize; x++)
                    {
                        final int pixel = fullImage.getPixelRGBA(x, y);
                        final int alpha = (pixel >> 24) & 0xFF;
                        if (alpha > 0)
                        {
                            minX = Math.min(minX, x);
                            minY = Math.min(minY, y);
                            maxX = Math.max(maxX, x);
                            maxY = Math.max(maxY, y);
                        }
                    }
                }

                // Handle empty images (no visible content)
                if (maxX < minX || maxY < minY)
                {
                    return fullImage.asByteArray();
                }

                // Calculate crop dimensions (make it square based on largest dimension)
                final int contentWidth = maxX - minX + 1;
                final int contentHeight = maxY - minY + 1;
                final int cropSize = Math.max(contentWidth, contentHeight);

                // Center the content in the crop area
                final int centerX = (minX + maxX) / 2;
                final int centerY = (minY + maxY) / 2;
                final int cropMinX = Math.max(0, centerX - cropSize / 2);
                final int cropMinY = Math.max(0, centerY - cropSize / 2);

                // Create final image at target size
                try (final NativeImage finalImage = new NativeImage(maxSize, maxSize, false))
                {
                    // Calculate scaling factor to fit content into maxSize
                    final float downscale = (float) maxSize / cropSize;

                    for (int y = 0; y < maxSize; y++)
                    {
                        for (int x = 0; x < maxSize; x++)
                        {
                            // Map destination pixel to source position with bilinear interpolation
                            final float srcXf = cropMinX + (x / downscale);
                            final float srcYf = cropMinY + (y / downscale);

                            finalImage.setPixelRGBA(x, y, sampleBilinear(fullImage, srcXf, srcYf, renderSize));
                        }
                    }

                    return finalImage.asByteArray();
                }
            }
        }
        catch (IOException e)
        {
            LOGGER.error("Error rendering item image", e);
            return null;
        }
        finally
        {
            // Clean up
            renderTarget.unbindWrite();
            Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
            renderTarget.destroyBuffers();
            Lighting.setupForFlatItems();
        }
    }

    /**
     * Samples an image using bilinear interpolation for smoother downscaling.
     */
    private static int sampleBilinear(final NativeImage image, final float x, final float y, final int renderSize)
    {
        final int x0 = (int) Math.floor(x);
        final int y0 = (int) Math.floor(y);
        final int x1 = x0 + 1;
        final int y1 = y0 + 1;

        final float xFrac = x - x0;
        final float yFrac = y - y0;

        // Get the four neighboring pixels (with bounds checking)
        final int p00 = getPixelSafe(image, x0, y0, renderSize);
        final int p10 = getPixelSafe(image, x1, y0, renderSize);
        final int p01 = getPixelSafe(image, x0, y1, renderSize);
        final int p11 = getPixelSafe(image, x1, y1, renderSize);

        // Interpolate each channel separately
        final int r = bilinearInterpolateChannel(p00, p10, p01, p11, xFrac, yFrac, 0);
        final int g = bilinearInterpolateChannel(p00, p10, p01, p11, xFrac, yFrac, 8);
        final int b = bilinearInterpolateChannel(p00, p10, p01, p11, xFrac, yFrac, 16);
        final int a = bilinearInterpolateChannel(p00, p10, p01, p11, xFrac, yFrac, 24);

        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    /**
     * Gets a pixel from the image with bounds checking.
     */
    private static int getPixelSafe(final NativeImage image, final int x, final int y, final int size)
    {
        if (x < 0 || x >= size || y < 0 || y >= size)
        {
            return 0; // Transparent for out-of-bounds
        }
        return image.getPixelRGBA(x, y);
    }

    /**
     * Performs bilinear interpolation on a single color channel.
     */
    private static int bilinearInterpolateChannel(
        final int p00, final int p10, final int p01, final int p11,
        final float xFrac, final float yFrac, final int shift)
    {
        final int c00 = (p00 >> shift) & 0xFF;
        final int c10 = (p10 >> shift) & 0xFF;
        final int c01 = (p01 >> shift) & 0xFF;
        final int c11 = (p11 >> shift) & 0xFF;

        // Bilinear interpolation formula
        final float top = c00 + xFrac * (c10 - c00);
        final float bottom = c01 + xFrac * (c11 - c01);
        final float result = top + yFrac * (bottom - top);

        return Math.min(255, Math.max(0, Math.round(result)));
    }
}
