package dev.axion.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Les sièges, les interactions et les variables d'une definition compilée (ADR-158 D1, D6, ADR-163 D2) : ce que la
 * montée, la descente, le clic sur un node et les commandes liées liront, chacun à l'index du node que le natif a
 * résolu.
 *
 * <p>Les règles de forme — un siège a un node, un seul conducteur, des contrôles connus, une
 * interaction complète — sont tranchées avant, par {@link DefinitionChecker} : une definition qui
 * arrive ici les respecte. Logique pure, sans Forge.
 *
 * @param seats les sièges, dans l'ordre de la definition
 * @param interactions les interactions, dans l'ordre de la definition
 * @param warnings les actions déclarées qui restent sans effet jusqu'à leur chantier (ADR-158 D6)
 * @param variables les variables que les commandes liées, les interactions et l'API écrivent (ADR-163 D2)
 * @param views les vues de ses sièges (ADR-170)
 * @param dashboard son tableau de bord (ADR-170 D4)
 * @param sensorNodes le node de chaque capteur déclaré, dans l'ordre de la definition (ADR-174 D5)
 */
public record Seating(List<Seat> seats, List<Interaction> interactions, List<String> warnings, Variables variables,
        VehicleViews views, VehicleDashboard dashboard, SocketTable sockets, int[] sensorNodes) {

    /** Aucun siège, aucune interaction, aucune variable. */
    public static final Seating NONE = new Seating(List.of(), List.of(), List.of(), Variables.NONE);

    /** Copie les listes : une table compilée ne change plus. */
    public Seating {
        seats = List.copyOf(seats);
        interactions = List.copyOf(interactions);
        warnings = List.copyOf(warnings);
        sensorNodes = sensorNodes == null ? new int[0] : sensorNodes.clone();
    }

    /**
     * Une table sans capteur.
     *
     * @param seats les sièges
     * @param interactions les interactions
     * @param warnings les actions sans effet
     * @param variables les variables
     * @param views les vues
     * @param dashboard le tableau de bord
     * @param sockets les sockets
     */
    public Seating(List<Seat> seats, List<Interaction> interactions, List<String> warnings, Variables variables,
            VehicleViews views, VehicleDashboard dashboard, SocketTable sockets) {
        this(seats, interactions, warnings, variables, views, dashboard, sockets, new int[0]);
    }

    @Override
    public int[] sensorNodes() {
        return sensorNodes.clone();
    }

    /**
     * {@return les sièges tels que l'API les dit (ADR-177), dans l'ordre de la definition : le nom, la pose et l'échelle
     * de l'occupant}
     */
    public java.util.List<dev.axion.api.assembly.Seat> apiSeats() {
        return seats.stream()
                .map(seat -> new dev.axion.api.assembly.Seat(seat.name(),
                        seat.occupant().pose() == Pose.STAND ? dev.axion.api.assembly.Seat.Pose.STAND
                                : dev.axion.api.assembly.Seat.Pose.SIT,
                        seat.occupant().scale()))
                .toList();
    }

    /**
     * Une table sans variable.
     *
     * @param seats les sièges
     * @param interactions les interactions
     * @param warnings les actions sans effet
     */
    public Seating(List<Seat> seats, List<Interaction> interactions, List<String> warnings) {
        this(seats, interactions, warnings, Variables.NONE);
    }

    /**
     * Une table sans vue déclarée ni par défaut.
     *
     * @param seats les sièges
     * @param interactions les interactions
     * @param warnings les actions sans effet
     * @param variables les variables
     */
    public Seating(List<Seat> seats, List<Interaction> interactions, List<String> warnings, Variables variables) {
        this(seats, interactions, warnings, variables, VehicleViews.NONE, VehicleDashboard.NONE, SocketTable.NONE);
    }

    /**
     * Une table sans socket (ADR-170).
     *
     * @param seats les sièges
     * @param interactions les interactions
     * @param warnings les actions sans effet
     * @param variables les variables
     * @param views les vues
     * @param dashboard le tableau de bord
     */
    public Seating(List<Seat> seats, List<Interaction> interactions, List<String> warnings, Variables variables,
            VehicleViews views, VehicleDashboard dashboard) {
        this(seats, interactions, warnings, variables, views, dashboard, SocketTable.NONE);
    }

    /** Les contrôles qu'un siège peut autoriser, ceux de {@code VehicleInput} (12.8), en bits. */
    public enum Control {
        /** Accélérateur. */
        THROTTLE("throttle"),
        /** Frein. */
        BRAKE("brake"),
        /** Direction. */
        STEER("steer"),
        /** Embrayage. */
        CLUTCH("clutch"),
        /** Frein à main. */
        HANDBRAKE("handbrake"),
        /** Rapport engagé. */
        GEAR("gear");

        private final String key;

        Control(String key) {
            this.key = key;
        }

        /** {@return le nom du contrôle dans une definition} */
        public String key() {
            return key;
        }

        /** {@return le bit du contrôle} */
        public int bit() {
            return 1 << ordinal();
        }

        /**
         * {@return le contrôle de ce nom, ou {@code null}}
         *
         * @param key nom dans une definition
         */
        public static Control of(String key) {
            for (Control control : values()) {
                if (control.key.equals(key)) {
                    return control;
                }
            }
            return null;
        }
    }

    /**
     * Un siège.
     *
     * @param name son nom
     * @param node l'index du node {@code SEAT} qui le porte
     * @param driver vrai pour le siège conducteur
     * @param exits les positions de sortie, dans le repère du siège, dans l'ordre déclaré
     * @param controls les contrôles qu'il autorise, en bits de {@link Control}
     * @param bindings les commandes génériques qu'il lie à des variables (ADR-163 D2)
     * @param occupant la pose, l'échelle et l'œil de son occupant (ADR-177 D1)
     */
    public record Seat(String name, int node, boolean driver, List<float[]> exits, int controls,
            List<Binding> bindings, Occupant occupant) {

        /** Copie les positions de sortie et les commandes. */
        public Seat {
            exits = exits.stream().map(float[]::clone).toList();
            bindings = List.copyOf(bindings);
            occupant = occupant == null ? Occupant.DEFAULT : occupant;
        }

        /**
         * Un siège dont l'occupant n'est pas réglé.
         *
         * @param name son nom
         * @param node l'index de son node
         * @param driver vrai pour le siège conducteur
         * @param exits ses positions de sortie
         * @param controls ses contrôles
         * @param bindings ses commandes liées
         */
        public Seat(String name, int node, boolean driver, List<float[]> exits, int controls,
                List<Binding> bindings) {
            this(name, node, driver, exits, controls, bindings, Occupant.DEFAULT);
        }

        /**
         * Un siège sans commande liée.
         *
         * @param name son nom
         * @param node l'index de son node
         * @param driver vrai pour le siège conducteur
         * @param exits ses positions de sortie
         * @param controls ses contrôles
         */
        public Seat(String name, int node, boolean driver, List<float[]> exits, int controls) {
            this(name, node, driver, exits, controls, List.of());
        }

        /** {@return vrai si le siège envoie des entrées : un contrôle ou une commande liée} */
        public boolean takesInput() {
            return controls != 0 || !bindings.isEmpty();
        }

        /** {@return les commandes qu'il lie, un bit par commande : le bit 0 pour la commande 1} */
        public int commandMask() {
            int mask = 0;
            for (Binding binding : bindings) {
                mask |= 1 << (binding.command() - 1);
            }
            return mask;
        }

        /** {@return une copie des positions de sortie} */
        @Override
        public List<float[]> exits() {
            return exits.stream().map(float[]::clone).toList();
        }

        /**
         * {@return vrai si le siège autorise ce contrôle}
         *
         * @param control le contrôle
         */
        public boolean allows(Control control) {
            return (controls & control.bit()) != 0;
        }
    }

    /** La pose d'un occupant (ADR-177 D1) : {@code lie} et {@code legs_out} attendent un contenu qui les demande. */
    public enum Pose {
        /** Assis, la pose vanilla. */
        SIT("sit"),
        /** Debout, pour une plateforme de tir ou un poste debout. */
        STAND("stand");

        private final String key;

        Pose(String key) {
            this.key = key;
        }

        /** {@return le nom de la pose dans une definition} */
        public String key() {
            return key;
        }

        /**
         * {@return la pose de ce nom, assis sans nom ou nom inconnu}
         *
         * @param key nom dans une definition
         */
        public static Pose of(String key) {
            for (Pose pose : values()) {
                if (pose.key.equals(key)) {
                    return pose;
                }
            }
            return SIT;
        }
    }

    /**
     * Ce qu'un siège règle de son occupant (ADR-177 D1).
     *
     * @param pose sa pose
     * @param scale l'échelle de son rendu, de 0,25 à 4 : sa boîte de collision ne change pas
     * @param eye son œil dans le repère du siège ; {@code null} : l'œil vanilla multiplié par {@code scale}
     */
    public record Occupant(Pose pose, float scale, float[] eye) {

        /** Un occupant que rien ne règle : assis, à l'échelle, à l'œil de Minecraft. */
        public static final Occupant DEFAULT = new Occupant(Pose.SIT, 1f, null);

        /** Copie l'œil. */
        public Occupant {
            java.util.Objects.requireNonNull(pose, "pose");
            eye = eye == null ? null : eye.clone();
        }

        /** {@return une copie de l'œil, {@code null} sans} */
        @Override
        public float[] eye() {
            return eye == null ? null : eye.clone();
        }

        /** {@return vrai si le siège ne règle rien de son occupant} */
        public boolean isDefault() {
            return pose == Pose.SIT && scale == 1f && eye == null;
        }

        /**
         * {@return l'occupant que décrit le champ {@code occupant} d'un siège, {@link #DEFAULT} sans lui}
         *
         * @param declared l'objet {@code seats[].occupant}, {@code null} sans
         */
        static Occupant of(JsonObject declared) {
            if (declared == null) {
                return DEFAULT;
            }
            float[] eye = null;
            if (declared.get("eye") instanceof JsonArray xyz) {
                eye = new float[] {xyz.get(0).getAsFloat(), xyz.get(1).getAsFloat(), xyz.get(2).getAsFloat()};
            }
            return new Occupant(Pose.of(text(declared, "pose")),
                    declared.has("scale") ? declared.get("scale").getAsFloat() : 1f, eye);
        }
    }

    /** Comment une commande liée pose sa variable (ADR-163 D2), sur la séquence des entrées. */
    public enum Mode {
        /** Au front montant, passe de l'une à l'autre de ses deux valeurs. */
        TOGGLE,
        /** Tenue, sa seconde valeur ; relâchée, la première. */
        HOLD,
        /** Au front montant, sa seconde valeur pour un seul tick, puis la première. */
        PRESS,
        /** Au front montant, la valeur suivante de sa liste, en boucle. */
        CYCLE;

        /**
         * {@return le mode de ce nom}
         *
         * @param key nom dans une definition
         */
        static Mode of(String key) {
            return valueOf(key.toUpperCase(java.util.Locale.ROOT));
        }
    }

    /**
     * Une commande générique liée à une variable (ADR-163 D2).
     *
     * @param command la commande, de 1 à 8
     * @param label son libellé, une clé de traduction ; {@code null} sans
     * @param variable le rang de la variable qu'elle pose
     * @param mode comment elle la pose
     * @param values les valeurs qu'elle pose : relâchée puis pressée, ou la liste d'un cycle ; copie
     */
    public record Binding(int command, String label, int variable, Mode mode, double[] values) {

        /** Copie les valeurs. */
        public Binding {
            values = values.clone();
        }

        /** {@return les valeurs, copie} */
        @Override
        public double[] values() {
            return values.clone();
        }

        /**
         * {@return la valeur de ce rang}
         *
         * @param rank rang dans {@link #values}
         */
        public double value(int rank) {
            return values[rank];
        }

        /** {@return le nombre de valeurs} */
        public int count() {
            return values.length;
        }
    }

    /** Ce qu'une interaction fait, et le chantier qui donnera leur effet aux autres (ADR-158 D6). */
    public enum Action {
        /** S'asseoir dans un siège. */
        SIT("sit", null),
        /** Jouer un clip, puis le rejouer à l'envers. */
        TOGGLE_ANIMATION("toggle_animation", null),
        /** Jouer un clip depuis son début. */
        PLAY_ANIMATION("play_animation", null),
        /**
         * Ouvrir un conteneur : une interaction déléguée (ADR-174 D6), que fait l'interaction {@code open_container}
         * qu'un mod enregistre ; sans lui, elle avertit.
         */
        OPEN_CONTAINER("open_container", null),
        /** Émettre un événement de contenu (ADR-174 D2) ; avant le schéma 7, sans événement nommé, sans effet. */
        EMIT_EVENT("emit_event", null),
        /** Poser une variable de contenu (ADR-163 D2). */
        SET_VARIABLE("set_variable", null),
        /** Réparer. */
        REPAIR("repair", "le dommage (M6)"),
        /** Attacher un socket au socket le plus proche d'une autre assembly, ou au bloc visé (ADR-171 D4). */
        ATTACH("attach", null),
        /** Défaire l'attache d'un socket (ADR-171 D4). */
        DETACH("detach", null),
        /** Ne jouer que la liste des actions de l'interaction (ADR-174 D3, schéma 7). */
        ACTIONS("actions", null),
        /** Une interaction qu'un mod enregistre, {@code <modId>:<nom>} (ADR-174 D6, schéma 7). */
        ADDON("<modId>:<nom>", null);

        private final String key;
        private final String awaits;

        Action(String key, String awaits) {
            this.key = key;
            this.awaits = awaits;
        }

        /** {@return le nom de l'action dans une definition} */
        public String key() {
            return key;
        }

        /** {@return vrai si l'action a son effet aujourd'hui} */
        public boolean effective() {
            return awaits == null;
        }

        /** {@return ce qui lui donnera son effet, {@code null} si elle l'a} */
        public String awaits() {
            return awaits;
        }

        /**
         * {@return l'action de ce nom, ou {@code null}}
         *
         * @param key nom dans une definition
         */
        public static Action of(String key) {
            for (Action action : values()) {
                if (action.key.equals(key)) {
                    return action;
                }
            }
            return null;
        }
    }

    /**
     * Une interaction.
     *
     * @param node l'index du node qui la porte
     * @param action ce qu'elle fait
     * @param animation l'animation de la definition qu'elle joue, {@code null} sans
     * @param clip le clip de cette animation, son nom s'il n'en déclare pas ; {@code null} sans
     * @param layer la couche de cette animation, 0 si elle n'en déclare pas
     * @param seat l'index du siège où elle assoit, {@code -1} sans
     * @param variable le rang de la variable que {@code set_variable} pose, {@code -1} sans
     * @param value la valeur qu'elle pose
     * @param socket le socket qu'{@code attach} ou {@code detach} vise, {@code null} sans (ADR-171 D4)
     * @param kinds les types qu'{@code attach} permet ; vide : ceux du socket
     * @param distance où {@code attach} cherche l'autre socket, en blocs ; {@code NaN} : la distance du serveur
     */
    public record Interaction(int node, Action action, String animation, String clip, int layer, int seat,
            int variable, double value, String socket, java.util.Set<String> kinds, double distance,
            List<NodeAction> actions, String handler, Map<String, Object> parameters) {

        /** Copie les types et les actions. */
        public Interaction {
            kinds = kinds == null ? java.util.Set.of() : java.util.Set.copyOf(kinds);
            actions = actions == null ? List.of() : List.copyOf(actions);
            parameters = parameters == null ? Map.of() : parameters;
        }

        /**
         * Une interaction d'AXION, sans interaction d'addon.
         *
         * @param node l'index de son node
         * @param action ce qu'elle fait
         * @param animation l'animation jouée
         * @param clip son clip
         * @param layer sa couche
         * @param seat le siège où elle assoit
         * @param variable la variable qu'elle pose
         * @param value la valeur posée
         * @param socket le socket visé
         * @param kinds les types permis
         * @param distance la distance de recherche
         * @param actions ses actions
         */
        public Interaction(int node, Action action, String animation, String clip, int layer, int seat,
                int variable, double value, String socket, java.util.Set<String> kinds, double distance,
                List<NodeAction> actions) {
            this(node, action, animation, clip, layer, seat, variable, value, socket, kinds, distance, actions, null,
                    Map.of());
        }

        /**
         * Une interaction sans liste d'actions (avant le schéma 7).
         *
         * @param node l'index de son node
         * @param action ce qu'elle fait
         * @param animation l'animation jouée
         * @param clip son clip
         * @param layer sa couche
         * @param seat le siège où elle assoit
         * @param variable la variable qu'elle pose
         * @param value la valeur posée
         * @param socket le socket visé
         * @param kinds les types permis
         * @param distance la distance de recherche
         */
        public Interaction(int node, Action action, String animation, String clip, int layer, int seat,
                int variable, double value, String socket, java.util.Set<String> kinds, double distance) {
            this(node, action, animation, clip, layer, seat, variable, value, socket, kinds, distance, List.of());
        }

        /**
         * Une interaction qui ne vise pas de socket.
         *
         * @param node l'index de son node
         * @param action ce qu'elle fait
         * @param animation l'animation jouée
         * @param clip son clip
         * @param layer sa couche
         * @param seat le siège où elle assoit
         * @param variable la variable qu'elle pose
         * @param value la valeur posée
         */
        public Interaction(int node, Action action, String animation, String clip, int layer, int seat, int variable,
                double value) {
            this(node, action, animation, clip, layer, seat, variable, value, null, java.util.Set.of(), Double.NaN);
        }

        /**
         * Une interaction qui ne pose pas de variable.
         *
         * @param node l'index de son node
         * @param action ce qu'elle fait
         * @param animation l'animation jouée
         * @param clip son clip
         * @param layer sa couche
         * @param seat le siège où elle assoit
         */
        public Interaction(int node, Action action, String animation, String clip, int layer, int seat) {
            this(node, action, animation, clip, layer, seat, -1, 0.0);
        }
    }

    /**
     * Une action sur l'état d'un node (ADR-174 D3), compilée : ce qu'elle fait, le node qu'elle vise par son index et par
     * son nom — l'état se sauvegarde par nom —, et ce qu'elle pose.
     *
     * @param kind ce qu'elle fait
     * @param node l'index du node visé
     * @param name le nom de ce node dans la definition
     * @param emissive l'émission posée par {@code set_material_param}, {@code NaN} sans
     * @param color la teinte posée par {@code set_material_param}, {@code null} sans
     * @param with l'index du node dont {@code swap_mesh} montre les maillages à la place, {@code -1} : l'original
     * @param withName le nom de ce node, {@code null} sans
     * @param value la valeur d'un {@code emit_event} (alors {@code name} est l'événement et {@code node} vaut
     *     {@code -1}) ; {@code NaN} pour une autre action
     */
    public record NodeAction(NodeActionKind kind, int node, String name, float emissive, float[] color, int with,
            String withName, double value, Map<String, Object> parameters) {

        /** Copie la teinte. */
        public NodeAction {
            java.util.Objects.requireNonNull(kind, "kind");
            java.util.Objects.requireNonNull(name, "name");
            color = color == null ? null : color.clone();
            parameters = parameters == null ? Map.of() : parameters;
        }

        /**
         * Une action d'AXION.
         *
         * @param kind ce qu'elle fait
         * @param node l'index du node visé, -1 sans
         * @param name son nom, ou celui de l'événement
         * @param emissive l'émission posée, {@code NaN} sans
         * @param color la teinte posée, {@code null} sans
         * @param with l'index du remplaçant, -1 sans
         * @param withName son nom, {@code null} sans
         * @param value la valeur d'un événement
         */
        public NodeAction(NodeActionKind kind, int node, String name, float emissive, float[] color, int with,
                String withName, double value) {
            this(kind, node, name, emissive, color, with, withName, value, Map.of());
        }

        /**
         * {@return une action qu'un mod enregistre (ADR-174 D6)}
         *
         * @param handler son identifiant, {@code <modId>:<nom>}
         * @param parameters ses paramètres, tels que la definition les donne
         */
        public static NodeAction addon(String handler, Map<String, Object> parameters) {
            return new NodeAction(NodeActionKind.ADDON, -1, handler, Float.NaN, null, -1, null, Double.NaN, parameters);
        }

        /**
         * Une action qui ne remplace pas de maillage.
         *
         * @param kind ce qu'elle fait
         * @param node l'index du node visé
         * @param name son nom
         * @param emissive l'émission posée, {@code NaN} sans
         * @param color la teinte posée, {@code null} sans
         */
        public NodeAction(NodeActionKind kind, int node, String name, float emissive, float[] color) {
            this(kind, node, name, emissive, color, -1, null, Double.NaN);
        }

        /**
         * Une action sur l'état d'un node.
         *
         * @param kind ce qu'elle fait
         * @param node l'index du node visé
         * @param name son nom
         * @param emissive l'émission posée, {@code NaN} sans
         * @param color la teinte posée, {@code null} sans
         * @param with le remplaçant d'un {@code swap_mesh}, {@code -1} sans
         * @param withName son nom, {@code null} sans
         */
        public NodeAction(NodeActionKind kind, int node, String name, float emissive, float[] color, int with,
                String withName) {
            this(kind, node, name, emissive, color, with, withName, Double.NaN);
        }

        /**
         * {@return l'émission d'un événement de contenu (ADR-174 D2)}
         *
         * @param event son nom
         * @param value sa valeur
         */
        public static NodeAction emit(String event, double value) {
            return new NodeAction(NodeActionKind.EMIT, -1, event, Float.NaN, null, -1, null, value);
        }

        /**
         * {@return la pose de l'usure d'un pneu (ADR-176 D4)}
         *
         * @param wheel le node de la roue, {@code null} pour toutes
         * @param value l'usure, de 0 à 1
         */
        public static NodeAction setWear(String wheel, double value) {
            return new NodeAction(NodeActionKind.SET_WEAR, -1, wheel == null ? "" : wheel, Float.NaN,
                    null, -1, null, value);
        }

        /**
         * {@return le plein d'un réservoir (ADR-176 D3)}
         *
         * @param litres ce que l'action verse
         */
        public static NodeAction refuel(double litres) {
            return new NodeAction(NodeActionKind.REFUEL, -1, "refuel", Float.NaN, null, -1, null, litres);
        }
    }

    /** Ce qu'une action fait à un node (ADR-174 D3). */
    public enum NodeActionKind {
        /** Le cacher, quel que soit son canal {@code visibility}. */
        HIDE,
        /** Lever le masque d'un {@code hide_node}. */
        SHOW,
        /** Poser son émission ou sa teinte. */
        MATERIAL,
        /** Montrer à sa place les maillages d'un autre node, ou revenir à l'original. */
        SWAP,
        /** Émettre un événement de contenu (ADR-174 D2) : le nom de l'action est celui de l'événement. */
        EMIT,
        /** Verser du carburant dans le réservoir (ADR-176 D3) : la valeur est le nombre de litres. */
        REFUEL,
        /** Poser l'usure du pneu d'une roue, ou de toutes (ADR-176 D4) : la valeur est l'usure, de 0 à 1. */
        SET_WEAR,
        /** Une action qu'un mod enregistre (ADR-174 D6) : son nom est son identifiant. */
        ADDON
    }

    /**
     * Ce qu'un clic vise (ADR-158 D2) : une interaction, ou un siège où s'asseoir.
     *
     * @param interaction rang de l'interaction visée, {@code -1} sans
     * @param seat rang du siège visé, {@code -1} sans ; celui d'une interaction {@code sit} si elle en a un
     */
    public record Target(int interaction, int seat) {}

    /**
     * {@return ce que vise un clic sur un node, ou {@code null} : du node touché vers la racine, le
     * premier node qui porte une interaction ou un siège} Sur ce node, l'interaction l'emporte : elle est
     * déclarée pour lui ; un siège seul y assoit. Un clic sur l'assise, le dossier ou la portière s'y
     * rapporte ainsi sans que chaque collider le déclare.
     *
     * @param node le node qui porte le collider touché, {@code -1} sans
     * @param anatomy les nodes de l'asset, pour leurs parents
     */
    public Target target(int node, dev.axion.asset.Anatomy anatomy) {
        int current = node;
        // Une chaîne plus longue que la table serait un cycle, que l'asset refuse : elle s'arrête là.
        for (int steps = 0; current >= 0 && steps <= anatomy.size(); steps++) {
            for (int i = 0; i < interactions.size(); i++) {
                Interaction interaction = interactions.get(i);
                if (interaction.node() == current) {
                    return new Target(i, interaction.action() == Action.SIT ? interaction.seat() : -1);
                }
            }
            int seat = seatAt(current);
            if (seat >= 0) {
                return new Target(-1, seat);
            }
            current = anatomy.parent(current);
        }
        return null;
    }

    /**
     * {@return le premier siège que porte ce node, ou {@code -1}}
     *
     * @param node index d'un node de l'asset
     */
    public int seatAt(int node) {
        for (int i = 0; i < seats.size(); i++) {
            if (seats.get(i).node() == node) {
                return i;
            }
        }
        return -1;
    }

    /**
     * {@return l'index de chaque node que les actions de la definition nomment, par son nom (ADR-174 D3)} : ce que
     * l'état sauvegardé par nom ({@code axion:nodes}) retrouve à la lecture.
     */
    public Map<String, Integer> actionNodes() {
        Map<String, Integer> named = new java.util.LinkedHashMap<>();
        for (Interaction interaction : interactions) {
            for (NodeAction action : interaction.actions()) {
                if (action.kind() == NodeActionKind.EMIT) {
                    continue;
                }
                named.putIfAbsent(action.name(), action.node());
                if (action.withName() != null) {
                    named.putIfAbsent(action.withName(), action.with());
                }
            }
        }
        return named;
    }

    /**
     * {@return l'index de chaque node qu'un {@code swap_mesh} montre à la place d'un autre, par son nom (ADR-174 D3)} :
     * un node de remplacement n'est pas dessiné à sa propre place tant qu'aucune action ne l'y appelle.
     */
    public Map<String, Integer> swapTargets() {
        Map<String, Integer> targets = new java.util.LinkedHashMap<>();
        for (Interaction interaction : interactions) {
            for (NodeAction action : interaction.actions()) {
                if (action.withName() != null) {
                    targets.putIfAbsent(action.withName(), action.with());
                }
            }
        }
        return targets;
    }

    /**
     * {@return les actions qu'une interaction joue après la sienne} : l'événement d'un {@code emit_event} d'abord,
     * puis sa liste (ADR-174 D2, D3).
     */
    private static List<NodeAction> interactionActions(Action action, JsonObject interaction, String path,
            Map<String, Integer> byPath) {
        List<NodeAction> listed = nodeActions(interaction, path, byPath);
        if (action != Action.EMIT_EVENT || interaction.get("event") == null) {
            return listed;
        }
        List<NodeAction> all = new ArrayList<>(listed.size() + 1);
        JsonElement value = interaction.get("value");
        all.add(NodeAction.emit(interaction.get("event").getAsString(),
                value instanceof com.google.gson.JsonPrimitive number && number.isNumber() ? number.getAsDouble() : 1.0));
        all.addAll(listed);
        return all;
    }

    /**
     * {@return les actions d'une interaction, dans leur ordre (ADR-174 D3)} Le node de chacune est celui que le natif a
     * résolu pour sa référence.
     */
    private static List<NodeAction> nodeActions(JsonObject interaction, String path, Map<String, Integer> byPath) {
        if (!(interaction.get("actions") instanceof JsonArray declared)) {
            return List.of();
        }
        List<NodeAction> actions = new ArrayList<>(declared.size());
        for (int k = 0; k < declared.size(); k++) {
            JsonObject step = declared.get(k).getAsJsonObject();
            String at = path + ".actions[" + k + "]";
            if (step.get("hide_node") != null) {
                actions.add(new NodeAction(NodeActionKind.HIDE, node(byPath, at + ".hide_node"),
                        step.get("hide_node").getAsString(), Float.NaN, null));
            } else if (step.get("show_node") != null) {
                actions.add(new NodeAction(NodeActionKind.SHOW, node(byPath, at + ".show_node"),
                        step.get("show_node").getAsString(), Float.NaN, null));
            } else if (step.get("swap_mesh") instanceof JsonObject swap) {
                String with = swap.get("with") == null ? null : swap.get("with").getAsString();
                actions.add(new NodeAction(NodeActionKind.SWAP, node(byPath, at + ".swap_mesh.node"),
                        swap.get("node").getAsString(), Float.NaN, null,
                        with == null ? -1 : node(byPath, at + ".swap_mesh.with"), with));
            } else if (step.get("set_wear") instanceof JsonObject wear) {
                actions.add(NodeAction.setWear(wear.get("wheel") == null ? null : wear.get("wheel").getAsString(),
                        wear.get("value").getAsDouble()));
            } else if (step.get("refuel") instanceof JsonObject refuel) {
                actions.add(NodeAction.refuel(refuel.get("litres").getAsDouble()));
            } else if (step.get("emit_event") instanceof JsonObject emit) {
                actions.add(NodeAction.emit(emit.get("event").getAsString(),
                        emit.has("value") ? emit.get("value").getAsDouble() : 1.0));
            } else if (step.size() == 1 && DefinitionRegistry.isId(step.keySet().iterator().next())) {
                // ADR-174 D6 : une action d'addon, ses paramètres tels quels.
                String handler = step.keySet().iterator().next();
                actions.add(NodeAction.addon(handler, step.get(handler) instanceof JsonObject parameters
                        ? JsonTrees.of(parameters) : Map.of()));
            } else if (step.get("set_material_param") instanceof JsonObject param) {
                JsonElement value = param.get("value");
                boolean emissive = "emissive".equals(param.get("param").getAsString());
                float[] color = null;
                if (!emissive) {
                    JsonArray rgb = value.getAsJsonArray();
                    color = new float[] {rgb.get(0).getAsFloat(), rgb.get(1).getAsFloat(), rgb.get(2).getAsFloat()};
                }
                actions.add(new NodeAction(NodeActionKind.MATERIAL, node(byPath, at + ".set_material_param.node"),
                        param.get("node").getAsString(), emissive ? value.getAsFloat() : Float.NaN, color));
            }
        }
        return actions;
    }

    /**
     * Compile les sièges et les interactions d'une definition acceptée.
     *
     * @param root document de la definition, accepté aux étapes 1 à 3
     * @param references ses références à l'asset, dans l'ordre de leurs rangs ({@link DefinitionEncoder})
     * @param nodes le node que le natif a résolu pour chaque référence, dans le même ordre
     * @return la table
     * @throws IllegalArgumentException si un node déclaré n'a pas été résolu : un défaut de la
     *     frontière, jamais de la definition, que le natif aurait refusée
     */
    static Seating of(JsonObject root, List<DefinitionChecker.AssetReference> references, int[] nodes) {
        Map<String, Integer> byPath = new HashMap<>();
        for (int rank = 0; rank < references.size() && rank < nodes.length; rank++) {
            byPath.put(references.get(rank).path(), nodes[rank]);
        }
        Variables variables = Variables.of(root);
        List<Seat> seats = new ArrayList<>();
        Map<String, Integer> seatRanks = new HashMap<>();
        for (JsonObject seat : objects(root, "seats")) {
            String path = "$.seats[" + seats.size() + "]";
            String name = seat.get("name").getAsString();
            int controls = 0;
            if (seat.get("controls") instanceof JsonArray declared) {
                for (JsonElement control : declared) {
                    controls |= Control.of(control.getAsString()).bit();
                }
            }
            List<float[]> exits = new ArrayList<>();
            if (seat.get("exit_offsets") instanceof JsonArray declared) {
                for (JsonElement exit : declared) {
                    JsonArray xyz = exit.getAsJsonArray();
                    exits.add(new float[] {xyz.get(0).getAsFloat(), xyz.get(1).getAsFloat(), xyz.get(2).getAsFloat()});
                }
            }
            List<Binding> bindings = new ArrayList<>();
            for (JsonObject binding : objects(seat, "bindings")) {
                double[] values = {0.0, 1.0};
                if (binding.get("values") instanceof JsonArray declared) {
                    values = new double[declared.size()];
                    for (int v = 0; v < values.length; v++) {
                        values[v] = Variables.value(declared.get(v));
                    }
                }
                bindings.add(new Binding(binding.get("command").getAsInt(), text(binding, "label"),
                        variables.indexOf(text(binding, "variable")), Mode.of(text(binding, "mode")), values));
            }
            seatRanks.putIfAbsent(name, seats.size());
            seats.add(new Seat(name, node(byPath, path + ".node"),
                    "driver".equals(text(seat, "role")), exits, controls, bindings,
                    Occupant.of(seat.get("occupant") instanceof JsonObject occupant ? occupant : null)));
        }
        List<Interaction> interactions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (JsonObject interaction : objects(root, "interaction")) {
            String path = "$.interaction[" + interactions.size() + "]";
            String named = interaction.get("action").getAsString();
            Action action = Action.of(named);
            // ADR-174 D6 : au schéma 7, une interaction d'addon, <modId>:<nom>.
            String handler = action == null && DefinitionRegistry.isId(named) ? named : null;
            if (handler != null) {
                action = Action.ADDON;
            }
            if (action == Action.EMIT_EVENT && interaction.get("event") == null) {
                // Avant le schéma 7, emit_event ne nomme pas d'événement : sans effet.
                warnings.add(path + " : l'action « emit_event » n'émet rien sans événement nommé, au schéma 7"
                        + " (ADR-174 D2)");
            }
            if (!action.effective()) {
                warnings.add(path + " : l'action « " + action.key() + " » reste sans effet jusqu'à "
                        + action.awaits() + " (ADR-158 D6)");
            }
            int variable = variables.indexOf(text(interaction, "variable"));
            if (action == Action.SET_VARIABLE && variable < 0) {
                // Avant le schéma 3, aucune variable ne se déclare : l'interaction reste sans effet.
                warnings.add(path + " : l'action « set_variable » ne pose rien sans variable déclarée, au schéma 3"
                        + " (ADR-163 D2)");
            }
            String seat = text(interaction, "seat");
            String animation = text(interaction, "animation");
            JsonObject played = animation != null && root.get("animations") instanceof JsonObject animations
                    && animations.get(animation) instanceof JsonObject declared ? declared : null;
            String clip = played != null && text(played, "clip") != null ? text(played, "clip") : animation;
            int layer = played != null && played.has("layer") ? played.get("layer").getAsInt() : 0;
            if ((action == Action.ATTACH || action == Action.DETACH) && text(interaction, "socket") == null) {
                // Avant le schéma 6, attach et detach ne nomment pas de socket : sans effet.
                warnings.add(path + " : l'action « " + action.key() + " » ne vise aucun socket avant le schéma 6"
                        + " (ADR-171 D4)");
            }
            java.util.Set<String> kinds = new java.util.LinkedHashSet<>();
            if (interaction.get("kinds") instanceof JsonArray declared) {
                for (JsonElement kind : declared) {
                    kinds.add(kind.getAsString());
                }
            }
            interactions.add(new Interaction(node(byPath, path + ".node"), action, animation, clip, layer,
                    seat == null ? -1 : seatRanks.getOrDefault(seat, -1), variable,
                    variable >= 0 && interaction.get("value") != null ? Variables.value(interaction.get("value")) : 0.0,
                    text(interaction, "socket"), kinds,
                    interaction.has("distance") ? interaction.get("distance").getAsDouble() : Double.NaN,
                    interactionActions(action, interaction, path, byPath), handler,
                    interaction.get("parameters") instanceof JsonObject parameters ? JsonTrees.of(parameters)
                            : Map.of()));
        }
        SocketTable sockets = SocketTable.of(root, byPath);
        // ADR-174 D5 : le node de chaque capteur, que l'overlay sensors dessine.
        int sensorCount = Sensors.of(root).declared().size();
        int[] sensorNodes = new int[sensorCount];
        for (int sensor = 0; sensor < sensorCount; sensor++) {
            Integer node = byPath.get("$.sensors[" + sensor + "].node");
            sensorNodes[sensor] = node == null ? -1 : node;
        }
        return seats.isEmpty() && interactions.isEmpty() && variables.size() == 0 && sockets.isEmpty()
                && sensorCount == 0
                ? NONE
                : new Seating(seats, interactions, warnings, variables, VehicleViews.of(root, byPath, seatRanks),
                        VehicleDashboard.of(root, seatRanks, drivers(seats)), sockets, sensorNodes);
    }

    /** {@return les sièges conducteurs, un bit par rang} */
    private static long drivers(List<Seat> seats) {
        long drivers = 0;
        for (int rank = 0; rank < seats.size() && rank < Long.SIZE; rank++) {
            if (seats.get(rank).driver()) {
                drivers |= 1L << rank;
            }
        }
        return drivers;
    }

    private static int node(Map<String, Integer> byPath, String path) {
        Integer node = byPath.get(path);
        if (node == null || node < 0) {
            throw new IllegalArgumentException(path + " : node non résolu par le natif");
        }
        return node;
    }

    private static List<JsonObject> objects(JsonObject root, String key) {
        List<JsonObject> found = new ArrayList<>();
        if (root.get(key) instanceof JsonArray list) {
            for (JsonElement element : list) {
                if (element instanceof JsonObject object) {
                    found.add(object);
                }
            }
        }
        return found;
    }

    private static String text(JsonObject object, String key) {
        return object.get(key) instanceof JsonPrimitive primitive && primitive.isString()
                ? primitive.getAsString()
                : null;
    }
}
