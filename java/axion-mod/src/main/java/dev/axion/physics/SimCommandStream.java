package dev.axion.physics;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Encodeur du flux de commandes de {@code SIM_IN} (IF-03, ADR-114).
 *
 * <p>Java écrit dans {@code SIM_IN} un {@code CommandStreamHeader} puis une suite
 * de commandes (chacune un {@code SimCommandHeader} — opcode + longueur de
 * payload — suivi de son payload {@code repr(C)}). La disposition est figée côté
 * natif ({@code crates/ax-model/src/dm/commands.rs}) et little-endian (R-271) ;
 * le test {@code SimCommandStreamTest} l'épingle ici.
 *
 * <p>{@code SET_DIMENSION_ENV} règle l'environnement d'une dimension (aucun corps
 * requis) ; {@code CREATE_ASSEMBLY} crée un corps depuis les colliders d'un asset
 * (C-32, Option A d'ADR-115 : les octets de la section {@code PHYS} suivent
 * l'en-tête dans le même payload). Les commandes qui ciblent un handle existant
 * (retrait, cinématique, impulsion) arriveront avec leur consommateur.
 */
public final class SimCommandStream {

    /**
     * Version courante du protocole ({@code CommandStreamHeader::CURRENT_SCHEMA}) : 3 depuis les
     * assemblies à plusieurs corps (ADR-151 D7), après l'index de matériau des tuiles (2, ADR-149).
     */
    public static final int CURRENT_SCHEMA = 3;

    /** Taille du {@code CommandStreamHeader}, en octets. */
    public static final int STREAM_HEADER_BYTES = 8;

    /** Taille d'un {@code SimCommandHeader} (opcode + payload_len), en octets. */
    public static final int COMMAND_HEADER_BYTES = 8;

    /** Opcode {@code SET_DIMENSION_ENV} (ADR-114). */
    public static final int OP_SET_DIMENSION_ENV = 5;

    /** Taille du payload {@code SetDimensionEnv}, en octets (remplissage compris). */
    public static final int SET_DIMENSION_ENV_BYTES = 48;

    /** Drapeau {@code FLUID_PRESENT} de {@code SetDimensionEnv}. */
    public static final int FLUID_PRESENT = 1;

    /** Opcode {@code CREATE_ASSEMBLY} (ADR-114). */
    public static final int OP_CREATE_ASSEMBLY = 0;

    /** Opcode {@code REMOVE_ASSEMBLY} (ADR-114). */
    public static final int OP_REMOVE_ASSEMBLY = 1;

    /** Taille du payload {@code RemoveAssembly} (un {@code Handle}), en octets. */
    public static final int REMOVE_ASSEMBLY_BYTES = 8;

    /**
     * Taille de l'en-tête {@code CreateAssembly}, en octets (hors corps et sections qui le
     * suivent). Figé côté natif : {@code CreateAssembly::BYTES}.
     */
    public static final int CREATE_ASSEMBLY_HEADER_BYTES = 72;

    /**
     * Taille d'un corps de {@code CREATE_ASSEMBLY} : sa description ({@link BodyDescription},
     * 56 o), son node racine, 4 octets réservés. Figé côté natif :
     * {@code AssemblyBodyEntry::WIRE_BYTES}.
     */
    public static final int ASSEMBLY_BODY_BYTES = BodyDescription.WIRE_BYTES + 8;

    /** Plafond de corps par assembly (R-190) : {@code MAX_ASSEMBLY_BODIES}. */
    public static final int MAX_ASSEMBLY_BODIES = 64;

    /** Drapeau {@code SELF_COLLISION} de {@code CreateAssembly} : ses corps se touchent (§10.4). */
    public static final int CREATE_ASSEMBLY_SELF_COLLISION = 1;

    /** Drapeau {@code RESTORED} de {@code CreateAssembly} : une section d'état restauré suit {@code NODE}. */
    public static final int CREATE_ASSEMBLY_RESTORED = 2;

    /** Drapeau {@code ASLEEP} de {@code CreateAssembly} : l'assembly naît endormie (22.4, ADR-153 D4). */
    public static final int CREATE_ASSEMBLY_ASLEEP = 4;

    /**
     * Drapeau {@code ANIMATED} de {@code CreateAssembly} : la section {@code ANIM} de l'asset suit l'état
     * restauré, préfixée de sa taille et d'un mot réservé (ADR-155). Le natif ne l'anime que si un collider
     * dépend d'un node animé (R-1430).
     */
    public static final int CREATE_ASSEMBLY_ANIMATED = 8;

    /** Taille de l'état restauré d'un corps ({@code RestoredBody}), en octets. */
    public static final int RESTORED_BODY_BYTES = 64;

    /** Taille de l'état restauré d'un joint ({@code RestoredJoint}), en octets. */
    public static final int RESTORED_JOINT_BYTES = 8;

    /** Drapeau d'un {@code RestoredJoint} : le joint est rompu. */
    public static final int RESTORED_JOINT_BROKEN = 1;

    /**
     * L'état qu'une sauvegarde a gardé d'une assembly (ADR-153 D3), rendu à sa création par le natif,
     * sans événement : un corps par corps de la commande, dans le même ordre, puis un joint par joint.
     *
     * @param bodies pose et vitesses de chaque corps
     * @param joints état de chaque joint, dans l'ordre de la definition
     */
    public record Restored(List<RestoredBody> bodies, List<RestoredJoint> joints) {}

    /**
     * La pose monde et les vitesses monde d'un corps restauré.
     *
     * @param position position monde {@code [x, y, z]}, en blocs
     * @param rotation quaternion {@code [x, y, z, w]}
     * @param linear vitesse linéaire {@code [x, y, z]}, en m/s
     * @param angular vitesse angulaire {@code [x, y, z]}, en rad/s
     */
    public record RestoredBody(double[] position, float[] rotation, float[] linear, float[] angular) {}

    /**
     * L'état d'un joint restauré.
     *
     * @param broken le joint est rompu
     * @param misalignment désalignement de son ancrage, en blocs ; 0 ne grippe pas
     */
    public record RestoredJoint(boolean broken, float misalignment) {}

    /** {@code body_kind} : corps statique (miroir de {@code BodyKind::Static}). */
    public static final int BODY_STATIC = 0;

    /** {@code body_kind} : corps cinématique ({@code BodyKind::Kinematic}). */
    public static final int BODY_KINEMATIC = 1;

    /** {@code body_kind} : corps dynamique ({@code BodyKind::Dynamic}). */
    public static final int BODY_DYNAMIC = 2;

    /** Opcode {@code SET_WORLD_COLLISION} (C-38, ADR-117). */
    public static final int OP_SET_WORLD_COLLISION = 6;

    /** Opcode {@code SET_WORLD_HEIGHTFIELD} (C-38, ADR-117). */
    public static final int OP_SET_WORLD_HEIGHTFIELD = 7;

    /** Opcode {@code SET_WORLD_FLUID} (C-38, ADR-117). */
    public static final int OP_SET_WORLD_FLUID = 8;

    /** Opcode {@code REMOVE_WORLD_COLLISION} (C-38, ADR-117). */
    public static final int OP_REMOVE_WORLD_COLLISION = 9;

    /** Opcode {@code REMOVE_WORLD_FLUID} (C-38, ADR-117). */
    public static final int OP_REMOVE_WORLD_FLUID = 10;

    /** Taille de l'en-tête {@code SetWorldCollision}, en octets (hors boîtes). */
    public static final int SET_WORLD_COLLISION_HEADER_BYTES = 32;

    /** Taille de l'en-tête {@code SetWorldHeightfield}, en octets (hors hauteurs). */
    public static final int SET_WORLD_HEIGHTFIELD_HEADER_BYTES = 48;

    /** Taille de l'en-tête {@code SetWorldFluid}, en octets (hors boîtes). */
    public static final int SET_WORLD_FLUID_HEADER_BYTES = 32;

    /** Taille du payload {@code RemoveWorldTile}, en octets. */
    public static final int REMOVE_WORLD_TILE_BYTES = 24;

    /** Octets d'une boîte {@code [f32;6]} sur la frontière. */
    public static final int BOX_BYTES = 24;

    /**
     * Plafond de boîtes d'une tuile (le natif refuse au-delà, ADR-117 / R-641) : au-delà,
     * la collision doit passer en champ de hauteurs.
     */
    public static final int MAX_WORLD_TILE_BOXES = 4096;

    /** Plafond des dimensions d'un champ de hauteurs de tuile (ADR-117). */
    public static final int MAX_WORLD_HEIGHTFIELD_DIM = 64;

    /** Opcode {@code SET_OBSERVERS} (ADR-123 §2). */
    public static final int OP_SET_OBSERVERS = 11;

    /** Opcode {@code SET_ENTITY_PROXIES} (ADR-123 §5). */
    public static final int OP_SET_ENTITY_PROXIES = 12;

    /** Opcode {@code SET_PHYSICS_MATERIALS} (ADR-149). */
    public static final int OP_SET_PHYSICS_MATERIALS = 13;

    /** Taille de l'en-tête {@code SetPhysicsMaterials}, en octets (hors matériaux). */
    public static final int SET_PHYSICS_MATERIALS_HEADER_BYTES = 8;

    /** Taille d'une entrée DM-07, alignement compris. */
    public static final int PHYSICS_MATERIAL_BYTES = 120;

    /** Index « aucun matériau » d'une tuile ({@code NO_MATERIAL}, ADR-149). */
    public static final int NO_MATERIAL = 0xFFFF;

    /** Opcode {@code JAM_JOINT} (ADR-151 D6, D7). */
    public static final int OP_JAM_JOINT = 14;

    /** Taille du payload {@code JamJoint}, en octets. */
    public static final int JAM_JOINT_BYTES = 16;

    /** Opcode {@code BREAK_JOINT} (ADR-151 D5, D7). */
    public static final int OP_BREAK_JOINT = 15;

    /** Taille du payload {@code BreakJoint}, en octets. */
    public static final int BREAK_JOINT_BYTES = 12;

    /** Opcode {@code ANIMATE_ASSEMBLY} (ADR-155) : une commande d'animation pour une assembly. */
    public static final int OP_ANIMATE_ASSEMBLY = 16;

    /** Taille du payload {@code AnimateAssembly}, en octets, sans les nodes du masque. */
    public static final int ANIMATE_ASSEMBLY_BYTES = 48;

    /** Plus grand rang de joint : il tient sur un {@code u16}. */
    public static final int MAX_JOINT_INDEX = 0xFFFF;

    /** Taille de l'en-tête {@code SetObservers}, en octets (hors positions). */
    public static final int SET_OBSERVERS_HEADER_BYTES = 16;

    /** Octets d'une position d'observateur {@code [f64;3]} sur la frontière. */
    public static final int OBSERVER_BYTES = 24;

    /** Taille de l'en-tête {@code SetEntityProxies}, en octets (hors proxies). */
    public static final int SET_ENTITY_PROXIES_HEADER_BYTES = 16;

    /** Taille d'un {@code EntityProxyDesc} sur la frontière, en octets. */
    public static final int ENTITY_PROXY_BYTES = 56;

    /** Plafond d'observateurs d'une dimension pour un tick (le natif refuse au-delà, ADR-123 §2). */
    public static final int MAX_OBSERVERS = 1024;

    /** Plafond de proxies d'une dimension pour un tick (le natif refuse au-delà, ADR-123 §5). */
    public static final int MAX_ENTITY_PROXIES = 4096;

    /** Forme de proxy : l'AABB de l'entité ({@code entity_proxy_shape::BOX}). */
    public static final int PROXY_BOX = 0;

    /** Forme de proxy : capsule verticale, un être vivant ({@code entity_proxy_shape::CAPSULE}). */
    public static final int PROXY_CAPSULE = 1;

    /**
     * Une entité vanilla vue par la physique pour un tick (R-614, ADR-123 §5) : le natif en fait
     * un corps cinématique temporaire, à sa vitesse, que les assemblies heurtent.
     *
     * @param entity identifiant réseau de l'entité, qui la nomme aussi dans les événements de
     *     contact
     * @param center centre de l'AABB, monde, en blocs {@code [x, y, z]}
     * @param halfExtents demi-dimensions de l'AABB {@code [x, y, z]}
     * @param velocity vitesse de l'entité {@code [x, y, z]}, en m/s
     * @param shape {@link #PROXY_BOX} ou {@link #PROXY_CAPSULE}
     */
    public record EntityProxy(int entity, double[] center, float[] halfExtents, float[] velocity, int shape) {

        /** Valide la forme du descripteur ; les valeurs inutilisables, le natif les ignore. */
        public EntityProxy {
            if (center.length != 3 || halfExtents.length != 3 || velocity.length != 3) {
                throw new IllegalArgumentException("centre, demi-dimensions et vitesse ont 3 composantes");
            }
            if (shape != PROXY_BOX && shape != PROXY_CAPSULE) {
                throw new IllegalArgumentException("forme de proxy inconnue : " + shape);
            }
        }
    }

    private final List<byte[]> commands = new ArrayList<>();

    /**
     * Ajoute une commande {@code SET_DIMENSION_ENV} (§10.6).
     *
     * @param dimension identifiant de la dimension visée
     * @param gravity gravité {@code [x, y, z]}, en m/s²
     * @param wind vent {@code [x, y, z]}, en m/s
     * @param fluidSurface altitude de la surface du fluide (ignorée si {@code !fluid})
     * @param fluidDensity masse volumique du fluide (ignorée si {@code !fluid})
     * @param fluid vrai si un fluide est présent ({@link #FLUID_PRESENT})
     * @return {@code this}, pour chaîner
     */
    public SimCommandStream setDimensionEnv(
            long dimension,
            float[] gravity,
            float[] wind,
            float fluidSurface,
            float fluidDensity,
            boolean fluid) {
        if (gravity.length != 3 || wind.length != 3) {
            throw new IllegalArgumentException("gravité et vent sont des vecteurs à 3 composantes");
        }
        ByteBuffer command = ByteBuffer.allocate(COMMAND_HEADER_BYTES + SET_DIMENSION_ENV_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        command.putInt(OP_SET_DIMENSION_ENV);
        command.putInt(SET_DIMENSION_ENV_BYTES);
        command.putLong(dimension);
        command.putFloat(gravity[0]);
        command.putFloat(gravity[1]);
        command.putFloat(gravity[2]);
        command.putFloat(wind[0]);
        command.putFloat(wind[1]);
        command.putFloat(wind[2]);
        command.putFloat(fluidSurface);
        command.putFloat(fluidDensity);
        command.putInt(fluid ? FLUID_PRESENT : 0);
        // Les 4 derniers octets du payload sont le remplissage, laissés à zéro.
        commands.add(command.array());
        return this;
    }

    /**
     * Un corps de {@code CREATE_ASSEMBLY} (ADR-151 D2, D7) : sa description DM-08 et son node
     * racine dans l'asset.
     *
     * @param description description du corps, son type compris
     * @param rootNode index de son node racine dans la section {@code NODE}
     */
    public record AssemblyBody(BodyDescription description, int rootNode) {}

    /**
     * Ajoute une commande {@code CREATE_ASSEMBLY} d'un seul corps, sans section {@code NODE} :
     * ses colliders sont pris tels quels, dans le repère de l'assembly (ADR-151 D7).
     *
     * @param handleIndex rang du handle d'assembly (routage du corps)
     * @param handleGeneration génération du handle ({@code 0} est invalide)
     * @param dimension dimension d'accueil (R-610)
     * @param position position monde de spawn {@code [x, y, z]}, en blocs
     * @param rotation quaternion de spawn {@code [x, y, z, w]}
     * @param body description du corps, son type compris
     * @param phys octets de la section {@code PHYS} (colliders compilés)
     * @return {@code this}, pour chaîner
     */
    public SimCommandStream createAssembly(
            int handleIndex,
            int handleGeneration,
            long dimension,
            double[] position,
            float[] rotation,
            BodyDescription body,
            byte[] phys) {
        if (body == null) {
            throw new IllegalArgumentException("la description du corps est requise");
        }
        return createAssembly(
                handleIndex,
                handleGeneration,
                dimension,
                position,
                rotation,
                List.of(new AssemblyBody(body, 0)),
                0.0f,
                false,
                new byte[0],
                phys);
    }

    /**
     * Ajoute une commande {@code CREATE_ASSEMBLY} (§4.5, ADR-114 ; Option A d'ADR-115 ;
     * ADR-151 D7).
     *
     * <p>Le payload est l'en-tête {@code CreateAssembly} (72 o), les corps (64 o chacun, le
     * corps racine d'abord), les octets de la section {@code NODE} de l'asset complétés à 8,
     * puis ceux de sa section {@code PHYS} — que l'appelant extrait de l'A3D
     * ({@link dev.axion.asset.A3dSections}). Le natif range chaque collider dans le corps de son
     * node, posé dans le repère de ce corps, crée tous les corps ou aucun, et applique à chacun
     * sa description : masse, centre et inertie déclarés priment sur ceux des densités (R-622).
     * Le type de l'en-tête est celui du corps racine ; les autres corps sont dynamiques.
     *
     * @param handleIndex rang du handle d'assembly (routage des corps)
     * @param handleGeneration génération du handle ({@code 0} est invalide)
     * @param dimension dimension d'accueil (R-610)
     * @param position position monde de spawn {@code [x, y, z]}, en blocs
     * @param rotation quaternion de spawn {@code [x, y, z, w]}
     * @param bodies les corps, de 1 à {@link #MAX_ASSEMBLY_BODIES}, le corps racine d'abord
     * @param assemblyMass masse de l'assembly en kg, répartie entre ses corps sans masse
     *     déclarée ; {@code 0} : aucune
     * @param selfCollision vrai si ses corps se touchent entre eux (§10.4)
     * @param nodes octets de la section {@code NODE} ; vide pour un seul corps aux colliders pris
     *     tels quels
     * @param phys octets de la section {@code PHYS} (colliders compilés)
     * @return {@code this}, pour chaîner
     */
    public SimCommandStream createAssembly(
            int handleIndex,
            int handleGeneration,
            long dimension,
            double[] position,
            float[] rotation,
            List<AssemblyBody> bodies,
            float assemblyMass,
            boolean selfCollision,
            byte[] nodes,
            byte[] phys) {
        return createAssembly(handleIndex, handleGeneration, dimension, position, rotation, bodies, List.of(),
                List.of(), assemblyMass, selfCollision, nodes, phys);
    }

    /**
     * Ajoute une commande {@code CREATE_ASSEMBLY} avec ses joints (C-34, ADR-151 D7).
     *
     * <p>Les joints suivent les corps, {@link JointDescription#BYTES} octets chacun, puis les points
     * de leurs courbes de grippage, {@link JointDescription#JAM_POINT_BYTES} octets chacun, avant la
     * section {@code NODE}. Le natif crée corps et joints d'un coup, ou rien.
     *
     * @param handleIndex rang du handle d'assembly (routage des corps)
     * @param handleGeneration génération du handle ({@code 0} est invalide)
     * @param dimension dimension d'accueil (R-610)
     * @param position position monde de spawn {@code [x, y, z]}, en blocs
     * @param rotation quaternion de spawn {@code [x, y, z, w]}
     * @param bodies les corps, de 1 à {@link #MAX_ASSEMBLY_BODIES}, le corps racine d'abord
     * @param joints les joints, dans l'ordre de la definition ({@link AssemblyJoints#of})
     * @param jamPoints les points des courbes de grippage, {@code [x, y]}, dans l'ordre des joints
     * @param assemblyMass masse de l'assembly en kg, répartie entre ses corps sans masse
     *     déclarée ; {@code 0} : aucune
     * @param selfCollision vrai si ses corps se touchent entre eux (§10.4)
     * @param nodes octets de la section {@code NODE} ; vide pour un seul corps aux colliders pris
     *     tels quels
     * @param phys octets de la section {@code PHYS} (colliders compilés)
     * @return {@code this}, pour chaîner
     */
    public SimCommandStream createAssembly(
            int handleIndex,
            int handleGeneration,
            long dimension,
            double[] position,
            float[] rotation,
            List<AssemblyBody> bodies,
            List<JointDescription> joints,
            List<float[]> jamPoints,
            float assemblyMass,
            boolean selfCollision,
            byte[] nodes,
            byte[] phys) {
        return createAssembly(handleIndex, handleGeneration, dimension, position, rotation, bodies, joints,
                jamPoints, assemblyMass, selfCollision, nodes, null, false, phys);
    }

    /**
     * Ajoute une commande {@code CREATE_ASSEMBLY} qui rend à l'assembly l'état que sa sauvegarde a
     * gardé (ADR-153 D3), et la fait naître endormie (22.4, D4). La section d'état restauré suit la
     * section {@code NODE}, avant {@code PHYS}.
     *
     * @param handleIndex rang du handle d'assembly (routage des corps)
     * @param handleGeneration génération du handle ({@code 0} est invalide)
     * @param dimension dimension d'accueil (R-610)
     * @param position position monde de spawn {@code [x, y, z]}, en blocs
     * @param rotation quaternion de spawn {@code [x, y, z, w]}
     * @param bodies les corps, de 1 à {@link #MAX_ASSEMBLY_BODIES}, le corps racine d'abord
     * @param joints les joints, dans l'ordre de la definition ({@link AssemblyJoints#of})
     * @param jamPoints les points des courbes de grippage, {@code [x, y]}, dans l'ordre des joints
     * @param assemblyMass masse de l'assembly en kg, répartie entre ses corps sans masse
     *     déclarée ; {@code 0} : aucune
     * @param selfCollision vrai si ses corps se touchent entre eux (§10.4)
     * @param nodes octets de la section {@code NODE}
     * @param restored l'état restauré, un par corps et par joint ; {@code null} : aucun
     * @param asleep vrai si l'assembly naît endormie, ses vitesses gelées
     * @param phys octets de la section {@code PHYS} (colliders compilés)
     * @return {@code this}, pour chaîner
     */
    public SimCommandStream createAssembly(
            int handleIndex,
            int handleGeneration,
            long dimension,
            double[] position,
            float[] rotation,
            List<AssemblyBody> bodies,
            List<JointDescription> joints,
            List<float[]> jamPoints,
            float assemblyMass,
            boolean selfCollision,
            byte[] nodes,
            Restored restored,
            boolean asleep,
            byte[] phys) {
        return createAssembly(handleIndex, handleGeneration, dimension, position, rotation, bodies, joints, jamPoints,
                assemblyMass, selfCollision, nodes, restored, asleep, null, phys);
    }

    /**
     * Ajoute une commande {@code CREATE_ASSEMBLY} avec la section {@code ANIM} de l'asset (ADR-155), après
     * l'état restauré : le serveur anime les colliders qui dépendent d'un node animé (R-1430).
     *
     * @param handleIndex rang du handle d'assembly (routage des corps)
     * @param handleGeneration génération du handle ({@code 0} est invalide)
     * @param dimension dimension d'accueil (R-610)
     * @param position position monde de spawn {@code [x, y, z]}, en blocs
     * @param rotation quaternion de spawn {@code [x, y, z, w]}
     * @param bodies les corps, de 1 à {@link #MAX_ASSEMBLY_BODIES}, le corps racine d'abord
     * @param joints les joints, dans l'ordre de la definition ({@link AssemblyJoints#of})
     * @param jamPoints les points des courbes de grippage, {@code [x, y]}, dans l'ordre des joints
     * @param assemblyMass masse de l'assembly en kg ; {@code 0} : aucune
     * @param selfCollision vrai si ses corps se touchent entre eux (§10.4)
     * @param nodes octets de la section {@code NODE}
     * @param restored l'état restauré, un par corps et par joint ; {@code null} : aucun
     * @param asleep vrai si l'assembly naît endormie, ses vitesses gelées
     * @param anim octets de la section {@code ANIM} ; {@code null} : aucune
     * @param phys octets de la section {@code PHYS} (colliders compilés)
     * @return {@code this}, pour chaîner
     */
    public SimCommandStream createAssembly(
            int handleIndex,
            int handleGeneration,
            long dimension,
            double[] position,
            float[] rotation,
            List<AssemblyBody> bodies,
            List<JointDescription> joints,
            List<float[]> jamPoints,
            float assemblyMass,
            boolean selfCollision,
            byte[] nodes,
            Restored restored,
            boolean asleep,
            byte[] anim,
            byte[] phys) {
        if (position.length != 3) {
            throw new IllegalArgumentException("la position est un vecteur à 3 composantes");
        }
        if (rotation.length != 4) {
            throw new IllegalArgumentException("la rotation est un quaternion à 4 composantes");
        }
        if (bodies == null || bodies.isEmpty() || bodies.size() > MAX_ASSEMBLY_BODIES) {
            throw new IllegalArgumentException("une assembly a de 1 à 64 corps (R-190)");
        }
        for (int i = 1; i < bodies.size(); i++) {
            if (bodies.get(i).description().bodyType() != BODY_DYNAMIC) {
                throw new IllegalArgumentException("un corps autre que la racine est dynamique");
            }
        }
        if (!(Float.isFinite(assemblyMass) && assemblyMass >= 0.0f)) {
            throw new IllegalArgumentException("la masse de l'assembly est finie et positive ou nulle");
        }
        if (nodes == null || phys == null) {
            throw new IllegalArgumentException("les octets NODE et PHYS sont requis");
        }
        if (nodes.length == 0 && bodies.size() > 1) {
            throw new IllegalArgumentException("plusieurs corps exigent la section NODE");
        }
        if (joints == null || jamPoints == null || joints.size() > 0xFFFF || jamPoints.size() > 0xFFFF) {
            throw new IllegalArgumentException("au plus 65 535 joints et 65 535 points de grippage");
        }
        for (float[] point : jamPoints) {
            if (point.length != 2) {
                throw new IllegalArgumentException("un point de grippage a deux composantes");
            }
        }
        if (restored != null) {
            checkRestored(restored, bodies.size(), joints.size());
        }
        int bodyKind = bodies.get(0).description().bodyType();
        int restoredBytes = restored == null
                ? 0
                : bodies.size() * RESTORED_BODY_BYTES + joints.size() * RESTORED_JOINT_BYTES;
        int animBytes = anim == null ? 0 : 8 + anim.length;

        // Le payload est aligné sur 8 octets : le lecteur natif avance d'un multiple de 8 après
        // chaque commande, pour lire la suivante en place (R-271, R-881).
        int physOffset = alignUp8(CREATE_ASSEMBLY_HEADER_BYTES + bodies.size() * ASSEMBLY_BODY_BYTES
                + joints.size() * JointDescription.BYTES + jamPoints.size() * JointDescription.JAM_POINT_BYTES
                + nodes.length + restoredBytes + animBytes);
        int payloadLen = physOffset + phys.length;
        int padded = alignUp8(payloadLen);
        ByteBuffer command = ByteBuffer.allocate(COMMAND_HEADER_BYTES + padded)
                .order(ByteOrder.LITTLE_ENDIAN);
        command.putInt(OP_CREATE_ASSEMBLY);
        // La longueur annoncée est celle du payload utile, sans le remplissage.
        command.putInt(payloadLen);
        // En-tête CreateAssembly (disposition figée, ax-model/dm/commands.rs).
        command.putInt(handleIndex);
        command.putInt(handleGeneration);
        command.putLong(dimension);
        command.putDouble(position[0]);
        command.putDouble(position[1]);
        command.putDouble(position[2]);
        command.putFloat(rotation[0]);
        command.putFloat(rotation[1]);
        command.putFloat(rotation[2]);
        command.putFloat(rotation[3]);
        command.put((byte) bodyKind);
        command.put((byte) ((selfCollision ? CREATE_ASSEMBLY_SELF_COLLISION : 0)
                | (restored != null ? CREATE_ASSEMBLY_RESTORED : 0)
                | (asleep ? CREATE_ASSEMBLY_ASLEEP : 0)
                | (anim != null ? CREATE_ASSEMBLY_ANIMATED : 0)));
        command.putShort((short) bodies.size());
        command.putShort((short) joints.size());
        command.putShort((short) jamPoints.size());
        command.putFloat(assemblyMass);
        command.putInt(nodes.length);
        for (AssemblyBody body : bodies) {
            body.description().writeTo(command);
            command.putInt(body.rootNode());
            command.putInt(0); // réservé
        }
        for (JointDescription joint : joints) {
            joint.writeTo(command);
        }
        for (float[] point : jamPoints) {
            command.putFloat(point[0]);
            command.putFloat(point[1]);
        }
        command.put(nodes);
        if (restored != null) {
            for (RestoredBody body : restored.bodies()) {
                for (double value : body.position()) {
                    command.putDouble(value);
                }
                for (float[] values : new float[][] {body.rotation(), body.linear(), body.angular()}) {
                    for (float value : values) {
                        command.putFloat(value);
                    }
                }
            }
            for (RestoredJoint joint : restored.joints()) {
                command.put((byte) (joint.broken() ? RESTORED_JOINT_BROKEN : 0)).put((byte) 0).put((byte) 0)
                        .put((byte) 0);
                command.putFloat(joint.misalignment());
            }
        }
        if (anim != null) {
            command.putInt(anim.length);
            command.putInt(0); // réservé
            command.put(anim);
        }
        command.position(COMMAND_HEADER_BYTES + physOffset);
        command.put(phys);
        commands.add(command.array());
        return this;
    }

    /**
     * Un état restauré a un corps par corps et un joint par joint, aux vecteurs de bonne taille, aux
     * valeurs finies, à la rotation non nulle et au désalignement positif ou nul : ce que le natif
     * exige pour ne pas refuser la création.
     */
    private static void checkRestored(Restored restored, int bodyCount, int jointCount) {
        if (restored.bodies().size() != bodyCount || restored.joints().size() != jointCount) {
            throw new IllegalArgumentException("un état restauré par corps et par joint");
        }
        for (RestoredBody body : restored.bodies()) {
            if (body.position().length != 3 || body.rotation().length != 4 || body.linear().length != 3
                    || body.angular().length != 3) {
                throw new IllegalArgumentException("un corps restauré : position, quaternion, deux vitesses");
            }
            float norm = 0f;
            for (float c : body.rotation()) {
                norm += c * c;
            }
            boolean finite = Double.isFinite(body.position()[0]) && Double.isFinite(body.position()[1])
                    && Double.isFinite(body.position()[2]) && Float.isFinite(norm);
            for (float[] values : new float[][] {body.linear(), body.angular()}) {
                for (float value : values) {
                    finite &= Float.isFinite(value);
                }
            }
            if (!finite || norm <= 1e-6f) {
                throw new IllegalArgumentException("un corps restauré a des valeurs finies et une rotation");
            }
        }
        for (RestoredJoint joint : restored.joints()) {
            if (!(Float.isFinite(joint.misalignment()) && joint.misalignment() >= 0f)) {
                throw new IllegalArgumentException("un désalignement restauré est fini et positif ou nul");
            }
        }
    }

    /**
     * Ajoute une commande {@code SET_WORLD_COLLISION} (C-38, R-640 ; ADR-117).
     *
     * <p>Pose une tuile de collision solide : {@code boxes} boîtes {@code [minx, miny,
     * minz, maxx, maxy, maxz]}, en blocs **relatives à l'origine de la section**
     * ({@code section × 16}), quantifiées en 1/16. Toutes portent le matériau dominant
     * (R-643). Une liste vide retire la tuile de collision.
     *
     * @param dimension dimension visée (R-610)
     * @param section index de section 16³ {@code [x, y, z]} ({@code coord_bloc >> 4})
     * @param boxes boîtes de collision, section-relatives ; chacune de longueur 6
     * @param material index du matériau dominant dans la table de la session (ADR-149), ou
     *     {@link #NO_MATERIAL}
     * @return {@code this}, pour chaîner
     */
    public SimCommandStream setWorldCollision(
            long dimension, int[] section, float[][] boxes, int material) {
        checkSection(section);
        checkBoxes(boxes);
        int payloadLen = SET_WORLD_COLLISION_HEADER_BYTES + boxes.length * BOX_BYTES;
        ByteBuffer command = beginCommand(OP_SET_WORLD_COLLISION, payloadLen);
        command.putLong(dimension);
        putSection(command, section);
        command.putInt(boxes.length);
        putMaterial(command, material);
        putBoxes(command, boxes);
        commands.add(command.array());
        return this;
    }

    /**
     * Ajoute une commande {@code SET_WORLD_HEIGHTFIELD} (C-38, R-641 ; ADR-117).
     *
     * <p>Pose une tuile de collision en champ de hauteurs (repli quand la section dépasse
     * {@link #MAX_WORLD_TILE_BOXES} boîtes). {@code heights} est ligne-major
     * ({@code height[row * cols + col]}, {@code row} sur z) ; la hauteur monde d'un sommet
     * vaut {@code height × scale[1]}.
     *
     * @param dimension dimension visée (R-610)
     * @param section index de section 16³
     * @param rows nombre de lignes (axe z), {@code 2..=}{@link #MAX_WORLD_HEIGHTFIELD_DIM}
     * @param cols nombre de colonnes (axe x), même plage
     * @param heights hauteurs ligne-major, de longueur {@code rows × cols}
     * @param scale échelle {@code [x, y, z]}
     * @param material index du matériau dominant (ADR-149), ou {@link #NO_MATERIAL}
     * @return {@code this}, pour chaîner
     */
    public SimCommandStream setWorldHeightfield(
            long dimension,
            int[] section,
            int rows,
            int cols,
            float[] heights,
            float[] scale,
            int material) {
        checkSection(section);
        if (scale.length != 3) {
            throw new IllegalArgumentException("l'échelle est un vecteur à 3 composantes");
        }
        if (rows < 2 || cols < 2 || rows > MAX_WORLD_HEIGHTFIELD_DIM || cols > MAX_WORLD_HEIGHTFIELD_DIM) {
            throw new IllegalArgumentException("rows et cols sont dans 2.." + MAX_WORLD_HEIGHTFIELD_DIM);
        }
        if (heights.length != rows * cols) {
            throw new IllegalArgumentException("heights doit contenir rows × cols valeurs");
        }
        int payloadLen = SET_WORLD_HEIGHTFIELD_HEADER_BYTES + heights.length * 4;
        ByteBuffer command = beginCommand(OP_SET_WORLD_HEIGHTFIELD, payloadLen);
        command.putLong(dimension);
        putSection(command, section);
        command.putInt(rows);
        command.putInt(cols);
        putMaterial(command, material);
        command.putFloat(scale[0]);
        command.putFloat(scale[1]);
        command.putFloat(scale[2]);
        for (float height : heights) {
            command.putFloat(height);
        }
        commands.add(command.array());
        return this;
    }

    /**
     * Ajoute une commande {@code SET_PHYSICS_MATERIALS} (ADR-149) : la table des matériaux de la
     * session, {@code count} entrées DM-07 déjà encodées. Le natif résout de nouveau les colliders et
     * les tuiles déjà posés à chaque table reçue.
     *
     * @param entries {@code count × }{@link #PHYSICS_MATERIAL_BYTES} octets
     * @param count nombre d'entrées, au plus {@code 0xFFFF}
     * @return {@code this}, pour chaîner
     */
    public SimCommandStream setPhysicsMaterials(byte[] entries, int count) {
        if (count < 0 || count > NO_MATERIAL || entries.length != count * PHYSICS_MATERIAL_BYTES) {
            throw new IllegalArgumentException("table de matériaux incohérente : " + count + " entrée(s), "
                    + entries.length + " octet(s)");
        }
        ByteBuffer command = beginCommand(OP_SET_PHYSICS_MATERIALS, SET_PHYSICS_MATERIALS_HEADER_BYTES + entries.length);
        command.putInt(count);
        command.putInt(0); // réservé
        command.put(entries);
        commands.add(command.array());
        return this;
    }

    /** Index de matériau d'un en-tête de tuile : {@code u16}, puis 6 octets réservés (ADR-149). */
    private static void putMaterial(ByteBuffer command, int material) {
        if (material < 0 || material > NO_MATERIAL) {
            throw new IllegalArgumentException("index de matériau hors d'un u16 : " + material);
        }
        command.putShort((short) material);
        command.putShort((short) 0);
        command.putInt(0);
    }

    /**
     * Ajoute une commande {@code SET_WORLD_FLUID} (C-38, R-642 ; ADR-117).
     *
     * <p>Pose des volumes de fluide (« capteurs » de flottabilité) : {@code boxes} boîtes
     * section-relatives, de masse volumique {@code density}. Une liste vide ou une densité
     * {@code ≤ 0} retire les volumes de fluide.
     *
     * @param dimension dimension visée (R-610)
     * @param section index de section 16³
     * @param boxes boîtes de fluide, section-relatives ; chacune de longueur 6
     * @param density masse volumique du fluide (eau douce ≈ 1000)
     * @return {@code this}, pour chaîner
     */
    public SimCommandStream setWorldFluid(
            long dimension, int[] section, float[][] boxes, float density) {
        checkSection(section);
        checkBoxes(boxes);
        int payloadLen = SET_WORLD_FLUID_HEADER_BYTES + boxes.length * BOX_BYTES;
        ByteBuffer command = beginCommand(OP_SET_WORLD_FLUID, payloadLen);
        command.putLong(dimension);
        putSection(command, section);
        command.putInt(boxes.length);
        command.putFloat(density);
        command.putInt(0); // _pad
        putBoxes(command, boxes);
        commands.add(command.array());
        return this;
    }

    /**
     * Ajoute une commande {@code REMOVE_WORLD_COLLISION} (C-38 ; ADR-117) : retire la
     * tuile de collision d'une section.
     *
     * @param dimension dimension visée
     * @param section index de section 16³ à retirer
     * @return {@code this}, pour chaîner
     */
    public SimCommandStream removeWorldCollision(long dimension, int[] section) {
        return removeWorldTile(OP_REMOVE_WORLD_COLLISION, dimension, section);
    }

    /**
     * Ajoute une commande {@code REMOVE_WORLD_FLUID} (C-38 ; ADR-117) : retire les volumes
     * de fluide d'une section.
     *
     * @param dimension dimension visée
     * @param section index de section 16³ à retirer
     * @return {@code this}, pour chaîner
     */
    public SimCommandStream removeWorldFluid(long dimension, int[] section) {
        return removeWorldTile(OP_REMOVE_WORLD_FLUID, dimension, section);
    }

    /**
     * Ajoute une commande {@code SET_OBSERVERS} (ADR-123 §2) : les positions monde des joueurs
     * d'une dimension, spectateurs exclus. La déclaration ne vaut que pour ce tick : une
     * dimension qui ne la répète pas au tick suivant n'a plus d'observateur.
     *
     * @param dimension dimension visée (R-610)
     * @param positions positions monde {@code [x, y, z]}, en blocs ; au plus
     *     {@link #MAX_OBSERVERS}
     * @return {@code this}, pour chaîner
     */
    public SimCommandStream setObservers(long dimension, double[][] positions) {
        if (positions.length > MAX_OBSERVERS) {
            throw new IllegalArgumentException(
                    "au plus " + MAX_OBSERVERS + " observateurs par dimension (ADR-123)");
        }
        for (double[] position : positions) {
            if (position.length != 3) {
                throw new IllegalArgumentException("une position est un vecteur à 3 composantes");
            }
        }
        int payloadLen = SET_OBSERVERS_HEADER_BYTES + positions.length * OBSERVER_BYTES;
        ByteBuffer command = beginCommand(OP_SET_OBSERVERS, payloadLen);
        command.putLong(dimension);
        command.putInt(positions.length);
        command.putInt(0); // _pad
        for (double[] position : positions) {
            command.putDouble(position[0]);
            command.putDouble(position[1]);
            command.putDouble(position[2]);
        }
        commands.add(command.array());
        return this;
    }

    /**
     * Ajoute une commande {@code SET_ENTITY_PROXIES} (R-614, ADR-123 §5) : les entités d'une
     * dimension que la physique doit voir ce tick. L'ensemble est remplacé à chaque tick : une
     * entité absente du flux perd son proxy.
     *
     * @param dimension dimension visée (R-610)
     * @param proxies entités, au plus {@link #MAX_ENTITY_PROXIES}
     * @return {@code this}, pour chaîner
     */
    public SimCommandStream setEntityProxies(long dimension, List<EntityProxy> proxies) {
        if (proxies.size() > MAX_ENTITY_PROXIES) {
            throw new IllegalArgumentException(
                    "au plus " + MAX_ENTITY_PROXIES + " proxies par dimension (ADR-123)");
        }
        int payloadLen = SET_ENTITY_PROXIES_HEADER_BYTES + proxies.size() * ENTITY_PROXY_BYTES;
        ByteBuffer command = beginCommand(OP_SET_ENTITY_PROXIES, payloadLen);
        command.putLong(dimension);
        command.putInt(proxies.size());
        command.putInt(0); // _pad
        for (EntityProxy proxy : proxies) {
            command.putDouble(proxy.center()[0]);
            command.putDouble(proxy.center()[1]);
            command.putDouble(proxy.center()[2]);
            command.putFloat(proxy.halfExtents()[0]);
            command.putFloat(proxy.halfExtents()[1]);
            command.putFloat(proxy.halfExtents()[2]);
            command.putFloat(proxy.velocity()[0]);
            command.putFloat(proxy.velocity()[1]);
            command.putFloat(proxy.velocity()[2]);
            command.putInt(proxy.entity());
            command.put((byte) proxy.shape());
            command.put((byte) 0); // _pad[3]
            command.put((byte) 0);
            command.put((byte) 0);
        }
        commands.add(command.array());
        return this;
    }

    private SimCommandStream removeWorldTile(int opcode, long dimension, int[] section) {
        checkSection(section);
        ByteBuffer command = beginCommand(opcode, REMOVE_WORLD_TILE_BYTES);
        command.putLong(dimension);
        putSection(command, section);
        command.putInt(0); // _pad
        commands.add(command.array());
        return this;
    }

    /**
     * Alloue le tampon d'une commande (en-tête + payload rembourré à 8 octets) et écrit
     * son {@code SimCommandHeader}. Le lecteur natif avance d'un multiple de 8 après
     * chaque commande (R-271, R-881) ; {@code payload_len} reste la longueur utile.
     */
    private static ByteBuffer beginCommand(int opcode, int payloadLen) {
        ByteBuffer command = ByteBuffer.allocate(COMMAND_HEADER_BYTES + alignUp8(payloadLen))
                .order(ByteOrder.LITTLE_ENDIAN);
        command.putInt(opcode);
        command.putInt(payloadLen);
        return command;
    }

    private static void putSection(ByteBuffer buffer, int[] section) {
        buffer.putInt(section[0]);
        buffer.putInt(section[1]);
        buffer.putInt(section[2]);
    }

    private static void putBoxes(ByteBuffer buffer, float[][] boxes) {
        for (float[] box : boxes) {
            for (int i = 0; i < 6; i++) {
                buffer.putFloat(box[i]);
            }
        }
    }

    private static void checkSection(int[] section) {
        if (section.length != 3) {
            throw new IllegalArgumentException("la section est un index à 3 composantes");
        }
    }

    private static void checkBoxes(float[][] boxes) {
        if (boxes.length > MAX_WORLD_TILE_BOXES) {
            throw new IllegalArgumentException(
                    "au plus " + MAX_WORLD_TILE_BOXES + " boîtes par tuile (R-641)");
        }
        for (float[] box : boxes) {
            if (box.length != 6) {
                throw new IllegalArgumentException("une boîte est [minx,miny,minz,maxx,maxy,maxz]");
            }
        }
    }

    /**
     * Ajoute une commande {@code REMOVE_ASSEMBLY} (ADR-114) : retire l'assembly (et ses
     * corps) désignée par son handle.
     *
     * @param handleIndex rang du handle d'assembly
     * @param handleGeneration génération du handle
     * @return {@code this}, pour chaîner
     */
    public SimCommandStream removeAssembly(int handleIndex, int handleGeneration) {
        ByteBuffer command = beginCommand(OP_REMOVE_ASSEMBLY, REMOVE_ASSEMBLY_BYTES);
        command.putInt(handleIndex);
        command.putInt(handleGeneration);
        commands.add(command.array());
        return this;
    }

    /**
     * Ajoute une commande {@code JAM_JOINT} (C-34, ADR-151 D6) : grippe un joint d'une assembly
     * au désalignement d'ancrage donné.
     *
     * <p>La courbe de grippage du joint en tire la fraction de plage restante. Un désalignement
     * nul rend exactement ses réglages d'origine (R-632). Le natif ignore un désalignement
     * négatif ou non fini, et un joint sans grippage.
     *
     * @param handleIndex rang du handle d'assembly
     * @param handleGeneration génération du handle
     * @param joint rang du joint dans la definition, de 0 à {@link #MAX_JOINT_INDEX}
     * @param misalignment désalignement d'ancrage, en blocs
     * @return {@code this}, pour chaîner
     */
    public SimCommandStream jamJoint(int handleIndex, int handleGeneration, int joint, float misalignment) {
        checkJointIndex(joint);
        ByteBuffer command = beginCommand(OP_JAM_JOINT, JAM_JOINT_BYTES);
        command.putInt(handleIndex);
        command.putInt(handleGeneration);
        command.putShort((short) joint);
        command.putShort((short) 0); // réservé
        command.putFloat(misalignment);
        commands.add(command.array());
        return this;
    }

    /**
     * Ajoute une commande {@code BREAK_JOINT} (C-34, ADR-151 D5) : rompt un joint d'une
     * assembly, comme un seuil de rupture franchi. La recréation d'un joint rompu attend la
     * réparation (M7).
     *
     * @param handleIndex rang du handle d'assembly
     * @param handleGeneration génération du handle
     * @param joint rang du joint dans la definition, de 0 à {@link #MAX_JOINT_INDEX}
     * @return {@code this}, pour chaîner
     */
    public SimCommandStream breakJoint(int handleIndex, int handleGeneration, int joint) {
        checkJointIndex(joint);
        ByteBuffer command = beginCommand(OP_BREAK_JOINT, BREAK_JOINT_BYTES);
        command.putInt(handleIndex);
        command.putInt(handleGeneration);
        command.putShort((short) joint);
        command.putShort((short) 0); // réservé
        commands.add(command.array());
        return this;
    }

    /**
     * Ajoute une commande {@code ANIMATE_ASSEMBLY} (ADR-155) : une {@code AnimCommand} pour une assembly, et
     * les nodes d'un masque. L'instant est un tick du serveur ; le fondu est en secondes.
     *
     * @param handleIndex rang du handle d'assembly
     * @param handleGeneration génération du handle
     * @param tick instant de la commande, en ticks du serveur
     * @param action {@code PLAY}, {@code STOP}, {@code SET_SPEED} ou {@code SET_MASK}, les valeurs de
     *     {@link dev.axion.render.AnimationCommands}
     * @param layer couche, de 0 à 3
     * @param clip rang du clip dans l'asset
     * @param weight poids, de 0 à 1
     * @param fadeSeconds durée du fondu, en secondes
     * @param speed vitesse
     * @param origin temps de clip à l'instant {@code tick}, en secondes ({@code PLAY}) ; 0 : le début
     * @param mask nodes du masque ({@code SET_MASK}) ; vide sinon
     * @return {@code this}, pour chaîner
     */
    public SimCommandStream animateAssembly(
            int handleIndex,
            int handleGeneration,
            double tick,
            int action,
            int layer,
            int clip,
            float weight,
            float fadeSeconds,
            float speed,
            float origin,
            int[] mask) {
        ByteBuffer command = beginCommand(OP_ANIMATE_ASSEMBLY, ANIMATE_ASSEMBLY_BYTES + mask.length * 4);
        command.putInt(handleIndex).putInt(handleGeneration).putDouble(tick).putInt(action).putInt(layer)
                .putInt(clip).putFloat(weight).putFloat(fadeSeconds).putFloat(speed).putInt(mask.length)
                .putFloat(origin);
        for (int node : mask) {
            command.putInt(node);
        }
        commands.add(command.array());
        return this;
    }

    private static void checkJointIndex(int joint) {
        if (joint < 0 || joint > MAX_JOINT_INDEX) {
            throw new IllegalArgumentException("le rang d'un joint tient sur un u16 : " + joint);
        }
    }

    /** {@return {@code value} arrondi au prochain multiple de 8} */
    private static int alignUp8(int value) {
        return (value + 7) & ~7;
    }

    /** {@return le nombre de commandes, à passer à {@code submit}} */
    public int count() {
        return commands.size();
    }

    /**
     * Ajoute à ce flux toutes les commandes d'un autre, dans l'ordre (composition de
     * plusieurs fournisseurs pour un même tick — tuiles du monde, assemblies…).
     *
     * @param other flux dont les commandes sont ajoutées à la fin de celui-ci
     * @return {@code this}, pour chaîner
     */
    public SimCommandStream merge(SimCommandStream other) {
        commands.addAll(other.commands);
        return this;
    }

    /**
     * {@return le flux complet — en-tête puis commandes — prêt à écrire dans
     * {@code SIM_IN}}
     */
    public byte[] toBytes() {
        int total = STREAM_HEADER_BYTES;
        for (byte[] command : commands) {
            total += command.length;
        }
        ByteBuffer stream = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        stream.putInt(CURRENT_SCHEMA);
        stream.putInt(0); // _pad
        for (byte[] command : commands) {
            stream.put(command);
        }
        return stream.array();
    }
}
