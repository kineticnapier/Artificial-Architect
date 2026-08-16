package dev.kineticnapier.artificialarchitect;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;

@Mod(ArtificialArchitectMod.MOD_ID)
public final class ArtificialArchitectMod {
    public static final String MOD_ID = "artificialarchitect";

    public ArtificialArchitectMod() {
        ArtificialArchitectNetwork.register();
        MinecraftForge.EVENT_BUS.addListener(ArtificialArchitectCommands::onRegisterCommands);
    }
}
