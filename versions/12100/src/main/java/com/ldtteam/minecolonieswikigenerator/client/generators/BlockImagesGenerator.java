package com.ldtteam.minecolonieswikigenerator.client.generators;

import com.ldtteam.minecolonieswikigenerator.client.IClientDataGenerator;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates PNG images for all block states.
 * This generator must run on the render thread since it requires OpenGL context.
 */
public class BlockImagesGenerator implements IClientDataGenerator
{
    private static final Logger LOGGER     = LogManager.getLogger();
    private static final int    IMAGE_SIZE = 300;

    private final Deque<Runnable>         renderQueue    = new ArrayDeque<>();
    private final AtomicInteger           totalTasks     = new AtomicInteger(0);
    private final AtomicInteger           completedTasks = new AtomicInteger(0);
    private       CompletableFuture<Void> completionFuture;
    private       Path                    outputPath;

    @Override
    public String getName()
    {
        return "Block Images";
    }

    @Override
    public CompletableFuture<Void> generate(final Path outputPath)
    {
        this.outputPath = outputPath;
        this.completionFuture = new CompletableFuture<>();

        // Queue all render tasks
        LOGGER.info("Queueing block image generation tasks...");
        queueBlockImages();
        LOGGER.info("Queued {} block image tasks", totalTasks.get());

        return completionFuture;
    }

    private void queueBlockImages()
    {
        BuiltInRegistries.BLOCK.entrySet().forEach(entry -> {
            final Block block = entry.getValue();
            final ResourceLocation blockId = entry.getKey().location();

            block.getStateDefinition().getPossibleStates().forEach(state -> {
                final String identifier = BlockStatesDataGenerator.getBlockStateIdentifier(state);
                totalTasks.incrementAndGet();
                renderQueue.add(() -> generateBlockImage(blockId, identifier, state));
            });
        });
    }

    private void generateBlockImage(final ResourceLocation blockId, final String identifier, final BlockState state)
    {
        try
        {
            final byte[] imageData = renderBlockStateToImage(state, IMAGE_SIZE);
            if (imageData != null)
            {
                saveFile(blockId.withSuffix("/" + identifier), imageData);
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
     *
     * @param state the block state to render
     * @param maxSize the maximum size of the output image (width and height)
     * @return the rendered image as PNG byte data, or null if rendering failed
     */
    public static byte[] renderBlockStateToImage(final BlockState state, final int maxSize)
    {
        // Render at a slightly larger size with padding to ensure block fits
        final int renderSize = maxSize * 2;

        final Minecraft mc = Minecraft.getInstance();
        final BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();
        final BakedModel model = blockRenderer.getBlockModel(state);

        // Create a framebuffer to render into
        final TextureTarget renderTarget = new TextureTarget(renderSize, renderSize, true, Minecraft.ON_OSX);
        renderTarget.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        renderTarget.clear(Minecraft.ON_OSX);
        renderTarget.bindWrite(true);

        // Set up the projection matrix for isometric-style rendering
        // Use a large ortho range to ensure block always fits
        final Matrix4f projectionMatrix = new Matrix4f().setOrtho(-2.0f, 2.0f, -2.0f, 2.0f, -100.0f, 100.0f);
        RenderSystem.setProjectionMatrix(projectionMatrix, VertexSorting.ORTHOGRAPHIC_Z);

        // Set up the pose stack with rotation for an isometric view
        final PoseStack poseStack = new PoseStack();
        poseStack.translate(0.0, 0.0, -50.0);
        // No scaling - render at natural size
        // Rotate for isometric view (similar to inventory rendering)
        poseStack.mulPose(Axis.XP.rotationDegrees(30.0f));
        poseStack.mulPose(Axis.YP.rotationDegrees(225.0f));
        // Center the block
        poseStack.translate(-0.5, -0.5, -0.5);

        // Set up lighting
        Lighting.setupFor3DItems();

        // Create buffer source for rendering
        final MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        try
        {
            // Render the block model
            blockRenderer.getModelRenderer()
                .renderModel(poseStack.last(), bufferSource.getBuffer(RenderType.solid()), state, model, 1.0f, 1.0f, 1.0f, 15728880, // Full bright light
                    OverlayTexture.NO_OVERLAY, ModelData.EMPTY, RenderType.solid());

            // Also render translucent and cutout layers if needed
            for (final RenderType renderType : model.getRenderTypes(state, RandomSource.create(), ModelData.EMPTY))
            {
                if (renderType != RenderType.solid())
                {
                    blockRenderer.getModelRenderer()
                        .renderModel(poseStack.last(),
                            bufferSource.getBuffer(renderType),
                            state,
                            model,
                            1.0f,
                            1.0f,
                            1.0f,
                            15728880,
                            OverlayTexture.NO_OVERLAY,
                            ModelData.EMPTY,
                            renderType);
                }
            }

            // Flush the buffer
            bufferSource.endBatch();

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
                    final float scale = (float) maxSize / cropSize;

                    for (int y = 0; y < maxSize; y++)
                    {
                        for (int x = 0; x < maxSize; x++)
                        {
                            // Map destination pixel to source pixel (nearest neighbor sampling)
                            final int srcX = cropMinX + (int) (x / scale);
                            final int srcY = cropMinY + (int) (y / scale);

                            if (srcX >= 0 && srcX < renderSize && srcY >= 0 && srcY < renderSize)
                            {
                                finalImage.setPixelRGBA(x, y, fullImage.getPixelRGBA(srcX, srcY));
                            }
                            else
                            {
                                finalImage.setPixelRGBA(x, y, 0); // Transparent
                            }
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
            renderTarget.unbindWrite();
            Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
            renderTarget.destroyBuffers();
            Lighting.setupForFlatItems();
        }
    }

    private void saveFile(final ResourceLocation id, final byte[] data)
    {
        final Path filePath = outputPath.resolve("block_images").resolve(id.getNamespace()).resolve(id.getPath() + ".png");

        try
        {
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, data);
        }
        catch (IOException e)
        {
            LOGGER.error("Failed to save file: {}", filePath, e);
        }
    }

    /**
     * Processes pending render tasks. Must be called from the render thread.
     *
     * @param batchSize the number of tasks to process per call
     */
    public void processRenderTasks(final int batchSize)
    {
        if (renderQueue.isEmpty())
        {
            if (!completionFuture.isDone())
            {
                LOGGER.info("Block images generation complete. Generated {} images.", completedTasks.get());
                completionFuture.complete(null);
            }
            return;
        }

        for (int i = 0; i < batchSize && !renderQueue.isEmpty(); i++)
        {
            final Runnable task = renderQueue.poll();
            if (task != null)
            {
                try
                {
                    task.run();
                    final int completed = completedTasks.incrementAndGet();
                    if (completed % 100 == 0)
                    {
                        LOGGER.info("Block images progress: {}/{}", completed, totalTasks.get());
                    }
                }
                catch (Exception e)
                {
                    LOGGER.error("Error executing render task", e);
                }
            }
        }
    }

    /**
     * Checks if this generator has pending render tasks.
     */
    public boolean hasPendingTasks()
    {
        return !renderQueue.isEmpty();
    }

    /**
     * Checks if this generator has completed all tasks.
     */
    public boolean isComplete()
    {
        return completionFuture != null && completionFuture.isDone();
    }
}
