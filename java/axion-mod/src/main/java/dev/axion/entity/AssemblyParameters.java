package dev.axion.entity;

import dev.axion.api.assembly.Modifier;
import dev.axion.api.assembly.Parameters;
import dev.axion.definition.Tuning;
import dev.axion.net.AssemblyState;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;

/**
 * Les paramètres d'une assembly (ADR-167), par instance : les modificateurs posés, dans l'ordre de leur pose, et la
 * valeur effective de chaque paramètre. Logique pure, sans Forge ; le serveur seul l'écrit, sur son thread
 * autoritatif (INV-02), et le client en tient le miroir que les snapshots lui donnent.
 *
 * <p><strong>La composition</strong> (D3) suit les attributs de Minecraft :
 * {@code (base + Σ add) × (1 + Σ multiply_base) × Π (1 + multiply_total)}, chaque somme et chaque produit dans
 * l'ordre des identifiants de cause. Le résultat ne dépend donc que de l'ensemble des modificateurs actifs, et vaut au
 * bit près la même chose au serveur et chez le client (Java 17 : arithmétique IEEE 754 stricte).
 *
 * <p><strong>L'activité</strong> d'un modificateur se rejoue, paramètre par paramètre : parcourus dans l'ordre de la
 * pose, les modificateurs s'activent un à un tant que la valeur reste dans les bornes du paramètre
 * ({@link Tuning.Parameter#refusal}), et le parcours recommence tant qu'il en active un. Un modificateur qui vise un
 * paramètre que la definition n'a pas reste inactif. Le rejeu ne dépend que de la liste et de la definition : le
 * client qui la reçoit retrouve les mêmes actifs.
 *
 * <p><strong>Les modificateurs déclarés</strong> (D4) suivent les variables de l'assembly ({@link #vary}) : ils
 * passent les premiers au rejeu, dans l'ordre de la definition, avant les modificateurs posés. Ils ne se posent ni ne se
 * retirent ; une écriture de variable qui les ferait sortir des bornes se refuse d'abord ({@link #refusal}).
 *
 * <p><strong>Les sorties des contrôleurs</strong> (D9) valent un tick ({@link #control}) : elles passent après les
 * déclarés et les posés, chacune active si elle laisse le paramètre dans ses bornes, écartée sinon ; elles ne rendent
 * jamais inactif un modificateur déclaré ou posé. La valeur composée sans elles ({@link #uncontrolled}) est celle que
 * les contrôleurs lisent. Elles ne se sauvegardent ni ne partent aux joueurs : le contrôleur les recalcule.
 *
 * <ul>
 *   <li>{@link #modify} refuse, et ne change rien, un modificateur qui serait inactif, ou qui rendrait inactif un
 *       modificateur actif ;</li>
 *   <li>{@link #remove} réussit toujours : un modificateur que le retrait met hors bornes devient inactif, avec sa
 *       raison, et redevient actif au rejeu qui l'y fait rentrer ;</li>
 *   <li>{@link #restore} reprend une liste — la sauvegarde, une definition recompilée — telle quelle : ce que les
 *       bornes refusent reste inactif et se dit, rien n'est jeté (CAP-38).</li>
 * </ul>
 */
public final class AssemblyParameters {

    /** Modificateurs posés sur une assembly, au plus, actifs ou non (D3) : autant que les snapshots en portent. */
    public static final int MAX_MODIFIERS = AssemblyState.Modifiers.MAX_MODIFIERS;

    /** Un modificateur posé par l'API. */
    public static final String API = "api";

    /** Un modificateur posé par {@code /axion params}. */
    public static final String COMMAND = "commande";

    /** Un modificateur relu de la sauvegarde. */
    public static final String SAVE = "sauvegarde";

    /** Un modificateur que la definition déclare, et qu'une variable règle (D4). */
    public static final String VARIABLE = "variable";

    /** Une sortie de contrôleur, pour un tick (D9). */
    public static final String CONTROLLER = "contrôleur";

    /** L'origine des causes que le moteur pose lui-même, en lecture seule (ADR-176 D2). */
    public static final String ENGINE = "moteur";

    /** Les origines, chacune au rang de son code sur le fil ({@link AssemblyState.Posed}). */
    static final List<String> ORIGINS = List.of(API, COMMAND, SAVE);

    /** L'espace de noms des causes du moteur, réservé (D2). */
    static final String ENGINE_NAMESPACE = "axion:";

    private final Tuning tuning;
    /** Les modificateurs déclarés, un par entrée de {@code tuning.declared()}, à la valeur de leur variable. */
    private final List<Entry> declared = new ArrayList<>();
    private final List<Entry> entries = new ArrayList<>();
    /** Les sorties des contrôleurs pour ce tick, dans l'ordre des contrôleurs (D9). */
    private final List<Entry> controlled = new ArrayList<>();
    /** Les causes du moteur (ADR-176 D2), lues de l'état répliqué, jamais sauvegardées. */
    private final List<Entry> engine = new ArrayList<>();
    private final double[] values;
    /** La valeur de chaque paramètre sans les sorties des contrôleurs : celle qu'ils lisent. */
    private final double[] uncontrolled;
    private long version;
    private long controlVersion;

    /** Un modificateur posé, et son état au dernier rejeu. */
    private static final class Entry {
        private final Modifier modifier;
        private final String origin;
        private boolean active;
        private String reason;

        private Entry(Modifier modifier, String origin) {
            if (!ORIGINS.contains(origin) && !VARIABLE.equals(origin) && !CONTROLLER.equals(origin)
                    && !ENGINE.equals(origin)) {
                throw new IllegalArgumentException("origine « " + origin + " » : " + ORIGINS + " attendue");
            }
            this.modifier = modifier;
            this.origin = origin;
        }

        private Entry copy() {
            Entry copy = new Entry(modifier, origin);
            copy.active = active;
            copy.reason = reason;
            return copy;
        }
    }

    /**
     * Les paramètres d'une assembly neuve : à leur base, composée avec les modificateurs déclarés aux valeurs par défaut
     * de leurs variables.
     *
     * @param tuning les paramètres que sa definition déclare
     */
    public AssemblyParameters(Tuning tuning) {
        this.tuning = Objects.requireNonNull(tuning);
        values = new double[tuning.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = tuning.get(i).base();
        }
        uncontrolled = values.clone();
        for (Tuning.Declared one : tuning.declared()) {
            declared.add(new Entry(one.modifier(one.initial()), VARIABLE));
        }
        if (!declared.isEmpty()) {
            for (int i = 0; i < values.length; i++) {
                replay(i);
            }
        }
    }

    /** Une copie, pour juger une écriture sans rien changer. */
    private AssemblyParameters(AssemblyParameters other) {
        tuning = other.tuning;
        values = other.values.clone();
        uncontrolled = other.uncontrolled.clone();
        version = other.version;
        controlVersion = other.controlVersion;
        other.declared.forEach(entry -> declared.add(entry.copy()));
        other.entries.forEach(entry -> entries.add(entry.copy()));
        other.controlled.forEach(entry -> controlled.add(entry.copy()));
        other.engine.forEach(entry -> engine.add(entry.copy()));
    }

    /**
     * Pose, remplace ou retire la cause que le moteur porte sur un paramètre (ADR-176 D2) : une addition, bornée par
     * la seule limite du paramètre, qui passe après les réglages. Elle ne se sauvegarde ni ne s'envoie : elle se
     * recalcule des deux côtés. Elle change {@link #controlVersion} quand la valeur effective change, jamais
     * {@link #version} : les crans du carburant ne renvoient pas la liste posée à chaque snapshot.
     *
     * @param cause la cause, dans l'espace de noms {@code axion:}
     * @param parameter le paramètre
     * @param amount la quantité ajoutée ; 0 retire la cause
     * @return vrai si la valeur effective du paramètre a changé ; faux aussi pour un paramètre que l'assembly n'a pas
     */
    public boolean engineCause(String cause, String parameter, double amount) {
        if (!cause.startsWith(ENGINE_NAMESPACE)) {
            throw new IllegalArgumentException("cause « " + cause + " » : une cause du moteur est dans l'espace de noms axion");
        }
        int index = tuning.indexOf(parameter);
        if (index < 0) {
            return false;
        }
        double before = values[index];
        engine.removeIf(entry -> entry.modifier.cause().equals(cause) && entry.modifier.parameter().equals(parameter));
        if (amount != 0.0) {
            engine.add(new Entry(new Modifier(cause, parameter, Modifier.Operation.ADD, amount), ENGINE));
        }
        replay(index);
        if (Double.compare(before, values[index]) == 0) {
            return false;
        }
        controlVersion++;
        return true;
    }

    /**
     * Pose les sorties des contrôleurs pour ce tick (D9), à la place de celles du tick précédent. Chacune, dans
     * l'ordre, s'active si le paramètre reste dans ses bornes ; sinon elle est écartée pour ce tick. Une sortie qui
     * vise un paramètre que l'assembly n'a pas est écartée aussi.
     *
     * @param outputs les modificateurs du tick, dans l'ordre des contrôleurs ; leur cause est le contrôleur
     * @return une ligne par sortie écartée, à journaliser
     */
    public List<String> control(List<Modifier> outputs) {
        if (outputs.isEmpty() && controlled.isEmpty()) {
            return List.of();
        }
        BitSet touched = new BitSet();
        for (Entry entry : controlled) {
            touched.set(tuning.indexOf(entry.modifier.parameter()));
        }
        controlled.clear();
        List<String> lines = new ArrayList<>();
        for (Modifier output : outputs) {
            int index = tuning.indexOf(output.parameter());
            if (index < 0) {
                lines.add("sortie du contrôleur « " + output.cause() + " » écartée : paramètre « "
                        + output.parameter() + " » inconnu de cette assembly");
                continue;
            }
            controlled.add(new Entry(output, CONTROLLER));
            touched.set(index);
        }
        double[] before = values.clone();
        touched.stream().forEach(this::replay);
        for (Entry entry : controlled) {
            if (!entry.active) {
                lines.add("sortie du contrôleur « " + entry.modifier.cause() + " » sur « " + entry.modifier.parameter()
                        + " » écartée pour ce tick : " + entry.reason);
            }
        }
        if (!java.util.Arrays.equals(before, values)) {
            controlVersion++;
        }
        return lines;
    }

    /**
     * {@return la valeur d'un paramètre composée sans les sorties des contrôleurs (D9)} : celle que chaque contrôleur
     * lit, quel que soit leur ordre.
     *
     * @param index son rang dans {@link #tuning()}
     */
    public double uncontrolled(int index) {
        return uncontrolled[index];
    }

    /** {@return un numéro qui change quand les sorties des contrôleurs changent une valeur effective} */
    public long controlVersion() {
        return controlVersion;
    }

    /**
     * Suit les variables de l'assembly (D4) : chaque modificateur déclaré prend la quantité que la valeur de sa variable
     * lui donne, et les paramètres qu'il règle se rejouent. Une valeur qui ne change aucune quantité ne change rien.
     *
     * @param variables la valeur de chaque variable de la definition, dans son ordre
     * @return vrai si une quantité a changé
     */
    public boolean vary(double[] variables) {
        BitSet touched = new BitSet();
        for (int k = 0; k < declared.size(); k++) {
            Tuning.Declared one = tuning.declared().get(k);
            Modifier next = one.modifier(one.variable() < variables.length ? variables[one.variable()] : one.initial());
            if (Double.compare(next.amount(), declared.get(k).modifier.amount()) != 0) {
                declared.set(k, new Entry(next, VARIABLE));
                touched.set(tuning.indexOf(one.parameter()));
            }
        }
        if (touched.isEmpty()) {
            return false;
        }
        touched.stream().forEach(this::replay);
        version++;
        return true;
    }

    /**
     * {@return pourquoi ces valeurs de variables sont refusées, {@code null} si elles conviennent} (D2, D4) : une
     * écriture de variable qui laisserait inactif un modificateur qu'elle déclare — sa valeur sortirait des bornes —, ou
     * qui rendrait inactif un modificateur actif, est refusée comme une variable hors bornes. Rien n'est changé.
     *
     * @param variable le rang de la variable écrite
     * @param variables la valeur de chaque variable après l'écriture
     */
    public String refusal(int variable, double[] variables) {
        AssemblyParameters after = new AssemblyParameters(this);
        if (!after.vary(variables)) {
            return null;
        }
        for (int k = 0; k < declared.size(); k++) {
            Entry now = after.declared.get(k);
            if (!now.active && (tuning.declared().get(k).variable() == variable || declared.get(k).active)) {
                return now.reason;
            }
        }
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).active && !after.entries.get(i).active) {
                return "rendrait inactif le modificateur de « " + entries.get(i).modifier.cause() + " » : "
                        + after.entries.get(i).reason;
            }
        }
        return null;
    }

    /** {@return les paramètres déclarés} */
    public Tuning tuning() {
        return tuning;
    }

    /**
     * {@return la valeur effective d'un paramètre}
     *
     * @param index son rang dans {@link #tuning()}
     */
    public double value(int index) {
        return values[index];
    }

    /** {@return un numéro qui change à chaque pose, retrait ou reprise} */
    public long version() {
        return version;
    }

    /** {@return vrai si une valeur effective diffère de sa base} */
    public boolean tuned() {
        for (int i = 0; i < values.length; i++) {
            if (Double.compare(values[i], tuning.get(i).base()) != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@return la valeur effective de chaque paramètre nommé, en {@code f32} pour le natif, NaN pour un paramètre que
     * l'assembly n'a pas : le natif garde alors sa base (ADR-167 D6)}
     *
     * @param names les paramètres, dans l'ordre de la commande
     */
    public float[] fields(List<String> names) {
        float[] fields = new float[names.size()];
        for (int i = 0; i < fields.length; i++) {
            String name = names.get(i);
            int index = name.startsWith(MOTOR_SLOT)
                    ? motorTorque(Integer.parseInt(name.substring(MOTOR_SLOT.length())))
                    : name.startsWith(REGEN_SLOT)
                            ? motorRegen(Integer.parseInt(name.substring(REGEN_SLOT.length())))
                            : tuning.indexOf(name);
            fields[i] = index < 0 ? Float.NaN : (float) values[index];
        }
        return fields;
    }

    /**
     * Le préfixe d'un champ qui désigne un moteur électrique par son rang (ADR-182 D4) : {@code motor#0} est le couple du
     * premier moteur, quel que soit son nom.
     */
    public static final String MOTOR_SLOT = "motor#";

    /** Le préfixe d'un champ qui désigne la récupération d'un moteur électrique par son rang (ADR-182, F). */
    public static final String REGEN_SLOT = "regen#";

    /** {@return le rang, dans la table, du facteur de récupération du moteur électrique de rang {@code motor} ; -1 sans} */
    private int motorRegen(int motor) {
        int torque = motorTorque(motor);
        if (torque < 0) {
            return -1;
        }
        String name = tuning.get(torque).name();
        return tuning.indexOf(name.substring(0, name.length() - ".torque".length()) + ".regen");
    }

    /** {@return le rang, dans la table, du couple du moteur électrique de rang {@code motor} ; -1 sans} */
    private int motorTorque(int motor) {
        int seen = 0;
        for (int i = 0; i < tuning.size(); i++) {
            String name = tuning.get(i).name();
            if (name.startsWith("motor.") && name.endsWith(".torque") && name.indexOf(':') < 0 && seen++ == motor) {
                return i;
            }
        }
        return -1;
    }

    /**
     * {@return la table entière des rapports, point mort compris, dans l'ordre de {@code gears} ; vide sans rapport
     * réglable} Les rangs des rapports sont contigus autour du point mort : ceux sous zéro le précèdent.
     */
    public float[] gearTable() {
        int lowest = 0;
        int highest = 0;
        for (int i = 0; i < tuning.size(); i++) {
            Integer rank = gearRank(tuning.get(i).name());
            if (rank != null) {
                lowest = Math.min(lowest, rank);
                highest = Math.max(highest, rank);
            }
        }
        if (lowest == 0 && highest == 0) {
            return new float[0];
        }
        float[] table = new float[highest - lowest + 1];
        for (int i = 0; i < tuning.size(); i++) {
            Integer rank = gearRank(tuning.get(i).name());
            if (rank != null) {
                table[rank - lowest] = (float) values[i];
            }
        }
        return table;
    }

    /** {@return le rang d'un paramètre de rapport, {@code null} pour un autre paramètre} */
    private static Integer gearRank(String name) {
        String prefix = "transmission.gear.";
        return name.startsWith(prefix) ? Integer.valueOf(name.substring(prefix.length())) : null;
    }

    /** {@return tous les modificateurs posés, dans l'ordre de leur pose} */
    public List<Modifier> modifiers() {
        return entries.stream().map(entry -> entry.modifier).toList();
    }

    /**
     * {@return les modificateurs posés tels que les snapshots les portent (ADR-167 D8) : dans l'ordre de leur pose,
     * chacun avec son origine}
     *
     * @param tick le tick du serveur où cette liste a pris effet
     */
    public AssemblyState.Modifiers posed(long tick) {
        return posed(tick, List.of());
    }

    /**
     * {@return les modificateurs posés tels que les snapshots les portent, avec les contrôleurs que l'API a attachés
     * (ADR-167 D8, D9)}
     *
     * @param tick le tick du serveur où cette liste a pris effet
     * @param controllers les contrôleurs attachés par l'API, dans l'ordre de l'attache
     */
    public AssemblyState.Modifiers posed(long tick, List<String> controllers) {
        return new AssemblyState.Modifiers(tick, entries.stream()
                .map(entry -> new AssemblyState.Posed(entry.modifier, ORIGINS.indexOf(entry.origin)))
                .toList(), controllers);
    }

    /**
     * {@return le miroir, chez le client, des paramètres d'une assembly (ADR-167 D8)} Les modificateurs reçus se
     * rejouent dans leur ordre sur la definition que le client a reçue, par la règle du serveur : le client compose
     * les mêmes valeurs, au bit près, et retrouve les mêmes actifs. Rien ne s'y journalise : le serveur l'a fait.
     *
     * @param tuning les paramètres de la definition reçue
     * @param received les modificateurs du dernier snapshot
     * @param variables les variables reçues avec lui, qui règlent les modificateurs déclarés
     */
    public static AssemblyParameters mirror(Tuning tuning, AssemblyState.Modifiers received, double[] variables) {
        AssemblyParameters mirror = new AssemblyParameters(tuning);
        mirror.vary(variables);
        mirror.restore(received.posed().stream().map(AssemblyState.Posed::modifier).toList(),
                received.posed().stream().map(posed -> ORIGINS.get(posed.origin())).toList());
        return mirror;
    }

    /**
     * {@return tous les modificateurs, avec leur origine et leur état : les déclarés d'abord, dans l'ordre de la
     * definition, puis les posés, dans l'ordre de leur pose, puis les sorties des contrôleurs de ce tick}
     */
    public List<Parameters.Applied> applied() {
        List<Parameters.Applied> applied =
                new ArrayList<>(declared.size() + entries.size() + controlled.size() + engine.size());
        for (List<Entry> group : List.of(declared, entries, controlled, engine)) {
            for (Entry entry : group) {
                applied.add(new Parameters.Applied(entry.modifier, entry.origin, entry.active, entry.reason));
            }
        }
        return List.copyOf(applied);
    }

    /**
     * Pose un modificateur, ou remplace celui que sa cause portait déjà sur ce paramètre, à sa place dans la pose.
     *
     * @param modifier le modificateur
     * @param origin d'où il vient, pour le diagnostic
     * @throws IllegalArgumentException si la cause est réservée au moteur ou à un modificateur déclaré, si le
     *     paramètre n'existe pas, si le plafond est atteint, ou si le rejeu le laisserait inactif ou rendrait inactif un
     *     modificateur actif ; rien n'est changé
     */
    public void modify(Modifier modifier, String origin) {
        if (modifier.cause().startsWith(ENGINE_NAMESPACE)) {
            throw new IllegalArgumentException("cause « " + modifier.cause()
                    + " » : l'espace de noms axion est réservé aux causes du moteur");
        }
        for (Tuning.Declared one : tuning.declared()) {
            if (one.cause().equals(modifier.cause())) {
                throw new IllegalArgumentException("cause « " + modifier.cause()
                        + " » : celle d'un modificateur que la definition déclare ; il suit sa variable");
            }
        }
        int index = tuning.indexOf(modifier.parameter());
        if (index < 0) {
            throw new IllegalArgumentException("paramètre « " + modifier.parameter()
                    + " » inconnu de cette assembly ; elle a : " + String.join(", ", tuning.names()));
        }
        int existing = find(modifier.cause(), modifier.parameter());
        if (existing < 0 && entries.size() >= MAX_MODIFIERS) {
            throw new IllegalArgumentException("cause « " + modifier.cause() + " » : l'assembly porte déjà "
                    + MAX_MODIFIERS + " modificateurs, le plafond");
        }
        List<Entry> before = entries.stream().map(Entry::copy).toList();
        Entry posed = new Entry(modifier, origin);
        if (existing < 0) {
            entries.add(posed);
        } else {
            entries.set(existing, posed);
        }
        replay(index);
        String refusal = !posed.active ? posed.reason : deactivated(before, modifier.parameter());
        if (refusal != null) {
            entries.clear();
            entries.addAll(before);
            replay(index);
            throw new IllegalArgumentException(refusal);
        }
        version++;
    }

    /** {@return pourquoi le rejeu a rendu inactif un modificateur qui était actif avant, {@code null} sinon} */
    private String deactivated(List<Entry> before, String parameter) {
        for (Entry old : before) {
            if (!old.active || !old.modifier.parameter().equals(parameter)) {
                continue;
            }
            int now = find(old.modifier.cause(), parameter);
            if (now >= 0 && !entries.get(now).active) {
                return "rendrait inactif le modificateur de « " + old.modifier.cause() + " » : "
                        + entries.get(now).reason;
            }
        }
        return null;
    }

    /**
     * Retire tous les modificateurs d'une cause.
     *
     * @param cause son identifiant
     * @return vrai si elle en portait
     */
    public boolean remove(String cause) {
        List<String> touched = new ArrayList<>();
        entries.removeIf(entry -> {
            boolean match = entry.modifier.cause().equals(cause);
            if (match) {
                touched.add(entry.modifier.parameter());
            }
            return match;
        });
        return replayed(touched);
    }

    /**
     * Retire le modificateur d'une cause sur un paramètre.
     *
     * @param cause son identifiant
     * @param parameter le paramètre
     * @return vrai s'il existait
     */
    public boolean remove(String cause, String parameter) {
        int at = find(cause, parameter);
        if (at < 0) {
            return false;
        }
        entries.remove(at);
        return replayed(List.of(parameter));
    }

    private boolean replayed(List<String> parameters) {
        if (parameters.isEmpty()) {
            return false;
        }
        for (String parameter : parameters) {
            int index = tuning.indexOf(parameter);
            if (index >= 0) {
                replay(index);
            }
        }
        version++;
        return true;
    }

    /**
     * Reprend une liste de modificateurs — relus de la sauvegarde, ou ceux d'une definition d'avant —, dans leur
     * ordre, à la place des modificateurs posés. Ce que les bornes de la definition refusent, ou qui vise un paramètre
     * qu'elle n'a plus, reste posé mais inactif : rien n'est jeté. Au-delà du plafond, le reste est écarté.
     *
     * @param modifiers les modificateurs, dans l'ordre de leur pose
     * @param origin d'où ils viennent, pour le diagnostic
     * @return une ligne par modificateur inactif ou écarté, à journaliser
     */
    public List<String> restore(List<Modifier> modifiers, String origin) {
        return restore(modifiers, modifiers.stream().map(modifier -> origin).toList());
    }

    /**
     * Reprend les modificateurs d'une table d'avant — la definition a été recompilée —, avec leur origine : comme
     * {@link #restore(List, String)}.
     *
     * @param previous la table d'avant
     * @return une ligne par modificateur inactif ou écarté, à journaliser
     */
    public List<String> adopt(AssemblyParameters previous) {
        return adopt(previous, java.util.function.UnaryOperator.identity());
    }

    /**
     * Comme {@link #adopt(AssemblyParameters)}, d'une definition à la suivante par ses migrations (ADR-175 D3) : chaque
     * modificateur vise le paramètre sous son nom nouveau.
     *
     * @param previous la table d'avant
     * @param renamed le nom nouveau d'un paramètre d'avant
     * @return une ligne par modificateur inactif ou écarté, à journaliser
     */
    public List<String> adopt(AssemblyParameters previous, java.util.function.UnaryOperator<String> renamed) {
        List<Modifier> carried = previous.modifiers().stream()
                .map(modifier -> new Modifier(modifier.cause(), renamed.apply(modifier.parameter()),
                        modifier.operation(), modifier.amount()))
                .toList();
        return restore(carried, previous.entries.stream().map(entry -> entry.origin).toList());
    }

    private List<String> restore(List<Modifier> modifiers, List<String> origins) {
        entries.clear();
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < modifiers.size(); i++) {
            Modifier modifier = modifiers.get(i);
            String origin = origins.get(i);
            if (modifier.cause().startsWith(ENGINE_NAMESPACE)) {
                // Une cause du moteur ne se relit pas d'une liste : le moteur la recalcule (obligation 3.13).
                lines.add("modificateur de « " + modifier.cause() + " » écarté : l'espace de noms axion est réservé"
                        + " aux causes du moteur");
            } else if (find(modifier.cause(), modifier.parameter()) >= 0) {
                entries.set(find(modifier.cause(), modifier.parameter()), new Entry(modifier, origin));
            } else if (entries.size() < MAX_MODIFIERS) {
                entries.add(new Entry(modifier, origin));
            } else {
                lines.add("modificateur de « " + modifier.cause() + " » sur « " + modifier.parameter()
                        + " » écarté : plafond de " + MAX_MODIFIERS + " atteint");
            }
        }
        for (int i = 0; i < tuning.size(); i++) {
            replay(i);
        }
        for (Entry entry : entries) {
            if (tuning.indexOf(entry.modifier.parameter()) < 0) {
                entry.active = false;
                entry.reason = "paramètre « " + entry.modifier.parameter() + " » absent de la definition";
            }
            if (!entry.active) {
                lines.add("modificateur de « " + entry.modifier.cause() + " » sur « " + entry.modifier.parameter()
                        + " » gardé inactif : " + entry.reason);
            }
        }
        version++;
        return lines;
    }

    /**
     * Rejoue un paramètre : ses modificateurs, parcourus dans l'ordre de la pose, deviennent actifs un à un tant que la
     * valeur reste dans les bornes ; le parcours recommence tant qu'il en active un, si bien qu'un modificateur posé
     * avant celui qui le fait rentrer dans ses bornes s'active aussi. Puis sa valeur, et la raison de chaque inactif.
     * Enfin les sorties des contrôleurs, en un parcours, sur ce que les autres causes ont laissé actif (D9).
     */
    private void replay(int index) {
        Tuning.Parameter parameter = tuning.get(index);
        // D4 : les déclarés d'abord, dans l'ordre de la definition, puis les posés, dans l'ordre de leur pose.
        List<Entry> mine = java.util.stream.Stream.concat(declared.stream(), entries.stream())
                .filter(entry -> entry.modifier.parameter().equals(parameter.name()))
                .toList();
        List<Modifier> active = new ArrayList<>();
        for (Entry entry : mine) {
            entry.active = false;
        }
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Entry entry : mine) {
                if (entry.active) {
                    continue;
                }
                active.add(entry.modifier);
                if (parameter.refusal(compose(parameter.base(), active)) == null) {
                    entry.active = true;
                    grew = true;
                } else {
                    active.remove(active.size() - 1);
                }
            }
        }
        for (Entry entry : mine) {
            if (entry.active) {
                entry.reason = null;
            } else {
                List<Modifier> with = new ArrayList<>(active);
                with.add(entry.modifier);
                entry.reason = parameter.refusal(compose(parameter.base(), with));
            }
        }
        // ADR-176 D2 : les causes du moteur passent après les réglages, bornées par la seule limite du paramètre, et font
        // partie de ce que les contrôleurs lisent : la masse que ceux-ci voient est celle du véhicule plein ou à demi.
        for (Entry entry : engine) {
            if (!entry.modifier.parameter().equals(parameter.name())) {
                continue;
            }
            active.add(entry.modifier);
            entry.reason = parameter.limitRefusal(compose(parameter.base(), active));
            entry.active = entry.reason == null;
            if (!entry.active) {
                active.remove(active.size() - 1);
            }
        }
        uncontrolled[index] = compose(parameter.base(), active);
        for (Entry entry : controlled) {
            if (!entry.modifier.parameter().equals(parameter.name())) {
                continue;
            }
            active.add(entry.modifier);
            entry.reason = parameter.refusal(compose(parameter.base(), active));
            entry.active = entry.reason == null;
            if (!entry.active) {
                active.remove(active.size() - 1);
            }
        }
        values[index] = compose(parameter.base(), active);
    }

    /**
     * {@return la composition d'une base avec des modificateurs} (D3) : la seule fonction qui calcule une valeur
     * effective, au serveur comme chez le client.
     *
     * @param base la base
     * @param modifiers les modificateurs actifs, dans un ordre quelconque
     */
    static double compose(double base, List<Modifier> modifiers) {
        return Tuning.compose(base, modifiers);
    }

    private int find(String cause, String parameter) {
        for (int i = 0; i < entries.size(); i++) {
            Modifier modifier = entries.get(i).modifier;
            if (modifier.cause().equals(cause) && modifier.parameter().equals(parameter)) {
                return i;
            }
        }
        return -1;
    }
}
