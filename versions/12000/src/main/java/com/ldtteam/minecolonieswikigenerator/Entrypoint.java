package com.ldtteam.minecolonieswikigenerator;

import com.ldtteam.minecolonieswikigenerator.client.ClientDataGenerator;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(Constants.MOD_ID)
public class Entrypoint
{
    public Entrypoint(final FMLJavaModLoadingContext context)
    {
        // Initialize client-side data generator if in datagen mode
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientDataGenerator::init);
    }
}