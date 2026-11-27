package dev.axion.forge;

import dev.axion.entity.AssemblyBinding;
import dev.axion.entity.CustomTable;
import dev.axion.net.SnapshotMessage;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.LongSupplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

/**
 * NBT et données d'apparition d'une AxionEntity (PARTIE 22.2, R-703).
 *
 * <p>Toutes les clés d'AXION commencent par {@code axion:}. L'entité en garde
 * une copie et la réécrit <strong>telle quelle</strong> : seules version,
 * definition et état ({@link PersistedState}) ont un composant qui les écrit, mais
 * une clé posée par une version ultérieure ne doit pas disparaître parce que
 * cette version-ci ne la comprend pas (R-1710).
 */
final class AssemblyTags {

    /** Préfixe de toute clé NBT d'AXION. */
    static final String PREFIX = "axion:";

    /** Version du schéma NBT. */
    static final String VERSION = "axion:v";

    /** Identifiant 64 bits de la definition. */
    static final String DEFINITION = "axion:def";

    /** Révision de la definition à laquelle l'état a été sauvegardé : un entier (ADR-175 D3). */
    static final String REVISION = "axion:rev";

    /** Empreinte SHA-256 du contenu de la definition à la sauvegarde : un texte hexadécimal (ADR-175 D3). */
    static final String SHA = "axion:sha";

    /** Orientation du corps : quaternion {@code (x, y, z, w)}, quatre flottants (R-461). */
    static final String ROTATION = "axion:rot";

    /** Données personnalisées des mods : une table CBOR, absente si elle est vide (ADR-153 D7). */
    static final String CUSTOM = "axion:custom";

    /** Variables gardées (ADR-163 D2) : à chaque nom de variable {@code persist}, sa valeur ; absente sans elles. */
    static final String VARIABLES = "axion:vars";

    /**
     * Modificateurs posés (ADR-167 D7) : la liste, dans l'ordre de leur pose, de ceux que l'API ou la commande ont
     * posés ; absente sans eux.
     */
    static final String PARAMETERS = "axion:params";

    /** Figée (ADR-183 D3) : un booléen vrai ; absente sans le gel. */
    static final String FROZEN = "axion:frozen";

    /** Le carburant et la batterie (ADR-176 D3) : {@code {fuel, battery}}, en {@code double}. */
    static final String ENERGY = "axion:energy";

    /** L'usure des pneus (ADR-176 D4) : un compound {@code <node de la roue> : double}, par nom de roue. */
    static final String WHEELS = "axion:wheels";

    /**
     * Contrôleurs de véhicule attachés par l'API (ADR-167 D7, D9) : leurs identifiants, dans l'ordre de l'attache ;
     * absente sans eux.
     */
    static final String CONTROLLERS = "axion:ctrls";

    /**
     * Attaches que l'assembly a posées (ADR-171 D7, R-692) : socket, autre extrémité ou bloc, type, longueur, mod ;
     * absente sans elles.
     */
    static final String ATTACHMENTS = "axion:atts";

    /** Un nom de socket ou de type gardé : la forme d'un nom de la definition, borné. */
    private static final java.util.regex.Pattern ATTACHMENT_NAME = java.util.regex.Pattern.compile("[ -~]{1,64}");

    /** La forme de l'identifiant d'un contrôleur. */
    private static final java.util.regex.Pattern CONTROLLER_ID =
            java.util.regex.Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    /** En deçà, un quaternion n'a pas de direction : il ne décrit aucune rotation. */
    private static final float MIN_QUATERNION_NORM_SQUARED = 1e-6f;

    /** Écart du carré de la norme à 1 en deçà duquel un quaternion est tenu pour unitaire. */
    private static final float UNIT_TOLERANCE = 1e-6f;

    /** Longueur maximale d'un identifiant transmis, celle des chaînes réseau de Minecraft. */
    static final int MAX_ID_CHARS = 32767;

    /**
     * Ce que le client reçoit à l'apparition : {@code S2C_AssemblySpawn} (ADR-150 D2), la position mise à
     * part, que Minecraft porte.
     *
     * @param definitionId identifiant de la definition, ou description d'une
     *     definition inconnue
     * @param inert l'entité est inerte
     * @param rotation orientation du corps
     * @param boundsMin coin minimal de l'emprise, relatif à la position, ou {@code null} si aucune
     * @param boundsMax coin maximal de l'emprise, relatif à la position, ou {@code null} si aucune
     */
    record SpawnState(String definitionId, boolean inert, Quaternionf rotation, Vector3f boundsMin, Vector3f boundsMax) {}

    private AssemblyTags() {}

    /** {@return une copie des clés {@code axion:*} d'un NBT d'entité} */
    static CompoundTag extract(CompoundTag entity) {
        CompoundTag stored = new CompoundTag();
        for (String key : entity.getAllKeys()) {
            if (key.startsWith(PREFIX)) {
                Tag value = entity.get(key);
                if (value != null) {
                    stored.put(key, value.copy());
                }
            }
        }
        return stored;
    }

    /** Réécrit les clés gardées dans un NBT d'entité. */
    static void restore(CompoundTag stored, CompoundTag entity) {
        for (String key : stored.getAllKeys()) {
            Tag value = stored.get(key);
            if (value != null) {
                entity.put(key, value.copy());
            }
        }
    }

    /** {@return la version de schéma, si le NBT en porte une entière} */
    static OptionalInt version(CompoundTag stored) {
        return stored.contains(VERSION, Tag.TAG_INT)
                ? OptionalInt.of(stored.getInt(VERSION))
                : OptionalInt.empty();
    }

    /** {@return l'identifiant de definition, si le NBT en porte un} */
    static OptionalLong definition(CompoundTag stored) {
        return stored.contains(DEFINITION, Tag.TAG_LONG)
                ? OptionalLong.of(stored.getLong(DEFINITION))
                : OptionalLong.empty();
    }

    /**
     * {@return la table des données personnalisées gardée, vide si le NBT n'en porte pas} Une clé
     * {@code axion:custom} qui n'est pas un tableau d'octets rend la table illisible : gardée telle
     * quelle, rien ne s'y écrit.
     *
     * @param limit plafond {@code persistence.max_custom_bytes}, en octets
     * @param readOnly vrai pour la table d'une assembly inerte
     */
    static CustomTable custom(CompoundTag stored, LongSupplier limit, boolean readOnly) {
        if (!stored.contains(CUSTOM)) {
            return CustomTable.read(null, limit, readOnly);
        }
        if (!stored.contains(CUSTOM, Tag.TAG_BYTE_ARRAY)) {
            return CustomTable.unreadable(CUSTOM + " n'est pas un tableau d'octets");
        }
        return CustomTable.read(stored.getByteArray(CUSTOM), limit, readOnly);
    }

    /**
     * Écrit la table des données personnalisées si une écriture l'a changée, et la retire si elle est
     * vide ; inchangée, la clé lue reste telle quelle.
     */
    static void putCustom(CompoundTag stored, CustomTable table) {
        if (table.changed()) {
            table.encoded().ifPresentOrElse(bytes -> stored.putByteArray(CUSTOM, bytes), () -> stored.remove(CUSTOM));
        }
    }

    /**
     * {@return les clés gardées, migrées au schéma courant quand une fonction explicite le permet
     * (R-1710)} Toute autre version reste telle quelle : postérieure, l'entité sera inerte et réécrite
     * à l'identique ; antérieure à 2, aucune version d'AXION ne l'a écrite.
     */
    static CompoundTag migrate(CompoundTag stored) {
        CompoundTag migrated = version(stored).orElse(-1) == 2 ? migrate2To3(stored) : stored;
        migrated = version(migrated).orElse(-1) == 3 ? migrate3To4(migrated) : migrated;
        migrated = version(migrated).orElse(-1) == 4 ? migrate4To5(migrated) : migrated;
        return version(migrated).orElse(-1) == 5 ? migrate5To6(migrated) : migrated;
    }

    /**
     * Le schéma 5 passe au 6 (ADR-175 D1) : ses corps et ses joints n'ont pas de nom. La migration n'en invente pas : la
     * restauration les nomme d'après la definition chargée, qui lit les index contre elle ({@code StateMatching}). Toute
     * clé est gardée ; seule la version change.
     */
    private static CompoundTag migrate5To6(CompoundTag stored) {
        CompoundTag migrated = stored.copy();
        migrated.putInt(VERSION, 6);
        return migrated;
    }

    /**
     * Le schéma 4 passe au 5 (ADR-167 D7) : il ne portait pas de modificateurs, et la migration n'en invente pas. Toute
     * clé est gardée ; seule la version change.
     */
    private static CompoundTag migrate4To5(CompoundTag stored) {
        CompoundTag migrated = stored.copy();
        migrated.putInt(VERSION, 5);
        return migrated;
    }

    /**
     * {@return les modificateurs gardés, dans l'ordre de leur pose ; vide sans {@code axion:params}} Une entrée
     * illisible — un champ manquant, une opération inconnue, une cause mal formée, une quantité non finie — est
     * ignorée : un NBT ne fait pas foi (obligation 3.13).
     */
    static java.util.List<dev.axion.api.assembly.Modifier> parameters(CompoundTag stored) {
        java.util.List<dev.axion.api.assembly.Modifier> saved = new java.util.ArrayList<>();
        if (!stored.contains(PARAMETERS, Tag.TAG_LIST)) {
            return saved;
        }
        ListTag list = stored.getList(PARAMETERS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.contains("cause", Tag.TAG_STRING) || !entry.contains("parameter", Tag.TAG_STRING)
                    || !entry.contains("operation", Tag.TAG_STRING) || !entry.contains("amount", Tag.TAG_DOUBLE)) {
                continue;
            }
            try {
                saved.add(new dev.axion.api.assembly.Modifier(entry.getString("cause"), entry.getString("parameter"),
                        dev.axion.api.assembly.Modifier.Operation.valueOf(
                                entry.getString("operation").toUpperCase(java.util.Locale.ROOT)),
                        entry.getDouble("amount")));
            } catch (IllegalArgumentException illisible) {
                // Ni cause ni opération reconnues : l'entrée ne décrit aucun modificateur.
            }
        }
        return saved;
    }

    /**
     * {@return les contrôleurs attachés gardés, dans l'ordre de l'attache ; vide sans {@code axion:ctrls}} Une entrée
     * illisible — pas un texte, un identifiant mal formé ou trop long, un doublon — est ignorée, et au-delà de
     * {@link dev.axion.net.AssemblyState.Modifiers#MAX_CONTROLLERS} le reste aussi : un NBT ne fait pas foi (obligation 3.13).
     */
    static java.util.List<String> controllers(CompoundTag stored) {
        java.util.List<String> saved = new java.util.ArrayList<>();
        if (!stored.contains(CONTROLLERS, Tag.TAG_LIST)) {
            return saved;
        }
        ListTag list = stored.getList(CONTROLLERS, Tag.TAG_STRING);
        for (int i = 0; i < list.size() && saved.size() < dev.axion.net.AssemblyState.Modifiers.MAX_CONTROLLERS; i++) {
            String id = list.getString(i);
            if (id.length() <= dev.axion.api.assembly.Modifier.MAX_LENGTH && CONTROLLER_ID.matcher(id).matches()
                    && !saved.contains(id)) {
                saved.add(id);
            }
        }
        return saved;
    }

    /**
     * {@return les attaches gardées, dans l'ordre où elles ont été posées ; vide sans {@code axion:atts}} Une entrée
     * illisible — un socket, un type ou un mod absents ou mal formés, ni autre assembly ni bloc — est ignorée, et au-delà
     * de {@link dev.axion.attach.AttachmentRecord#MAX} le reste aussi : un NBT ne fait pas foi (obligation 3.13).
     */
    static java.util.List<dev.axion.attach.AttachmentRecord> attachments(CompoundTag stored) {
        java.util.List<dev.axion.attach.AttachmentRecord> saved = new java.util.ArrayList<>();
        if (!stored.contains(ATTACHMENTS, Tag.TAG_LIST)) {
            return saved;
        }
        ListTag list = stored.getList(ATTACHMENTS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && saved.size() < dev.axion.attach.AttachmentRecord.MAX; i++) {
            CompoundTag entry = list.getCompound(i);
            String socket = entry.getString("socket");
            String kind = entry.getString("kind");
            String owner = entry.getString("owner");
            if (!ATTACHMENT_NAME.matcher(socket).matches() || !ATTACHMENT_NAME.matcher(kind).matches()
                    || !ATTACHMENT_NAME.matcher(owner).matches()) {
                continue;
            }
            double length = entry.contains("length", Tag.TAG_DOUBLE) ? entry.getDouble("length") : Double.NaN;
            if (entry.hasUUID("other")) {
                String otherSocket = entry.getString("other_socket");
                if (ATTACHMENT_NAME.matcher(otherSocket).matches()) {
                    // ADR-179 : absente ou mal formée, la révision est inconnue et le nom se lit tel quel.
                    int revision = entry.contains("other_rev", Tag.TAG_INT)
                            ? Math.max(0, entry.getInt("other_rev")) : 0;
                    saved.add(new dev.axion.attach.AttachmentRecord(socket, entry.getUUID("other"), otherSocket, null,
                            kind, length, owner, revision));
                }
            } else if (entry.contains("anchor", Tag.TAG_INT_ARRAY) && entry.getIntArray("anchor").length == 3) {
                saved.add(new dev.axion.attach.AttachmentRecord(socket, null, null, entry.getIntArray("anchor"), kind,
                        length, owner, 0));
            }
        }
        return saved;
    }

    /**
     * Écrit les attaches posées par l'assembly, dans l'ordre ; sans aucune, la clé est retirée.
     *
     * @param stored les clés gardées
     * @param records les attaches
     */
    static void putAttachments(CompoundTag stored, java.util.List<dev.axion.attach.AttachmentRecord> records) {
        if (records.isEmpty()) {
            stored.remove(ATTACHMENTS);
            return;
        }
        ListTag list = new ListTag();
        for (dev.axion.attach.AttachmentRecord record : records) {
            CompoundTag entry = new CompoundTag();
            entry.putString("socket", record.socket());
            if (record.anchored()) {
                entry.putIntArray("anchor", record.anchor());
            } else {
                entry.putUUID("other", record.other());
                entry.putString("other_socket", record.otherSocket());
                if (record.otherRevision() > 0) {
                    entry.putInt("other_rev", record.otherRevision());
                }
            }
            entry.putString("kind", record.kind());
            if (Double.isFinite(record.length())) {
                entry.putDouble("length", record.length());
            }
            entry.putString("owner", record.owner());
            list.add(entry);
        }
        stored.put(ATTACHMENTS, list);
    }

    /**
     * Écrit les contrôleurs attachés par l'API, dans l'ordre de l'attache ; sans aucun, la clé est retirée.
     *
     * @param stored les clés gardées
     * @param controllers les identifiants des contrôleurs
     */
    static void putControllers(CompoundTag stored, java.util.List<String> controllers) {
        if (controllers.isEmpty()) {
            stored.remove(CONTROLLERS);
            return;
        }
        ListTag list = new ListTag();
        for (String id : controllers) {
            list.add(net.minecraft.nbt.StringTag.valueOf(id));
        }
        stored.put(CONTROLLERS, list);
    }

    /**
     * Écrit l'usure des pneus, par nom de roue (ADR-176 D4, CAP-38) ; sans aucune, la clé est retirée.
     *
     * @param stored les clés gardées
     * @param wear l'usure de chaque roue, par le nom de son node, dans [0, 1]
     */
    static void putWheels(CompoundTag stored, Map<String, Double> wear) {
        if (wear.isEmpty()) {
            stored.remove(WHEELS);
            return;
        }
        CompoundTag wheels = new CompoundTag();
        wear.forEach(wheels::putDouble);
        stored.put(WHEELS, wheels);
    }

    /**
     * {@return l'usure gardée, par nom de roue, dans l'ordre de la sauvegarde ; vide sans {@code axion:wheels}} Une valeur
     * absente, non finie ou négative vaut zéro, et au-delà de 1 elle vaut 1 : un NBT ne fait pas foi (obligation 3.13).
     *
     * @param stored les clés gardées
     */
    static Map<String, Double> wheels(CompoundTag stored) {
        Map<String, Double> saved = new java.util.LinkedHashMap<>();
        if (!stored.contains(WHEELS, Tag.TAG_COMPOUND)) {
            return saved;
        }
        CompoundTag wheels = stored.getCompound(WHEELS);
        for (String name : wheels.getAllKeys()) {
            double value = wheels.contains(name, Tag.TAG_DOUBLE) ? wheels.getDouble(name) : 0.0;
            saved.put(name, Double.isFinite(value) ? Math.max(0.0, Math.min(1.0, value)) : 0.0);
        }
        return saved;
    }

    /**
     * Écrit le contenu des réserves, par nom (ADR-176 D3, ADR-182 D6) : au schéma 7, {@code fuel} et {@code battery}.
     *
     * @param stored les clés gardées
     * @param amounts le contenu de chaque réserve, par nom, celles que la definition n'a plus comprises
     */
    static void putEnergy(CompoundTag stored, Map<String, Double> amounts) {
        CompoundTag energy = new CompoundTag();
        amounts.forEach(energy::putDouble);
        stored.put(ENERGY, energy);
    }

    /**
     * {@return le contenu gardé des réserves, par nom, dans l'ordre du compound, ou {@code null} sans {@code axion:energy}}
     * Une valeur non finie ou négative vaut zéro : un NBT ne fait pas foi (obligation 3.13) ; la capacité de la definition
     * la borne ensuite. Une clé qui n'est pas un nombre est ignorée.
     *
     * @param stored les clés gardées
     */
    static Map<String, Double> energy(CompoundTag stored) {
        if (!stored.contains(ENERGY, Tag.TAG_COMPOUND)) {
            return null;
        }
        CompoundTag energy = stored.getCompound(ENERGY);
        Map<String, Double> saved = new java.util.TreeMap<>();
        for (String name : energy.getAllKeys()) {
            if (energy.contains(name, Tag.TAG_DOUBLE)) {
                double value = energy.getDouble(name);
                saved.put(name, Double.isFinite(value) && value > 0.0 ? value : 0.0);
            }
        }
        return saved;
    }

    /**
     * Écrit les modificateurs posés, dans l'ordre de leur pose ; sans aucun, la clé est retirée.
     *
     * @param stored les clés gardées
     * @param modifiers les modificateurs, actifs ou non
     */
    static void putParameters(CompoundTag stored, java.util.List<dev.axion.api.assembly.Modifier> modifiers) {
        if (modifiers.isEmpty()) {
            stored.remove(PARAMETERS);
            return;
        }
        ListTag list = new ListTag();
        for (dev.axion.api.assembly.Modifier modifier : modifiers) {
            CompoundTag entry = new CompoundTag();
            entry.putString("cause", modifier.cause());
            entry.putString("parameter", modifier.parameter());
            entry.putString("operation", modifier.operation().key());
            entry.putDouble("amount", modifier.amount());
            list.add(entry);
        }
        stored.put(PARAMETERS, list);
    }

    /**
     * Le schéma 3 passe au 4 (ADR-163 D2) : il ne portait pas de variables, qui repartent de leur défaut. Toute clé
     * est gardée ; seule la version change.
     */
    private static CompoundTag migrate3To4(CompoundTag stored) {
        CompoundTag migrated = stored.copy();
        migrated.putInt(VERSION, 4);
        return migrated;
    }

    /** L'état des nodes que les actions ont changé, par nom (ADR-174 D3). */
    static final String NODES = "axion:nodes";

    /**
     * L'état d'un node tel que le NBT le garde (ADR-174 D3).
     *
     * @param hidden caché
     * @param emissive émission posée, {@code NaN} sans
     * @param color teinte posée, {@code null} sans
     * @param swap le nom du node montré à sa place, {@code null} sans
     */
    record SavedNode(boolean hidden, float emissive, float[] color, String swap) {}

    /**
     * {@return l'état gardé de chaque node, par nom ; vide sans {@code axion:nodes}} Une entrée mal formée est ignorée.
     *
     * @param stored les clés gardées
     */
    static Map<String, SavedNode> nodes(CompoundTag stored) {
        Map<String, SavedNode> saved = new LinkedHashMap<>();
        if (stored.contains(NODES, Tag.TAG_COMPOUND)) {
            CompoundTag nodes = stored.getCompound(NODES);
            for (String name : nodes.getAllKeys()) {
                if (!nodes.contains(name, Tag.TAG_COMPOUND)) {
                    continue;
                }
                CompoundTag node = nodes.getCompound(name);
                float emissive = node.contains("emissive", Tag.TAG_FLOAT) ? node.getFloat("emissive") : Float.NaN;
                float[] color = null;
                if (node.contains("color", Tag.TAG_LIST)) {
                    net.minecraft.nbt.ListTag rgb = node.getList("color", Tag.TAG_FLOAT);
                    if (rgb.size() == 3) {
                        color = new float[] {rgb.getFloat(0), rgb.getFloat(1), rgb.getFloat(2)};
                    }
                }
                String swap = node.contains("swap", Tag.TAG_STRING) ? node.getString("swap") : null;
                if (Float.isFinite(emissive) || Float.isNaN(emissive)) {
                    saved.put(name, new SavedNode(node.getBoolean("hidden"), emissive, color, swap));
                }
            }
        }
        return saved;
    }

    /**
     * Écrit l'état des nodes. Un node que la definition ne nomme plus garde l'état lu (R-1710) ; un node connu absent
     * de {@code current} est retiré ; sans aucun, la clé est retirée.
     *
     * @param stored les clés gardées
     * @param current l'état de chaque node connu qui n'est pas à son état de départ, par nom
     * @param known les nodes que la definition nomme
     */
    static void putNodes(CompoundTag stored, Map<String, SavedNode> current, java.util.Set<String> known) {
        CompoundTag nodes = stored.contains(NODES, Tag.TAG_COMPOUND) ? stored.getCompound(NODES).copy()
                : new CompoundTag();
        for (String name : known) {
            nodes.remove(name);
        }
        current.forEach((name, state) -> {
            CompoundTag node = new CompoundTag();
            node.putBoolean("hidden", state.hidden());
            if (!Float.isNaN(state.emissive())) {
                node.putFloat("emissive", state.emissive());
            }
            if (state.color() != null) {
                net.minecraft.nbt.ListTag rgb = new net.minecraft.nbt.ListTag();
                for (float channel : state.color()) {
                    rgb.add(net.minecraft.nbt.FloatTag.valueOf(channel));
                }
                node.put("color", rgb);
            }
            if (state.swap() != null) {
                node.putString("swap", state.swap());
            }
            nodes.put(name, node);
        });
        if (nodes.isEmpty()) {
            stored.remove(NODES);
        } else {
            stored.put(NODES, nodes);
        }
    }

    /**
     * {@return les variables gardées, par nom ; vide sans {@code axion:vars}} Une entrée qui n'est pas un
     * {@code double} est ignorée.
     */
    static Map<String, Double> variables(CompoundTag stored) {
        Map<String, Double> saved = new LinkedHashMap<>();
        if (stored.contains(VARIABLES, Tag.TAG_COMPOUND)) {
            CompoundTag variables = stored.getCompound(VARIABLES);
            for (String name : variables.getAllKeys()) {
                if (variables.contains(name, Tag.TAG_DOUBLE)) {
                    saved.put(name, variables.getDouble(name));
                }
            }
        }
        return saved;
    }

    /**
     * Écrit les variables gardées. Une variable que la definition ne déclare plus garde la valeur lue (R-1710) ;
     * sans aucune, la clé est retirée.
     *
     * @param stored les clés gardées
     * @param persisted la valeur de chaque variable {@code persist}, par nom
     */
    static void putVariables(CompoundTag stored, Map<String, Double> persisted) {
        CompoundTag variables = stored.contains(VARIABLES, Tag.TAG_COMPOUND)
                ? stored.getCompound(VARIABLES).copy()
                : new CompoundTag();
        persisted.forEach(variables::putDouble);
        if (variables.isEmpty()) {
            stored.remove(VARIABLES);
        } else {
            stored.put(VARIABLES, variables);
        }
    }

    /**
     * {@return les valeurs gardées des variables par siège (ADR-174 D2), par nom puis par nom de siège} Une variable
     * par siège se garde dans {@code axion:vars} comme une table de ses sièges.
     *
     * @param stored les clés gardées
     */
    static Map<String, Map<String, Double>> seatVariables(CompoundTag stored) {
        Map<String, Map<String, Double>> saved = new LinkedHashMap<>();
        if (stored.contains(VARIABLES, Tag.TAG_COMPOUND)) {
            CompoundTag variables = stored.getCompound(VARIABLES);
            for (String name : variables.getAllKeys()) {
                if (variables.contains(name, Tag.TAG_COMPOUND)) {
                    CompoundTag seats = variables.getCompound(name);
                    Map<String, Double> bySeat = new LinkedHashMap<>();
                    for (String seat : seats.getAllKeys()) {
                        if (seats.contains(seat, Tag.TAG_DOUBLE)) {
                            bySeat.put(seat, seats.getDouble(seat));
                        }
                    }
                    saved.put(name, bySeat);
                }
            }
        }
        return saved;
    }

    /**
     * Écrit les variables par siège gardées (ADR-174 D2), chacune une table de ses sièges, à côté des autres.
     *
     * @param stored les clés gardées
     * @param persisted les valeurs, par nom puis par nom de siège
     */
    static void putSeatVariables(CompoundTag stored, Map<String, Map<String, Double>> persisted) {
        if (persisted.isEmpty()) {
            return;
        }
        CompoundTag variables = stored.contains(VARIABLES, Tag.TAG_COMPOUND)
                ? stored.getCompound(VARIABLES).copy()
                : new CompoundTag();
        persisted.forEach((name, bySeat) -> {
            CompoundTag seats = new CompoundTag();
            bySeat.forEach(seats::putDouble);
            variables.put(name, seats);
        });
        stored.put(VARIABLES, variables);
    }

    /** {@return la révision que l'état gardait, ou vide s'il n'en dit pas : celle d'une definition sans révision, 1} */
    static java.util.OptionalInt revision(CompoundTag stored) {
        return stored.contains(REVISION, Tag.TAG_INT) ? java.util.OptionalInt.of(stored.getInt(REVISION))
                : java.util.OptionalInt.empty();
    }

    /** {@return l'empreinte du contenu que l'état gardait, ou vide} */
    static java.util.Optional<String> sha(CompoundTag stored) {
        return stored.contains(SHA, Tag.TAG_STRING) ? java.util.Optional.of(stored.getString(SHA))
                : java.util.Optional.empty();
    }

    /**
     * Garde la révision et l'empreinte de la definition à laquelle l'état est écrit (ADR-175 D3).
     *
     * @param stored les clés gardées
     * @param revision la révision de la definition
     * @param sha256 l'empreinte de son contenu
     */
    static void putRevision(CompoundTag stored, int revision, String sha256) {
        stored.putInt(REVISION, revision);
        stored.putString(SHA, sha256);
    }

    /**
     * Fait évoluer un état sauvegardé vers sa definition (ADR-175 D3) : lié à l'identifiant que la definition a pris
     * — {@code axion:def} le suit —, puis mené de la révision qu'il gardait à la sienne par les migrations qui les
     * séparent, qui renomment ce que l'état nomme. Un contenu qui a changé sans nouvelle révision se restaure par nom et
     * le dit ; un état d'une révision plus récente aussi.
     *
     * @param stored les clés gardées, migrées au schéma courant ; modifiées en place
     * @param revisions les révisions de la definition
     * @param sha256 l'empreinte de son contenu
     * @param definitionHash l'empreinte de son identifiant, que {@code axion:def} porte désormais
     * @param note ce qu'il faut dire au journal
     */
    static void evolve(CompoundTag stored, dev.axion.definition.Revisions revisions, String sha256, long definitionHash,
            java.util.function.Consumer<String> note) {
        long before = definition(stored).orElse(definitionHash);
        stored.putLong(DEFINITION, definitionHash);
        int saved = revision(stored).orElse(1);
        if (before != definitionHash) {
            note.accept("sauvegardée sous l'identifiant d'une definition qu'elle remplace, reliée à celle-ci");
        }
        if (saved < revisions.revision()) {
            java.util.List<dev.axion.definition.Revisions.Migration> chain = revisions.chain(saved);
            int renamed = 0;
            for (dev.axion.definition.Revisions.Migration migration : chain) {
                renamed += rename(stored, migration);
            }
            note.accept("révision " + saved + " → " + revisions.revision() + " : " + chain.size()
                    + " migration(s), " + renamed + " nom(s) changé(s)");
        } else if (saved > revisions.revision()) {
            note.accept("sauvegardée à la révision " + saved + ", plus récente que la révision "
                    + revisions.revision() + " de la definition : restaurée par nom");
        } else if (sha(stored).filter(kept -> !kept.equals(sha256)).isPresent()) {
            note.accept("le contenu de la definition a changé sans nouvelle révision : restaurée par nom");
        }
    }

    /**
     * Applique une migration aux noms que l'état garde (ADR-175 D3) : les nodes, des corps et de l'état des nodes ; les
     * joints ; les variables, et leurs sièges ; les sockets des attaches ; les paramètres des modificateurs ; les
     * réserves d'énergie (ADR-182 D6).
     *
     * @param stored les clés gardées ; modifiées en place
     * @param migration les renommages
     * @return le nombre de noms changés
     */
    static int rename(CompoundTag stored, dev.axion.definition.Revisions.Migration migration) {
        int count = 0;
        Map<String, String> nodes = migration.of("nodes");
        count += renameList(stored, PersistedState.BODY_NAMES, nodes);
        count += renameList(stored, PersistedState.JOINT_NAMES, migration.of("joints"));
        if (stored.contains(NODES, Tag.TAG_COMPOUND)) {
            CompoundTag states = stored.getCompound(NODES);
            for (String name : java.util.List.copyOf(states.getAllKeys())) {
                CompoundTag state = states.getCompound(name);
                if (state.contains("swap", Tag.TAG_STRING) && nodes.containsKey(state.getString("swap"))) {
                    state.putString("swap", nodes.get(state.getString("swap")));
                    count++;
                }
            }
            count += renameKeys(states, nodes);
        }
        if (stored.contains(VARIABLES, Tag.TAG_COMPOUND)) {
            CompoundTag variables = stored.getCompound(VARIABLES);
            for (String name : java.util.List.copyOf(variables.getAllKeys())) {
                if (variables.contains(name, Tag.TAG_COMPOUND)) {
                    count += renameKeys(variables.getCompound(name), migration.of("seats"));
                }
            }
            count += renameKeys(variables, migration.of("variables"));
        }
        if (stored.contains(WHEELS, Tag.TAG_COMPOUND)) {
            count += renameKeys(stored.getCompound(WHEELS), nodes);
        }
        if (stored.contains(ENERGY, Tag.TAG_COMPOUND)) {
            count += renameKeys(stored.getCompound(ENERGY), migration.of("stores"));
        }
        count += renameField(stored, ATTACHMENTS, "socket", migration.of("sockets"));
        count += renameField(stored, PARAMETERS, "parameter", migration.of("parameters"));
        return count;
    }

    private static int renameList(CompoundTag stored, String key, Map<String, String> table) {
        if (table.isEmpty() || !stored.contains(key, Tag.TAG_LIST)) {
            return 0;
        }
        ListTag names = stored.getList(key, Tag.TAG_STRING);
        ListTag renamed = new ListTag();
        int count = 0;
        for (int i = 0; i < names.size(); i++) {
            String name = names.getString(i);
            String to = table.get(name);
            renamed.add(net.minecraft.nbt.StringTag.valueOf(to == null ? name : to));
            count += to == null ? 0 : 1;
        }
        stored.put(key, renamed);
        return count;
    }

    /**
     * Renomme les clés d'un composé, toutes à la fois : chaque valeur est retirée avant qu'aucune ne soit reposée, pour
     * que deux noms puissent s'échanger et qu'une chaîne {@code a → b → c} ne perde rien, quel que soit l'ordre de la
     * table.
     */
    private static int renameKeys(CompoundTag compound, Map<String, String> table) {
        Map<String, net.minecraft.nbt.Tag> moved = new LinkedHashMap<>();
        table.forEach((from, to) -> {
            net.minecraft.nbt.Tag value = compound.get(from);
            if (value != null) {
                moved.put(to, value);
            }
        });
        table.keySet().forEach(compound::remove);
        moved.forEach(compound::put);
        return moved.size();
    }

    private static int renameField(CompoundTag stored, String key, String field, Map<String, String> table) {
        if (table.isEmpty() || !stored.contains(key, Tag.TAG_LIST)) {
            return 0;
        }
        int count = 0;
        ListTag list = stored.getList(key, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            String to = table.get(entry.getString(field));
            if (to != null) {
                entry.putString(field, to);
                count++;
            }
        }
        return count;
    }

    /**
     * Le schéma 2 passe au 3 (ADR-153 D5) : il ne portait ni vitesses, ni sommeil, ni corps secondaires,
     * ni état des joints, et la migration n'en invente pas — l'assembly repartira de sa pose de repos. Elle
     * garde {@code axion:def}, {@code axion:rot} et toute autre clé, et ne change que la version.
     */
    private static CompoundTag migrate2To3(CompoundTag stored) {
        CompoundTag migrated = stored.copy();
        migrated.putInt(VERSION, 3);
        return migrated;
    }

    /** {@return les clés gardées, liées à une definition au schéma courant} */
    static CompoundTag bound(CompoundTag stored, long definitionHash) {
        CompoundTag out = stored.copy();
        out.putInt(VERSION, AssemblyBinding.NBT_SCHEMA);
        out.putLong(DEFINITION, definitionHash);
        return out;
    }

    /**
     * {@return l'orientation gardée, normalisée, ou l'identité}
     *
     * <p>Un NBT sans orientation — entité d'avant cette clé, ou créée par commande — décrit
     * un corps droit. Une orientation inexploitable (mauvais type, composante non finie,
     * quaternion nul) aussi : un corps recréé avec une rotation absurde serait pire qu'un
     * corps redressé.
     */
    static Quaternionf rotation(CompoundTag stored) {
        ListTag list = stored.getList(ROTATION, Tag.TAG_FLOAT);
        if (list.size() == 4) {
            Quaternionf rotation =
                    new Quaternionf(list.getFloat(0), list.getFloat(1), list.getFloat(2), list.getFloat(3));
            float norm = rotation.lengthSquared();
            if (Float.isFinite(norm) && norm > MIN_QUATERNION_NORM_SQUARED) {
                // Unitaire au flottant près, elle est rendue au bit près (ADR-153 D2) : la renormaliser
                // changerait son dernier bit à chaque rechargement.
                return Math.abs(norm - 1f) <= UNIT_TOLERANCE ? rotation : rotation.normalize();
            }
        }
        return new Quaternionf();
    }

    /** Pose l'orientation dans les clés gardées (§22.2). */
    static void putRotation(CompoundTag stored, Quaternionfc rotation) {
        ListTag list = new ListTag();
        list.add(FloatTag.valueOf(rotation.x()));
        list.add(FloatTag.valueOf(rotation.y()));
        list.add(FloatTag.valueOf(rotation.z()));
        list.add(FloatTag.valueOf(rotation.w()));
        stored.put(ROTATION, list);
    }

    /** {@return ce qu'on peut dire d'une definition non résolue} */
    static String describe(CompoundTag stored) {
        OptionalLong hash = definition(stored);
        return hash.isPresent()
                ? "definition " + HexFormat.of().toHexDigits(hash.getAsLong())
                : "sans definition";
    }

    /**
     * Écrit les données d'apparition : definition, état inerte, version du descripteur d'états
     * procéduraux (vide en version 1, ADR-150 D6), orientation, puis l'emprise si elle existe.
     */
    static void writeSpawn(FriendlyByteBuf buffer, SpawnState state) {
        buffer.writeUtf(state.definitionId(), MAX_ID_CHARS);
        buffer.writeBoolean(state.inert());
        buffer.writeByte(SnapshotMessage.DESCRIPTOR_VERSION);
        Quaternionf q = state.rotation();
        buffer.writeFloat(q.x).writeFloat(q.y).writeFloat(q.z).writeFloat(q.w);
        boolean bounded = state.boundsMin() != null && state.boundsMax() != null;
        buffer.writeBoolean(bounded);
        if (bounded) {
            for (Vector3f corner : new Vector3f[] {state.boundsMin(), state.boundsMax()}) {
                buffer.writeFloat(corner.x).writeFloat(corner.y).writeFloat(corner.z);
            }
        }
    }

    /**
     * Lit les données d'apparition, bornées comme toute chaîne réseau. Une orientation non finie ou nulle
     * donne l'identité ; une emprise non finie ou retournée, aucune emprise — la boîte provisoire.
     */
    static SpawnState readSpawn(FriendlyByteBuf buffer) {
        String definitionId = buffer.readUtf(MAX_ID_CHARS);
        boolean inert = buffer.readBoolean();
        buffer.readUnsignedByte(); // descripteur : vide en version 1, rien à lire
        Quaternionf rotation = new Quaternionf(buffer.readFloat(), buffer.readFloat(), buffer.readFloat(),
                buffer.readFloat());
        float norm = rotation.lengthSquared();
        if (Float.isFinite(norm) && norm > MIN_QUATERNION_NORM_SQUARED) {
            rotation.normalize();
        } else {
            rotation.identity();
        }
        Vector3f min = null;
        Vector3f max = null;
        if (buffer.readBoolean()) {
            min = new Vector3f(buffer.readFloat(), buffer.readFloat(), buffer.readFloat());
            max = new Vector3f(buffer.readFloat(), buffer.readFloat(), buffer.readFloat());
            if (!min.isFinite() || !max.isFinite() || min.x > max.x || min.y > max.y || min.z > max.z) {
                min = null;
                max = null;
            }
        }
        return new SpawnState(definitionId, inert, rotation, min, max);
    }
}
