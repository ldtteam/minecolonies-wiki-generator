package com.ldtteam.minecolonieswikigenerator;

import com.ldtteam.minecolonieswikigenerator.generators.*;
import com.ldtteam.minecolonieswikigenerator.research.ResearchObjectType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.List;

@Mod(Constants.MOD_ID)
public class Entrypoint extends RootEntrypoint<ClientLevel>
{
    public Entrypoint()
    {
        super();
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> this::initialize);
        MinecraftForge.EVENT_BUS.register(this);
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

        this.tick();
    }

    @Override
    public ClientLevel getLevel()
    {
        return Minecraft.getInstance().level;
    }

    @Override
    public Path getOutputPath()
    {
        return FMLPaths.GAMEDIR.get().resolve("..").resolve("output").normalize();
    }

    @Override
    public List<? extends DataGenerator<ClientLevel>> getGenerators()
    {
        return List.of(new BlockImageGenerator(),
            new BlockDataGenerator(),
            new BlockStateDataGenerator(),
            new CrafterRecipeDataGenerator(),
            new ItemImageDataGenerator(),
            new ItemDataGenerator(),
            new LanguageDataGenerator(),
            new RecipeDataGenerator(),
            new ResearchDataGenerator(ResearchObjectType.RESEARCH),
            new ResearchDataGenerator(ResearchObjectType.RESEARCH_TREE),
            new ResearchDataGenerator(ResearchObjectType.RESEARCH_EFFECT));
    }

    @Override
    public void shutdown()
    {
        Minecraft.getInstance().tell(Minecraft.getInstance()::stop);
    }
}
