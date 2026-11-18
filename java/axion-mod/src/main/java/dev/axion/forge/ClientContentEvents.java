package dev.axion.forge;

import dev.axion.net.EventMessage;
import java.util.List;
import net.minecraft.client.Minecraft;

/**
 * Les événements de contenu arrivés chez le client (ADR-174 D2), relayés aux écouteurs des mods sur son thread
 * principal. Un événement dont l'entité n'est plus là, ou dont le rang dépasse les événements de sa definition, est
 * ignoré : le serveur ne l'aurait pas envoyé à une definition que le client tient du même registre.
 */
public final class ClientContentEvents {

    private ClientContentEvents() {}

    /**
     * Relaie des événements, dans leur ordre.
     *
     * @param events les événements d'un message
     */
    public static void relay(List<EventMessage.Event> events) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        for (EventMessage.Event event : events) {
            if (!(minecraft.level.getEntity(event.entity()) instanceof AxionEntity assembly)) {
                continue;
            }
            List<dev.axion.definition.ContentEvents.Event> declared = assembly.contentEvents().declared();
            if (event.event() >= declared.size()) {
                continue;
            }
            String name = declared.get(event.event()).name();
            ContentListeners.notifyClient(ContentListeners.events(),
                    listener -> listener.onClientEvent(assembly, name, event.value()), "l'événement " + name);
        }
    }
}
