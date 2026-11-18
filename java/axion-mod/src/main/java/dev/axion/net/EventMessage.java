package dev.axion.net;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code S2C_Event} (ADR-174 D2, protocole 13) : les événements de contenu qu'un tick du serveur a émis vers ce joueur,
 * dans l'ordre de leur émission. Chacun nomme l'entité par son identifiant réseau, l'événement par son rang dans la
 * definition — que le client tient du registre —, et porte sa valeur. Il part après le snapshot du même tick.
 *
 * @param events les événements ; copie
 */
public record EventMessage(List<Event> events) {

    /** Événements d'un message, au plus : la borne de {@code net.events_per_player_tick}. */
    public static final int MAX_EVENTS = 256;

    /**
     * Un événement.
     *
     * @param entity l'identifiant réseau de l'assembly qui l'émet
     * @param event son rang dans les {@code events} de sa definition
     * @param value sa valeur
     */
    public record Event(int entity, int event, double value) {

        /** Refuse un identifiant ou un rang négatif, une valeur non finie. */
        public Event {
            if (entity < 0 || event < 0 || !Double.isFinite(value)) {
                throw new IllegalArgumentException("événement " + event + " de l'entité " + entity + ", valeur "
                        + value);
            }
        }
    }

    /** Copie la liste, et en refuse une vide ou trop longue. */
    public EventMessage {
        if (events.isEmpty() || events.size() > MAX_EVENTS) {
            throw new IllegalArgumentException(events.size() + " événements dans un message");
        }
        events = List.copyOf(events);
    }

    /** {@return le message, genre compris} */
    public byte[] encode() {
        WireWriter writer = new WireWriter(4 + 14 * events.size()).u8(MessageKinds.EVENT).varUInt(events.size());
        for (Event event : events) {
            writer.varUInt(event.entity()).varUInt(event.event()).f64(event.value());
        }
        return writer.toByteArray();
    }

    /**
     * Lit un message (R-1650) : le genre, au plus {@link #MAX_EVENTS} événements, des valeurs finies, rien après.
     *
     * @param message le message, genre compris
     * @return les événements
     * @throws MalformedPacketException s'il est mal formé
     */
    public static EventMessage decode(byte[] message) throws MalformedPacketException {
        WireReader reader = new WireReader(message);
        if (reader.u8() != MessageKinds.EVENT) {
            throw new MalformedPacketException("genre d'un message d'événements");
        }
        int count = reader.varUInt();
        if (count == 0 || count > MAX_EVENTS) {
            throw new MalformedPacketException(count + " événements dans un message, de 1 à " + MAX_EVENTS);
        }
        List<Event> events = new ArrayList<>(count);
        try {
            for (int i = 0; i < count; i++) {
                events.add(new Event(reader.varUInt(), reader.varUInt(), reader.f64()));
            }
        } catch (IllegalArgumentException refused) {
            throw new MalformedPacketException(refused.getMessage());
        }
        reader.expectEnd();
        return new EventMessage(events);
    }
}
