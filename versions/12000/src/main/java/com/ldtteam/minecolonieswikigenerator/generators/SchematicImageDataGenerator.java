package com.ldtteam.minecolonieswikigenerator.generators;

import com.ldtteam.structurize.blocks.ModBlocks;
import com.ldtteam.structurize.blocks.schematic.BlockFluidSubstitution;
import com.ldtteam.structurize.blocks.schematic.BlockSolidSubstitution;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.blueprints.v1.BlueprintTagUtils;
import com.ldtteam.structurize.blueprints.v1.BlueprintUtil;
import com.ldtteam.structurize.storage.StructurePacks;
import com.ldtteam.structurize.util.BlockInfo;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.SimpleWeightedRandomList;
import net.minecraft.util.random.WeightedEntry;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.client.ChunkRenderTypeSet;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Generates front and back isometric PNG images for every blueprint registered in StructurePacks.
 * Each schematic is rendered with ambient occlusion via a blueprint-backed BlockAndTintGetter.
 */
public class SchematicImageDataGenerator extends DataGenerator<ClientLevel>
{
    private static final Logger LOGGER = LogManager.getLogger();

    private static final int BATCH_SIZE       = 5;
    private static final int OUTPUT_SIZE      = 512;
    // Controls internal render resolution: pixels per projected block unit
    private static final int PIXELS_PER_BLOCK = 64;

    private static final float PITCH     = 20f;
    private static final float FRONT_YAW = 22.5f;
    private static final float BACK_YAW  = 202.5f;

    // Subsurface fill block used to give the ground slab depth (rendered from groundY-1 downward)
    private static final Map<BlockState, BlockState> SUBSURFACE_BLOCK_BY_GROUND = Map.ofEntries(Map.entry(Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState()),
        Map.entry(Blocks.COARSE_DIRT.defaultBlockState(), Blocks.DIRT.defaultBlockState()),
        Map.entry(Blocks.SAND.defaultBlockState(), Blocks.SANDSTONE.defaultBlockState()),
        Map.entry(Blocks.SNOW_BLOCK.defaultBlockState(), Blocks.PACKED_ICE.defaultBlockState()),
        Map.entry(Blocks.GRAVEL.defaultBlockState(), Blocks.STONE.defaultBlockState()),
        Map.entry(Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState()),
        Map.entry(Blocks.COBBLESTONE.defaultBlockState(), Blocks.STONE.defaultBlockState()),
        Map.entry(Blocks.TERRACOTTA.defaultBlockState(), Blocks.TERRACOTTA.defaultBlockState()),
        Map.entry(Blocks.WARPED_NYLIUM.defaultBlockState(), Blocks.NETHERRACK.defaultBlockState()));

    // Override the ground block for specific pack folder IDs (packRoot.getFileName()); all others default to grass_block
    private static final Map<String, BlockState> GROUND_BLOCK_BY_PACK = Map.ofEntries(
        // minecolonies
        Map.entry("cavern", Blocks.STONE.defaultBlockState()),
        Map.entry("incan", Blocks.COARSE_DIRT.defaultBlockState()),
        Map.entry("lostcity", Blocks.TERRACOTTA.defaultBlockState()),
        Map.entry("nordic", Blocks.SNOW_BLOCK.defaultBlockState()),
        Map.entry("sandstone", Blocks.SAND.defaultBlockState()),
        Map.entry("spacewars", Blocks.SAND.defaultBlockState()),
        Map.entry("truedwarven", Blocks.STONE.defaultBlockState()),
        Map.entry("warped", Blocks.WARPED_NYLIUM.defaultBlockState()),
        // stylecolonies
        Map.entry("Tropical", Blocks.SAND.defaultBlockState()),
        Map.entry("aquatica", Blocks.GRAVEL.defaultBlockState()),
        Map.entry("underwaterbase", Blocks.GRAVEL.defaultBlockState()));

    // Vegetation candidates per ground block with relative weights; chosen randomly for tiles outside the schematic footprint
    private static final Map<BlockState, SimpleWeightedRandomList<BlockState>> VEGETATION_BY_GROUND = Map.ofEntries(Map.entry(Blocks.GRASS_BLOCK.defaultBlockState(),
            SimpleWeightedRandomList.<BlockState>builder()
                .add(Blocks.GRASS.defaultBlockState(), 60)
                .add(Blocks.TALL_GRASS.defaultBlockState(), 30)
                .add(Blocks.DANDELION.defaultBlockState(), 5)
                .add(Blocks.POPPY.defaultBlockState(), 5)
                .build()),
        Map.entry(Blocks.SAND.defaultBlockState(),
            SimpleWeightedRandomList.<BlockState>builder().add(Blocks.DEAD_BUSH.defaultBlockState(), 50).add(Blocks.CACTUS.defaultBlockState(), 50).build()),
        Map.entry(Blocks.SNOW_BLOCK.defaultBlockState(),
            SimpleWeightedRandomList.<BlockState>builder().add(Blocks.SWEET_BERRY_BUSH.defaultBlockState(), 60).add(Blocks.DEAD_BUSH.defaultBlockState(), 40).build()),
        Map.entry(Blocks.WARPED_NYLIUM.defaultBlockState(),
            SimpleWeightedRandomList.<BlockState>builder().add(Blocks.WARPED_ROOTS.defaultBlockState(), 70).add(Blocks.WARPED_FUNGUS.defaultBlockState(), 30).build()),
        Map.entry(Blocks.COARSE_DIRT.defaultBlockState(),
            SimpleWeightedRandomList.<BlockState>builder().add(Blocks.GRASS.defaultBlockState(), 70).add(Blocks.DEAD_BUSH.defaultBlockState(), 30).build()));

    // Default probability (0–1) that any outside-footprint ground tile gets a vegetation block on top
    private static final float VEGETATION_CHANCE = 0.15f;

    // Per-ground-block overrides for vegetation density; falls back to VEGETATION_CHANCE if not present
    private static final Map<BlockState, Float> VEGETATION_CHANCE_BY_GROUND = Map.of(Blocks.SAND.defaultBlockState(),
        0.005f,
        Blocks.SNOW_BLOCK.defaultBlockState(),
        0.01f,
        Blocks.WARPED_NYLIUM.defaultBlockState(),
        0.10f,
        Blocks.COARSE_DIRT.defaultBlockState(),
        0.10f);

    // What BlockFluidSubstitution becomes in the "placed" render, keyed by ground block
    private static final Map<BlockState, BlockState> FLUID_BLOCK_BY_GROUND = Map.ofEntries(Map.entry(Blocks.NETHERRACK.defaultBlockState(), Blocks.LAVA.defaultBlockState()),
        Map.entry(Blocks.WARPED_NYLIUM.defaultBlockState(), Blocks.LAVA.defaultBlockState()));

    @Override
    public Integer batchSize()
    {
        return BATCH_SIZE;
    }

    @Override
    public String getName()
    {
        return "Schematic Images";
    }

    @Override
    public boolean shouldClearBeforeGeneration()
    {
        return false;
    }

    @Override
    public Path getGeneratorOutputPath(final Path rootPath)
    {
        return rootPath.resolve("schematic_images");
    }

    @Override
    public Set<GeneratorTarget> listTargets(final ClientLevel level)
    {
        return StructurePacks.getPackMetas().stream()
            .flatMap(packMeta -> {
                final Path packRoot = packMeta.getPath();
                final String packId = packRoot.getFileName().toString();
                try (final Stream<Path> walk = Files.walk(packRoot))
                {
                    return walk.filter(p -> !Files.isDirectory(p) && p.toString().endsWith(".blueprint"))
                        .map(blueprintPath -> {
                            final String subDir = packRoot.relativize(blueprintPath.getParent()).toString().replace('\\', '/');
                            final String fileName = blueprintPath.getFileName().toString().replace(".blueprint", "");
                            final String filePath = subDir.isEmpty() ? fileName : subDir + "/" + fileName;
                            return new GeneratorTarget(packId, filePath);
                        })
                        .toList()
                        .stream();
                }
                catch (final IOException e)
                {
                    LOGGER.warn("Could not walk blueprint pack '{}': {}", packId, e.getMessage());
                    return Stream.empty();
                }
            })
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public CompletableFuture<Void> generate(final GeneratorTarget target, final DataGeneratorOptions<ClientLevel> options)
    {
        final String packId = target.namespace();
        final String filePath = target.path();

        final var packMeta = StructurePacks.getPackMetas().stream()
            .filter(m -> m.getPath().getFileName().toString().equals(packId))
            .findFirst()
            .orElse(null);

        if (packMeta == null)
        {
            return CompletableFuture.completedFuture(null);
        }

        final String packName = packMeta.getName();
        final Path packRoot = packMeta.getPath();
        final Path packOutputPath = options.getOutputPath().resolve(packId);

        final int lastSlash = filePath.lastIndexOf('/');
        final String subDir = lastSlash < 0 ? "" : filePath.substring(0, lastSlash);
        final String fileName = lastSlash < 0 ? filePath : filePath.substring(lastSlash + 1);

        final Path blueprintPath = subDir.isEmpty() ? packRoot.resolve(fileName + ".blueprint") : packRoot.resolve(subDir).resolve(fileName + ".blueprint");
        final Path hashFile = packOutputPath.resolve(filePath + ".hash");

        final String currentHash;
        try
        {
            currentHash = md5Stream(blueprintPath);
        }
        catch (final IOException e)
        {
            LOGGER.warn("Could not hash blueprint '{}': {}", blueprintPath, e.getMessage());
            return CompletableFuture.completedFuture(null);
        }

        if (hashMatches(hashFile, currentHash))
        {
            return CompletableFuture.completedFuture(null);
        }

        final Blueprint blueprint = loadBlueprint(packName, blueprintPath);
        if (blueprint != null)
        {
            renderAndSave(options, packName, packId, subDir, blueprint, hashFile, currentHash);
        }
        return CompletableFuture.completedFuture(null);
    }

    private static String md5Stream(final Path path) throws IOException
    {
        try
        {
            final MessageDigest digest = MessageDigest.getInstance("MD5");
            try (final DigestInputStream in = new DigestInputStream(Files.newInputStream(path), digest))
            {
                in.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (final NoSuchAlgorithmException e)
        {
            throw new RuntimeException(e);
        }
    }

    private static boolean hashMatches(final Path hashFile, final String currentHash)
    {
        try
        {
            return Files.exists(hashFile) && Files.readString(hashFile, StandardCharsets.UTF_8).trim().equals(currentHash);
        }
        catch (final IOException e)
        {
            return false;
        }
    }

    private static Blueprint loadBlueprint(final String packName, final Path path)
    {
        try
        {
            final CompoundTag nbt = NbtIo.readCompressed(new ByteArrayInputStream(Files.readAllBytes(path)));
            final Blueprint blueprint = BlueprintUtil.readBlueprintFromNBT(nbt);
            if (blueprint != null)
            {
                blueprint.setFileName(path.getFileName().toString().replace(".blueprint", ""));
                blueprint.setFilePath(path.getParent()).setPackName(packName);
            }
            return blueprint;
        }
        catch (final IOException e)
        {
            LOGGER.warn("Could not load blueprint '{}': {}", path, e.getMessage());
            return null;
        }
    }

    private void renderAndSave(
        final DataGeneratorOptions<ClientLevel> options,
        final String packName,
        final String packId,
        final String subDir,
        final Blueprint blueprint,
        final Path hashFile,
        final String hash)
    {
        final String fileName = blueprint.getFileName();
        try
        {
            final ClientLevel level = options.getLevel();
            final List<BlockInfo> blocks = blueprint.getBlockInfoAsList();
            final int sizeX = blueprint.getSizeX();
            final int sizeY = blueprint.getSizeY();
            final int sizeZ = blueprint.getSizeZ();
            final BlueprintBlockView blockView = new BlueprintBlockView(blocks, sizeX, sizeY, sizeZ);

            final Map<BlockPos, CompoundTag> teData = new HashMap<>();
            for (final BlockInfo info : blocks)
            {
                if (info.hasTileEntityData())
                {
                    teData.put(info.getPos(), info.getTileEntityData());
                }
            }

            // Collect light placeholder positions so we can overdraw them for the _full variant
            final List<BlockPos> lightPlaceholderPositions = blocks.stream().filter(info -> isLightPlaceholder(info.getState())).map(BlockInfo::getPos).toList();

            final String filePath = subDir.isEmpty() ? fileName : subDir + "/" + fileName;
            final BlockState groundBlock = GROUND_BLOCK_BY_PACK.getOrDefault(packId, Blocks.GRASS_BLOCK.defaultBlockState());
            final BlockState fluidBlock = FLUID_BLOCK_BY_GROUND.getOrDefault(groundBlock, Blocks.WATER.defaultBlockState());
            final long rngSeed = fileName.hashCode();
            final int groundPlaneY = blueprint.getPrimaryBlockOffset().getY() - BlueprintTagUtils.getGroundAnchorOffset(blueprint, 1);

            final Minecraft mc = Minecraft.getInstance();
            final float orthoHalf = computeOrthoHalf(sizeX, sizeY, sizeZ);
            // Padding: enough to fill grass to the ortho viewport edge from any angle, times 3 for safe coverage
            final int grassPadding = (int) Math.ceil(orthoHalf * 4) + 2;
            final int renderSize = Math.min((int) (orthoHalf * 2 * PIXELS_PER_BLOCK), 2048);

            final TextureTarget renderTarget = new TextureTarget(renderSize, renderSize, true, Minecraft.ON_OSX);
            renderTarget.setClearColor(0.53f, 0.81f, 0.98f, 1.0f);

            final Matrix4f projectionMatrix = new Matrix4f().setOrtho(-orthoHalf, orthoHalf, -orthoHalf, orthoHalf, -500.0f, 500.0f);

            try
            {
                RenderSystem.setProjectionMatrix(projectionMatrix, VertexSorting.ORTHOGRAPHIC_Z);
                RenderSystem.setShader(GameRenderer::getPositionTexShader);
                RenderSystem.setShaderTexture(0, InventoryMenu.BLOCK_ATLAS);
                RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                RenderSystem.enableDepthTest();
                Lighting.setupLevel(new Matrix4f().rotationAround(Axis.YP.rotationDegrees(-45), 0.0f, 0.5f, 0.0f));
                resetAnimatedTextures(mc);

                for (final float yaw : new float[] {FRONT_YAW, BACK_YAW})
                {
                    final String dirSuffix = yaw == FRONT_YAW ? "_front" : "_back";

                    // --- Phase 1: ground + schematic without placeholders → _clean ---
                    renderTarget.clear(Minecraft.ON_OSX);
                    renderTarget.bindWrite(true);

                    final PoseStack cameraStack = new PoseStack();
                    cameraStack.translate(0, 0, -250);
                    cameraStack.scale(-1.0f, -1.0f, 1.0f);
                    cameraStack.mulPose(Axis.XP.rotationDegrees(PITCH));
                    cameraStack.mulPose(Axis.YP.rotationDegrees(yaw));
                    cameraStack.translate(-(sizeX / 2.0f), -(sizeY / 2.0f), -(sizeZ / 2.0f));

                    renderGround(mc, cameraStack, bufferSource(mc), blockView, groundBlock, sizeX, sizeY, sizeZ, -1, rngSeed, grassPadding, grassPadding);
                    renderBlocks(mc, cameraStack, bufferSource(mc), blockView, teData, level, false, groundBlock, fluidBlock, -1);
                    // Draw bounding box last with depth test disabled so it always shows on top
                    RenderSystem.disableDepthTest();
                    renderBoundingBox(cameraStack, (float) sizeX, (float) sizeY, (float) sizeZ);
                    RenderSystem.enableDepthTest();
                    flushAndSave(renderTarget, renderSize, options, packId, filePath + "_clean" + dirSuffix);

                    // --- Phase 2: overdraw placeholders → _full ---
                    // No clear — just draw the placeholder blocks on top of the existing framebuffer
                    renderTarget.bindWrite(true);
                    renderPlaceholders(mc, cameraStack, bufferSource(mc), blockView, lightPlaceholderPositions, level);
                    // Redraw bounding box on top after placeholder overdraw
                    RenderSystem.disableDepthTest();
                    renderBoundingBox(cameraStack, (float) sizeX, (float) sizeY, (float) sizeZ);
                    RenderSystem.enableDepthTest();
                    flushAndSave(renderTarget, renderSize, options, packId, filePath + "_full" + dirSuffix);

                    // --- Phase 3: clear, re-render with ground at computed placement level → _placed ---
                    renderTarget.clear(Minecraft.ON_OSX);
                    renderTarget.bindWrite(true);
                    // Tell blockView about the ground so edge water faces are culled against it
                    blockView.setGroundContext(groundBlock, groundPlaneY);
                    renderGround(mc, cameraStack, bufferSource(mc), blockView, groundBlock, sizeX, sizeY, sizeZ, groundPlaneY, rngSeed, grassPadding, grassPadding);
                    renderBlocks(mc, cameraStack, bufferSource(mc), blockView, teData, level, true, groundBlock, fluidBlock, groundPlaneY);
                    blockView.clearGroundContext();
                    flushAndSave(renderTarget, renderSize, options, packId, filePath + "_placed" + dirSuffix);
                }
            }
            finally
            {
                RenderSystem.disableBlend();
                renderTarget.unbindWrite();
                mc.getMainRenderTarget().bindWrite(true);
                renderTarget.destroyBuffers();
                RenderSystem.clearColor(0.0f, 0.0f, 0.0f, 0.0f);
            }
        }
        catch (final Exception e)
        {
            LOGGER.error("Error rendering schematic '{}' in pack '{}'", fileName, packName, e);
            return;
        }

        try
        {
            Files.createDirectories(hashFile.getParent());
            Files.writeString(hashFile, hash, StandardCharsets.UTF_8);
        }
        catch (final IOException e)
        {
            LOGGER.error("Could not write hash file for schematic '{}' in pack '{}'", fileName, packName, e);
        }
    }

    private static boolean isLightPlaceholder(final BlockState state)
    {
        return state != null && state.is(ModBlocks.blockSubstitution.get());
    }

    /**
     * Computes the ortho half-extent needed to fit the schematic bounding box exactly in frame,
     * by projecting all 8 corners through the camera transform for both yaws and taking the max.
     */
    private static float computeOrthoHalf(final int sizeX, final int sizeY, final int sizeZ)
    {
        final double pitchRad = Math.toRadians(PITCH);
        final double cosPitch = Math.cos(pitchRad);
        final double sinPitch = Math.sin(pitchRad);

        float maxHalf = 0f;
        for (final float yaw : new float[] {FRONT_YAW, BACK_YAW})
        {
            final double yawRad = Math.toRadians(yaw);
            final double cosYaw = Math.cos(yawRad);
            final double sinYaw = Math.sin(yawRad);

            // Center of the bounding box
            final double cx = sizeX / 2.0, cy = sizeY / 2.0, cz = sizeZ / 2.0;

            for (final int ix : new int[] {0, sizeX})
            {
                for (final int iy : new int[] {0, sizeY})
                {
                    for (final int iz : new int[] {0, sizeZ})
                    {
                        // Translate to center
                        double x = ix - cx, y = iy - cy, z = iz - cz;

                        // Rotate around Y by yaw (camera looks along -Z after rotation)
                        // Note: scale(-1,-1,1) is applied in the camera stack, so negate x and y
                        final double rx = x * cosYaw + z * sinYaw;
                        final double rz = -x * sinYaw + z * cosYaw;

                        // Rotate around X by pitch
                        final double projX = -(rx);                          // screen X (negated by scale)
                        final double projY = -(y * cosPitch - rz * sinPitch); // screen Y (negated by scale)

                        maxHalf = Math.max(maxHalf, (float) Math.abs(projX));
                        maxHalf = Math.max(maxHalf, (float) Math.abs(projY));
                    }
                }
            }
        }
        // Small margin so the schematic isn't flush against the frame edge
        return maxHalf + 1.0f;
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

    private static void renderGround(
        final Minecraft mc,
        final PoseStack cameraStack,
        final MultiBufferSource.BufferSource bufferSource,
        final BlueprintBlockView blockView,
        final BlockState groundBlock,
        final int sizeX,
        final int sizeY,
        final int sizeZ,
        final int groundY,
        final long rngSeed,
        final int paddingX,
        final int paddingZ)
    {
        final RandomSource rng = RandomSource.create(rngSeed);
        final SimpleWeightedRandomList<BlockState> vegetationList = VEGETATION_BY_GROUND.getOrDefault(groundBlock, SimpleWeightedRandomList.empty());
        final float vegetationChance = VEGETATION_CHANCE_BY_GROUND.getOrDefault(groundBlock, VEGETATION_CHANCE);
        final BlockState subsurfaceBlock = SUBSURFACE_BLOCK_BY_GROUND.getOrDefault(groundBlock, Blocks.DIRT.defaultBlockState());
        final int fillDepth = sizeY + 2;

        // renderSingleBlock calls blockColors.getColor(state, null, null) → default tint.
        // We want the actual biome tint from blockView. Compute a per-channel correction ratio and
        // wrap bufferSource so vertex colors are adjusted inline before being written to the buffer.
        final int biomeTint = mc.getBlockColors().getColor(groundBlock, blockView, BlockPos.ZERO, 0);
        final int defaultTint = mc.getBlockColors().getColor(groundBlock, null, null, 0);
        final MultiBufferSource groundBufferSource;
        if (biomeTint != -1 && defaultTint != -1 && biomeTint != defaultTint)
        {
            final float biomeR = ((biomeTint >> 16) & 0xFF) / 255.0f;
            final float biomeG = ((biomeTint >> 8) & 0xFF) / 255.0f;
            final float biomeB = (biomeTint & 0xFF) / 255.0f;
            final float defaultR = ((defaultTint >> 16) & 0xFF) / 255.0f;
            final float defaultG = ((defaultTint >> 8) & 0xFF) / 255.0f;
            final float defaultB = (defaultTint & 0xFF) / 255.0f;
            final float cr = defaultR > 0 ? biomeR / defaultR : 1f;
            final float cg = defaultG > 0 ? biomeG / defaultG : 1f;
            final float cb = defaultB > 0 ? biomeB / defaultB : 1f;
            groundBufferSource = new TintCorrectingBufferSource(bufferSource, cr, cg, cb);
        }
        else
        {
            groundBufferSource = bufferSource;
        }

        for (int gx = -paddingX; gx < sizeX + paddingX; gx++)
        {
            for (int gz = -paddingZ; gz < sizeZ + paddingZ; gz++)
            {
                final boolean insideFootprint = gx >= 0 && gx < sizeX && gz >= 0 && gz < sizeZ;

                // For the raised ground plane (_placed), skip tiles inside the footprint — the schematic covers them
                if (!insideFootprint || groundY == -1)
                {
                    cameraStack.pushPose();
                    cameraStack.translate(gx, groundY, gz);
                    final BakedModel groundModel = mc.getBlockRenderer().getBlockModelShaper().getBlockModel(groundBlock);
                    for (final RenderType rt : groundModel.getRenderTypes(groundBlock, mc.level.random, ModelData.EMPTY))
                    {
                        mc.getBlockRenderer()
                            .renderSingleBlock(groundBlock, cameraStack, groundBufferSource, LightTexture.pack(15, 15), OverlayTexture.NO_OVERLAY, ModelData.EMPTY, rt);
                    }
                    cameraStack.popPose();

                    final boolean onPerimeter = gx == -1 || gx == sizeX || gz == -1 || gz == sizeZ;
                    if (groundY != -1 && onPerimeter)
                    {
                        for (int dy = 1; dy <= fillDepth; dy++)
                        {
                            cameraStack.pushPose();
                            cameraStack.translate(gx, groundY - dy, gz);
                            mc.getBlockRenderer()
                                .renderSingleBlock(subsurfaceBlock,
                                    cameraStack,
                                    bufferSource,
                                    LightTexture.pack(15, 15),
                                    OverlayTexture.NO_OVERLAY,
                                    ModelData.EMPTY,
                                    RenderType.solid());
                            cameraStack.popPose();
                        }
                    }
                }

                if (!insideFootprint && !vegetationList.isEmpty() && rng.nextFloat() < vegetationChance)
                {
                    final BlockState vegetation = vegetationList.getRandom(rng).map(WeightedEntry.Wrapper::getData).orElse(null);
                    if (vegetation != null)
                    {
                        cameraStack.pushPose();
                        cameraStack.translate(gx, groundY + 1, gz);
                        mc.getBlockRenderer()
                            .renderSingleBlock(vegetation,
                                cameraStack,
                                groundBufferSource,
                                LightTexture.pack(15, 15),
                                OverlayTexture.NO_OVERLAY,
                                ModelData.EMPTY,
                                RenderType.cutout());
                        cameraStack.popPose();
                    }
                }
            }
        }
        bufferSource.endBatch(RenderType.solid());
        bufferSource.endBatch(RenderType.cutout());
        bufferSource.endBatch(RenderType.cutoutMipped());
    }

    private static MultiBufferSource.BufferSource bufferSource(final Minecraft mc)
    {
        return mc.renderBuffers().bufferSource();
    }

    private static void renderBlocks(
        final Minecraft mc,
        final PoseStack cameraStack,
        final MultiBufferSource.BufferSource bufferSource,
        final BlueprintBlockView blockView,
        final Map<BlockPos, CompoundTag> teData,
        final ClientLevel level,
        final boolean replacePlaceholders,
        final BlockState groundBlock,
        final BlockState fluidBlock,
        final int groundPlaneY)
    {
        final Set<RenderType> usedRenderTypes = new LinkedHashSet<>();
        final List<BlockPos> fluidPositions = new ArrayList<>();
        final List<FluidState> fluidStates = new ArrayList<>();
        final List<BlockState> fluidBlockStates = new ArrayList<>();
        for (final BlockPos pos : blockView.getPositions())
        {
            BlockState state = blockView.getBlockState(pos);
            if (state.isAir())
            {
                continue;
            }

            if (isLightPlaceholder(state))
            {
                if (replacePlaceholders && pos.getY() <= groundPlaneY)
                {
                    state = groundBlock;
                }
                else
                {
                    continue;
                }
            }
            else if (replacePlaceholders)
            {
                if (state.getBlock() instanceof BlockSolidSubstitution)
                {
                    state = groundBlock;
                }
                else if (state.getBlock() instanceof BlockFluidSubstitution)
                {
                    state = fluidBlock;
                }
            }

            final FluidState fluidState = state.getFluidState();
            if (replacePlaceholders && !fluidState.isEmpty())
            {
                fluidPositions.add(pos);
                fluidStates.add(fluidState);
                fluidBlockStates.add(state);
                if (state.getBlock() instanceof LiquidBlock)
                {
                    continue;
                }
            }

            cameraStack.pushPose();
            cameraStack.translate(pos.getX(), pos.getY(), pos.getZ());

            ModelData modelData = ModelData.EMPTY;
            final CompoundTag tileNbt = teData.get(pos);
            if (tileNbt != null && state.getBlock() instanceof EntityBlock entityBlock)
            {
                final BlockEntity be = entityBlock.newBlockEntity(pos, state);
                if (be != null)
                {
                    be.setLevel(level);
                    be.load(tileNbt);
                    modelData = be.getModelData();
                }
            }

            if (!state.isAir())
            {
                final BakedModel bakedModel = mc.getBlockRenderer().getBlockModelShaper().getBlockModel(state);
                final ChunkRenderTypeSet renderLayers = bakedModel.getRenderTypes(state, level.random, modelData);
                for (final RenderType renderType : renderLayers)
                {
                    usedRenderTypes.add(renderType);
                    mc.getBlockRenderer().renderBatched(state, pos, blockView, cameraStack, bufferSource.getBuffer(renderType), true, level.random, modelData, renderType);
                }

                renderBlockEntity(state, pos, tileNbt, cameraStack, bufferSource, mc, level);
            }

            cameraStack.popPose();
        }

        if (!fluidPositions.isEmpty())
        {
            // Patch blockView so neighbor lookups during renderLiquid see the resolved fluid states,
            // not the original substitution blocks — this ensures correct face culling and height calc.
            for (int i = 0; i < fluidPositions.size(); i++)
            {
                blockView.setBlock(fluidPositions.get(i), fluidBlockStates.get(i));
            }

            for (int i = 0; i < fluidPositions.size(); i++)
            {
                final BlockPos fp = fluidPositions.get(i);
                final FluidState fs = fluidStates.get(i);
                final int chunkOriginX = fp.getX() & ~15;
                final int chunkOriginY = fp.getY() & ~15;
                final int chunkOriginZ = fp.getZ() & ~15;
                cameraStack.pushPose();
                cameraStack.translate(chunkOriginX, chunkOriginY, chunkOriginZ);
                final Matrix4f cameraMat = cameraStack.last().pose();
                final RenderType fluidRenderType = ItemBlockRenderTypes.getRenderLayer(fs);
                final VertexConsumer transformingConsumer = new TransformingVertexConsumer(bufferSource.getBuffer(fluidRenderType), cameraMat);
                mc.getBlockRenderer().renderLiquid(fp, blockView, transformingConsumer, fluidBlockStates.get(i), fs);
                bufferSource.endBatch(fluidRenderType);
                cameraStack.popPose();
            }
        }

        for (final RenderType rt : usedRenderTypes)
        {
            bufferSource.endBatch(rt);
        }
        bufferSource.endBatch();
    }

    private static void renderBoundingBox(final PoseStack poseStack, final float sizeX, final float sizeY, final float sizeZ)
    {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        final Tesselator tesselator = Tesselator.getInstance();
        final BufferBuilder buf = tesselator.getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        final Matrix4f mat = poseStack.last().pose();
        final float t = 0.03f; // half-thickness in blocks

        // X-axis edges (along X)
        addEdgeX(buf, mat, 0, 0, 0, sizeX, t);
        addEdgeX(buf, mat, 0, sizeY, 0, sizeX, t);
        addEdgeX(buf, mat, 0, 0, sizeZ, sizeX, t);
        addEdgeX(buf, mat, 0, sizeY, sizeZ, sizeX, t);

        // Z-axis edges (along Z)
        addEdgeZ(buf, mat, 0, 0, 0, sizeZ, t);
        addEdgeZ(buf, mat, sizeX, 0, 0, sizeZ, t);
        addEdgeZ(buf, mat, 0, sizeY, 0, sizeZ, t);
        addEdgeZ(buf, mat, sizeX, sizeY, 0, sizeZ, t);

        // Y-axis edges (along Y)
        addEdgeY(buf, mat, 0, 0, 0, sizeY, t);
        addEdgeY(buf, mat, sizeX, 0, 0, sizeY, t);
        addEdgeY(buf, mat, 0, 0, sizeZ, sizeY, t);
        addEdgeY(buf, mat, sizeX, 0, sizeZ, sizeY, t);

        tesselator.end();
    }

    private static void flushAndSave(
        final TextureTarget renderTarget,
        final int renderSize,
        final DataGeneratorOptions<ClientLevel> options,
        final String packId,
        final String filePath) throws IOException
    {
        renderTarget.bindWrite(true);
        try (final NativeImage fullImage = new NativeImage(renderSize, renderSize, false))
        {
            RenderSystem.bindTexture(renderTarget.getColorTextureId());
            fullImage.downloadTexture(0, false);
            try (final NativeImage outputImage = new NativeImage(OUTPUT_SIZE, OUTPUT_SIZE, false))
            {
                downsample(fullImage, outputImage);
                options.saveFile(packId, filePath, "png", outputImage.asByteArray());
            }
        }
    }

    private static void renderPlaceholders(
        final Minecraft mc,
        final PoseStack cameraStack,
        final MultiBufferSource.BufferSource bufferSource,
        final BlueprintBlockView blockView,
        final List<BlockPos> placeholderPositions,
        final ClientLevel level)
    {
        final Set<RenderType> usedRenderTypes = new LinkedHashSet<>();
        for (final BlockPos pos : placeholderPositions)
        {
            final BlockState state = blockView.getBlockState(pos);
            cameraStack.pushPose();
            cameraStack.translate(pos.getX(), pos.getY(), pos.getZ());
            final BakedModel bakedModel = mc.getBlockRenderer().getBlockModelShaper().getBlockModel(state);
            final ChunkRenderTypeSet renderLayers = bakedModel.getRenderTypes(state, level.random, ModelData.EMPTY);
            for (final RenderType renderType : renderLayers)
            {
                usedRenderTypes.add(renderType);
                mc.getBlockRenderer().renderBatched(state, pos, blockView, cameraStack, bufferSource.getBuffer(renderType), true, level.random, ModelData.EMPTY, renderType);
            }
            cameraStack.popPose();
        }
        for (final RenderType rt : usedRenderTypes)
        {
            bufferSource.endBatch(rt);
        }
        bufferSource.endBatch();
    }

    private static void renderBlockEntity(
        final BlockState state,
        final BlockPos pos,
        @Nullable final CompoundTag nbt,
        final PoseStack poseStack,
        final MultiBufferSource.BufferSource bufferSource,
        final Minecraft mc,
        final ClientLevel level)
    {
        if (!(state.getBlock() instanceof EntityBlock entityBlock))
        {
            return;
        }
        try
        {
            BlockEntity be = entityBlock.newBlockEntity(pos, state);
            if (be == null)
            {
                return;
            }
            be.setLevel(level);
            if (nbt != null)
            {
                be.load(nbt);
            }

            final BlockEntityRenderDispatcher dispatcher = mc.getBlockEntityRenderDispatcher();
            BlockEntityRenderer<BlockEntity> renderer = dispatcher.getRenderer(be);

            // Some blocks (e.g. Domum Ornamentum compat blocks) create a BlockEntity whose type
            // differs from the one that has a renderer registered. Fall back to the type in the NBT id.
            if (renderer == null && nbt != null && nbt.contains("id"))
            {
                @SuppressWarnings({"removal"}) final var beType = ForgeRegistries.BLOCK_ENTITY_TYPES.getValue(new ResourceLocation(nbt.getString("id")));
                if (beType != null)
                {
                    final BlockEntity nbtBe = beType.create(pos, state);
                    if (nbtBe != null)
                    {
                        nbtBe.setLevel(level);
                        nbtBe.load(nbt);
                        final BlockEntityRenderer<BlockEntity> nbtRenderer = dispatcher.getRenderer(nbtBe);
                        if (nbtRenderer != null)
                        {
                            be = nbtBe;
                            renderer = nbtRenderer;
                        }
                    }
                }
            }

            if (renderer == null)
            {
                return;
            }
            renderer.render(be, 0.0f, poseStack, bufferSource, LightTexture.pack(15, 15), OverlayTexture.NO_OVERLAY);
        }
        catch (final Exception e)
        {
            LOGGER.debug("Could not render BlockEntity for {}: {}", state.getBlock().getDescriptionId(), e.getMessage());
        }
    }

    private static void addEdgeX(final BufferBuilder buf, final Matrix4f mat, final float x, final float y, final float z, final float len, final float t)
    {
        // Top face (+Y)
        buf.vertex(mat, x, y + t, z - t).color(1f, 1f, 1f, 1f).endVertex();
        buf.vertex(mat, x + len, y + t, z - t).color(1f, 1f, 1f, 1f).endVertex();
        buf.vertex(mat, x + len, y + t, z + t).color(1f, 1f, 1f, 1f).endVertex();
        buf.vertex(mat, x, y + t, z + t).color(1f, 1f, 1f, 1f).endVertex();
        // Bottom face (-Y)
        buf.vertex(mat, x, y - t, z + t).color(1f, 1f, 1f, 1f).endVertex();
        buf.vertex(mat, x + len, y - t, z + t).color(1f, 1f, 1f, 1f).endVertex();
        buf.vertex(mat, x + len, y - t, z - t).color(1f, 1f, 1f, 1f).endVertex();
        buf.vertex(mat, x, y - t, z - t).color(1f, 1f, 1f, 1f).endVertex();
        // Front face (+Z)
        buf.vertex(mat, x, y - t, z + t).color(1f, 1f, 1f, 1f).endVertex();
        buf.vertex(mat, x + len, y - t, z + t).color(1f, 1f, 1f, 1f).endVertex();
        buf.vertex(mat, x + len, y + t, z + t).color(1f, 1f, 1f, 1f).endVertex();
        buf.vertex(mat, x, y + t, z + t).color(1f, 1f, 1f, 1f).endVertex();
        // Back face (-Z)
        buf.vertex(mat, x, y + t, z - t).color(1f, 1f, 1f, 1f).endVertex();
        buf.vertex(mat, x + len, y + t, z - t).color(1f, 1f, 1f, 1f).endVertex();
        buf.vertex(mat, x + len, y - t, z - t).color(1f, 1f, 1f, 1f).endVertex();
        buf.vertex(mat, x, y - t, z - t).color(1f, 1f, 1f, 1f).endVertex();
    }

    private static void addEdgeZ(final BufferBuilder buf, final Matrix4f mat, final float x, final float y, final float z, final float len, final float t)
    {
        // Top face (+Y)
        buf.vertex(mat, x - t, y + t, z).color(1f, 1f, 1f, 1f).endVertex();
        buf.vertex(mat, x + t, y + t, z).color(1f, 1f, 1f, 1f).endVertex();
        buf.vertex(mat, x + t, y + t, z + len).color(1f, 1f, 1f, 1f).endVertex();
        buf.vertex(mat, x - t, y + t, z + len).color(1f, 1f, 1f, 1f).endVertex();
        // Bottom face (-Y)
        buf.vertex(mat, x - t, y - t, z + len).color(1f, 1f, 1f, 1f).endVertex();
        buf.vertex(mat, x + t, y - t, z + len).color(1f, 1f, 1f, 1f).endVertex();
        buf.vertex(mat, x + t, y - t, z).color(1f, 1f, 1f, 1f).endVertex();
        buf.vertex(mat, x - t, y - t, z).color(1f, 1f, 1f, 1f).endVertex();
        // Right face (+X)
        buf.vertex(mat, x + t, y + t, z).color(1f, 1f, 1f, 1f).endVertex();
        buf.vertex(mat, x + t, y - t, z).color(1f, 1f, 1f, 1f).endVertex();
        buf.vertex(mat, x + t, y - t, z + len).color(1f, 1f, 1f, 1f).endVertex();
        buf.vertex(mat, x + t, y + t, z + len).color(1f, 1f, 1f, 1f).endVertex();
        // Left face (-X)
        buf.vertex(mat, x - t, y - t, z).color(1f, 1f, 1f, 1f).endVertex();
        buf.vertex(mat, x - t, y + t, z).color(1f, 1f, 1f, 1f).endVertex();
        buf.vertex(mat, x - t, y + t, z + len).color(1f, 1f, 1f, 1f).endVertex();
        buf.vertex(mat, x - t, y - t, z + len).color(1f, 1f, 1f, 1f).endVertex();
    }

    private static void addEdgeY(final BufferBuilder buf, final Matrix4f mat, final float x, final float y, final float z, final float len, final float t)
    {
        // Front face (+Z)
        buf.vertex(mat, x - t, y, z + t).color(1f, 1f, 1f, 1f).endVertex();
        buf.vertex(mat, x + t, y, z + t).color(1f, 1f, 1f, 1f).endVertex();
        buf.vertex(mat, x + t, y + len, z + t).color(1f, 1f, 1f, 1f).endVertex();
        buf.vertex(mat, x - t, y + len, z + t).color(1f, 1f, 1f, 1f).endVertex();
        // Back face (-Z)
        buf.vertex(mat, x + t, y, z - t).color(1f, 1f, 1f, 1f).endVertex();
        buf.vertex(mat, x - t, y, z - t).color(1f, 1f, 1f, 1f).endVertex();
        buf.vertex(mat, x - t, y + len, z - t).color(1f, 1f, 1f, 1f).endVertex();
        buf.vertex(mat, x + t, y + len, z - t).color(1f, 1f, 1f, 1f).endVertex();
        // Right face (+X)
        buf.vertex(mat, x + t, y, z + t).color(1f, 1f, 1f, 1f).endVertex();
        buf.vertex(mat, x + t, y, z - t).color(1f, 1f, 1f, 1f).endVertex();
        buf.vertex(mat, x + t, y + len, z - t).color(1f, 1f, 1f, 1f).endVertex();
        buf.vertex(mat, x + t, y + len, z + t).color(1f, 1f, 1f, 1f).endVertex();
        // Left face (-X)
        buf.vertex(mat, x - t, y, z - t).color(1f, 1f, 1f, 1f).endVertex();
        buf.vertex(mat, x - t, y, z + t).color(1f, 1f, 1f, 1f).endVertex();
        buf.vertex(mat, x - t, y + len, z + t).color(1f, 1f, 1f, 1f).endVertex();
        buf.vertex(mat, x - t, y + len, z - t).color(1f, 1f, 1f, 1f).endVertex();
    }

    /**
     * Downsamples src into dst using area averaging (box filter).
     */
    private static void downsample(final NativeImage src, final NativeImage dst)
    {
        final float scaleX = (float) src.getWidth() / dst.getWidth();
        final float scaleY = (float) src.getHeight() / dst.getHeight();

        for (int dy = 0; dy < dst.getHeight(); dy++)
        {
            final float srcY0 = dy * scaleY;
            final float srcY1 = srcY0 + scaleY;

            for (int dx = 0; dx < dst.getWidth(); dx++)
            {
                final float srcX0 = dx * scaleX;
                final float srcX1 = srcX0 + scaleX;

                float r = 0, g = 0, b = 0, a = 0, weight = 0;

                for (int sy = (int) srcY0; sy < (int) Math.ceil(srcY1); sy++)
                {
                    final float wy = Math.min(sy + 1, srcY1) - Math.max(sy, srcY0);
                    for (int sx = (int) srcX0; sx < (int) Math.ceil(srcX1); sx++)
                    {
                        final float w = (Math.min(sx + 1, srcX1) - Math.max(sx, srcX0)) * wy;
                        final int pixel = src.getPixelRGBA(sx, sy);
                        r += (pixel & 0xFF) * w;
                        g += ((pixel >> 8) & 0xFF) * w;
                        b += ((pixel >> 16) & 0xFF) * w;
                        a += ((pixel >> 24) & 0xFF) * w;
                        weight += w;
                    }
                }

                if (weight > 0)
                {
                    dst.setPixelRGBA(dx,
                        dy,
                        (Math.min(255, Math.round(a / weight)) << 24) | (Math.min(255, Math.round(b / weight)) << 16) | (Math.min(255, Math.round(g / weight)) << 8) | Math.min(255,
                            Math.round(r / weight)));
                }
            }
        }
    }

    /**
     * Wraps a BufferSource so that every VertexConsumer it vends has its RGB vertex colors
     * multiplied by the given correction ratios. Used to fix the tint mismatch between
     * renderSingleBlock (which applies the default/null-world tint) and renderBatched (which
     * samples the actual biome tint via BlockAndTintGetter).
     */
    private record TintCorrectingBufferSource(
        BufferSource delegate,
        float cr,
        float cg,
        float cb) implements MultiBufferSource
    {

        @Override
        @NotNull
        public VertexConsumer getBuffer(@NotNull final RenderType renderType)
        {
            return new TintCorrectingVertexConsumer(delegate.getBuffer(renderType), cr, cg, cb);
        }
    }

    private static class TintCorrectingVertexConsumer extends net.minecraftforge.client.model.pipeline.VertexConsumerWrapper
    {
        private final float cr, cg, cb;

        TintCorrectingVertexConsumer(final VertexConsumer delegate, final float cr, final float cg, final float cb)
        {
            super(delegate);
            this.cr = cr;
            this.cg = cg;
            this.cb = cb;
        }

        @Override
        @NotNull
        public VertexConsumer color(final int r, final int g, final int b, final int a)
        {
            return parent.color(Math.min(255, (int) (r * cr)), Math.min(255, (int) (g * cg)), Math.min(255, (int) (b * cb)), a);
        }
    }

    /**
     * Extends VertexConsumerWrapper to pre-transform vertex positions through a Matrix4f.
     * Used so LiquidBlockRenderer (which writes raw block-local coords) lands in the same clip space
     * as renderBatched geometry (which goes through a PoseStack).
     */
    private static class TransformingVertexConsumer extends net.minecraftforge.client.model.pipeline.VertexConsumerWrapper
    {
        private final Matrix4f mat;

        TransformingVertexConsumer(final VertexConsumer delegate, final Matrix4f mat)
        {
            super(delegate);
            this.mat = mat;
        }

        @Override
        @NotNull
        public VertexConsumer vertex(final double x, final double y, final double z)
        {
            final float tx = mat.m00() * (float) x + mat.m10() * (float) y + mat.m20() * (float) z + mat.m30();
            final float ty = mat.m01() * (float) x + mat.m11() * (float) y + mat.m21() * (float) z + mat.m31();
            final float tz = mat.m02() * (float) x + mat.m12() * (float) y + mat.m22() * (float) z + mat.m32();
            return parent.vertex(tx, ty, tz);
        }
    }

    /**
     * A lightweight BlockAndTintGetter backed by blueprint block data.
     * Provides neighbor block lookups so renderBatched can compute ambient occlusion.
     * When a groundPlaneY is set (>= 0), positions outside the schematic bounds at or
     * below that Y are reported as the ground block so that LiquidBlockRenderer correctly
     * culls water faces at the schematic's edges instead of rendering large sheets.
     */
    private static class BlueprintBlockView implements BlockAndTintGetter
    {
        private final Map<BlockPos, BlockState> blocks       = new HashMap<>();
        private final int                       sizeX;
        private final int                       sizeY;
        private final int                       sizeZ;
        private       BlockState                groundBlock  = Blocks.AIR.defaultBlockState();
        private       int                       groundPlaneY = -1;

        BlueprintBlockView(final List<BlockInfo> blockInfos, final int sizeX, final int sizeY, final int sizeZ)
        {
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            for (final BlockInfo info : blockInfos)
            {
                if (info.getState() != null && !info.getState().isAir())
                {
                    blocks.put(info.getPos(), info.getState());
                }
            }
        }

        /**
         * Call before rendering the _placed phase so edge water faces are culled correctly.
         */
        void setGroundContext(final BlockState groundBlock, final int groundPlaneY)
        {
            this.groundBlock = groundBlock;
            this.groundPlaneY = groundPlaneY;
        }

        /**
         * Reset after the _placed phase so _clean/_full are not affected.
         */
        void clearGroundContext()
        {
            this.groundBlock = Blocks.AIR.defaultBlockState();
            this.groundPlaneY = -1;
        }

        void setBlock(final BlockPos pos, final BlockState state)
        {
            blocks.put(pos, state);
        }

        Iterable<BlockPos> getPositions()
        {
            return blocks.keySet();
        }

        @Override
        @Nullable
        public BlockEntity getBlockEntity(@NotNull final BlockPos pos)
        {
            return null;
        }

        @Override
        @NotNull
        public BlockState getBlockState(@NotNull final BlockPos pos)
        {
            final boolean inBounds = pos.getX() >= 0 && pos.getX() < sizeX && pos.getY() >= 0 && pos.getY() < sizeY && pos.getZ() >= 0 && pos.getZ() < sizeZ;
            if (!inBounds)
            {
                if (groundPlaneY >= 0 && pos.getY() <= groundPlaneY)
                {
                    return groundBlock;
                }
                return Blocks.AIR.defaultBlockState();
            }
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }

        @Override
        @NotNull
        public FluidState getFluidState(@NotNull final BlockPos pos)
        {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight()
        {
            return sizeY + 16;
        }

        @Override
        public int getMinBuildHeight()
        {
            return 0;
        }

        @Override
        public float getShade(@NotNull final Direction direction, final boolean shade)
        {
            if (!shade)
            {
                return 1.0f;
            }
            return switch (direction)
            {
                case DOWN -> 0.5f;
                case UP -> 1.0f;
                case NORTH, SOUTH -> 0.8f;
                case EAST, WEST -> 0.6f;
            };
        }

        @Override
        @NotNull
        public LevelLightEngine getLightEngine()
        {
            final ClientLevel level = Minecraft.getInstance().level;
            if (level == null)
            {
                throw new IllegalStateException("getLightEngine called before world loaded");
            }
            return level.getLightEngine();
        }

        @Override
        public int getBlockTint(@NotNull final BlockPos pos, @NotNull final ColorResolver colorResolver)
        {
            final ClientLevel level = Minecraft.getInstance().level;
            if (level == null)
            {
                return 0;
            }
            return colorResolver.getColor(level.getBiome(pos).value(), pos.getX(), pos.getZ());
        }

        @Override
        public int getBrightness(@NotNull final LightLayer layer, @NotNull final BlockPos pos)
        {
            return 15;
        }

        @Override
        public int getRawBrightness(@NotNull final BlockPos pos, final int amount)
        {
            return Math.max(0, 15 - amount);
        }
    }
}
