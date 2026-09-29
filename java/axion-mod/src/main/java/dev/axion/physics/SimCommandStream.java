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

    /** Version courante du protocole ({@code CommandStreamHeader::CURRENT_SCHEMA}). */
    public static final int CURRENT_SCHEMA = 1;

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

    /**
     * Taille de l'en-tête {@code CreateAssembly}, en octets (hors octets {@code
     * PHYS} qui le suivent). Figé côté natif : {@code CreateAssembly::BYTES}.
     */
    public static final int CREATE_ASSEMBLY_HEADER_BYTES = 64;

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
     * Ajoute une commande {@code CREATE_ASSEMBLY} (§4.5, ADR-114 ; Option A
     * d'ADR-115).
     *
     * <p>Le payload est l'en-tête {@code CreateAssembly} (64 o) suivi des octets de
     * la section {@code PHYS} de l'asset — que l'appelant extrait de l'A3D
     * ({@link dev.axion.asset.A3dSections}). Le natif décode ces colliders, en
     * construit une forme (unique ou composée) et crée le corps à sa pose de spawn.
     * La masse et le centre de masse sont calculés par le moteur depuis les
     * densités des colliders (R-622).
     *
     * @param handleIndex rang du handle d'assembly (routage du corps)
     * @param handleGeneration génération du handle ({@code 0} est invalide)
     * @param dimension dimension d'accueil (R-610)
     * @param position position monde de spawn {@code [x, y, z]}, en blocs
     * @param rotation quaternion de spawn {@code [x, y, z, w]}
     * @param bodyKind {@link #BODY_STATIC}, {@link #BODY_KINEMATIC} ou
     *     {@link #BODY_DYNAMIC}
     * @param phys octets de la section {@code PHYS} (colliders compilés)
     * @return {@code this}, pour chaîner
     */
    public SimCommandStream createAssembly(
            int handleIndex,
            int handleGeneration,
            long dimension,
            double[] position,
            float[] rotation,
            int bodyKind,
            byte[] phys) {
        if (position.length != 3) {
            throw new IllegalArgumentException("la position est un vecteur à 3 composantes");
        }
        if (rotation.length != 4) {
            throw new IllegalArgumentException("la rotation est un quaternion à 4 composantes");
        }
        if (bodyKind < BODY_STATIC || bodyKind > BODY_DYNAMIC) {
            throw new IllegalArgumentException("body_kind inconnu : " + bodyKind);
        }
        if (phys == null) {
            throw new IllegalArgumentException("les octets PHYS sont requis");
        }

        // Le payload (en-tête + PHYS) est aligné sur 8 octets : le lecteur natif
        // avance d'un multiple de 8 après chaque commande, pour lire la suivante
        // en place (R-271, R-881).
        int payloadLen = CREATE_ASSEMBLY_HEADER_BYTES + phys.length;
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
        // Les 7 octets de _pad et le remplissage d'alignement restent à zéro.
        command.position(COMMAND_HEADER_BYTES + CREATE_ASSEMBLY_HEADER_BYTES);
        command.put(phys);
        commands.add(command.array());
        return this;
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
     * @param friction frottement du matériau dominant
     * @param restitution restitution du matériau dominant
     * @return {@code this}, pour chaîner
     */
    public SimCommandStream setWorldCollision(
            long dimension, int[] section, float[][] boxes, float friction, float restitution) {
        checkSection(section);
        checkBoxes(boxes);
        int payloadLen = SET_WORLD_COLLISION_HEADER_BYTES + boxes.length * BOX_BYTES;
        ByteBuffer command = beginCommand(OP_SET_WORLD_COLLISION, payloadLen);
        command.putLong(dimension);
        putSection(command, section);
        command.putInt(boxes.length);
        command.putFloat(friction);
        command.putFloat(restitution);
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
     * @param friction frottement du matériau dominant
     * @param restitution restitution du matériau dominant
     * @return {@code this}, pour chaîner
     */
    public SimCommandStream setWorldHeightfield(
            long dimension,
            int[] section,
            int rows,
            int cols,
            float[] heights,
            float[] scale,
            float friction,
            float restitution) {
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
        command.putFloat(friction);
        command.putFloat(restitution);
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

    /** {@return {@code value} arrondi au prochain multiple de 8} */
    private static int alignUp8(int value) {
        return (value + 7) & ~7;
    }

    /** {@return le nombre de commandes, à passer à {@code submit}} */
    public int count() {
        return commands.size();
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
