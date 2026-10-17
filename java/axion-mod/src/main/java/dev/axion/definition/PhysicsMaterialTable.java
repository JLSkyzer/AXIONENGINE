package dev.axion.definition;

import dev.axion.definition.PhysicsMaterials.Combine;
import dev.axion.definition.PhysicsMaterials.Field;
import dev.axion.definition.PhysicsMaterials.Flag;
import dev.axion.definition.PhysicsMaterials.Material;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * La table des matériaux physiques de la session (DM-07, ADR-149), telle que le natif la reçoit
 * par {@code SET_PHYSICS_MATERIALS}.
 *
 * <p>L'index d'un matériau est son rang dans la table, <strong>stable pour la session</strong> :
 * l'entrée 0 est l'acier générique de R-1032 ; un matériau déclaré prend l'index suivant la
 * première fois qu'on le rencontre — dans l'ordre des identifiants, à chaque registre adopté — et
 * le garde ; un matériau retiré par un rechargement garde son entrée, qui vaut alors l'acier
 * générique, comme tout matériau non déclaré. C'est cet index que portent les colliders des
 * assemblies et les tuiles, et le natif résout de nouveau tout ce qui est posé à chaque table
 * reçue (D1).
 */
public final class PhysicsMaterialTable {

    /** Taille d'une entrée DM-07 sur la frontière, alignement compris. */
    public static final int ENTRY_BYTES = 120;

    /** Index « aucun matériau » ({@code NO_MATERIAL}) : le contact neutre du solveur. */
    public static final int NO_MATERIAL = 0xFFFF;

    /** Index de l'acier générique du moteur (R-1032). */
    public static final int GENERIC_STEEL = 0;

    /** Entrées au plus : un index tient dans un {@code u16}, et {@link #NO_MATERIAL} n'en est pas un. */
    public static final int MAX_ENTRIES = 0xFFFF;

    /** Les 20 grandeurs qui suivent les modes, dans l'ordre de la disposition DM-07. */
    private static final Field[] QUANTITIES = {
        Field.DENSITY, Field.HARDNESS, Field.STIFFNESS, Field.ELASTICITY, Field.PLASTICITY,
        Field.YIELD_STRENGTH, Field.FRACTURE_STRENGTH, Field.DEFORMATION_RESISTANCE, Field.MAX_STRAIN,
        Field.DAMPING, Field.FRACTURE_ENERGY, Field.HARDNESS_PRESSURE, Field.INDENTER_RADIUS_REF,
        Field.SPREAD_FACTOR, Field.THICKNESS_SPREAD, Field.DAMAGE_MULTIPLIER, Field.ENERGY_ABSORPTION,
        Field.SCRATCH_RESISTANCE, Field.BURN_THRESHOLD, Field.SOIL_RATE,
    };

    /** La table encodée, prête pour {@code SET_PHYSICS_MATERIALS}. */
    public record Encoded(byte[] entries, int count) {}

    /** Identifiant de chaque entrée, par index ; {@code null} pour l'acier générique. */
    private final List<String> ids = new ArrayList<>();

    private final Map<String, Integer> indices = new HashMap<>();
    private PhysicsMaterials registry = PhysicsMaterials.empty();
    private boolean changed = true;

    /** Une table qui ne connaît que l'acier générique, à envoyer. */
    public PhysicsMaterialTable() {
        ids.add(null);
    }

    /**
     * Adopte un registre : au démarrage, puis à chaque rechargement des matériaux. Les identifiants
     * nouveaux prennent les index suivants, dans l'ordre des identifiants ; la table est à
     * renvoyer.
     *
     * @param materials registre des matériaux physiques
     */
    public synchronized void update(PhysicsMaterials materials) {
        registry = materials;
        for (String id : materials.ids()) {
            assign(id);
        }
        changed = true;
    }

    /** Une session native neuve n'a pas reçu la table : elle est à renvoyer. */
    public synchronized void markChanged() {
        changed = true;
    }

    /**
     * {@return l'index d'un matériau} Un identifiant déclaré garde le sien pour la session ; un
     * identifiant absent ou non déclaré vaut l'acier générique (R-1032).
     *
     * @param id identifiant {@code <ns>:<chemin>}, ou {@code null}
     */
    public synchronized int indexOf(String id) {
        if (id == null || !registry.knows(id)) {
            return GENERIC_STEEL;
        }
        return assign(id);
    }

    /** {@return la table encodée si elle a changé depuis l'appel précédent, sinon {@code null}} */
    public synchronized Encoded takeIfChanged() {
        if (!changed) {
            return null;
        }
        changed = false;
        ByteBuffer out = ByteBuffer.allocate(ids.size() * ENTRY_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (String id : ids) {
            write(id == null ? PhysicsMaterials.genericSteel() : registry.resolve(id), id, out);
        }
        return new Encoded(out.array(), ids.size());
    }

    /** {@return le nombre d'entrées de la table} */
    public synchronized int size() {
        return ids.size();
    }

    private int assign(String id) {
        Integer known = indices.get(id);
        if (known != null) {
            return known;
        }
        if (ids.size() >= MAX_ENTRIES) {
            return GENERIC_STEEL;
        }
        int index = ids.size();
        ids.add(id);
        indices.put(id, index);
        changed = true;
        return index;
    }

    /**
     * Écrit une entrée DM-07 (§3.7) : empreinte FNV-1a 64 de l'identifiant (0 pour l'acier
     * générique), contact, modes, les 20 grandeurs, groupes de son et de particules (32 bits de
     * poids faible de l'empreinte de leur identifiant, 0 sans groupe), drapeaux, et 4 octets
     * d'alignement à zéro.
     */
    static void write(Material material, String id, ByteBuffer out) {
        out.putLong(id == null ? 0L : DefinitionIds.hash(id));
        out.putFloat((float) material.friction());
        out.putFloat((float) material.restitution());
        out.putFloat((float) material.number(Field.ROLLING_FRICTION));
        out.put(wire(material.combine(Field.FRICTION_COMBINE)));
        out.put(wire(material.combine(Field.RESTITUTION_COMBINE)));
        out.put((byte) 0).put((byte) 0);
        for (Field field : QUANTITIES) {
            out.putFloat((float) material.number(field));
        }
        out.putInt(group(material, Field.SOUND_GROUP));
        out.putInt(group(material, Field.PARTICLE_GROUP));
        int flags = 0;
        for (Flag flag : material.flags()) {
            flags |= 1 << flag.ordinal();
        }
        out.putInt(flags);
        out.putInt(0); // alignement
    }

    /** La valeur DM-07 d'un mode : celles du solveur (ADR-149 D3), pas l'ordre de l'énumération. */
    static byte wire(Combine combine) {
        return switch (combine) {
            case AVERAGE -> 0;
            case MIN -> 1;
            case MULTIPLY -> 2;
            case MAX -> 3;
        };
    }

    private static int group(Material material, Field field) {
        return material.group(field).map(name -> (int) DefinitionIds.hash(name)).orElse(0);
    }
}
