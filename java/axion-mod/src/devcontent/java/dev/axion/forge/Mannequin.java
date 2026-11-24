package dev.axion.forge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Le mannequin du joueur (ADR-177 D2, CAP-56) : un GLB à l'échelle du jeu, lu dans le code de Minecraft et vérifié contre lui.
 *
 * <p>Ses cotes sont celles du modèle du joueur, par la même voie que le jeu : {@code PlayerModel.createMesh}, le modèle large ou
 * fin, cuit comme le fait le client. Aucun nombre n'est écrit ici : les cubes, les pivots, les rotations de chaque pose viennent
 * du modèle, dont {@code setupAnim} donne le debout, l'accroupi et l'assis (la pose de monture : {@code riding}). Aucune texture de
 * Mojang : celle du fichier est neutre, rangée au format d'un skin (64 × 64), teintée par partie pour qu'on s'y repère.
 *
 * <p>Le fichier suit la hiérarchie du jeu : un nœud racine, et un nœud par partie du corps, aux pivots du modèle ; chaque pose
 * est un clip d'une seule image (debout, accroupi, assis) qui pose chaque partie. La racine porte ce que le
 * rendu de Minecraft fait du modèle : l'échelle des joueurs (0,9375), le retournement du modèle (x et y) et sa remontée de 1,501 ;
 * le lacet n'y est pas : le modèle regarde vers -Z, l'avant d'AXION. Posé au node {@code SEAT} d'un véhicule, le clip {@code sit}
 * y met l'entité du joueur où AXION la met : au décalage de monture de Minecraft sous le node.
 */
final class Mannequin {

    /** Les parties du corps, dans l'ordre du fichier : le corps, puis la seconde couche de chaque partie. */
    static final List<String> PARTIES = List.of("head", "hat", "body", "jacket", "right_arm", "right_sleeve", "left_arm",
            "left_sleeve", "right_leg", "right_pants", "left_leg", "left_pants");

    /** L'échelle des joueurs au rendu ({@code PlayerRenderer.scale}), et la remontée du modèle ({@code LivingEntityRenderer}). */
    static final float ECHELLE = 0.9375f;

    static final float REMONTEE = 1.501f;

    /** Le décalage du rendu d'un joueur accroupi ({@code PlayerRenderer.getRenderOffset}), en blocs. */
    static final float DECALAGE_ACCROUPI = -0.125f;

    /** Les poses du fichier. */
    enum Pose {
        DEBOUT("stand"), ACCROUPI("sneak"), ASSIS("sit");

        final String clip;

        Pose(String clip) {
            this.clip = clip;
        }
    }

    /** Les deux modèles du jeu : le large (bras de 4) et le fin (bras de 3). */
    enum Modele {
        LARGE("joueur_large", false), FIN("joueur_fin", true);

        final String fichier;
        final boolean fin;

        Modele(String fichier, boolean fin) {
            this.fichier = fichier;
            this.fin = fin;
        }
    }

    /**
     * Une partie du corps : sa géométrie, au repos, et sa pose à chaque clip.
     *
     * @param nom son nom dans le jeu
     * @param positions les sommets, en blocs, dans le repère de la partie (y vers le bas, comme le modèle)
     * @param normales les normales, une par sommet
     * @param uv les coordonnées de texture, entre 0 et 1, l'origine en haut à gauche
     * @param indices les triangles
     */
    record Partie(String nom, float[] positions, float[] normales, float[] uv, int[] indices) {}

    /** La pose d'une partie : son pivot en blocs et sa rotation en radians (x, y, z). */
    record Articulation(float[] pivot, float[] rotation) {}

    private Mannequin() {}

    /** {@return les octets du mannequin du dépôt, tel que le jeu les charge, {@code null} s'il manque} */
    static byte[] depot(Modele modele) {
        try (java.io.InputStream in = Mannequin.class.getResourceAsStream("/axion/models/mannequin/" + modele.fichier + ".glb")) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException faute) {
            throw new UncheckedIOException(faute);
        }
    }

    /** {@return le décalage vertical de la racine, en blocs, de chaque pose : la remontée du rendu et le décalage de la pose} */
    static float hauteurRacine(Pose pose, float decalageMonture) {
        float base = ECHELLE * REMONTEE;
        return switch (pose) {
            case DEBOUT -> base;
            case ACCROUPI -> base + DECALAGE_ACCROUPI;
            case ASSIS -> base + decalageMonture;
        };
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Le modèle du jeu
    // ----------------------------------------------------------------------------------------------------------------

    /** {@return le modèle du joueur que le client cuit : le même maillage, 64 × 64} */
    static PlayerModel<LivingEntity> modele(Modele modele) {
        ModelPart racine = LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, modele.fin), 64, 64)
                .bakeRoot();
        return new PlayerModel<>(racine, modele.fin);
    }

    /** {@return le modèle, mis dans la pose, par {@code setupAnim} du jeu} */
    static PlayerModel<LivingEntity> modeleEnPose(Modele modele, Pose pose, LivingEntity entite) {
        PlayerModel<LivingEntity> cuit = modele(modele);
        cuit.riding = pose == Pose.ASSIS;
        cuit.crouching = pose == Pose.ACCROUPI;
        cuit.setupAnim(entite, 0f, 0f, 0f, 0f, 0f);
        return cuit;
    }

    /** {@return la partie de ce nom du modèle} */
    static ModelPart partie(PlayerModel<LivingEntity> modele, String nom) {
        return switch (nom) {
            case "head" -> modele.head;
            case "hat" -> modele.hat;
            case "body" -> modele.body;
            case "jacket" -> modele.jacket;
            case "right_arm" -> modele.rightArm;
            case "right_sleeve" -> modele.rightSleeve;
            case "left_arm" -> modele.leftArm;
            case "left_sleeve" -> modele.leftSleeve;
            case "right_leg" -> modele.rightLeg;
            case "right_pants" -> modele.rightPants;
            case "left_leg" -> modele.leftLeg;
            case "left_pants" -> modele.leftPants;
            default -> throw new IllegalArgumentException(nom);
        };
    }

    /** {@return l'articulation d'une partie : ce que le jeu en a fait, pivot et rotation} */
    static Articulation articulation(ModelPart partie) {
        return new Articulation(new float[] {partie.x / 16f, partie.y / 16f, partie.z / 16f},
                new float[] {partie.xRot, partie.yRot, partie.zRot});
    }

    /** {@return la géométrie d'une partie, lue dans son cube : ses polygones, que Minecraft ne publie pas} */
    static Partie geometrie(String nom, ModelPart partie) {
        List<float[]> positions = new ArrayList<>();
        List<float[]> normales = new ArrayList<>();
        List<float[]> uvs = new ArrayList<>();
        List<int[]> triangles = new ArrayList<>();
        try {
            Field cubes = ModelPart.class.getDeclaredField("cubes");
            cubes.setAccessible(true);
            for (Object cube : (List<?>) cubes.get(partie)) {
                Field polygones = cube.getClass().getDeclaredField("polygons");
                polygones.setAccessible(true);
                for (Object polygone : (Object[]) polygones.get(cube)) {
                    Field sommets = polygone.getClass().getDeclaredField("vertices");
                    sommets.setAccessible(true);
                    Field normale = polygone.getClass().getDeclaredField("normal");
                    normale.setAccessible(true);
                    Vector3f n = (Vector3f) normale.get(polygone);
                    Object[] quatre = (Object[]) sommets.get(polygone);
                    int premier = positions.size();
                    for (Object sommet : quatre) {
                        Field pos = sommet.getClass().getDeclaredField("pos");
                        Field u = sommet.getClass().getDeclaredField("u");
                        Field v = sommet.getClass().getDeclaredField("v");
                        pos.setAccessible(true);
                        u.setAccessible(true);
                        v.setAccessible(true);
                        Vector3f p = (Vector3f) pos.get(sommet);
                        positions.add(new float[] {p.x / 16f, p.y / 16f, p.z / 16f});
                        normales.add(new float[] {n.x, n.y, n.z});
                        uvs.add(new float[] {u.getFloat(sommet), v.getFloat(sommet)});
                    }
                    // Le sens des triangles se déduit de la normale du polygone : CCW vu de côté de la normale, comme glTF.
                    float[] a = positions.get(premier);
                    float[] b = positions.get(premier + 1);
                    float[] c = positions.get(premier + 2);
                    Vector3f produit = new Vector3f(b[0] - a[0], b[1] - a[1], b[2] - a[2])
                            .cross(new Vector3f(c[0] - a[0], c[1] - a[1], c[2] - a[2]));
                    boolean sens = produit.dot(n) >= 0f;
                    triangles.add(sens ? new int[] {premier, premier + 1, premier + 2}
                            : new int[] {premier, premier + 2, premier + 1});
                    triangles.add(sens ? new int[] {premier, premier + 2, premier + 3}
                            : new int[] {premier, premier + 3, premier + 2});
                }
            }
        } catch (ReflectiveOperationException faute) {
            throw new IllegalStateException("le modèle du joueur n'a plus la forme de 1.20.1 : " + faute, faute);
        }
        return new Partie(nom, aplatir(positions, 3), aplatir(normales, 3), aplatir(uvs, 2), indices(triangles));
    }

    private static float[] aplatir(List<float[]> lignes, int largeur) {
        float[] tout = new float[lignes.size() * largeur];
        for (int i = 0; i < lignes.size(); i++) {
            System.arraycopy(lignes.get(i), 0, tout, i * largeur, largeur);
        }
        return tout;
    }

    private static int[] indices(List<int[]> triangles) {
        int[] tout = new int[triangles.size() * 3];
        for (int i = 0; i < triangles.size(); i++) {
            System.arraycopy(triangles.get(i), 0, tout, i * 3, 3);
        }
        return tout;
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Le fichier
    // ----------------------------------------------------------------------------------------------------------------

    /**
     * {@return le GLB d'un modèle}
     *
     * @param modele le modèle
     * @param entite un joueur, pour que {@code setupAnim} ait son entité
     * @param decalageMonture le décalage de monture d'un joueur assis ({@code getMyRidingOffset}), en blocs
     */
    static byte[] ecrire(Modele modele, LivingEntity entite, float decalageMonture) {
        Binaire binaire = new Binaire();
        JsonArray nodes = new JsonArray();
        JsonArray meshes = new JsonArray();
        JsonObject racine = new JsonObject();
        racine.addProperty("name", modele.fichier);
        JsonObject extras = new JsonObject();
        JsonObject axion = new JsonObject();
        axion.addProperty("mannequin", modele.fin ? "fin" : "large");
        axion.addProperty("minecraft", "1.20.1");
        axion.addProperty("echelle_du_rendu", ECHELLE);
        axion.addProperty("decalage_de_monture", decalageMonture);
        axion.addProperty("poses", "stand, sneak, sit : un clip d'une image chacun");
        extras.add("axion", axion);
        racine.add("extras", extras);
        JsonArray enfants = new JsonArray();
        nodes.add(racine);

        PlayerModel<LivingEntity> repos = modeleEnPose(modele, Pose.DEBOUT, entite);
        BufferedImage texture = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < 64 * 64; i++) {
            texture.setRGB(i % 64, i / 64, 0xFFC8C8C8);
        }
        for (int rang = 0; rang < PARTIES.size(); rang++) {
            String nom = PARTIES.get(rang);
            ModelPart partie = partie(repos, nom);
            Partie geometrie = geometrie(nom, partie);
            teinter(texture, geometrie, rang);
            Articulation debout = articulation(partie);
            JsonObject noeud = new JsonObject();
            noeud.addProperty("name", nom);
            noeud.add("translation", vecteur(debout.pivot()));
            noeud.add("rotation", quaternion(debout.rotation()));
            noeud.addProperty("mesh", meshes.size());
            nodes.add(noeud);
            enfants.add(nodes.size() - 1);

            JsonObject primitive = new JsonObject();
            JsonObject attributs = new JsonObject();
            attributs.addProperty("POSITION", binaire.accesseur(geometrie.positions(), 3, "VEC3", true));
            attributs.addProperty("NORMAL", binaire.accesseur(geometrie.normales(), 3, "VEC3", false, 34962));
            attributs.addProperty("TEXCOORD_0", binaire.accesseur(geometrie.uv(), 2, "VEC2", false, 34962));
            primitive.add("attributes", attributs);
            primitive.addProperty("indices", binaire.indices(geometrie.indices()));
            primitive.addProperty("material", 0);
            JsonArray primitives = new JsonArray();
            primitives.add(primitive);
            JsonObject mesh = new JsonObject();
            mesh.addProperty("name", nom);
            mesh.add("primitives", primitives);
            meshes.add(mesh);
        }
        racine.add("children", enfants);
        Pose debout = Pose.DEBOUT;
        racine.add("translation", vecteur(new float[] {0f, hauteurRacine(debout, decalageMonture), 0f}));
        // Le retournement du modèle par le rendu (x et y : un demi-tour autour de z). Le modèle de Minecraft regarde vers -Z, avant
        // que le rendu ne le tourne à son lacet : c'est aussi l'avant d'AXION (§6.5), le fichier n'ajoute aucun demi-tour.
        racine.add("rotation", quaternionBrut(0f, 0f, 1f, 0f));
        JsonArray echelle = new JsonArray();
        for (int i = 0; i < 3; i++) {
            echelle.add(ECHELLE);
        }
        racine.add("scale", echelle);

        int temps = binaire.accesseur(new float[] {0f}, 1, "SCALAR", true);
        JsonArray animations = new JsonArray();
        for (Pose pose : Pose.values()) {
            PlayerModel<LivingEntity> cuit = modeleEnPose(modele, pose, entite);
            JsonArray canaux = new JsonArray();
            JsonArray echantillons = new JsonArray();
            // La racine : sa hauteur change avec la pose.
            ajouterCanal(canaux, echantillons, 0, "translation", temps,
                    binaire.accesseur(new float[] {0f, hauteurRacine(pose, decalageMonture), 0f}, 3, "VEC3", false));
            for (int rang = 0; rang < PARTIES.size(); rang++) {
                Articulation articulation = articulation(partie(cuit, PARTIES.get(rang)));
                ajouterCanal(canaux, echantillons, rang + 1, "translation", temps,
                        binaire.accesseur(articulation.pivot(), 3, "VEC3", false));
                ajouterCanal(canaux, echantillons, rang + 1, "rotation", temps,
                        binaire.accesseur(quaternionTableau(articulation.rotation()), 4, "VEC4", false));
            }
            JsonObject clip = new JsonObject();
            clip.addProperty("name", pose.clip);
            clip.add("channels", canaux);
            clip.add("samplers", echantillons);
            animations.add(clip);
        }

        byte[] png = png(texture);
        int vuePng = binaire.vue(png, null);
        JsonObject image = new JsonObject();
        image.addProperty("bufferView", vuePng);
        image.addProperty("mimeType", "image/png");
        image.addProperty("name", "skin_neutre");
        JsonObject echantillonneur = new JsonObject();
        echantillonneur.addProperty("magFilter", 9728);
        echantillonneur.addProperty("minFilter", 9728);
        JsonObject materiau = new JsonObject();
        materiau.addProperty("name", "skin");
        JsonObject pbr = new JsonObject();
        JsonObject base = new JsonObject();
        base.addProperty("index", 0);
        pbr.add("baseColorTexture", base);
        pbr.addProperty("metallicFactor", 0.0);
        pbr.addProperty("roughnessFactor", 1.0);
        materiau.add("pbrMetallicRoughness", pbr);
        materiau.addProperty("alphaMode", "OPAQUE");
        JsonObject tex = new JsonObject();
        tex.addProperty("source", 0);
        tex.addProperty("sampler", 0);

        JsonObject glb = new JsonObject();
        JsonObject actif = new JsonObject();
        actif.addProperty("version", "2.0");
        actif.addProperty("generator", "AXION ENGINE, ./gradlew genMannequin (ADR-177 D2)");
        glb.add("asset", actif);
        glb.addProperty("scene", 0);
        JsonArray scenes = new JsonArray();
        JsonObject scene = new JsonObject();
        JsonArray racines = new JsonArray();
        racines.add(0);
        scene.add("nodes", racines);
        scenes.add(scene);
        glb.add("scenes", scenes);
        glb.add("nodes", nodes);
        glb.add("meshes", meshes);
        glb.add("animations", animations);
        glb.add("materials", un(materiau));
        glb.add("textures", un(tex));
        glb.add("images", un(image));
        glb.add("samplers", un(echantillonneur));
        binaire.verserDans(glb);
        return conteneur(glb.toString().getBytes(StandardCharsets.UTF_8), binaire.octets());
    }

    private static JsonArray un(JsonObject objet) {
        JsonArray tableau = new JsonArray();
        tableau.add(objet);
        return tableau;
    }

    private static void ajouterCanal(JsonArray canaux, JsonArray echantillons, int noeud, String chemin, int temps,
            int valeurs) {
        JsonObject echantillon = new JsonObject();
        echantillon.addProperty("input", temps);
        echantillon.addProperty("output", valeurs);
        echantillon.addProperty("interpolation", "STEP");
        echantillons.add(echantillon);
        JsonObject cible = new JsonObject();
        cible.addProperty("node", noeud);
        cible.addProperty("path", chemin);
        JsonObject canal = new JsonObject();
        canal.addProperty("sampler", echantillons.size() - 1);
        canal.add("target", cible);
        canaux.add(canal);
    }

    /** Teinte les rectangles d'une partie, d'une couleur claire par partie : la seconde couche est plus pâle. */
    private static void teinter(BufferedImage texture, Partie partie, int rang) {
        int[] teintes = {0xE8B4B4, 0xF2D6D6, 0xB4C8E8, 0xD6E0F2, 0xB4E8C0, 0xD6F2DC, 0xE8E0B4, 0xF2EED6, 0xD0B4E8, 0xE6D6F2,
            0xB4E8E4, 0xD6F2F0};
        int couleur = 0xFF000000 | teintes[rang];
        float[] uv = partie.uv();
        for (int quad = 0; quad + 3 < uv.length / 2; quad += 4) {
            float u0 = Float.MAX_VALUE;
            float v0 = Float.MAX_VALUE;
            float u1 = -Float.MAX_VALUE;
            float v1 = -Float.MAX_VALUE;
            for (int k = 0; k < 4; k++) {
                u0 = Math.min(u0, uv[(quad + k) * 2]);
                u1 = Math.max(u1, uv[(quad + k) * 2]);
                v0 = Math.min(v0, uv[(quad + k) * 2 + 1]);
                v1 = Math.max(v1, uv[(quad + k) * 2 + 1]);
            }
            for (int y = Math.round(v0 * 64f); y < Math.round(v1 * 64f); y++) {
                for (int x = Math.round(u0 * 64f); x < Math.round(u1 * 64f); x++) {
                    texture.setRGB(x, y, couleur);
                }
            }
        }
    }

    private static byte[] png(BufferedImage image) {
        try {
            ByteArrayOutputStream sortie = new ByteArrayOutputStream();
            ImageIO.write(image, "png", sortie);
            return sortie.toByteArray();
        } catch (IOException faute) {
            throw new UncheckedIOException(faute);
        }
    }

    private static JsonArray vecteur(float[] valeurs) {
        JsonArray tableau = new JsonArray();
        for (float valeur : valeurs) {
            tableau.add(valeur);
        }
        return tableau;
    }

    /** {@return le quaternion {@code [x, y, z, w]} d'une rotation d'Euler du jeu, appliquée z, puis y, puis x} */
    static float[] quaternionTableau(float[] rotation) {
        Quaternionf q = new Quaternionf().rotationZYX(rotation[2], rotation[1], rotation[0]);
        return new float[] {q.x, q.y, q.z, q.w};
    }

    private static JsonArray quaternion(float[] rotation) {
        return vecteur(quaternionTableau(rotation));
    }

    private static JsonArray quaternionBrut(float x, float y, float z, float w) {
        return vecteur(new float[] {x, y, z, w});
    }

    /** Le conteneur GLB : l'en-tête, le bloc JSON, le bloc binaire, chacun complété à quatre octets. */
    private static byte[] conteneur(byte[] json, byte[] bin) {
        int jsonLong = (json.length + 3) & ~3;
        int binLong = (bin.length + 3) & ~3;
        ByteBuffer tampon = ByteBuffer.allocate(12 + 8 + jsonLong + 8 + binLong).order(ByteOrder.LITTLE_ENDIAN);
        tampon.putInt(0x46546C67).putInt(2).putInt(tampon.capacity());
        tampon.putInt(jsonLong).putInt(0x4E4F534A).put(json);
        for (int i = json.length; i < jsonLong; i++) {
            tampon.put((byte) ' ');
        }
        tampon.putInt(binLong).putInt(0x004E4942).put(bin);
        return tampon.array();
    }

    /** Le tampon binaire, ses vues et ses accesseurs. */
    private static final class Binaire {
        private final ByteArrayOutputStream octets = new ByteArrayOutputStream();
        private final JsonArray vues = new JsonArray();
        private final JsonArray accesseurs = new JsonArray();

        /** Ajoute des flottants ; rend le rang de l'accesseur. */
        int accesseur(float[] valeurs, int composantes, String type, boolean bornes) {
            return accesseur(valeurs, composantes, type, bornes, bornes ? 34962 : null);
        }

        /** Ajoute des flottants, d'une cible de vue donnée ({@code null} pour ce qui n'est pas un sommet). */
        int accesseur(float[] valeurs, int composantes, String type, boolean bornes, Integer cible) {
            ByteBuffer tampon = ByteBuffer.allocate(valeurs.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (float valeur : valeurs) {
                tampon.putFloat(valeur);
            }
            int vue = vue(tampon.array(), cible);
            JsonObject accesseur = new JsonObject();
            accesseur.addProperty("bufferView", vue);
            accesseur.addProperty("componentType", 5126);
            accesseur.addProperty("count", valeurs.length / composantes);
            accesseur.addProperty("type", type);
            if (bornes) {
                float[] min = new float[composantes];
                float[] max = new float[composantes];
                java.util.Arrays.fill(min, Float.MAX_VALUE);
                java.util.Arrays.fill(max, -Float.MAX_VALUE);
                for (int i = 0; i < valeurs.length; i++) {
                    min[i % composantes] = Math.min(min[i % composantes], valeurs[i]);
                    max[i % composantes] = Math.max(max[i % composantes], valeurs[i]);
                }
                accesseur.add("min", vecteur(min));
                accesseur.add("max", vecteur(max));
            }
            accesseurs.add(accesseur);
            return accesseurs.size() - 1;
        }

        int indices(int[] valeurs) {
            ByteBuffer tampon = ByteBuffer.allocate(valeurs.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (int valeur : valeurs) {
                tampon.putShort((short) valeur);
            }
            int vue = vue(tampon.array(), 34963);
            JsonObject accesseur = new JsonObject();
            accesseur.addProperty("bufferView", vue);
            accesseur.addProperty("componentType", 5123);
            accesseur.addProperty("count", valeurs.length);
            accesseur.addProperty("type", "SCALAR");
            accesseurs.add(accesseur);
            return accesseurs.size() - 1;
        }

        /** Ajoute une vue, complétée à quatre octets ; rend son rang. */
        int vue(byte[] donnees, Integer cible) {
            while (octets.size() % 4 != 0) {
                octets.write(0);
            }
            JsonObject vue = new JsonObject();
            vue.addProperty("buffer", 0);
            vue.addProperty("byteOffset", octets.size());
            vue.addProperty("byteLength", donnees.length);
            if (cible != null) {
                vue.addProperty("target", cible);
            }
            octets.writeBytes(donnees);
            vues.add(vue);
            return vues.size() - 1;
        }

        void verserDans(JsonObject glb) {
            JsonArray tampons = new JsonArray();
            JsonObject tampon = new JsonObject();
            tampon.addProperty("byteLength", octets.size());
            tampons.add(tampon);
            glb.add("buffers", tampons);
            glb.add("bufferViews", vues);
            glb.add("accessors", accesseurs);
        }

        byte[] octets() {
            return octets.toByteArray();
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // La lecture : ce que le fichier pose, sommet par sommet
    // ----------------------------------------------------------------------------------------------------------------

    /**
     * Les sommets de chaque partie d'un GLB, dans le repère de sa scène, à la pose d'un clip : ce que Blender, et tout autre
     * outil, y verrait.
     *
     * @param glb le fichier
     * @param clip le clip, {@code stand}, {@code sneak} ou {@code sit}
     * @return les sommets, trois flottants chacun, par nom de partie
     */
    static Map<String, float[]> sommets(byte[] glb, String clip) {
        Lu lu = lire(glb, clip);
        JsonObject json = lu.json();
        ByteBuffer tampon = lu.tampon();
        int binDebut = lu.binDebut();
        Matrix4f[] mondes = lu.mondes();
        JsonArray nodes = json.getAsJsonArray("nodes");
        Map<String, float[]> resultat = new LinkedHashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            JsonObject noeud = nodes.get(i).getAsJsonObject();
            if (!noeud.has("mesh")) {
                continue;
            }
            JsonObject primitive = json.getAsJsonArray("meshes").get(noeud.get("mesh").getAsInt()).getAsJsonObject()
                    .getAsJsonArray("primitives").get(0).getAsJsonObject();
            float[] locaux = lireFlottants(json, tampon, binDebut,
                    primitive.getAsJsonObject("attributes").get("POSITION").getAsInt());
            float[] monde = new float[locaux.length];
            Vector3f p = new Vector3f();
            for (int k = 0; k < locaux.length; k += 3) {
                mondes[i].transformPosition(p.set(locaux[k], locaux[k + 1], locaux[k + 2]));
                monde[k] = p.x;
                monde[k + 1] = p.y;
                monde[k + 2] = p.z;
            }
            resultat.put(noeud.get("name").getAsString(), monde);
        }
        return resultat;
    }

    /**
     * {@return où le fichier pose l'origine de l'entité, dans le repère de sa scène, à la pose d'un clip} Le jeu pose le modèle sur la
     * position de l'entité, remontée de 1,501 : c'est le point {@code (0, 1,501, 0)} du modèle. Un joueur assis, c'est là où le
     * serveur le met, sous le node {@code SEAT} de son décalage de monture.
     *
     * @param glb le fichier
     * @param clip le clip
     */
    static float[] origine(byte[] glb, String clip) {
        Vector3f p = lire(glb, clip).mondes()[0].transformPosition(new Vector3f(0f, REMONTEE, 0f));
        return new float[] {p.x, p.y, p.z};
    }

    /** Un GLB lu : son JSON, son binaire, et la matrice monde de chaque nœud à la pose d'un clip. */
    private record Lu(JsonObject json, ByteBuffer tampon, int binDebut, Matrix4f[] mondes) {}

    private static Lu lire(byte[] glb, String clip) {
        ByteBuffer tampon = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);
        if (tampon.getInt(0) != 0x46546C67) {
            throw new IllegalArgumentException("pas un GLB");
        }
        int jsonLong = tampon.getInt(12);
        JsonObject json = JsonParser.parseString(new String(glb, 20, jsonLong, StandardCharsets.UTF_8)).getAsJsonObject();
        int binDebut = 20 + jsonLong + 8;
        JsonArray nodes = json.getAsJsonArray("nodes");
        // La pose de base de chaque nœud, puis ce que le clip lui pose.
        Vector3f[] translations = new Vector3f[nodes.size()];
        Quaternionf[] rotations = new Quaternionf[nodes.size()];
        Vector3f[] echelles = new Vector3f[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            JsonObject noeud = nodes.get(i).getAsJsonObject();
            translations[i] = noeud.has("translation") ? vecteur3(noeud.getAsJsonArray("translation")) : new Vector3f();
            rotations[i] = noeud.has("rotation") ? quaternion4(noeud.getAsJsonArray("rotation")) : new Quaternionf();
            echelles[i] = noeud.has("scale") ? vecteur3(noeud.getAsJsonArray("scale")) : new Vector3f(1f, 1f, 1f);
        }
        for (var animation : json.getAsJsonArray("animations")) {
            JsonObject lue = animation.getAsJsonObject();
            if (!clip.equals(lue.get("name").getAsString())) {
                continue;
            }
            JsonArray echantillons = lue.getAsJsonArray("samplers");
            for (var canal : lue.getAsJsonArray("channels")) {
                JsonObject c = canal.getAsJsonObject();
                int noeud = c.getAsJsonObject("target").get("node").getAsInt();
                String chemin = c.getAsJsonObject("target").get("path").getAsString();
                int sortie = echantillons.get(c.get("sampler").getAsInt()).getAsJsonObject().get("output").getAsInt();
                float[] valeurs = lireFlottants(json, tampon, binDebut, sortie);
                if (chemin.equals("translation")) {
                    translations[noeud].set(valeurs[0], valeurs[1], valeurs[2]);
                } else {
                    rotations[noeud].set(valeurs[0], valeurs[1], valeurs[2], valeurs[3]);
                }
            }
        }
        Matrix4f[] mondes = new Matrix4f[nodes.size()];
        placer(json, nodes, 0, new Matrix4f(), translations, rotations, echelles, mondes);
        return new Lu(json, tampon, binDebut, mondes);
    }

    private static void placer(JsonObject json, JsonArray nodes, int rang, Matrix4f parent, Vector3f[] translations,
            Quaternionf[] rotations, Vector3f[] echelles, Matrix4f[] mondes) {
        Matrix4f monde = new Matrix4f(parent).translate(translations[rang]).rotate(rotations[rang]).scale(echelles[rang]);
        mondes[rang] = monde;
        JsonObject noeud = nodes.get(rang).getAsJsonObject();
        if (noeud.has("children")) {
            for (var enfant : noeud.getAsJsonArray("children")) {
                placer(json, nodes, enfant.getAsInt(), monde, translations, rotations, echelles, mondes);
            }
        }
    }

    private static Vector3f vecteur3(JsonArray valeurs) {
        return new Vector3f(valeurs.get(0).getAsFloat(), valeurs.get(1).getAsFloat(), valeurs.get(2).getAsFloat());
    }

    private static Quaternionf quaternion4(JsonArray valeurs) {
        return new Quaternionf(valeurs.get(0).getAsFloat(), valeurs.get(1).getAsFloat(), valeurs.get(2).getAsFloat(),
                valeurs.get(3).getAsFloat());
    }

    private static float[] lireFlottants(JsonObject json, ByteBuffer tampon, int binDebut, int accesseur) {
        JsonObject a = json.getAsJsonArray("accessors").get(accesseur).getAsJsonObject();
        JsonObject vue = json.getAsJsonArray("bufferViews").get(a.get("bufferView").getAsInt()).getAsJsonObject();
        int composantes = switch (a.get("type").getAsString()) {
            case "SCALAR" -> 1;
            case "VEC2" -> 2;
            case "VEC3" -> 3;
            default -> 4;
        };
        float[] valeurs = new float[a.get("count").getAsInt() * composantes];
        int debut = binDebut + vue.get("byteOffset").getAsInt();
        for (int i = 0; i < valeurs.length; i++) {
            valeurs[i] = tampon.getFloat(debut + i * 4);
        }
        return valeurs;
    }

    /** {@return l'image du fichier, lue comme un lecteur la lirait : sa taille} */
    static int[] tailleDeLaTexture(byte[] glb) {
        ByteBuffer tampon = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);
        int jsonLong = tampon.getInt(12);
        JsonObject json = JsonParser.parseString(new String(glb, 20, jsonLong, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject vue = json.getAsJsonArray("bufferViews")
                .get(json.getAsJsonArray("images").get(0).getAsJsonObject().get("bufferView").getAsInt()).getAsJsonObject();
        int debut = 20 + jsonLong + 8 + vue.get("byteOffset").getAsInt();
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(glb, debut, vue.get("byteLength").getAsInt()));
            return new int[] {image.getWidth(), image.getHeight()};
        } catch (IOException faute) {
            throw new UncheckedIOException(faute);
        }
    }

    /**
     * {@return les sommets de chaque partie, à la pose du jeu, dans le repère du fichier} Le calcul est celui du rendu de Minecraft
     * ({@code LivingEntityRenderer.render}), fait à la main avec les types du jeu : {@code PoseStack}, la partie qui se
     * pose et se tourne comme elle le fait en dessinant.
     *
     * @param modele le modèle, mis dans sa pose
     * @param pose la pose, pour la hauteur de la racine
     * @param decalageMonture le décalage de monture d'un joueur assis
     */
    static Map<String, float[]> sommetsDuJeu(PlayerModel<LivingEntity> modele, Pose pose, float decalageMonture) {
        Map<String, float[]> resultat = new LinkedHashMap<>();
        for (String nom : PARTIES) {
            ModelPart partie = partie(modele, nom);
            Partie geometrie = geometrie(nom, partie);
            PoseStack pile = new PoseStack();
            // La racine du fichier : la pose, puis le rendu du jeu (retournement, échelle, remontée), sans le lacet.
            pile.translate(0f, pose == Pose.ACCROUPI ? DECALAGE_ACCROUPI : pose == Pose.ASSIS ? decalageMonture : 0f, 0f);
            pile.scale(-1f, -1f, 1f);
            pile.scale(ECHELLE, ECHELLE, ECHELLE);
            pile.translate(0f, -REMONTEE, 0f);
            partie.translateAndRotate(pile);
            Matrix4f matrice = pile.last().pose();
            float[] locaux = geometrie.positions();
            float[] monde = new float[locaux.length];
            Vector3f p = new Vector3f();
            for (int k = 0; k < locaux.length; k += 3) {
                matrice.transformPosition(p.set(locaux[k], locaux[k + 1], locaux[k + 2]));
                monde[k] = p.x;
                monde[k + 1] = p.y;
                monde[k + 2] = p.z;
            }
            resultat.put(nom, monde);
        }
        return resultat;
    }
}
