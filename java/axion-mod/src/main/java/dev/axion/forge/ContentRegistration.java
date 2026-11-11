package dev.axion.forge;

import com.mojang.logging.LogUtils;
import dev.axion.api.content.AxionContent;
import dev.axion.api.content.ContentKind;
import dev.axion.api.content.ContentProvider;
import dev.axion.content.ContentProviders;
import dev.axion.content.ContentRegistry;
import dev.axion.content.RegisteredContent;
import dev.axion.forge.net.RequiredAddons;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import net.minecraftforge.fml.InterModComms;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent;
import net.minecraftforge.fml.event.lifecycle.InterModProcessEvent;
import net.minecraftforge.forgespi.language.IModFileInfo;
import org.slf4j.Logger;

/**
 * La phase d'enregistrement du contenu, côté Forge (ADR-162 D2, D9).
 *
 * <p>Chaque mod envoie à AXION un {@link ContentProvider} par les communications entre mods de Forge, pendant
 * {@link InterModEnqueueEvent} ; AXION, qui en fait autant pour ses propres exemples (ADR-162 D10), les reçoit dans
 * {@link InterModProcessEvent} et les appelle l'un après l'autre, sur le thread principal, dans l'ordre des modId.
 * Forge certifie le mod expéditeur d'un message : c'est lui qui donne l'espace de noms des entrées (CAP-46). Chaque
 * fournisseur ne lit que le JAR de son mod.
 *
 * <p>Forge ne délivre un message qu'une fois : les fournisseurs reçus sont gardés, et {@link #reregister} les rappelle
 * pour {@code /axion dev reload}.
 */
final class ContentRegistration {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Le dossier d'AXION, à la racine de son JAR : ses exemples, et en développement son contenu de test. */
    static final String AXION_FOLDER = "axion/";

    /** Les fournisseurs reçus au chargement des mods ; {@code null} avant. */
    private static volatile ContentProviders providers;

    /**
     * Ce qu'un enregistrement a donné.
     *
     * @param mods les mods dont un fournisseur a été appelé
     * @param counts le nombre d'entrées de chaque famille
     * @param errors les erreurs consignées, entrées refusées et fournisseurs en échec
     */
    record Outcome(List<String> mods, Map<ContentKind, Integer> counts, int errors) {

        /** {@return le résumé du nombre d'entrées, famille par famille} */
        String describe() {
            return counts.entrySet().stream()
                    .map(e -> e.getValue() + " " + e.getKey().folder())
                    .collect(Collectors.joining(", "));
        }
    }

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
        event.enqueueWork(() -> {
            providers = receive();
            register(providers, maxSiblingBytes.getAsLong());
        });
    }

    /**
     * Rappelle chaque fournisseur reçu au chargement, sur un registre neuf, et publie le contenu résolu
     * (ADR-162 D9). Un dossier qu'un mod lit depuis ses sources est relu depuis le disque.
     *
     * @param maxSiblingBytes limite de lecture d'un voisin de modèle ({@code assets.max_source_bytes})
     * @return ce que l'enregistrement a donné
     * @throws IllegalStateException avant l'enregistrement du chargement des mods
     */
    static Outcome reregister(long maxSiblingBytes) {
        ContentProviders known = providers;
        if (known == null) {
            throw new IllegalStateException("aucun fournisseur reçu : le chargement des mods n'a pas enregistré");
        }
        return register(known, maxSiblingBytes);
    }

    /** {@return les fournisseurs que les mods ont envoyés ; un message illisible est écarté et journalisé} */
    private static ContentProviders receive() {
        List<ContentProviders.Provided> received = new ArrayList<>();
        InterModComms.getMessages(AxionContent.MOD_ID, AxionContent.REGISTER::equals)
                .sorted(Comparator.comparing(InterModComms.IMCMessage::senderModId))
                .forEach(message -> {
                    String mod = message.senderModId();
                    Object provided;
                    try {
                        provided = message.messageSupplier().get();
                    } catch (RuntimeException failure) {
                        LOGGER.error("AXION : contenu — le message d'enregistrement de {} a échoué", mod, failure);
                        return;
                    }
                    if (provided instanceof ContentProvider provider) {
                        received.add(new ContentProviders.Provided(mod, provider));
                    } else {
                        LOGGER.error("AXION : contenu — {} envoie « {} » sans ContentProvider ({})", mod,
                                AxionContent.REGISTER, provided == null ? "null" : provided.getClass().getName());
                    }
                });
        return new ContentProviders(received);
    }

    private static Outcome register(ContentProviders known, long maxSiblingBytes) {
        int[] failures = {0};
        ContentRegistry registry = known.registerAll(new ContentRegistry(maxSiblingBytes),
                ContentRegistration::inJar, (mod, failure) -> {
                    failures[0]++;
                    LOGGER.error("AXION : contenu — le fournisseur de {} a échoué", mod, failure);
                });
        Map<ContentKind, List<ContentRegistry.Entry>> resolved = registry.resolve();
        registry.errors().forEach(error -> LOGGER.warn("AXION : contenu — {}", error));
        RegisteredContent.publish(resolved);
        // ADR-165 D1 : chaque mod propriétaire d'une entrée, remplacements compris, est exigé du client.
        Map<String, String> owners = new TreeMap<>();
        resolved.values().forEach(entries -> entries.forEach(entry -> {
            String version = RequiredAddons.installed(entry.owner());
            owners.put(entry.owner(), version == null ? "?" : version);
        }));
        RegisteredContent.publishOwners(owners);
        Map<ContentKind, Integer> counts = new EnumMap<>(ContentKind.class);
        resolved.forEach((kind, entries) -> counts.put(kind, entries.size()));
        Outcome outcome = new Outcome(known.modIds(), counts, registry.errors().size() + failures[0]);
        LOGGER.info("AXION : addons de contenu exigés des clients (ADR-165) : {}", owners);
        LOGGER.info("AXION : contenu enregistré par {} — {}", outcome.mods(), outcome.describe());
        return outcome;
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
