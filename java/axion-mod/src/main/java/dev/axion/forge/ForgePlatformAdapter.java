package dev.axion.forge;

import dev.axion.platform.PlatformAdapter;
import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.versions.forge.ForgeVersion;

/**
 * Implémentation de {@link PlatformAdapter} sur Forge (C-01, IF-10).
 *
 * <p>Une seule classe traduit Forge pour tout le reste du moteur. Le serveur
 * courant lui est confié au démarrage et repris à l'arrêt : c'est lui qui
 * définit le thread autoritatif et le compteur de ticks.
 */
public final class ForgePlatformAdapter implements PlatformAdapter {

    private volatile MinecraftServer server;

    /**
     * Confie le serveur courant à l'adaptateur.
     *
     * @param server serveur qui démarre, ou {@code null} à l'arrêt
     */
    void setServer(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public String platformName() {
        // Version majeure seulement : c'est elle qui décrit la compatibilité,
        // et la version complète change à chaque correctif.
        String version = ForgeVersion.getVersion();
        int dot = version.indexOf('.');
        return "forge-" + (dot > 0 ? version.substring(0, dot) : version);
    }

    @Override
    public String minecraftVersion() {
        return SharedConstants.getCurrentVersion().getName();
    }

    @Override
    public boolean isModLoaded(String modid) {
        // ModList est absente très tôt au chargement : l'absence se traduit par
        // « pas chargé » plutôt que par une exception.
        ModList list = ModList.get();
        return list != null && list.isLoaded(modid);
    }

    @Override
    public boolean isAuthoritativeThread() {
        MinecraftServer current = server;
        return current != null && current.isSameThread();
    }

    @Override
    public boolean isClient() {
        return FMLEnvironment.dist.isClient();
    }

    @Override
    public long currentTick() {
        MinecraftServer current = server;
        return current == null ? 0L : current.getTickCount();
    }

    @Override
    public void runOnAuthoritativeThread(Runnable work) {
        MinecraftServer current = server;
        if (current == null) {
            // Aucun serveur : il n'y a pas d'état autoritatif à protéger, et
            // différer le travail indéfiniment serait pire que l'exécuter.
            work.run();
            return;
        }
        if (current.isSameThread()) {
            work.run();
        } else {
            current.execute(work);
        }
    }

    @Override
    public Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }
}
