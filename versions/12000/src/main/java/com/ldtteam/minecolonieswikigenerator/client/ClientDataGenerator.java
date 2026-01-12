package com.ldtteam.minecolonieswikigenerator.client;

import com.ldtteam.minecolonieswikigenerator.client.generators.*;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates client-side data generation.
 * Runs all generators in parallel and shuts down the game when complete.
 */
public class ClientDataGenerator
{
    private static final Logger LOGGER = LogManager.getLogger();

    private final Path                       outputPath;
    private final List<IClientDataGenerator> generators  = new ArrayList<>();
    private final BlockImagesGenerator       blockImagesGenerator;
    private final AtomicBoolean              initialized = new AtomicBoolean(false);
    private final AtomicBoolean              completed   = new AtomicBoolean(false);
    private       CompletableFuture<Void>    allGeneratorsFuture;
    private       int                        ticksWaited = 0;

    private ClientDataGenerator(final Path outputPath)
    {
        this.outputPath = outputPath;

        // Add all generators
        generators.add(new BlocksDataGenerator());
        generators.add(new BlockStatesDataGenerator());
        generators.add(new ItemsDataGenerator());
        blockImagesGenerator = new BlockImagesGenerator();
        generators.add(blockImagesGenerator);
    }

    /**
     * Initializes the client data generator if enabled.
     */
    public static void init()
    {
        final Path outputPath = FMLPaths.GAMEDIR.get().resolve("..").resolve("output").normalize();

        LOGGER.info("Client datagen mode enabled. Output path: {}", outputPath);

        final ClientDataGenerator instance = new ClientDataGenerator(outputPath);
        MinecraftForge.EVENT_BUS.register(instance);
    }

    @SubscribeEvent
    public void onClientTick(final TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
        {
            return;
        }

        final Minecraft mc = Minecraft.getInstance();

        // Wait for the game to be fully loaded
        if (mc.level == null || mc.player == null)
        {
            return;
        }

        // Wait a few ticks for everything to stabilize
        if (ticksWaited < 20)
        {
            ticksWaited++;
            return;
        }

        // Initialize and start all generators once
        if (!initialized.getAndSet(true))
        {
            LOGGER.info("Starting client-side data generation with {} generators...", generators.size());
            startAllGenerators();
        }

        // Process render tasks for block images (must run on render thread)
        // Always call processRenderTasks until complete to ensure the future gets completed
        if (!blockImagesGenerator.isComplete())
        {
            blockImagesGenerator.processRenderTasks(10);
        }

        // Check if all generators are complete
        if (allGeneratorsFuture != null && allGeneratorsFuture.isDone() && !completed.getAndSet(true))
        {
            LOGGER.info("All data generation complete!");
            LOGGER.info("Shutting down...");

            // Schedule shutdown for next tick to allow logging to flush
            mc.tell(mc::stop);
        }
    }

    private void startAllGenerators()
    {
        // Start all generators in parallel
        final List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (final IClientDataGenerator generator : generators)
        {
            LOGGER.info("Starting generator: {}", generator.getName());
            futures.add(generator.generate(outputPath));
        }

        // Combine all futures
        allGeneratorsFuture = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }
}
