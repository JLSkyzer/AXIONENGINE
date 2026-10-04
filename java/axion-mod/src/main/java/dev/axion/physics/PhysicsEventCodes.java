package dev.axion.physics;

/**
 * Codes des événements physiques (§10.7) : genres et sens du champ {@code data}.
 *
 * <p><strong>Fichier généré — ne pas modifier à la main.</strong> La source est
 * {@code crates/ax-model/src/dm/physics.rs} ({@code event_kind}, {@code event_data}). Régénérer
 * avec :
 *
 * <pre>cargo run -p axion-codegen --bin gen_java_config</pre>
 *
 * <p>Les valeurs sont gelées avec la structure de l'événement (ADR-113) et le sens de
 * {@code data} ratifié avec ADR-123. Un lecteur ignore un genre ou un code inconnu.
 */
public final class PhysicsEventCodes {

    private PhysicsEventCodes() {
        throw new AssertionError("classe de constantes, non instanciable");
    }

    /** Genres d'événement ({@code PhysicsEvent.kind}). */
    public static final class Kind {

        private Kind() {
            throw new AssertionError("classe de constantes, non instanciable");
        }

        /** Début d'un contact. */
        public static final int CONTACT_START = 0;

        /** Fin d'un contact. */
        public static final int CONTACT_END = 1;

        /** Impulsion d'un contact persistant. */
        public static final int CONTACT_IMPULSE = 2;

        /** Entrée dans un capteur. */
        public static final int SENSOR_ENTER = 3;

        /** Sortie d'un capteur. */
        public static final int SENSOR_EXIT = 4;

        /** Rupture d'une liaison. */
        public static final int JOINT_BROKEN = 5;

        /** Blocage d'une liaison. */
        public static final int JOINT_JAMMED = 6;

        /** Endormissement d'un corps. */
        public static final int SLEEP = 7;

        /** Réveil d'un corps. */
        public static final int WAKE = 8;

        /** Grandeur clampée (budget dépassé). */
        public static final int CLAMPED = 9;

        /** Retour à un état valide. */
        public static final int RECOVERED = 10;

        /** Attache d'un élément. */
        public static final int ATTACH = 11;

        /** Détachement d'un attachement. */
        public static final int DETACH_ATTACHMENT = 12;
    }

    /** Sens du champ {@code PhysicsEvent.data}, selon le genre. */
    public static final class Data {

        private Data() {
            throw new AssertionError("classe de constantes, non instanciable");
        }

        /** Contact : l'autre corps est le proxy d'une entité vanilla (ADR-123 §6). */
        public static final int CONTACT_OTHER_ENTITY = 1;

        /** {@code RECOVERED} : restauré d'un état non fini ou hors du monde (E-2030). */
        public static final int RECOVERED_INVALID_STATE = 2030;

        /** {@code CLAMPED} : vitesse bornée (R-180), au plus une fois par minute. */
        public static final int CLAMPED_VELOCITY = 1;

        /** {@code CLAMPED} : corps endormi par la dégradation de budget (FM-21). */
        public static final int CLAMPED_BUDGET = 2;

        /** {@code CLAMPED} : empilement agité sur place, amorti puis endormi (FM-22). */
        public static final int CLAMPED_STACKING = 3;
    }
}
