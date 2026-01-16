package com.ldtteam.minecolonieswikigenerator.generators;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Generates PNG images for all block states, including BlockEntity rendering.
 * This renders BlockEntity components like the book on enchanting tables.
 * <p>
 * This generator must run on the render thread since it requires OpenGL context.
 */
public class BlockImagesGenerator extends LongRunningDataGenerator<ClientLevel>
{
    private static final Logger LOGGER = LogManager.getLogger();

    private static final int IMAGE_SIZE = 300;
    private static final int BATCH_SIZE = 10;

    public BlockImagesGenerator()
    {
        super(BATCH_SIZE);
    }

    @Override
    public String getName()
    {
        return "Block Images";
    }

    @Override
    public Path getGeneratorOutputPath(final Path rootPath)
    {
        return rootPath.resolve("block_images");
    }

    @Override
    protected void queueTasks(final Consumer<Runnable> register, final DataGeneratorOptions<ClientLevel> options)
    {
        ForgeRegistries.BLOCKS.getEntries().forEach(entry -> {
            final Block block = entry.getValue();
            final ResourceLocation blockId = entry.getKey().location();

            block.getStateDefinition().getPossibleStates().forEach(state -> register.accept(() -> generateBlockImage(options, blockId, state)));
        });
    }

    private void generateBlockImage(final DataGeneratorOptions<ClientLevel> options, final ResourceLocation blockId, final BlockState state)
    {
        final String identifier = BlockStatesDataGenerator.getBlockStateIdentifier(state);
        try
        {
            final byte[] imageData = renderBlockStateToImage(state, IMAGE_SIZE, options.getLevel());
            if (imageData != null)
            {
                options.saveFile(blockId.getNamespace(), blockId.withSuffix("/" + identifier).getPath(), "png", imageData);
            }
            else
            {
                LOGGER.warn("Failed to render image for block state: {} ({})", blockId, identifier);
            }
        }
        catch (Exception e)
        {
            LOGGER.error("Error generating image for block state: {} ({})", blockId, identifier, e);
        }
    }

    /**
     * Renders a block state to a PNG image, cropped to content bounds.
     * This version also renders any associated BlockEntity (like the book on enchanting tables).
     *
     * @param state   the block state to render
     * @param maxSize the maximum size of the output image (width and height)
     * @param level   the client level, used for BlockEntity rendering
     * @return the rendered image as PNG byte data, or null if rendering failed
     */
    public static byte[] renderBlockStateToImage(final BlockState state, final int maxSize, final ClientLevel level)
    {
        // Render at 4x resolution for better antialiasing through supersampling
        final int renderSize = maxSize * 4;

        final Minecraft mc = Minecraft.getInstance();

        // Create a framebuffer to render into
        final TextureTarget renderTarget = new TextureTarget(renderSize, renderSize, true, Minecraft.ON_OSX);
        renderTarget.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        renderTarget.clear(Minecraft.ON_OSX);
        renderTarget.bindWrite(true);


        // Set up the projection matrix for isometric-style rendering
        final Matrix4f projectionMatrix = new Matrix4f().setOrtho(-2.0f, 2.0f, -2.0f, 2.0f, -100.0f, 100.0f);
        RenderSystem.setProjectionMatrix(projectionMatrix, VertexSorting.ORTHOGRAPHIC_Z);

        // Set up the pose stack - using the same approach as MineColonies RenderHelper
        final PoseStack poseStack = new PoseStack();

        // Position and scale (similar to RenderHelper.renderBlock)
        // Use positive Z scale to maintain correct depth ordering
        final float scale = 1.0f;
        poseStack.translate(0, 0, -50);
        poseStack.scale(-scale, -scale, scale);
        poseStack.translate(-0.5f, -0.5f, 0);

        // Rotation for isometric view (30 degrees pitch, 225 degrees yaw - standard block view)
        poseStack.mulPose(Axis.XP.rotationDegrees(30));
        poseStack.translate(0.5f, 0, -0.5f);
        poseStack.mulPose(Axis.YP.rotationDegrees(225));
        poseStack.translate(-0.5f, 0, 0.5f);

        // Additional translation for depth
        poseStack.pushPose();
        poseStack.translate(0, 0, -1);

        // Set up shader and texture (same as RenderHelper)
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, InventoryMenu.BLOCK_ATLAS);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        // Enable blending for proper transparency handling
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        // Disable depth test to avoid issues with transparency rendering
        RenderSystem.disableDepthTest();

        // Set up 3D item lighting for good directional illumination
        Lighting.setupFor3DItems();

        // Create buffer source for rendering
        final MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        try
        {
            // Render block with cutout render type for transparency support without ambient occlusion darkening
            // Use FULL_BRIGHT for maximum lighting (15 sky light + 15 block light)
            mc.getBlockRenderer().renderSingleBlock(state, poseStack, bufferSource, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, RenderType.cutout());

            // Render BlockEntity if this block has one (e.g., enchanting table book)
            renderBlockEntity(state, poseStack, bufferSource, mc, level);

            bufferSource.endBatch();

            poseStack.popPose();

            // Read the pixels from the framebuffer
            try (final NativeImage fullImage = new NativeImage(renderSize, renderSize, false))
            {
                RenderSystem.bindTexture(renderTarget.getColorTextureId());
                fullImage.downloadTexture(0, false);
                // Don't flip - the projection and scale already handle orientation

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
            LOGGER.error("Error rendering block state image", e);
            return null;
        }
        finally
        {
            // Clean up
            RenderSystem.enableDepthTest();
            RenderSystem.disableBlend();
            renderTarget.unbindWrite();
            Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
            renderTarget.destroyBuffers();
        }
    }

    /**
     * Attempts to render the BlockEntity associated with a block state.
     * This handles blocks like enchanting tables that have a separate renderer for the book.
     *
     * @param state        the block state
     * @param poseStack    the pose stack for transformations
     * @param bufferSource the buffer source for rendering
     * @param mc           the Minecraft instance
     * @param level        the client level for BlockEntity context
     */
    private static void renderBlockEntity(
        final BlockState state,
        final PoseStack poseStack,
        final MultiBufferSource.BufferSource bufferSource,
        final Minecraft mc,
        final ClientLevel level)
    {
        final Block block = state.getBlock();

        // Check if this block can have a BlockEntity
        if (!(block instanceof EntityBlock entityBlock))
        {
            return;
        }

        try
        {
            // Create a temporary BlockEntity at origin
            // We use BlockPos.ZERO since we don't have a real world position
            final BlockEntity blockEntity = entityBlock.newBlockEntity(BlockPos.ZERO, state);

            if (blockEntity == null)
            {
                return;
            }

            // Set the level on the BlockEntity so renderers that need world access can work
            blockEntity.setLevel(level);

            // Get the renderer for this BlockEntity type
            final BlockEntityRenderDispatcher dispatcher = mc.getBlockEntityRenderDispatcher();
            final BlockEntityRenderer<BlockEntity> renderer = dispatcher.getRenderer(blockEntity);

            if (renderer == null)
            {
                return;
            }

            // Render the BlockEntity
            // We pass 0 for partialTick since this is a static render
            // The BlockEntity is rendered at the same position as the block
            try
            {
                renderer.render(blockEntity, 0.0f, poseStack, bufferSource, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            }
            catch (Exception e)
            {
                // Some BlockEntity renderers may fail without a proper Level
                // This is expected for some complex BlockEntities, so we just log at debug level
                LOGGER.debug("Could not render BlockEntity for {}: {}", state.getBlock().getDescriptionId(), e.getMessage());
            }
        }
        catch (Exception e)
        {
            // Failed to create or render BlockEntity - this is fine, just skip it
            LOGGER.debug("Could not create BlockEntity for {}: {}", state.getBlock().getDescriptionId(), e.getMessage());
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

        final int p00 = getPixelSafe(image, x0, y0, renderSize);
        final int p10 = getPixelSafe(image, x1, y0, renderSize);
        final int p01 = getPixelSafe(image, x0, y1, renderSize);
        final int p11 = getPixelSafe(image, x1, y1, renderSize);

        final int r = bilinearInterpolateChannel(p00, p10, p01, p11, xFrac, yFrac, 0);
        final int g = bilinearInterpolateChannel(p00, p10, p01, p11, xFrac, yFrac, 8);
        final int b = bilinearInterpolateChannel(p00, p10, p01, p11, xFrac, yFrac, 16);
        final int a = bilinearInterpolateChannel(p00, p10, p01, p11, xFrac, yFrac, 24);

        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    private static int getPixelSafe(final NativeImage image, final int x, final int y, final int size)
    {
        if (x < 0 || x >= size || y < 0 || y >= size)
        {
            return 0;
        }
        return image.getPixelRGBA(x, y);
    }

    private static int bilinearInterpolateChannel(final int p00, final int p10, final int p01, final int p11, final float xFrac, final float yFrac, final int shift)
    {
        final int c00 = (p00 >> shift) & 0xFF;
        final int c10 = (p10 >> shift) & 0xFF;
        final int c01 = (p01 >> shift) & 0xFF;
        final int c11 = (p11 >> shift) & 0xFF;

        final float top = c00 + xFrac * (c10 - c00);
        final float bottom = c01 + xFrac * (c11 - c01);
        final float result = top + yFrac * (bottom - top);

        return Math.min(255, Math.max(0, Math.round(result)));
    }
}
