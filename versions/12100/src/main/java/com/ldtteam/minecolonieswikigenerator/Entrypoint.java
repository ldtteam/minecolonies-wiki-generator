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
    protected void getGenerators(final DataGeneratorCollector<ClientLevel> collector)
    {
        collector.add(false, new BlockDataGenerator());
        collector.add(false, new BlockImageGenerator());
        collector.add(false, new BlockStateDataGenerator());
        collector.add(false, new CitizenNamesDataGenerator());
        collector.add(false, new CrafterRecipeDataGenerator());
        collector.add(false, new ItemDataGenerator());
        collector.add(false, new ItemImageDataGenerator());
        collector.add(true, new ItemTagDataGenerator());
        collector.add(false, new LanguageDataGenerator());
        collector.add(false, new RecipeDataGenerator());
        collector.add(false, new ResearchDataGenerator(ResearchObjectType.RESEARCH));
        collector.add(false, new ResearchDataGenerator(ResearchObjectType.RESEARCH_TREE));
        collector.add(false, new ResearchDataGenerator(ResearchObjectType.RESEARCH_EFFECT));
    }

    @Override
    public void shutdown()
    {
        Minecraft.getInstance().tell(Minecraft.getInstance()::stop);
    }
}
