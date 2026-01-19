package com.ldtteam.minecolonieswikigenerator;

import com.ldtteam.minecolonieswikigenerator.generators.*;
import com.ldtteam.minecolonieswikigenerator.research.ResearchObjectType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Path;
import java.util.List;

@Mod(Constants.MOD_ID)
public class Entrypoint extends RootEntrypoint<ClientLevel>
{
    public Entrypoint(final Dist dist)
    {
        super();
        if (dist.isClient())
        {
            this.initialize();
        }
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onClientTick(final ClientTickEvent.Post event)
    {
        final Minecraft mc = Minecraft.getInstance();

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
        return List.of(new BlockDataGenerator(),
            new BlockImageGenerator(),
            new BlockStateDataGenerator(),
            new CrafterRecipeDataGenerator(),
            new ItemDataGenerator(),
            new ItemImageGenerator(),
            new LanguageDataGenerator(),
            new RecipesDataGenerator(),
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
