package dev.axion.definition;

import dev.axion.definition.PhysicsMaterials.Combine;
import dev.axion.definition.PhysicsMaterials.Field;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * L'acier générique du moteur (R-1032, ADR-145 point 2) : la valeur de chaque champ de DM-07
 * qu'aucun ancêtre d'un matériau ne déclare, et celle d'un matériau non déclaré.
 *
 * <p>Le moteur ne connaît aucun nom de matériau (R-1030) : ce jeu de valeurs n'est pas
 * {@code axion:steel}, que la bibliothèque de base déclare comme n'importe quel datapack, mais
 * il en reprend les grandeurs physiques. Leurs sources sont dans {@code MATERIALS.md} :
 *
 * <ul>
 *   <li><b>sourcées</b> — acier de construction ASTM A36 (AZoM, article 6117) : masse volumique,
 *       module d'Young, limite d'élasticité, résistance à la traction (bas de la plage),
 *       allongement à la rupture ; énergie de rupture <b>dérivée</b> de la ténacité d'un AISI 1020
 *       (K<sub>c</sub>²/E, manuel de Roylance, MIT), faute d'une ténacité publiée pour l'A36 ;
 *   <li><b>de conception</b>, provisoires jusqu'à leur calibration par les scénarios de T-842 (M6),
 *       quand leur consommateur existe — le frottement et la restitution, coefficients de couple et
 *       non d'un matériau seul (ADR-145, précision) ; l'élasticité et la plasticité, d'après le
 *       tableau 11.3 (« acier : plasticity élevée ») ; les multiplicateurs et le diviseur à la valeur
 *       neutre 1 ; les autres paramètres sans équivalent physique.
 * </ul>
 *
 * <p>{@code hardness_pressure} n'y figure pas : son défaut est la relation de Tabor (R-1038).
 * Les groupes de son et de particules non plus : un matériau sans groupe n'en a pas.
 */
final class GenericSteel {

    /** Valeur de chaque champ, hors {@code hardness_pressure}, groupes et drapeaux. */
    static final Map<Field, Object> VALUES = values();

    private GenericSteel() {}

    private static Map<Field, Object> values() {
        Map<Field, Object> values = new EnumMap<>(Field.class);
        // CONTACT — de conception : le contact générique des tuiles d'avant ADR-145, sans rebond ni
        // résistance au roulement, combiné par moyenne comme le fait le solveur par défaut.
        values.put(Field.FRICTION, 0.6);
        values.put(Field.RESTITUTION, 0.0);
        values.put(Field.ROLLING_FRICTION, 0.0);
        values.put(Field.FRICTION_COMBINE, Combine.AVERAGE);
        values.put(Field.RESTITUTION_COMBINE, Combine.AVERAGE);
        // MASSE, RIGIDITÉ, SEUILS — sourcés (ASTM A36), sauf l'énergie de rupture, dérivée.
        values.put(Field.DENSITY, 7850.0);
        values.put(Field.STIFFNESS, 2.0e11);
        values.put(Field.YIELD_STRENGTH, 2.5e8);
        values.put(Field.FRACTURE_STRENGTH, 4.0e8);
        values.put(Field.MAX_STRAIN, 0.20);
        values.put(Field.FRACTURE_ENERGY, 9.3e4);
        // COMPORTEMENT — de conception, d'après le tableau 11.3.
        values.put(Field.ELASTICITY, 0.2);
        values.put(Field.PLASTICITY, 0.8);
        values.put(Field.DAMPING, 0.1);
        // GÉOMÉTRIE ET DOMMAGE — neutres : le diviseur et les multiplicateurs à 1.
        values.put(Field.DEFORMATION_RESISTANCE, 1.0);
        values.put(Field.SPREAD_FACTOR, 1.0);
        values.put(Field.THICKNESS_SPREAD, 1.0);
        values.put(Field.DAMAGE_MULTIPLIER, 1.0);
        // De conception, provisoires : rayon d'indenteur de référence d'un contact émoussé, part de
        // l'énergie d'impact gardée en déformation et dommage.
        values.put(Field.INDENTER_RADIUS_REF, 0.05);
        values.put(Field.ENERGY_ABSORPTION, 0.5);
        // SURFACE — de conception, provisoires, avec l'usure (C-47) qui les consommera.
        values.put(Field.HARDNESS, 0.5);
        values.put(Field.SCRATCH_RESISTANCE, 0.5);
        values.put(Field.BURN_THRESHOLD, 1.0e4);
        values.put(Field.SOIL_RATE, 0.0);
        return Collections.unmodifiableMap(values);
    }
}
