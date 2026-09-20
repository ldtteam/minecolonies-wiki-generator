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
import net.minecraft.client.renderer.texture.*;
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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class BlockImageDataGenerator extends DataGenerator<ClientLevel>
{
    private static final Logger LOGGER = LogManager.getLogger();

    private static final int IMAGE_SIZE = 300;

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
    public Set<GeneratorTarget> listTargets(final ClientLevel level)
    {
        return ForgeRegistries.BLOCKS.getEntries().stream()
            .flatMap(entry -> {
                final ResourceLocation blockId = entry.getKey().location();
                return entry.getValue().getStateDefinition().getPossibleStates().stream()
                    .map(state -> new GeneratorTarget(
                        blockId.getNamespace(),
                        blockId.withSuffix("/" + BlockStateDataGenerator.getBlockStateIdentifier(state)).getPath()
                    ));
            })
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
        final String path = target.path();
        final int slash = path.lastIndexOf('/');
        final String blockPath = path.substring(0, slash);
        final String identifier = path.substring(slash + 1);

        final ResourceLocation blockId = new ResourceLocation(target.namespace(), blockPath);
        final Block block = ForgeRegistries.BLOCKS.getValue(blockId);
        if (block == null)
        {
            return CompletableFuture.completedFuture(null);
        }

        final BlockState state = block.getStateDefinition().getPossibleStates().stream()
            .filter(s -> BlockStateDataGenerator.getBlockStateIdentifier(s).equals(identifier))
            .findFirst()
            .orElse(null);

        if (state == null)
        {
            return CompletableFuture.completedFuture(null);
        }

        try
        {
            final byte[] imageData = renderBlockStateToImage(state, IMAGE_SIZE, options.getLevel());
            if (imageData != null)
            {
                options.saveFile(target.namespace(), target.path(), "png", imageData);
            }
            else
            {
                LOGGER.warn("Failed to render image for block state: {}", target);
            }
        }
        catch (Exception e)
        {
            LOGGER.error("Error generating image for block state: {}", target, e);
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Renders a block state to a PNG image, cropped to content bounds.
     *
     * @param state   the block state to render
     * @param maxSize the maximum size of the output image
     * @param level   the client level, used for BlockEntity rendering
     * @return the rendered image as PNG byte data, or null if rendering failed
     */
    public static byte[] renderBlockStateToImage(final BlockState state, final int maxSize, final ClientLevel level)
    {
        final int renderSize = maxSize * 4;

        final Minecraft mc = Minecraft.getInstance();

        final TextureTarget renderTarget = new TextureTarget(renderSize, renderSize, true, Minecraft.ON_OSX);
        renderTarget.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        renderTarget.clear(Minecraft.ON_OSX);
        renderTarget.bindWrite(true);

        final Matrix4f projectionMatrix = new Matrix4f().setOrtho(-2.0f, 2.0f, -2.0f, 2.0f, -100.0f, 100.0f);
        RenderSystem.setProjectionMatrix(projectionMatrix, VertexSorting.ORTHOGRAPHIC_Z);

        final PoseStack poseStack = new PoseStack();

        final float scale = 1.0f;
        poseStack.translate(0, 0, -50);
        poseStack.scale(-scale, -scale, scale);
        poseStack.translate(-0.5f, -0.5f, 0);

        poseStack.mulPose(Axis.XP.rotationDegrees(30));
        poseStack.translate(0.5f, 0, -0.5f);
        poseStack.mulPose(Axis.YP.rotationDegrees(225));
        poseStack.translate(-0.5f, 0, 0.5f);

        poseStack.pushPose();
        poseStack.translate(0, 0, -1);

        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, InventoryMenu.BLOCK_ATLAS);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();

        Lighting.setupFor3DItems();

        final MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        try
        {
            resetAnimatedTextures(mc);

            mc.getBlockRenderer().renderSingleBlock(state, poseStack, bufferSource, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, RenderType.cutout());

            renderBlockEntity(state, poseStack, bufferSource, mc, level);

            bufferSource.endBatch();

            poseStack.popPose();

            try (final NativeImage fullImage = new NativeImage(renderSize, renderSize, false))
            {
                RenderSystem.bindTexture(renderTarget.getColorTextureId());
                fullImage.downloadTexture(0, false);

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

                if (maxX < minX || maxY < minY)
                {
                    return fullImage.asByteArray();
                }

                final int contentWidth = maxX - minX + 1;
                final int contentHeight = maxY - minY + 1;
                final int cropSize = Math.max(contentWidth, contentHeight);

                final int centerX = (minX + maxX) / 2;
                final int centerY = (minY + maxY) / 2;
                final int cropMinX = Math.max(0, centerX - cropSize / 2);
                final int cropMinY = Math.max(0, centerY - cropSize / 2);

                try (final NativeImage finalImage = new NativeImage(maxSize, maxSize, false))
                {
                    final float downscale = (float) maxSize / cropSize;

                    for (int y = 0; y < maxSize; y++)
                    {
                        for (int x = 0; x < maxSize; x++)
                        {
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
            RenderSystem.enableDepthTest();
            RenderSystem.disableBlend();
            renderTarget.unbindWrite();
            Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
            renderTarget.destroyBuffers();
        }
    }

    private static void resetAnimatedTextures(final Minecraft mc)
    {
        final AbstractTexture texture = mc.getTextureManager().getTexture(InventoryMenu.BLOCK_ATLAS);
        if (!(texture instanceof TextureAtlas atlas))
        {
            return;
        }

        atlas.bind();
        for (final TextureAtlasSprite.Ticker ticker : atlas.animatedTextures)
        {
            if (ticker instanceof SpriteContents.Ticker spriteTicker)
            {
                spriteTicker.frame = 0;
                spriteTicker.subFrame = 0;
            }
            ticker.tickAndUpload();
        }
    }

    private static void renderBlockEntity(
        final BlockState state,
        final PoseStack poseStack,
        final MultiBufferSource.BufferSource bufferSource,
        final Minecraft mc,
        final ClientLevel level)
    {
        final Block block = state.getBlock();

        if (!(block instanceof EntityBlock entityBlock))
        {
            return;
        }

        try
        {
            final BlockEntity blockEntity = entityBlock.newBlockEntity(BlockPos.ZERO, state);

            if (blockEntity == null)
            {
                return;
            }

            blockEntity.setLevel(level);

            final BlockEntityRenderDispatcher dispatcher = mc.getBlockEntityRenderDispatcher();
            final BlockEntityRenderer<BlockEntity> renderer = dispatcher.getRenderer(blockEntity);

            if (renderer == null)
            {
                return;
            }

            try
            {
                renderer.render(blockEntity, 0.0f, poseStack, bufferSource, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            }
            catch (Exception e)
            {
                LOGGER.debug("Could not render BlockEntity for {}: {}", state.getBlock().getDescriptionId(), e.getMessage());
            }
        }
        catch (Exception e)
        {
            LOGGER.debug("Could not create BlockEntity for {}: {}", state.getBlock().getDescriptionId(), e.getMessage());
        }
    }

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
