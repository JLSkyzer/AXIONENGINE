package dev.axion.physics;

import java.nio.ByteBuffer;

/**
 * Un joint d'une assembly (C-34, ADR-151 D7), tel que {@code CREATE_ASSEMBLY} le porte.
 *
 * <p>Miroir du DM natif {@code JointDesc} ({@code crates/ax-model/src/dm/joints.rs}) :
 * <strong>160 octets</strong>, little-endian (R-271). Unités de R-102 ; {@code anchor} et
 * {@code axis} dans le repère du node {@code bNode}. Un réglage absent n'agit pas : son drapeau
 * manque, ou, pour un seuil de rupture, il vaut {@code +inf}.
 *
 * @param kind type, voir les constantes {@code KIND_*}
 * @param flags drapeaux, voir les constantes {@code FLAG_*}
 * @param bodyA rang du corps {@code a} parmi les corps de l'assembly
 * @param bodyB rang du corps {@code b}, celui dont {@code bNode} est la racine
 * @param axes modes des six axes de {@code generic}, voir les constantes {@code AXIS_*}
 * @param bNode node {@code b_node} de l'asset
 * @param anchor ancrage, {@code [x, y, z]}
 * @param axis axe, {@code [x, y, z]}, normalisé
 * @param limits plage de l'axe libre, {@code [min, max]}
 * @param motorSpeed vitesse cible du moteur
 * @param motorTarget position cible du servo
 * @param motorStiffness raideur du servo
 * @param motorDamping amortissement du servo
 * @param motorMaxForce force ou couple maximal du moteur ({@code +inf} : non déclaré)
 * @param friction friction interne
 * @param breakForce force de rupture ({@code +inf} : aucune)
 * @param breakTorque couple de rupture ({@code +inf} : aucun)
 * @param breakEnergy énergie de rupture sur un tick ({@code +inf} : aucune)
 * @param jamFriction friction interne d'un joint entièrement grippé
 * @param jamFirst premier point de sa courbe de grippage dans l'annexe
 * @param jamCount nombre de points de sa courbe de grippage
 * @param axisLimits plages des six axes de {@code generic}, {@code [min, max]} chacune
 * @param maxLength distance maximale ({@code rope})
 * @param restLength longueur de repos ({@code spring})
 * @param stiffness raideur du ressort ({@code spring})
 * @param damping amortissement du ressort ({@code spring})
 */
public record JointDescription(
        int kind,
        int flags,
        int bodyA,
        int bodyB,
        int[] axes,
        int bNode,
        float[] anchor,
        float[] axis,
        float[] limits,
        float motorSpeed,
        float motorTarget,
        float motorStiffness,
        float motorDamping,
        float motorMaxForce,
        float friction,
        float breakForce,
        float breakTorque,
        float breakEnergy,
        float jamFriction,
        int jamFirst,
        int jamCount,
        float[][] axisLimits,
        float maxLength,
        float restLength,
        float stiffness,
        float damping) {

    /** Taille sur la frontière, en octets. */
    public static final int BYTES = 160;

    /** Taille d'un point de courbe de grippage dans l'annexe, en octets. */
    public static final int JAM_POINT_BYTES = 8;

    /** {@code fixed}. */
    public static final int KIND_FIXED = 0;
    /** {@code revolute}. */
    public static final int KIND_REVOLUTE = 1;
    /** {@code prismatic}. */
    public static final int KIND_PRISMATIC = 2;
    /** {@code spherical}. */
    public static final int KIND_SPHERICAL = 3;
    /** {@code generic}. */
    public static final int KIND_GENERIC = 4;
    /** {@code rope}. */
    public static final int KIND_ROPE = 5;
    /** {@code spring}. */
    public static final int KIND_SPRING = 6;

    /** {@code limits} porte la plage de l'axe libre. */
    public static final int FLAG_LIMITS = 1;
    /** Un moteur en vitesse. */
    public static final int FLAG_MOTOR_VELOCITY = 1 << 1;
    /** Un servo en position. */
    public static final int FLAG_MOTOR_POSITION = 1 << 2;
    /** Une friction interne. */
    public static final int FLAG_FRICTION = 1 << 3;
    /** Un grippage. */
    public static final int FLAG_JAM = 1 << 4;
    /** Les deux corps du joint se touchent. */
    public static final int FLAG_COLLIDE = 1 << 5;

    /** Axe bloqué. */
    public static final int AXIS_LOCKED = 0;
    /** Axe libre. */
    public static final int AXIS_FREE = 1;
    /** Axe libre dans sa plage. */
    public static final int AXIS_LIMITED = 2;

    /**
     * Écrit le joint à la position courante d'un tampon little-endian : {@link #BYTES} octets.
     *
     * @param out tampon little-endian
     */
    public void writeTo(ByteBuffer out) {
        out.put((byte) kind);
        out.put((byte) flags);
        out.putShort((short) bodyA);
        out.putShort((short) bodyB);
        for (int mode : axes) {
            out.put((byte) mode);
        }
        out.putInt(bNode);
        for (float[] vector : new float[][] {anchor, axis, limits}) {
            for (float value : vector) {
                out.putFloat(value);
            }
        }
        for (float value : new float[] {motorSpeed, motorTarget, motorStiffness, motorDamping, motorMaxForce,
                friction, breakForce, breakTorque, breakEnergy, jamFriction}) {
            out.putFloat(value);
        }
        out.putInt(jamFirst);
        out.putInt(jamCount);
        for (float[] range : axisLimits) {
            out.putFloat(range[0]);
            out.putFloat(range[1]);
        }
        out.putFloat(maxLength);
        out.putFloat(restLength);
        out.putFloat(stiffness);
        out.putFloat(damping);
    }
}
