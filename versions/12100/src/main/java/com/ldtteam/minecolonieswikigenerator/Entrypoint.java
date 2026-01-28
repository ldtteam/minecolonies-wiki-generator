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
        collector.add(true, new BlockDataGenerator());
        collector.add(true, new BlockImageDataGenerator());
        collector.add(true, new BlockStateDataGenerator());
        collector.add(true, new CitizenNamesDataGenerator());
        collector.add(true, new ConfigurationDataGenerator());
        collector.add(true, new CrafterRecipeDataGenerator());
        collector.add(true, new ItemDataGenerator());
        collector.add(true, new ItemImageDataGenerator());
        collector.add(true, new ItemTagDataGenerator());
        collector.add(true, new LanguageDataGenerator());
        collector.add(true, new RecipeDataGenerator());
        collector.add(true, new ResearchDataGenerator(ResearchObjectType.RESEARCH));
        collector.add(true, new ResearchDataGenerator(ResearchObjectType.RESEARCH_TREE));
        collector.add(true, new ResearchDataGenerator(ResearchObjectType.RESEARCH_EFFECT));
    }

    @Override
    public void shutdown()
    {
        Minecraft.getInstance().tell(Minecraft.getInstance()::stop);
    }
}
