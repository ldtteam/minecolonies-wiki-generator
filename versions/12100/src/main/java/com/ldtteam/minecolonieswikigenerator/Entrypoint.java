package com.ldtteam.minecolonieswikigenerator;

import com.ldtteam.minecolonieswikigenerator.client.ClientDataGenerator;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

@Mod(Constants.MOD_ID)
public class Entrypoint
{
    public Entrypoint(final Dist dist)
    {
        if (dist.isClient())
        {
            ClientDataGenerator.init();
        }
    }
}
