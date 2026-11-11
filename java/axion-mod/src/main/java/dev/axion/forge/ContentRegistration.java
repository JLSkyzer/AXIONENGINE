package dev.axion.forge;

import com.mojang.logging.LogUtils;
import dev.axion.api.content.AxionContent;
import dev.axion.api.content.ContentKind;
import dev.axion.api.content.ContentProvider;
import dev.axion.content.ContentRegistry;
import dev.axion.content.RegisteredContent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import net.minecraftforge.fml.InterModComms;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent;
import net.minecraftforge.fml.event.lifecycle.InterModProcessEvent;
import net.minecraftforge.forgespi.language.IModFileInfo;
import org.slf4j.Logger;

/**
 * La phase d'enregistrement du contenu, côté Forge (ADR-162 D2).
 *
 * <p>Chaque mod envoie à AXION un {@link ContentProvider} par les communications entre mods de Forge, pendant
 * {@link InterModEnqueueEvent} ; AXION, qui en fait autant pour ses propres exemples (ADR-162 D10), les reçoit dans
 * {@link InterModProcessEvent} et les appelle l'un après l'autre, sur le thread principal, dans l'ordre des modId.
 * Forge certifie le mod expéditeur d'un message : c'est lui qui donne l'espace de noms des entrées (CAP-46). Chaque
 * fournisseur ne lit que le JAR de son mod.
 */
final class ContentRegistration {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Le dossier d'AXION, à la racine de son JAR : ses exemples, et en développement son contenu de test. */
    static final String AXION_FOLDER = "axion/";

    private ContentRegistration() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    /**
     * AXION enregistre son propre dossier comme un addon, par l'API publique (ADR-162 D10).
     *
     * @param event l'envoi des messages entre mods
     */
    static void enqueue(InterModEnqueueEvent event) {
        InterModComms.sendTo(AxionContent.MOD_ID, AxionContent.REGISTER,
                () -> (ContentProvider) registrar -> registrar.registerFolder(AXION_FOLDER));
    }

    /**
     * Appelle les fournisseurs reçus, sur le thread principal, une fois les envois de tous les mods faits.
     *
     * @param event le traitement des messages entre mods
     * @param maxSiblingBytes limite de lecture d'un voisin de modèle ({@code assets.max_source_bytes})
     */
    static void process(InterModProcessEvent event, LongSupplier maxSiblingBytes) {
        event.enqueueWork(() -> register(maxSiblingBytes.getAsLong()));
    }

    private static void register(long maxSiblingBytes) {
        ContentRegistry registry = new ContentRegistry(maxSiblingBytes);
        List<InterModComms.IMCMessage> messages = InterModComms
                .getMessages(AxionContent.MOD_ID, AxionContent.REGISTER::equals)
                .sorted(Comparator.comparing(InterModComms.IMCMessage::senderModId))
                .toList();
        for (InterModComms.IMCMessage message : messages) {
            String mod = message.senderModId();
            Object provided;
            try {
                provided = message.messageSupplier().get();
            } catch (RuntimeException failure) {
                LOGGER.error("AXION : contenu — le message d'enregistrement de {} a échoué", mod, failure);
                continue;
            }
            if (!(provided instanceof ContentProvider provider)) {
                LOGGER.error("AXION : contenu — {} envoie « {} » sans ContentProvider ({})", mod,
                        AxionContent.REGISTER, provided == null ? "null" : provided.getClass().getName());
                continue;
            }
            try (ContentRegistry.Session session = registry.open(mod, relative -> inJar(mod, relative))) {
                provider.register(session);
            } catch (RuntimeException failure) {
                LOGGER.error("AXION : contenu — le fournisseur de {} a échoué", mod, failure);
            }
        }
        Map<ContentKind, List<ContentRegistry.Entry>> resolved = registry.resolve();
        registry.errors().forEach(error -> LOGGER.warn("AXION : contenu — {}", error));
        RegisteredContent.publish(resolved);
        // ADR-165 D1 : chaque mod propriétaire d'une entrée, remplacements compris, est exigé du client.
        Map<String, String> owners = new TreeMap<>();
        resolved.values().forEach(entries -> entries.forEach(entry -> {
            String version = dev.axion.forge.net.RequiredAddons.installed(entry.owner());
            owners.put(entry.owner(), version == null ? "?" : version);
        }));
        RegisteredContent.publishOwners(owners);
        LOGGER.info("AXION : addons de contenu exigés des clients (ADR-165) : {}", owners);
        LOGGER.info("AXION : contenu enregistré par {} — {}",
                messages.stream().map(InterModComms.IMCMessage::senderModId).distinct().toList(),
                resolved.entrySet().stream()
                        .map(e -> e.getValue().size() + " " + e.getKey().folder())
                        .collect(Collectors.joining(", ")));
    }

    /** {@return un chemin du JAR d'un mod, ou {@code null} s'il n'y est pas} */
    private static Path inJar(String modId, String relative) {
        IModFileInfo file = ModList.get().getModFileById(modId);
        if (file == null) {
            return null;
        }
        Path path = file.getFile().findResource(relative.split("/"));
        return Files.exists(path) ? path : null;
    }
}
