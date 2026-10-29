package dev.axion.forge;

import dev.axion.AxionMod;
import dev.axion.asset.AssetRegistry;
import dev.axion.forge.client.AxionRenderPass;
import dev.axion.forge.client.GlStateCheck;
import dev.axion.diag.StatusReport;
import dev.axion.lifecycle.AxionRuntime;
import dev.axion.render.BackendSelection;
import dev.axion.render.GpuPassTimings;
import dev.axion.render.RenderCapabilities;
import dev.axion.render.ShaderBinaryFile;
import dev.axion.config.ConfigLoader;
import dev.axion.config.ConfigSchema.Scope;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.Options;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Banc de rendu client (ADR-126) : vérifie mécaniquement les tests de rendu de M3 — T-470 rendu
 * vanilla inchangé, T-471 aucune erreur GL sur 10 000 frames, T-472 état GL restauré après chaque
 * passe, T-473 aucun appel GL hors du render thread, T-474 assembly visible, orientée, éclairée ;
 * sur le backend natif, T-479 aussi : des shaders refusés au rechargement des ressources font
 * basculer sur vanilla, sans crash ; sous Oculus et un shaderpack, T-480 et T-490 : le backend
 * vanilla retenu de lui-même, et des objets que le shaderpack éclaire.
 *
 * <p>Inerte sans {@code -Daxion.rendertest} ; les lancements Gradle {@code runRenderTest},
 * {@code runRenderTestNative} et {@code runRenderTestShaders} le posent, chacun avec son backend
 * — le dernier sans en imposer.
 * Il crée un monde plat neuf, fige la scène, joue son scénario frame après frame, écrit
 * {@code axion-rendertest/rapport.json} et ses captures, puis arrête le client ; la tâche Gradle
 * échoue si le rapport manque ou n'est pas vert.
 *
 * <p>Toute comparaison se fait dans un même passage : jamais contre une image de référence, qui
 * dépendrait du pilote (ADR-126 §4).
 *
 * <p>Contenu de développement, jamais empaqueté (R-1790). Render thread seul, sauf ce qu'il confie
 * au serveur intégré.
 */
@Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class BancDeRendu {

    /** Le journal du banc : il n'est pas compté parmi les erreurs d'AXION. */
    private static final Logger LOGGER = LoggerFactory.getLogger("axion.banc");

    private static final boolean ACTIF = System.getProperty("axion.rendertest") != null;

    private static final String MONDE = "axion-rendertest";

    /** T-471. */
    private static final int FRAMES_T471 = 10_000;

    /** Frames comptées sans AXION avant T-471 : les erreurs qui ne seraient pas les siennes. */
    private static final int FRAMES_SANS_AXION = 300;

    /** Au-delà, le banc s'arrête en rouge plutôt que de tourner sans fin. */
    private static final long DUREE_MAX_MS = 40 * 60_000L;

    /** Écart toléré entre la boîte d'une silhouette et celle de sa projection, en pixels. */
    private static final int ECART_BOITE = 2;

    /** T-904 : frames témoins, avant toute remise en service, puis frames relevées après chacune. */
    private static final int FRAMES_TEMOINS_T904 = 600;
    private static final int FRAMES_APRES_T904 = 120;

    /** T-491 : écart toléré entre les boîtes du cube dans les deux backends, même scène, même vue. */
    private static final int ECART_BACKENDS = 1;

    /**
     * Une scène est stable quand {@value} relevés de suite, espacés de {@link #TICKS_ENTRE_RELEVES}
     * ticks de jeu, sont identiques à leur précédent. Un délai fixe ne suffit pas : des sections se
     * recompilent après coup — la lumière d'un bloc posé, une vue nouvelle quand la caméra tourne —,
     * et le troisième passage en CI a capturé une scène qui changeait encore. Et l'espacement se
     * compte en ticks, non en frames : Minecraft ne change d'état qu'au tick — lightmap comprise —,
     * et sur une machine rapide, des relevés espacés de quelques frames tenaient dans un seul tick
     * (premier passage local : une nuit capturée encore éclairée, une section lointaine mise à jour
     * juste après les relevés).
     */
    private static final int RELEVES_EGAUX = 5;

    private static final int TICKS_ENTRE_RELEVES = 2;

    /** Au-delà, une scène qui ne se stabilise pas fait échouer le banc. */
    private static final int STABILITE_MAX_S = 180;

    /**
     * Assemblies posées derrière la caméra : toutes les passes, opaque, découpe, translucide, émission.
     * Hors du champ, le culling (C-64) les écarterait ; le banc les marque {@code noCulling} côté client
     * pour qu'elles exercent encore chaque passe, sans paraître à l'image.
     */
    private static final List<String> DERRIERE = List.of(
            "axion:test_cube",
            "axion:test/materiaux/atlas",
            "axion:test/materiaux/cube_texture",
            "axion:test/materiaux/grille",
            "axion:test/materiaux/multi_materiaux",
            "axion:test/materiaux/panneau_emissif",
            "axion:test/materiaux/ressource",
            "axion:test/materiaux/sol_repete",
            "axion:test/materiaux/vitre");

    private static final String CUBE = "axion:test_cube";

    /**
     * ADR-151 D9 : deux cubes, {@code a} statique et {@code b} pendu à une charnière d'axe z limitée à un
     * demi-radian ({@code test/charniere_fixe}).
     */
    private static final String CHARNIERE = "axion:test/charniere_fixe";

    /**
     * ADR-155 : un socle et son coulisseau, que le clip {@code glisse} pousse d'un bloc le long de l'axe x de
     * l'asset ({@code tools/assets/animation_essai.py}).
     */
    private static final String GLISSIERE = "axion:test/anime/glissiere";

    /** ADR-155 : de combien de pixels, au moins, le coulisseau poussé élargit la silhouette. */
    private static final int ELARGISSEMENT = 10;

    /**
     * ADR-155, tranche 5 : une colonne skinnée sur son socle, que le clip {@code plier} plie d'un quart de tour
     * au coude ({@code tools/assets/animation_essai.py}).
     */
    private static final String BRAS = "axion:test/anime/bras_skinne";

    /** Le bras : demi-largeur de la colonne, hauteur du socle, pied de la colonne, longueur d'un segment. */
    private static final float BRAS_DEMI = 0.2f;
    private static final float BRAS_SOCLE = 0.25f;
    private static final float BRAS_PIED = 0.5f;
    private static final float BRAS_SEGMENT = 1.0f;

    /** R-791 : le défaut de {@code render.max_skinned_instances}, que le banc ne change pas. */
    private static final int PLAFOND_SKINNES = 64;

    /** Un quart de tour autour de y, vers la droite : l'axe x de l'asset part vers +z. */
    private static final float[] QUART_DE_TOUR_A_DROITE = {0f, -0.70710677f, 0f, 0.70710677f};

    /** Les panneaux des cartes de matériau (ADR-127 §5) : le même, avec et sans normal map ni ORM. */
    private static final String RELIEF = "axion:test/materiaux/relief";
    private static final String PLAT = "axion:test/materiaux/plat";

    /**
     * T-476 : deux panneaux translucides, rouge et bleu à demi opaques, l'un derrière l'autre sur
     * l'axe de la caméra — le bleu à 3 blocs d'elle, le rouge à 2.
     */
    private static final String TRANSLUCIDE_ROUGE = "axion:test/materiaux/translucide_rouge";
    private static final String TRANSLUCIDE_BLEU = "axion:test/materiaux/translucide_bleu";
    private static final double X_BLEU = 1.0;
    private static final double X_ROUGE = 2.0;

    /** T-476 : de combien, sur 255, le rouge du panneau de devant doit l'emporter sur le bleu. */
    private static final int MARGE_T476 = 20;

    /**
     * T-483 : le pack de test que le build pose dans {@code resourcepacks/} — il remplace la texture
     * de bois que le modèle {@link #RESSOURCE} lit dans les ressources du client, par du magenta.
     */
    private static final String PACK_DE_TEST = "file/axion_banc_pack";
    private static final String RESSOURCE = "axion:test/materiaux/ressource";

    /** T-483 : de combien, sur 255, le bleu de la face doit monter avec le pack (le bois n'en a guère). */
    private static final int MONTEE_T483 = 40;

    /** T-483 : écart toléré par canal entre le bois d'avant le pack et celui d'après. */
    private static final int RETOUR_T483 = 4;

    /**
     * ADR-150 D3 : un cube du mod ({@code data/}, rouge) que le pack de test remplace par le même cube
     * en bleu ({@code assets/}). Le serveur ne lit que {@code data/} ; le client compile ses resource
     * packs d'abord : avec le pack, il doit dessiner le bleu, et le rouge sans lui. Un rendu qui
     * lirait le registre du serveur resterait rouge.
     */
    private static final String MODELE_DU_PACK = "axion:test/materiaux/modele_du_pack";

    /** ADR-150 D3 : de combien, sur 255, le canal attendu doit l'emporter sur l'autre. */
    private static final int MARGE_D3 = 40;

    /**
     * T-713f (cas 4 de 22.5.3) : une definition et son modèle livrés par un datapack du monde. Le serveur
     * lit les datapacks du monde ; le client, ses resource packs et les fichiers de ses mods (ADR-150 D3) :
     * il n'a pas ce modèle.
     */
    private static final String ABSENT_CHEZ_LE_CLIENT = "axion:test/absent_chez_le_client";

    private static final String ASSET_ABSENT = "axion:models/test/absent_chez_le_client.gltf";

    /** Le datapack du monde qui les livre, et le cube du mod que son modèle recopie. */
    private static final String PACK_ABSENT = "axion_absent_chez_le_client";

    private static final String CUBE_DU_MOD = "/data/axion/axion/models/test/cube.gltf";

    /** Un quart de tour autour de la verticale : la face avant des panneaux, +Z, regarde l'est, la caméra. */
    private static final float[] VERS_L_EST = {0f, 0.70710678f, 0f, 0.70710678f};

    /** Décalage des panneaux le long de z, de part et d'autre du cube : relief au nord, plat au sud. */
    private static final double Z_RELIEF = -1.5;
    private static final double Z_PLAT = 1.5;
    private static final float[] IDENTITE = {0f, 0f, 0f, 1f};
    /** Un huitième de tour autour de y : {@code [0, sin 22,5°, 0, cos 22,5°]}. */
    private static final float[] HUITIEME_DE_TOUR = {0f, 0.38268343f, 0f, 0.9238795f};

    /** Un pas du scénario, joué à chaque frame jusqu'à ce qu'il rende vrai. */
    @FunctionalInterface
    private interface Pas {
        boolean jouer(Minecraft minecraft);
    }

    private record Etape(String nom, Pas pas) {}

    /** Matrices et caméra d'une frame, relevées au stage {@code AFTER_ENTITIES}. */
    private record Vue(Matrix4f projection, Matrix4f pose, Vec3 camera) {}

    /**
     * Le backend que le lancement impose ({@code -Daxion.render.backend}) : c'est lui qui doit
     * dessiner, sans quoi un repli silencieux ferait passer un backend pour l'autre (ADR-127 §7).
     */
    private static final String BACKEND_DEMANDE = System.getProperty("axion.render.backend");

    /** Vrai sur le backend natif : T-479, sa bascule sur vanilla, ne se joue que là. */
    private static final boolean NATIF = "native".equals(BACKEND_DEMANDE);

    /**
     * Vrai sous Oculus et le shaderpack de test ({@code -Daxion.rendertest.shaders}) : T-480 et
     * T-490 s'y jouent, à la place de T-474 — l'éclairage y est celui du shaderpack (R-1513).
     */
    private static final boolean SHADERS = Boolean.getBoolean("axion.rendertest.shaders");

    /**
     * Le shaderpack de test ({@code src/devcontent/shaderpacks/axion_banc}) écrit un bleu nul :
     * un pixel qui garde du bleu n'est pas passé par ses programmes. Le cube de test, gris, en a
     * autant que de rouge et de vert hors shaderpack.
     */
    private static final int BLEU_DU_PACK = 2;

    /** L'erreur que T-479 provoque : la compilation des shaders refusée (R-761). */
    private static final Pattern E_4001 = Pattern.compile("\\(E-4001\\)");

    private static final List<Etape> SCENARIO = new ArrayList<>();
    private static final RapportDuBanc RAPPORT = new RapportDuBanc(SHADERS
            ? List.of("backend", "T-490", "T-470", "T-480", "cartes de matériau", "R-1490, changement de shaderpack",
                    "T-491d, journal et statut", "T-472", "T-473", "erreurs AXION")
            : NATIF
            ? List.of("backend", "T-512", "T-470", "T-474", "ADR-151 D9", "ADR-151 D7", "cartes de matériau", "ADR-155, animation", "T-908", "ADR-155, overlays", "T-541", "T-542", "T-476", "T-483", "ADR-150 D3", "T-713f", "T-410", "ADR-150 D7", "T-471", "T-503", "T-510", "T-479", "T-511", "T-491", "T-491b",
                    "T-491c", "T-905", "T-904", "T-492", "T-481", "T-491d, journal et statut", "T-472", "T-473", "erreurs AXION")
            : List.of("backend", "T-470", "T-474", "ADR-151 D9", "ADR-151 D7", "cartes de matériau", "ADR-155, animation", "R-742", "ADR-155, overlays", "T-493", "T-476", "T-483", "ADR-150 D3", "T-713f", "T-410", "ADR-150 D7", "T-471", "T-491d, journal et statut", "T-472", "T-473", "erreurs AXION"));

    private static JournalDuBanc journal;
    private static int courante;
    private static int framesDansLEtape;
    private static long debutEtape;
    private static long debut;
    private static boolean fini;

    /**
     * Vrai pendant qu'une étape se joue. Minecraft rend lui-même des frames depuis certaines
     * actions — créer un monde boucle sur l'écran de chargement jusqu'à ce que le serveur soit
     * prêt —, et chacune de ces frames rappelle {@link #onRenderTick} : sans ce garde, l'étape se
     * rejouerait depuis elle-même. Constaté au premier passage en CI : un second
     * {@code createFreshLevel}, et le verrou du monde déjà pris par le premier.
     */
    private static boolean dansUneEtape;

    /** Ticks du client depuis le lancement : l'horloge où Minecraft change d'état. */
    private static long ticks;

    private static int surface;
    private static boolean cameraFixee;
    private static float lacet;
    private static float tangage;
    private static Vue derniereVue;
    private static AxionEntity cube;

    /** Les 200 copies du cube de T-477 et T-478. */
    private static final List<AxionEntity> copies = new ArrayList<>();

    /** Appels de dessin et assemblies dessinées de la scène avant les copies (T-477). */
    private static int appelsSansCopies;
    private static int dessineesSansCopies;

    /** Le ballon, qui porte quatre niveaux de détail (T-475). */
    private static final String BALLON = "axion:test/ballon";

    /** Les ballons proche et lointain de T-475, et les relevés de décisions de T-906. */
    private static AxionEntity ballonPres;
    private static AxionEntity ballonMilieu;
    private static AxionEntity ballonLoin;
    private static final List<java.util.Map<Integer, Integer>> releves = new ArrayList<>();

    /** Les octets des textures d'AXION avant le LOD forcé, puis sous lui (T-484). */
    private static long texturesAvant;
    private static long texturesForcees;

    /** L'image des copies instanciées, et le seuil d'instancing à rendre après T-478. */
    private static CapturesDuBanc.Capture copiesInstanciees;
    private static int seuilInstancing;

    /** Le chemin indirect de la configuration, l'image des copies par lui et ses appels (T-907). */
    private static boolean indirectInitial;
    private static boolean indirectTeste;
    private static CapturesDuBanc.Capture copiesIndirectes;
    private static int appelsIndirects;
    private static int cubePrecedent;

    /** ADR-151 D9 : la charnière, le fond sans elle, et ses poses au serveur à la capture. */
    private static AxionEntity charniere;
    private static CapturesDuBanc.Capture fondCharniere;
    private static PosesCharniere posesCharniere;

    /** ADR-155, tranche 5 : le bras skinné, le fond sans lui, sa capture en TBO, et ses copies (T-542). */
    private static AxionEntity bras;
    private static CapturesDuBanc.Capture fondBras;
    private static CapturesDuBanc.Capture brasTbo;
    private static final List<AxionEntity> brasCopies = new ArrayList<>();
    private static long plafonnesAvant;
    /** R-742 : les sommets que skinne un bras sur le CPU, et les bras laissés au repos avant de plier les copies. */
    private static long sommetsParBras;
    private static long plafonnesVanillaAvant;
    /** ADR-155, tranche 6b : le bras plié, capturé sans overlay, pour juger les overlays de l'animation. */
    private static CapturesDuBanc.Capture brasPlie;
    private static int assembliesAvantBras;

    /** ADR-155 : la glissière, et le fond sans elle, puis au repos, puis le coulisseau poussé. */
    private static AxionEntity glissiere;
    private static CapturesDuBanc.Capture fondGlissiere;
    private static CapturesDuBanc.Capture reposGlissiere;
    private static CapturesDuBanc.Capture animeeGlissiere;

    private static CapturesDuBanc.Capture avec;
    private static CapturesDuBanc.Capture sans;
    private static AxionRenderPass.FrameStats passesAvec;
    private static CapturesDuBanc.Capture fond;
    private static CapturesDuBanc.Capture droit;
    private static Vue vueDroit;
    private static CapturesDuBanc.Capture tourne;
    private static Vue vueTourne;
    private static CapturesDuBanc.Capture minuit;

    private static CompletableFuture<Void> rechargement;
    private static AxionEntity panneauRelief;
    private static AxionEntity panneauPlat;
    private static AxionEntity panneauRouge;
    private static AxionEntity panneauBleu;
    private static AxionEntity modeleRessource;
    private static double[] boisAvant;
    private static double[] boisAvecPack;
    private static AxionEntity modeleDuPack;
    /**
     * T-713f : l'assembly au modèle absent chez le client, la frame d'avant sa pose, et ce que le serveur en
     * a relevé sur son thread — liée, état simulé rapporté, definition dans son NBT.
     */
    private static AxionEntity modeleAbsent;
    private static AxionRenderPass.FrameStats avantModeleAbsent;
    private static boolean modeleAbsentLie;
    private static boolean modeleAbsentSimule;
    private static boolean modeleAbsentPersiste;
    /** T-410 : l'assembly lâchée en l'air, son identifiant réseau, et ses hauteurs relevées. */
    private static AxionEntity chute;
    private static int chuteId;
    private static double chuteClientDepart;
    private static double chuteServeur;
    private static int chuteTickDepart;
    private static int chuteTickFin;
    /**
     * ADR-150 D7 : la pose rendue de l'assembly lâchée, à chaque frame de sa chute — début de la frame en
     * nanosecondes, hauteur, et mode de l'interpolation de 21.7 ({@code -1} : aucun, avant le premier
     * snapshot).
     */
    private static final List<double[]> chuteRendue = new ArrayList<>();
    private static double[] duPackAvant;
    private static double[] duPackAvecPack;
    private static boolean packDisponible;
    private static int demarragesAvantRechargement;
    /** Démarrages de backend à l'installation du journal : ceux qui suivent y laissent leur entrée. */
    private static int demarragesALInstallation;
    private static List<String> sortieDuStatut;

    /**
     * T-904 : une série de frames — l'intervalle entre deux débuts de frame, et le temps que les
     * variantes à la demande du natif ont pris au render thread pendant la frame.
     */
    private static final class SerieT904 {
        final List<Long> intervalles = new ArrayList<>();
        final List<Long> variantes = new ArrayList<>();
    }

    /** La série relevée, ou {@code null} : aucune. */
    private static SerieT904 serieT904;
    private static long debutDeFramePrecedent;
    private static SerieT904 temoinT904;
    private static SerieT904 compileeT904;
    private static SerieT904 relueT904;
    private static SerieT904 serielleT904;

    /** Pack coupé (ADR-129) : le cube en natif, avec et sans les passes d'AXION, et ce que le choix a dit. */
    private static CapturesDuBanc.Capture packCoupeAvec;
    private static CapturesDuBanc.Capture packCoupeSans;
    private static Vue vuePackCoupe;
    private static BackendSelection.Kind backendPackCoupe;
    private static String raisonPackCoupe;
    private static int objetsPackCoupe;
    private static boolean packCoupeEnUsage;
    private static int demarrages;
    private static int fermetures;
    /** Objets GL du natif à chaque retour sur lui, puis à chaque passage en vanilla. */
    private static final List<Integer> objetsNatif = new ArrayList<>();
    private static final List<Integer> objetsVanilla = new ArrayList<>();
    /** Textures d'AXION vivantes, relevées à chaque passage sur le natif puis sur vanilla (T-481). */
    private static final List<Integer> texturesNatif = new ArrayList<>();
    private static final List<Integer> texturesVanilla = new ArrayList<>();
    private static CapturesDuBanc.Capture silhouetteNatif;
    private static CapturesDuBanc.Capture silhouetteVanilla;
    private static AxionRenderPass.FrameStats passesNatif;
    private static byte[] etatAvant;
    private static byte[] etatApres;
    private static volatile int assembliesComparees;
    private static AxionRenderPass.ShaderStats statsDemarrage;
    private static AxionRenderPass.ShaderStats statsRelecture;
    private static AxionRenderPass.ShaderStats statsRecompile;
    private static int fichiersCorrompus;
    private static int provoqueesAvant;

    private static boolean compter;
    private static long erreursFrames;
    private static long framesComptees;
    private static long erreursSansAxion;
    private static long erreursPassesAvant;
    private static long erreursAvantPassesAvant;

    private BancDeRendu() {}

    /**
     * Début de frame : la caméra reste là où le scénario la veut, quoi que fasse la souris. Fin de
     * frame : l'étape courante est jouée, l'image de la frame étant complète.
     *
     * @param event frame du client
     */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (!ACTIF || fini) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (event.phase == TickEvent.Phase.START) {
            // T-904 : l'intervalle couvre la frame qui vient de finir, et le temps des variantes est
            // celui qu'elle a pris.
            long maintenant = System.nanoTime();
            if (serieT904 != null && debutDeFramePrecedent != 0) {
                serieT904.intervalles.add(maintenant - debutDeFramePrecedent);
                serieT904.variantes.add(AxionRenderPass.nativeShaderPollNanos());
            }
            debutDeFramePrecedent = maintenant;
            fixerLaCamera(minecraft);
            return;
        }
        if (dansUneEtape) {
            return;
        }
        dansUneEtape = true;
        try {
            jouerLaFrame(minecraft);
        } finally {
            dansUneEtape = false;
        }
    }

    private static void jouerLaFrame(Minecraft minecraft) {
        if (SCENARIO.isEmpty()) {
            debut = System.currentTimeMillis();
            debutEtape = System.nanoTime();
            journal = JournalDuBanc.installer();
            demarragesALInstallation = AxionRenderPass.backendStarts();
            scenario();
        }
        if (compter) {
            erreursFrames += viderErreursGl();
            framesComptees++;
        }
        Etape etape = SCENARIO.get(courante);
        try {
            if (System.currentTimeMillis() - debut > DUREE_MAX_MS) {
                throw new IllegalStateException("le banc a dépassé " + DUREE_MAX_MS / 60_000 + " min");
            }
            framesDansLEtape++;
            if (etape.pas().jouer(minecraft)) {
                LOGGER.info("AXION banc de rendu : « {} » fait", etape.nom());
                courante++;
                framesDansLEtape = 0;
                debutEtape = System.nanoTime();
                if (courante == SCENARIO.size()) {
                    terminer(minecraft, null);
                }
            }
        } catch (RuntimeException echec) {
            LOGGER.error("AXION banc de rendu : échec à l'étape « {} »", etape.nom(), echec);
            terminer(minecraft, "étape « " + etape.nom() + " » : " + echec);
        }
    }

    /**
     * Compte les ticks du client : la stabilité d'une scène se mesure à cette horloge.
     *
     * @param event tick du client
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (ACTIF && event.phase == TickEvent.Phase.END) {
            ticks++;
            Minecraft minecraft = Minecraft.getInstance();
            // Garde-fou : le banc ne se joue jamais en pause. Un écran de pause ouvert sur un monde chargé
            // — focus perdu, touche Échap — est refermé aussitôt.
            if (!fini && minecraft.level != null && minecraft.screen instanceof PauseScreen) {
                minecraft.setScreen(null);
            }
            ClientLevel level = minecraft.level;
            if (level != null) {
                for (Entity entity : level.entitiesForRendering()) {
                    if (entity instanceof AxionEntity assembly && DERRIERE.contains(assembly.definitionId())) {
                        assembly.noCulling = true;
                    }
                }
            }
        }
    }

    /**
     * Relève les matrices de la frame, pour projeter ce qu'on attend à l'écran (T-474).
     *
     * @param event étape du rendu du niveau
     */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (ACTIF && !fini && event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            derniereVue = new Vue(
                    new Matrix4f(event.getProjectionMatrix()),
                    new Matrix4f(event.getPoseStack().last().pose()),
                    event.getCamera().getPosition());
        }
    }

    private static void scenario() {
        jusqua("le jeu prêt", 600, mc -> mc.getOverlay() == null && mc.level == null && mc.screen != null);
        une("créer le monde", BancDeRendu::creerLeMonde);
        jusqua("le monde chargé", 600, mc -> mc.level != null && mc.player != null
                && mc.getSingleplayerServer() != null && mc.screen == null);
        une("figer les réglages", BancDeRendu::reglerLesOptions);
        serveur("mettre en place la scène", BancDeRendu::mettreEnPlace);
        une("regarder au nord", mc -> viser(180f, 15f));
        jusqua("la scène prête", 600, BancDeRendu::scenePrete);
        une("juger le backend", mc -> jugerBackend());
        if (SHADERS) {
            une("juger T-490", mc -> jugerT490());
        }
        if (NATIF) {
            // T-512 : la variante CUTOUT, que les surfaces découpées du décor demandent, se compile à la
            // demande ; la base dessine en attendant. Le cache est neuf : rien n'en est relu.
            jusqua("la variante CUTOUT prête", 120, mc -> {
                AxionRenderPass.ShaderStats stats = AxionRenderPass.nativeShaderStats();
                return stats != null && stats.cutoutReady();
            });
            une("juger T-512", mc -> jugerT512(AxionRenderPass.nativeShaderStats()));
        }

        // T-550, amendement A1 d'ADR-121 : les sept overlays allumés sur la scène posée — un cube
        // au sol, ses tuiles —, la géométrie de chacun reçue du natif et dessinée. Une erreur au
        // dessin éteint les overlays et se journalise : le verdict « erreurs AXION » la voit.
        une("allumer les overlays de debug", mc -> AxionRenderPass.setAllOverlays(true));
        stabiliser("les overlays de debug");
        une("juger les overlays de debug", mc -> jugerOverlays());
        une("éteindre les overlays de debug", mc -> AxionRenderPass.setAllOverlays(false));
        stabiliser("la scène vanilla");

        // T-470 : la même scène, avec puis sans les passes d'AXION, puis de nouveau avec.
        une("capturer avec AXION", mc -> {
            avec = capturer(mc, "t470_avec.png");
            passesAvec = AxionRenderPass.lastFrame();
        });
        une("couper AXION", mc -> AxionRenderPass.setPassesEnabled(false));
        frames(3);
        une("capturer sans AXION", mc -> sans = capturer(mc, "t470_sans.png"));
        une("rétablir AXION", mc -> AxionRenderPass.setPassesEnabled(true));
        frames(3);
        une("juger T-470", mc -> jugerT470(capturer(mc, "t470_encore.png")));

        // T-474 : un cube devant la caméra, droit puis tourné, de jour puis de nuit.
        une("regarder à l'ouest", mc -> viser(90f, 20f));
        stabiliser("la vue à l'ouest");
        une("capturer le fond", mc -> fond = capturer(mc, "t474_fond.png"));
        serveur("poser le cube droit", s -> cube = AssembliesDeTest.poser(s.overworld(), CUBE, positionCube(), IDENTITE));
        jusqua("le cube droit rendu", 120, mc -> rendu(mc, DERRIERE.size() + 1, cube.getId()));
        stabiliser("le cube droit");
        une("capturer le cube droit", mc -> {
            droit = capturer(mc, "t474_droit.png");
            vueDroit = derniereVue;
        });
        serveur("tourner le cube d'un huitième de tour", s -> {
            cubePrecedent = cube.getId();
            cube.discard();
            cube = AssembliesDeTest.poser(s.overworld(), CUBE, positionCube(), HUITIEME_DE_TOUR);
        });
        jusqua("le cube tourné rendu", 120, mc -> mc.level.getEntity(cubePrecedent) == null
                && rendu(mc, DERRIERE.size() + 1, cube.getId()));
        stabiliser("le cube tourné");
        une("capturer le cube tourné", mc -> {
            tourne = capturer(mc, "t474_tourne.png");
            vueTourne = derniereVue;
        });
        serveur("passer à minuit", s -> commandes(s, "time set 18000"));
        jusqua("la nuit tombée", 60, mc -> mc.level.getDayTime() % 24_000L == 18_000L);
        stabiliser("la nuit");
        une("capturer de nuit", mc -> minuit = capturer(mc, "t474_minuit.png"));
        serveur("revenir à midi", s -> commandes(s, "time set 6000"));
        jusqua("le jour revenu", 60, mc -> mc.level.getDayTime() % 24_000L == 6_000L);
        une(SHADERS ? "juger T-480" : "juger T-474", mc -> {
            if (SHADERS) {
                jugerT480();
            } else {
                jugerT474();
            }
        });

        // ADR-151 D9 : une charnière à racine statique, à l'écart du cube, b pendu à la butée d'un
        // demi-radian. Chaque corps se dessine à sa pose : la silhouette est celle des deux cubes aux poses
        // du serveur, et non celle de l'asset au repos, b à l'horizontale.
        if (!SHADERS) {
            // R-612 : le joueur du banc est spectateur et n'observe pas ; sans observateur à portée, la
            // gestion d'activité endormirait la charnière, racine fixe comprise (ADR-153 D4). Déclaré avant
            // le fond : les assemblies qu'il réveille aussi s'y posent, et n'entrent pas dans la silhouette.
            serveur("observer la charnière", s -> ObservateursDeTest.declarer(s.overworld(), positionCharniere()));
            // Le retour à midi de T-474 éclaire encore le terrain : le fond attend une image stable.
            stabiliser("le fond de la charnière");
            une("capturer le fond de la charnière", mc -> fondCharniere = capturer(mc, "d9_fond.png"));
            serveur("poser la charnière", s -> charniere = AssembliesDeTest.poser(
                    s.overworld(), CHARNIERE, positionCharniere(), QUART_DE_TOUR_A_DROITE));
            jusqua("b à la butée et dessiné", 60, mc -> rendu(mc, DERRIERE.size() + 2, charniere.getId())
                    && aLaButee() && corpsDessines(mc));
            stabiliser("la charnière");
            serveur("relever les poses de la charnière", s -> posesCharniere = PosesCharniere.de(charniere));
            une("juger ADR-151 D9", mc -> jugerD9(capturer(mc, "d9_charniere.png"), derniereVue));
            // ADR-151 D7 : joints et limits, que la scène des overlays de debug, sans joint, ne trace pas.
            une("allumer les overlays sur la charnière", mc -> AxionRenderPass.setAllOverlays(true));
            stabiliser("les overlays de la charnière");
            une("juger ADR-151 D7", mc -> jugerD7());
            une("éteindre les overlays de la charnière", mc -> AxionRenderPass.setAllOverlays(false));
            serveur("retirer la charnière", s -> charniere.discard());
            jusqua("la charnière retirée", 30, mc -> mc.level.getEntity(charniere.getId()) == null);
        }

        // Cartes de matériau (ADR-127 §5) : deux panneaux identiques face à la caméra, l'un avec
        // normal map et ORM, l'autre sans. En natif, sous le soleil de midi, le relief fait varier la
        // luminance de l'un bien plus que de l'autre ; en vanilla, qui ignore ces cartes (R-1513),
        // les deux se confondent. Ils sont retirés ensuite.
        serveur("poser les panneaux", s -> {
            panneauRelief = AssembliesDeTest.poser(s.overworld(), RELIEF, positionPanneau(Z_RELIEF), VERS_L_EST);
            panneauPlat = AssembliesDeTest.poser(s.overworld(), PLAT, positionPanneau(Z_PLAT), VERS_L_EST);
        });
        jusqua("les panneaux rendus", 120, mc -> AxionRenderPass.lastFrame().assemblies() == DERRIERE.size() + 3
                && AxionRenderPass.lastFrame().withAsset() == DERRIERE.size() + 3);
        stabiliser("les panneaux");
        une("juger les cartes de matériau", mc -> jugerCartes(capturer(mc, "cartes.png"), derniereVue));
        serveur("retirer les panneaux", s -> {
            panneauRelief.discard();
            panneauPlat.discard();
        });
        jusqua("les panneaux retirés", 120, mc -> AxionRenderPass.lastFrame().assemblies() == DERRIERE.size() + 1);

        // ADR-155, tranche 4 : la glissière, tournée pour que son coulisseau glisse en travers de l'image. Le
        // serveur joue le clip par AnimationControl ; le descripteur v3 en porte l'état au client, qui le pousse
        // au natif ; l'image montre le coulisseau poussé, puis, la couche arrêtée, revenu au repos.
        if (!SHADERS) {
            stabiliser("le fond de la glissière");
            une("capturer le fond de la glissière", mc -> fondGlissiere = capturer(mc, "adr155_fond.png"));
            serveur("poser la glissière", s -> glissiere = AssembliesDeTest.poser(
                    s.overworld(), GLISSIERE, positionPanneau(Z_PLAT), QUART_DE_TOUR_A_DROITE));
            jusqua("la glissière rendue, ses clips liés", 120, mc -> rendu(mc, DERRIERE.size() + 2, glissiere.getId())
                    && !glissiere.animation().clips().isEmpty());
            stabiliser("la glissière au repos");
            une("capturer la glissière au repos", mc -> reposGlissiere = capturer(mc, "adr155_repos.png"));
            serveur("jouer glisse", s -> glissiere.animation().play("glisse", 0, 1f, 0f));
            jusqua("la couche reçue par le client", 60, mc -> coucheAuClient(mc).playing());
            stabiliser("le coulisseau poussé");
            une("capturer le coulisseau poussé", mc -> animeeGlissiere = capturer(mc, "adr155_animee.png"));
            serveur("arrêter la couche", s -> glissiere.animation().stop(0, 0f));
            jusqua("l'arrêt reçu par le client", 60, mc -> !coucheAuClient(mc).playing());
            stabiliser("la glissière arrêtée");
            une("juger ADR-155", mc -> jugerAnimation(capturer(mc, "adr155_arretee.png")));
            serveur("retirer la glissière", s -> glissiere.discard());
            jusqua("la glissière retirée", 120, mc -> AxionRenderPass.lastFrame().assemblies() == DERRIERE.size() + 1);
        }

        // ADR-155, tranche 6a : le skinning du backend vanilla, sur le CPU à l'émission des sommets (R-742). Le bras
        // plié se dessine là où sa référence CPU le place ; au-delà de render.vanilla_max_skinned_vertices — abaissé
        // pour le banc —, les bras les plus lointains restent au repos, et la métrique le compte (T-493).
        if (!NATIF && !SHADERS) {
            plierLeBras();
            une("juger R-742", mc -> {
                sommetsParBras = sommetsSkinnesVanilla();
                jugerBrasPlie("R-742", "sur le CPU", capturer(mc, "r742.png"), derniereVue);
            });
            jugerLesOverlaysDuBras();
            plierLesCopies(mc -> skinnes() == brasSkinnesVanilla() && plafonnesVanilla() > plafonnesVanillaAvant);
            une("juger T-493", mc -> jugerT493());
            retirerLesBras();
        }


        // T-477, T-478 (C-65, ADR-154) : 200 copies du cube devant la caméra. Instanciées, elles ne coûtent
        // que quelques appels de dessin ; sans instancing, l'image est la même, pixel pour pixel.
        if (NATIF) {
            stabiliser("la scène sans les copies");
            une("compter les appels sans les copies", mc -> {
                appelsSansCopies = AxionRenderPass.nativeDrawCalls();
                dessineesSansCopies = AxionRenderPass.lastFrame().assemblies();
            });
            serveur("poser 200 copies", s -> {
                copies.clear();
                for (int rangee = 0; rangee < 20; rangee++) {
                    for (int colonne = 0; colonne < 10; colonne++) {
                        copies.add(AssembliesDeTest.poser(s.overworld(), CUBE,
                                new Vec3(-6.5 - 2.0 * rangee, surface, -8.5 + 2.0 * colonne), IDENTITE));
                    }
                }
            });
            jusqua("les copies rendues", 120, mc -> copies.stream().allMatch(c -> mc.level.getEntity(c.getId()) != null)
                    && AxionRenderPass.lastFrame().withAsset() == AxionRenderPass.lastFrame().assemblies()
                    && AxionRenderPass.lastFrame().assemblies() > dessineesSansCopies);
            stabiliser("les copies");
            une("juger T-477", mc -> jugerT477());
            une("capturer les copies instanciées", mc -> copiesInstanciees = capturer(mc, "t478_instancie.png"));
            une("couper l'instancing", mc -> {
                seuilInstancing = AxionRenderPass.instancingThreshold();
                indirectInitial = AxionRenderPass.nativeIndirect();
                AxionRenderPass.setInstancingThreshold(Integer.MAX_VALUE);
            });
            stabiliser("les copies sans instancing");
            une("juger T-478", mc -> jugerT478(capturer(mc, "t478_direct.png")));
            une("rendre l'instancing", mc -> AxionRenderPass.setInstancingThreshold(seuilInstancing));
            // T-907 (R-782) : sous GL 4.3, le chemin indirect rend l'image du chemin instancié direct. Le
            // contexte se lit quand l'étape s'exécute : à la construction du scénario, aucun backend n'existe.
            une("prendre le chemin indirect", mc -> {
                indirectTeste = AxionRenderPass.indirectAvailable();
                if (indirectTeste) {
                    AxionRenderPass.setNativeIndirect(true);
                }
            });
            stabiliser("les copies par le chemin indirect");
            une("capturer le chemin indirect", mc -> {
                if (indirectTeste) {
                    appelsIndirects = AxionRenderPass.nativeDrawCalls();
                    copiesIndirectes = capturer(mc, "t907_indirect.png");
                    AxionRenderPass.setNativeIndirect(false);
                }
            });
            stabiliser("les copies par le chemin direct");
            une("juger T-907", mc -> {
                if (indirectTeste) {
                    jugerT907(capturer(mc, "t907_direct.png"));
                    AxionRenderPass.setNativeIndirect(indirectInitial);
                } else {
                    RAPPORT.noter("T-907", true, "non exécuté : le contexte n'offre pas GL 4.3, le chemin indirect"
                            + " est indisponible (R-782, optionnel) — " + AxionRenderPass.indirectSupport());
                }
            });
            // T-485 (§19.13) : 100 assemblies visibles, appels de dessin et triangles mesurés et bornés.
            serveur("retirer la moitié des copies", s -> copies.subList(100, copies.size()).forEach(Entity::discard));
            jusqua("100 copies dessinées", 120, mc -> AxionRenderPass.lastFrame().assemblies() == dessineesSansCopies + 100
                    && AxionRenderPass.lastFrame().withAsset() == AxionRenderPass.lastFrame().assemblies());
            stabiliser("les 100 copies");
            une("juger T-485", mc -> jugerT485());
            serveur("retirer les copies", s -> copies.forEach(Entity::discard));
            jusqua("les copies retirées", 120, mc -> AxionRenderPass.lastFrame().assemblies() == DERRIERE.size() + 1);
        }

        // T-484 (R-750, ADR-154) : le palier « LOD forcé » retire un mip aux textures d'AXION, qui se rechargent
        // en plus petit, la scène toujours dessinée ; rendu, elles retrouvent leur taille.
        if (!SHADERS) {
            une("relever les textures", mc -> texturesAvant = AxionRenderPass.textureBytes());
            une("forcer le LOD", mc -> AxionRenderPass.forceGpuLevel(dev.axion.render.GpuDegradation.Level.FORCED_LOD_1));
            jusqua("les textures rechargées en plus petit", 120, mc -> AxionRenderPass.textureBytes() > 0
                    && AxionRenderPass.textureBytes() < texturesAvant
                    && AxionRenderPass.lastFrame().withAsset() == AxionRenderPass.lastFrame().assemblies());
            stabiliser("le LOD forcé");
            une("juger T-484", mc -> jugerT484());
            une("rendre le palier", mc -> AxionRenderPass.forceGpuLevel(null));
            // Rendues, les textures des assets dessinés retrouvent leurs mips : quatre fois leur taille réduite.
            // Celles d'assets retirés plus tôt, encore en cache avant l'essai, ne se rechargent pas.
            jusqua("les textures rendues à leur taille", 120, mc -> AxionRenderPass.textureBytes() >= 3 * texturesForcees
                    && AxionRenderPass.lastFrame().withAsset() == AxionRenderPass.lastFrame().assemblies());
        }

        // T-475 (§19.13) et T-906 (C-64) : trois ballons de plus en plus loin prennent des niveaux de détail de plus
        // en plus grossiers — les seuils eux-mêmes, au pixel près, sont l'affaire de T-522, la taille apparente
        // dépendant de la fenêtre ; caméra immobile, aucune décision ne change d'un relevé à l'autre. Les décisions
        // ne sont gardées que sous l'overlay lod (R-2280) : il est allumé le temps de l'essai.
        if (!SHADERS) {
            une("allumer lod", mc -> AxionRenderPass.setOverlay(dev.axion.debug.DebugOverlays.Overlay.LOD, true));
            serveur("poser les ballons", s -> {
                ballonPres = AssembliesDeTest.poser(s.overworld(), BALLON, new Vec3(-3.5, surface, 3.5), IDENTITE);
                ballonMilieu = AssembliesDeTest.poser(s.overworld(), BALLON, new Vec3(-20.5, surface, 3.5), IDENTITE);
                ballonLoin = AssembliesDeTest.poser(s.overworld(), BALLON, new Vec3(-60.5, surface, 0.5), IDENTITE);
            });
            jusqua("les ballons décidés", 120, mc -> niveau(mc, ballonPres) != null && niveau(mc, ballonMilieu) != null
                    && niveau(mc, ballonLoin) != null);
            stabiliser("les ballons");
            une("juger T-475", mc -> jugerT475(mc));
            jusqua("relever 60 frames", 60, mc -> {
                releves.add(decisions(mc));
                return releves.size() >= 60;
            });
            une("juger T-906", mc -> jugerT906());
            une("éteindre lod", mc -> AxionRenderPass.setOverlay(dev.axion.debug.DebugOverlays.Overlay.LOD, false));
            serveur("retirer les ballons", s -> {
                ballonPres.discard();
                ballonMilieu.discard();
                ballonLoin.discard();
            });
            jusqua("les ballons retirés", 120, mc -> mc.level.getEntity(ballonPres.getId()) == null
                    && mc.level.getEntity(ballonMilieu.getId()) == null
                    && mc.level.getEntity(ballonLoin.getId()) == null);
        }

        // T-476 (§19.13, R-1580) : la transparence ordonnée. Sous un shaderpack, le pack de test
        // efface le bleu de tout pixel (BLEU_DU_PACK) : le verdict ne s'y joue pas.
        if (!SHADERS) {
            serveur("poser les panneaux translucides", s -> {
                panneauBleu = AssembliesDeTest.poser(s.overworld(), TRANSLUCIDE_BLEU,
                        positionCube().add(X_BLEU, 0.0, 0.0), VERS_L_EST);
                panneauRouge = AssembliesDeTest.poser(s.overworld(), TRANSLUCIDE_ROUGE,
                        positionCube().add(X_ROUGE, 0.0, 0.0), VERS_L_EST);
            });
            jusqua("les panneaux translucides rendus", 120, mc -> AxionRenderPass.lastFrame().assemblies() == DERRIERE.size() + 3
                    && AxionRenderPass.lastFrame().withAsset() == DERRIERE.size() + 3);
            stabiliser("les panneaux translucides");
            une("juger T-476", mc -> jugerT476(capturer(mc, "t476.png"), derniereVue));
            if (NATIF) {
                // ADR-146 §2 : les panneaux font travailler la passe translucide du natif ; le temps GPU
                // des deux passes se lit, la scène stabilisée, sans jamais attendre le GPU.
                une("juger le temps GPU par passe", mc -> jugerTempsGpu());
            }
            serveur("retirer les panneaux translucides", s -> {
                panneauRouge.discard();
                panneauBleu.discard();
            });
            jusqua("les panneaux translucides retirés", 120,
                    mc -> AxionRenderPass.lastFrame().assemblies() == DERRIERE.size() + 1);

            // T-483 (§19.13, R-752) : un vrai changement de resource pack. Le pack de test remplace
            // la texture de bois que le modèle ressource lit dans les ressources du client : activé,
            // la face tournée vers la caméra vire au magenta ; retiré, elle revient au bois.
            // ADR-150 D3, dans le même changement de pack : le modèle du pack, de l'autre côté du cube.
            serveur("poser le modèle à texture de ressource et le modèle du pack", s -> {
                modeleRessource = AssembliesDeTest.poser(s.overworld(), RESSOURCE, positionPanneau(Z_RELIEF), IDENTITE);
                modeleDuPack = AssembliesDeTest.poser(s.overworld(), MODELE_DU_PACK, positionPanneau(Z_PLAT), IDENTITE);
            });
            jusqua("le modèle à texture de ressource rendu", 120, mc -> AxionRenderPass.lastFrame().assemblies() == DERRIERE.size() + 3
                    && AxionRenderPass.lastFrame().withAsset() == DERRIERE.size() + 3);
            stabiliser("le bois");
            une("relever le bois", mc -> {
                CapturesDuBanc.Capture capture = capturer(mc, "t483_avant.png");
                boisAvant = couleurDeRessource(capture, derniereVue);
                duPackAvant = couleurDeFace(capture, derniereVue, Z_PLAT);
            });
            changerDePack(true, "activer le pack de test");
            stabiliser("le pack de test");
            une("relever le pack", mc -> {
                CapturesDuBanc.Capture capture = capturer(mc, "t483_pack.png");
                boisAvecPack = couleurDeRessource(capture, derniereVue);
                duPackAvecPack = couleurDeFace(capture, derniereVue, Z_PLAT);
            });
            changerDePack(false, "retirer le pack de test");
            stabiliser("le pack retiré");
            une("juger T-483 et ADR-150 D3", mc -> {
                CapturesDuBanc.Capture capture = capturer(mc, "t483_apres.png");
                jugerT483(couleurDeRessource(capture, derniereVue));
                jugerModeleDuPack(couleurDeFace(capture, derniereVue, Z_PLAT));
            });
            serveur("retirer le modèle à texture de ressource et le modèle du pack", s -> {
                modeleRessource.discard();
                modeleDuPack.discard();
            });
            jusqua("le modèle à texture de ressource retiré", 120,
                    mc -> AxionRenderPass.lastFrame().assemblies() == DERRIERE.size() + 1);

            // T-713f (cas 4 de 22.5.3, R-1640) : un modèle que le serveur a et que le client n'a pas. Le
            // client dessine la boîte de repli, l'écrit au journal et reste connecté ; le serveur simule et
            // persiste l'assembly comme les autres.
            serveur("activer le datapack au modèle absent chez le client", s -> donneesDuServeur(s, true));
            jusqua("la definition et le modèle du datapack prêts côté serveur", 120, mc -> modeleAbsentPret());
            une("relever la frame d'avant", mc -> avantModeleAbsent = AxionRenderPass.lastFrame());
            serveur("poser l'assembly au modèle absent chez le client", s -> modeleAbsent =
                    AssembliesDeTest.poser(s.overworld(), ABSENT_CHEZ_LE_CLIENT, positionPanneau(Z_PLAT), IDENTITE));
            jusqua("l'assembly au modèle absent rendue", 120,
                    mc -> AxionRenderPass.lastFrame().assemblies() == avantModeleAbsent.assemblies() + 1);
            jusqua("son état simulé rapporté par le serveur", 120, mc -> modeleAbsent.netState() != null);
            serveur("relever l'assembly côté serveur", BancDeRendu::releverModeleAbsent);
            une("juger T-713f", BancDeRendu::jugerT713f);
            serveur("retirer l'assembly au modèle absent et son datapack", s -> {
                modeleAbsent.discard();
                donneesDuServeur(s, false);
            });
            jusqua("l'assembly au modèle absent retirée", 120,
                    mc -> AxionRenderPass.lastFrame().assemblies() == avantModeleAbsent.assemblies());

        }

        if (SHADERS) {
            // R-1490, ADR-129 : le pack coupé, le natif reprend la main et dessine hors du pack ;
            // rétabli, le vanilla revient, et le natif rend tout ce qu'il tenait (R-744).
            une("couper le shaderpack", mc -> {
                demarragesAvantRechargement = AxionRenderPass.backendStarts();
                activerLeShaderpack(false);
            });
            jusqua("la scène redessinée en natif, pack coupé", 120, mc -> redessinee(BackendSelection.Kind.NATIVE));
            stabiliser("la scène, pack coupé");
            une("capturer le cube en natif", mc -> {
                packCoupeAvec = capturer(mc, "pack_coupe_avec.png");
                vuePackCoupe = derniereVue;
                backendPackCoupe = AxionRenderPass.activeBackend();
                raisonPackCoupe = raisonDuChoix();
                objetsPackCoupe = AxionRenderPass.nativeGlObjects();
                packCoupeEnUsage = shaderpackEnUsage();
            });
            une("couper AXION, pack coupé", mc -> AxionRenderPass.setPassesEnabled(false));
            frames(3);
            une("capturer sans AXION, pack coupé", mc -> packCoupeSans = capturer(mc, "pack_coupe_sans.png"));
            une("rétablir AXION, pack coupé", mc -> AxionRenderPass.setPassesEnabled(true));
            une("rétablir le shaderpack", mc -> {
                demarragesAvantRechargement = AxionRenderPass.backendStarts();
                activerLeShaderpack(true);
            });
            jusqua("la scène redessinée en vanilla, pack rétabli", 120, mc -> redessinee(BackendSelection.Kind.VANILLA));
            une("juger le changement de shaderpack", mc -> jugerChangementDePack());
        }

        // T-471 : des frames sans AXION d'abord, pour savoir ce qui n'est pas de lui. Pas sous
        // Oculus : ses propres erreurs GL y seraient comptées, et ne diraient rien d'AXION.
        if (!SHADERS) {
            une("compter sans AXION", mc -> {
                AxionRenderPass.setPassesEnabled(false);
                viderErreursGl();
                erreursFrames = 0;
                framesComptees = 0;
                compter = true;
            });
            frames(FRAMES_SANS_AXION);
            une("compter avec AXION", mc -> {
                erreursSansAxion = erreursFrames;
                erreursFrames = 0;
                framesComptees = 0;
                erreursPassesAvant = GlStateCheck.errors();
                erreursAvantPassesAvant = GlStateCheck.errorsBeforePasses();
                AxionRenderPass.setPassesEnabled(true);
            });
            frames(FRAMES_T471);
            une("juger T-471", mc -> {
                compter = false;
                jugerT471();
            });
        }

        if (NATIF) {
            // T-503, T-510 : un rechargement des ressources rend tout ce que le natif tient, puis le
            // reconstruit — shaders relus et recompilés, assets retéléversés (R-752, R-760).
            une("relever le natif", mc -> {
                demarrages = AxionRenderPass.backendStarts();
                fermetures = AxionRenderPass.nativeCloses();
                objetsNatif.add(AxionRenderPass.nativeGlObjects());
            });
            recharger("les ressources");
            jusqua("la scène redessinée en natif", 120, mc -> redessinee(BackendSelection.Kind.NATIVE));
            une("juger T-503 et T-510", mc -> jugerT503EtT510());

            // T-491 : la même scène, dans les deux backends, dans ce même passage — capturée en natif,
            // puis en vanilla après la bascule de T-479 ; et l'état du serveur de part et d'autre.
            stabiliser("le cube en natif");
            une("capturer le cube en natif", mc -> {
                silhouetteNatif = capturer(mc, "t491_natif.png");
                passesNatif = AxionRenderPass.lastFrame();
            });
            serveur("relever l'état du serveur", s -> etatAvant = etatDuServeur(s));

            // T-479 : des shaders qui ne compilent pas font basculer sur vanilla, sans crash (R-761).
            // L'échec survient au rechargement des ressources, où le backend est choisi de nouveau
            // (R-1490) ; la scène doit être redessinée, assets compris, par le backend vanilla.
            basculerSurVanilla("les ressources aux shaders refusés");
            une("juger T-479", mc -> jugerT479());
            serveur("relever l'état du serveur après la bascule", s -> etatApres = etatDuServeur(s));
            stabiliser("le cube en vanilla");
            une("capturer le cube en vanilla, juger T-491", mc -> {
                silhouetteVanilla = capturer(mc, "t491_vanilla.png");
                jugerT491(AxionRenderPass.lastFrame());
            });

            // T-492 : retour au natif, de nouveau vanilla, puis natif encore — sans rien laisser derrière.
            une("corrompre le cache des shaders", mc -> fichiersCorrompus = corrompreLeCache(mc));
            revenirAuNatif("retour au natif");
            jusqua("la variante CUTOUT de nouveau prête", 120, mc -> {
                AxionRenderPass.ShaderStats stats = AxionRenderPass.nativeShaderStats();
                return stats != null && stats.cutoutReady();
            });
            une("relever le cache recompilé", mc -> statsRecompile = AxionRenderPass.nativeShaderStats());
            jusqua("le cache réécrit", 60, mc -> cacheRelisible(mc) == fichiersCorrompus);
            une("juger T-905", mc -> jugerT905(mc));

            // T-904 : la variante CUTOUT, que le décor demande à chaque frame, oubliée puis remise
            // en service sur une scène établie — compilée, relue du cache binaire, puis compilée
            // comme sans compilation parallèle —, comparée à des frames témoins de la même scène.
            stabiliser("la scène, avant T-904");
            une("ouvrir les frames témoins", mc -> {
                temoinT904 = new SerieT904();
                serieT904 = temoinT904;
            });
            frames(FRAMES_TEMOINS_T904);
            une("fermer les frames témoins", mc -> serieT904 = null);
            remettreEnService("compilée", false, false, serie -> compileeT904 = serie);
            remettreEnService("relue du cache", true, false, serie -> relueT904 = serie);
            remettreEnService("compilée sans parallèle", false, true, serie -> serielleT904 = serie);
            une("juger T-904", mc -> jugerT904());
            basculerSurVanilla("seconde bascule sur vanilla");
            revenirAuNatif("second retour au natif");
            une("juger T-492", mc -> jugerT492());
            une("juger T-481", mc -> jugerT481());

            // ADR-155, tranche 5 : le skinning du backend natif, sur le natif rendu après T-492 — ses variantes
            // SKINNED n'entrent au cache qu'ensuite, que T-905 réécrit entier. Le bras plié se dessine là où sa
            // référence CPU le place (T-908, partie skinning) ; le repli UBO donne la même image que le TBO
            // (T-541) ; au-delà de render.max_skinned_instances, la pose de repos et la métrique (T-542).
            plierLeBras();
            une("juger T-908", mc -> jugerBrasPlie("T-908", "au GPU", capturer(mc, "t908.png"), derniereVue));
            jugerLesOverlaysDuBras();
            plierLesCopies(mc -> skinnes() == PLAFOND_SKINNES && plafonnes() > plafonnesAvant);
            une("juger T-542", mc -> jugerT542());
            // T-541 sur les 65 bras : leurs palettes s'étalent dans la table, bien au-delà de la première page.
            stabiliser("les bras pliés");
            une("capturer les bras en TBO", mc -> brasTbo = capturer(mc, "t541_tbo.png"));
            une("passer au repli UBO", mc -> AxionRenderPass.setPaletteUbo(true));
            stabiliser("les bras pliés, en UBO");
            une("juger T-541", mc -> jugerT541(capturer(mc, "t541_ubo.png")));
            une("rendre le TBO", mc -> AxionRenderPass.setPaletteUbo(false));
            retirerLesBras();

            // R-761 : une variante refusée à la demande, sur un natif qui avait démarré, fait aussi
            // basculer sur vanilla — jusqu'au rechargement suivant.
            une("refuser les variantes", mc -> {
                journal.attendre(E_4001);
                provoqueesAvant = journal.erreursAttendues().size();
                AxionRenderPass.forceShaderVariantFailure(true);
            });
            recharger("les ressources aux variantes refusées");
            jusqua("la scène redessinée en vanilla — variante refusée", 120,
                    mc -> redessinee(BackendSelection.Kind.VANILLA));
            une("juger la variante refusée", mc -> {
                journal.attendre(null);
                AxionRenderPass.forceShaderVariantFailure(false);
                jugerVarianteRefusee();
            });
        }

        // T-491d, pour sa part jouable en M3 (ADR-128) : un dernier démarrage du backend, dans les
        // deux passages, puis son entrée au journal et la sortie de /axion status, réellement
        // exécutée, confrontées à la matrice active. « Sans artefact » est jugé par T-470 et par
        // les cartes de matériau, ignorées en vanilla (R-1513) ; l'overlay viendra avec C-73.
        recharger("pour T-491d");
        jusqua("le backend redémarré — T-491d", 120, mc -> AxionRenderPass.backendStarts() > demarragesAvantRechargement
                && AxionRenderPass.activeBackend() != null);
        serveur("exécuter /axion status", s -> sortieDuStatut = sortieDe(s, "axion status"));
        une("juger T-491d", mc -> jugerT491d());

        if (!SHADERS) {
            // T-410 (21.11) : connexion, apparition, mouvement, sur la vraie connexion du monde solo. Une
            // assembly lâchée en l'air tombe chez le serveur ; chez le client, seuls les snapshots d'AXION
            // la déplacent depuis son apparition (ADR-150 D2) : elle doit finir où le serveur la pose. Le
            // joueur du banc est spectateur, et un spectateur n'observe pas (R-612) : sans observateur
            // déclaré, tout corps dort. En dernier, pour qu'aucun verdict ne voie la scène éveillée.
            serveur("lâcher une assembly pour T-410", s -> {
                ObservateursDeTest.declarer(s.overworld(), positionChute());
                chute = AssembliesDeTest.poser(s.overworld(), CUBE, positionChute(), IDENTITE);
                chuteId = chute.getId();
                chuteTickDepart = s.getTickCount();
            });
            jusqua("l'assembly lâchée apparue chez le client", 60, mc -> mc.level.getEntity(chuteId) != null);
            une("relever l'apparition chez le client", mc -> chuteClientDepart = mc.level.getEntity(chuteId).getY());
            pendant("la chute", 6 * 20, mc -> {
                if (mc.level.getEntity(chuteId) instanceof AxionEntity rendue) {
                    AxionEntity.RenderPose pose = rendue.renderPose(mc.getPartialTick());
                    chuteRendue.add(new double[] {debutDeFramePrecedent, pose.y(),
                        pose.mode() == null ? -1 : pose.mode().ordinal()});
                }
            });
            serveur("relever l'assembly lâchée chez le serveur", s -> {
                chuteServeur = chute.getY();
                chuteTickFin = s.getTickCount();
            });
            pendant("le dernier snapshot", 10);
            une("juger T-410", mc -> jugerT410(mc, mc.level.getEntity(chuteId)));
            une("juger ADR-150 D7", mc -> jugerD7(mc, mc.level.getEntity(chuteId)));
            serveur("retirer l'assembly lâchée", s -> chute.discard());
            jusqua("l'assembly lâchée retirée", 60, mc -> mc.level.getEntity(chuteId) == null);
        }

        une("juger T-472, T-473 et le journal", mc -> {
            jugerT472();
            jugerT473();
        });
    }

    // --- Mise en place ---------------------------------------------------------------------------

    private static void creerLeMonde(Minecraft minecraft) {
        // Avant le chargement : une fenêtre qui perd le focus pendant qu'il se fait ouvrirait l'écran de
        // pause, et « le monde chargé » attendrait un écran nul qui ne viendrait jamais.
        minecraft.options.pauseOnLostFocus = false;
        GameRules regles = new GameRules();
        regles.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        regles.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        regles.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        // Aucun tick aléatoire : ni feuille qui se fane, ni herbe qui pousse dans la scène.
        regles.getRule(GameRules.RULE_RANDOMTICKING).set(0, null);
        // Spectateur : ni main à l'écran, ni observateur pour la physique (R-612) — les corps
        // dorment où ils sont posés, et la scène ne bouge pas.
        LevelSettings reglages = new LevelSettings(
                MONDE, GameType.SPECTATOR, false, Difficulty.PEACEFUL, true, regles, WorldDataConfiguration.DEFAULT);
        minecraft.createWorldOpenFlows().createFreshLevel(
                MONDE,
                reglages,
                new WorldOptions(0L, false, false),
                acces -> acces.registryOrThrow(Registries.WORLD_PRESET)
                        .getHolderOrThrow(WorldPresets.FLAT)
                        .value()
                        .createWorldDimensions());
    }

    private static void reglerLesOptions(Minecraft minecraft) {
        Options options = minecraft.options;
        options.hideGui = true;
        options.pauseOnLostFocus = false;
        options.tutorialStep = TutorialSteps.NONE;
        options.fov().set(70);
        options.renderDistance().set(4);
        options.cloudStatus().set(CloudStatus.OFF);
        options.particles().set(ParticleStatus.MINIMAL);
        options.entityShadows().set(false);
        options.bobView().set(false);
        options.fovEffectScale().set(0.0);
        options.screenEffectScale().set(0.0);
        options.enableVsync().set(false);
        options.framerateLimit().set(260);
        minecraft.getToasts().clear();
        minecraft.mouseHandler.releaseMouse();
    }

    /** Sur le thread du serveur : décor, heure, caméra, assemblies derrière elle. */
    private static void mettreEnPlace(MinecraftServer serveur) {
        ServerLevel monde = serveur.overworld();
        surface = monde.getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0);
        commandes(serveur, "time set 6000", "weather clear", "tp @a 0.5 " + surface + " 0.5 180 15");
        // Le décor vanilla de T-470, au nord : opaque, découpé, translucide. Rien d'animé, et aucune
        // source de lumière de bloc : Minecraft fait vaciller au hasard, à chaque tick, l'éclairage
        // qu'elles donnent (LightTexture.tick, Math.random) — au quatrième passage en CI, le
        // glowstone du décor changeait l'image d'une capture à l'autre.
        String[] blocs = {
            "stone", "glass", "oak_leaves[persistent=true]", "white_stained_glass", "red_concrete", "iron_bars", "cobweb"
        };
        for (int i = 0; i < blocs.length; i++) {
            commandes(serveur, "setblock " + (i - 3) + " " + surface + " -5 minecraft:" + blocs[i]);
        }
        commandes(serveur,
                "setblock -1 " + (surface + 1) + " -5 minecraft:tinted_glass",
                "setblock 1 " + (surface + 1) + " -5 minecraft:blue_stained_glass");
        // Derrière la caméra, au sud : hors de vue au nord comme à l'ouest.
        for (int i = 0; i < DERRIERE.size(); i++) {
            AssembliesDeTest.poser(monde, DERRIERE.get(i), new Vec3(1.5 + 2 * i, surface, 6.5), IDENTITE);
        }
    }

    private static boolean scenePrete(Minecraft minecraft) {
        AxionRenderPass.FrameStats frame = AxionRenderPass.lastFrame();
        return minecraft.levelRenderer.hasRenderedAllChunks()
                && minecraft.player.position().distanceToSqr(0.5, surface, 0.5) < 1e-4
                && minecraft.level.getBlockState(new BlockPos(-3, surface, -5)).is(Blocks.STONE)
                && minecraft.level.getBlockState(new BlockPos(1, surface + 1, -5)).is(Blocks.BLUE_STAINED_GLASS)
                && frame.assemblies() == DERRIERE.size()
                && frame.withAsset() == DERRIERE.size();
    }

    /** {@return vrai si la dernière frame dessine {@code attendues} assemblies, toutes avec leur asset} */
    private static boolean dessinees(int attendues) {
        AxionRenderPass.FrameStats frame = AxionRenderPass.lastFrame();
        return frame.assemblies() == attendues && frame.withAsset() == attendues;
    }

    /**
     * ADR-155 : le bras skinné posé devant la caméra, son fond capturé avant lui, puis plié ; à la fin, la frame le
     * montre plié, skinné par le backend.
     */
    private static void plierLeBras() {
        stabiliser("le fond du bras");
        une("capturer le fond du bras", mc -> {
            fondBras = capturer(mc, "skin_fond.png");
            assembliesAvantBras = AxionRenderPass.lastFrame().assemblies();
        });
        serveur("poser le bras skinné", s -> bras = AssembliesDeTest.poser(
                s.overworld(), BRAS, positionPanneau(Z_PLAT), QUART_DE_TOUR_A_DROITE));
        jusqua("le bras rendu, ses clips liés", 120, mc -> mc.level.getEntity(bras.getId()) != null
                && dessinees(assembliesAvantBras + 1) && !bras.animation().clips().isEmpty());
        serveur("plier le bras", s -> bras.animation().play("plier", 0, 1f, 0f));
        jusqua("le bras skinné au rendu", 120, mc -> skinnes() == 1);
        stabiliser("le bras plié");
    }

    /**
     * ADR-155 : 64 bras de plus, plus loin que le premier, rendus puis pliés, jusqu'à ce que le plafond de skinning
     * du backend les départage.
     *
     * @param atteint vrai quand la frame montre le plafond atteint
     */
    private static void plierLesCopies(Predicate<Minecraft> atteint) {
        serveur("poser 64 bras de plus", s -> {
            brasCopies.clear();
            for (int rang = 0; rang < PLAFOND_SKINNES; rang++) {
                brasCopies.add(AssembliesDeTest.poser(s.overworld(), BRAS,
                        new Vec3(-6.5 - 2.0 * (rang / 10), surface, -8.5 + 2.0 * (rang % 10)), IDENTITE));
            }
        });
        jusqua("les bras rendus, leurs clips liés", 240, mc -> dessinees(assembliesAvantBras + 1 + PLAFOND_SKINNES)
                && brasCopies.stream().noneMatch(copie -> copie.animation().clips().isEmpty()));
        une("relever les plafonnés", mc -> {
            plafonnesAvant = plafonnes();
            plafonnesVanillaAvant = plafonnesVanilla();
        });
        serveur("plier tous les bras", s -> brasCopies.forEach(copie -> copie.animation().play("plier", 0, 1f, 0f)));
        jusqua("le plafond atteint", 120, atteint);
    }

    /**
     * ADR-155, tranche 6b : sur le bras plié, les overlays {@code bones}, {@code nodes} et {@code pivots} allumés
     * seuls, jugés, puis éteints.
     */
    private static void jugerLesOverlaysDuBras() {
        une("allumer les overlays de l'animation", mc -> overlaysDeLAnimation(true));
        stabiliser("les overlays de l'animation");
        une("juger les overlays de l'animation", mc -> jugerOverlaysDuBras(capturer(mc, "adr155_overlays.png"), derniereVue));
        une("éteindre les overlays de l'animation", mc -> overlaysDeLAnimation(false));
    }

    private static void overlaysDeLAnimation(boolean allumes) {
        AxionRenderPass.setOverlay(dev.axion.debug.DebugOverlays.Overlay.BONES, allumes);
        AxionRenderPass.setOverlay(dev.axion.debug.DebugOverlays.Overlay.NODES, allumes);
        AxionRenderPass.setOverlay(dev.axion.debug.DebugOverlays.Overlay.PIVOTS, allumes);
    }

    /** ADR-155 : le bras et ses copies retirés, la scène revenue à ce qu'elle dessinait avant eux. */
    private static void retirerLesBras() {
        serveur("retirer les bras", s -> {
            bras.discard();
            brasCopies.forEach(Entity::discard);
        });
        jusqua("les bras retirés", 120, mc -> AxionRenderPass.lastFrame().assemblies() == assembliesAvantBras);
    }

    /** {@return les retenus skinnés de la dernière frame : la jauge {@code axion.render.skinned}} */
    private static long skinnes() {
        return dev.axion.diag.JavaMetrics.GLOBAL.gauge("axion.render.skinned", "count").value();
    }

    /** {@return les retenus skinnés laissés au repos depuis le lancement : {@code axion.render.skinned_capped}} */
    private static long plafonnes() {
        return dev.axion.diag.JavaMetrics.GLOBAL.counter("axion.render.skinned_capped", "count").value();
    }

    /** {@return les sommets skinnés sur le CPU à la dernière frame : {@code axion.render.vanilla_skinned_vertices}} */
    private static long sommetsSkinnesVanilla() {
        return dev.axion.diag.JavaMetrics.GLOBAL.gauge("axion.render.vanilla_skinned_vertices", "count").value();
    }

    /**
     * {@return les retenus laissés au repos par {@code render.vanilla_max_skinned_vertices} depuis le lancement :
     * {@code axion.render.vanilla_skinned_capped}}
     */
    private static long plafonnesVanilla() {
        return dev.axion.diag.JavaMetrics.GLOBAL.counter("axion.render.vanilla_skinned_capped", "count").value();
    }

    /**
     * {@return les bras que {@code render.vanilla_max_skinned_vertices} laisse skinner, au plus ceux que le natif
     * dote d'une palette (R-791) ; -1 tant que les sommets d'un bras ne sont pas relevés}
     */
    private static long brasSkinnesVanilla() {
        return sommetsParBras <= 0
                ? -1
                : Math.min(PLAFOND_SKINNES, AxionRenderPass.vanillaMaxSkinnedVertices() / sommetsParBras);
    }

    /**
     * T-908 au natif, partie skinning (ADR-155, tranche 5), et R-742 au vanilla (tranche 6a) : la silhouette du bras
     * plié couvre la boîte que projettent ses sommets skinnés par cette référence — chacun porté par ses os, au
     * coude plié d'un quart de tour autour de z, puis posé à la place du bras —, à {@link #ECART_BOITE} pixels
     * près. La déformation s'y ajoutera en M6.
     *
     * @param id l'identifiant noté au rapport
     * @param ou où le backend skinne : « au GPU », « sur le CPU »
     */
    private static void jugerBrasPlie(String id, String ou, CapturesDuBanc.Capture plie, Vue vue) {
        brasPlie = plie;
        CapturesDuBanc.Masque masque = CapturesDuBanc.masque(fondBras, plie);
        Quaternionf pose = bras.bodyRotation();
        Vec3 origine = bras.position();
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        List<Vector3f> sommets = new ArrayList<>();
        for (float x : new float[] {-0.5f, 0.5f}) {
            for (float y : new float[] {0f, 2 * BRAS_SOCLE}) {
                for (float z : new float[] {-0.5f, 0.5f}) {
                    sommets.add(new Vector3f(x, y, z));
                }
            }
        }
        // Le coude pivote autour de (0, pied + segment, 0) : un sommet du milieu à moitié, du haut en entier.
        float coude = BRAS_PIED + BRAS_SEGMENT;
        for (float x : new float[] {-BRAS_DEMI, BRAS_DEMI}) {
            for (float z : new float[] {-BRAS_DEMI, BRAS_DEMI}) {
                for (int rang = 0; rang < 3; rang++) {
                    float y = BRAS_PIED + rang * BRAS_SEGMENT;
                    Vector3f repos = new Vector3f(x, y, z);
                    Vector3f plieCoude = new Vector3f(-(y - coude), coude + x, z);
                    float poids = rang * 0.5f;
                    sommets.add(new Vector3f(repos).mul(1 - poids).add(new Vector3f(plieCoude).mul(poids)));
                }
            }
        }
        for (Vector3f sommet : sommets) {
            Vector3f monde = pose.transform(new Vector3f(sommet));
            float[] ecran = projeter(vue, origine.add(monde.x, monde.y, monde.z), plie.largeur(), plie.hauteur());
            minX = Math.min(minX, ecran[0]);
            minY = Math.min(minY, ecran[1]);
            maxX = Math.max(maxX, ecran[0]);
            maxY = Math.max(maxY, ecran[1]);
        }
        int[] attendue = {
            (int) Math.ceil(minX - 0.5f), (int) Math.ceil(minY - 0.5f),
            (int) Math.floor(maxX - 0.5f), (int) Math.floor(maxY - 0.5f)
        };
        int ecart = ecart(masque, attendue);
        RAPPORT.noter(id, masque.nombre() >= aire(attendue) / 4 && ecart <= ECART_BOITE, String.format(
                Locale.ROOT, "bras plié skinné %s : %d px, boîte %s ; référence projetée %s (écart %d, %d tolérés) ;"
                        + " la déformation s'y ajoute en M6",
                ou, masque.nombre(), Arrays.toString(masque.boite()), Arrays.toString(attendue), ecart, ECART_BOITE));
    }

    /**
     * ADR-155, tranche 6b : allumés sur le bras plié, {@code bones}, {@code nodes} et {@code pivots} reçoivent chacun
     * sa plage du contexte de rendu ; et l'os de l'épaule au coude se voit à travers la peau — au milieu de la
     * colonne, où il passe, l'image n'est plus celle du bras sans overlay.
     */
    private static void jugerOverlaysDuBras(CapturesDuBanc.Capture avec, Vue vue) {
        java.util.Map<Integer, Integer> plages = AxionRenderPass.debugRangesByOverlay();
        int os = plages.getOrDefault(dev.axion.debug.DebugOverlays.Overlay.BONES.rank(), 0);
        int nodes = plages.getOrDefault(dev.axion.debug.DebugOverlays.Overlay.NODES.rank(), 0);
        int pivots = plages.getOrDefault(dev.axion.debug.DebugOverlays.Overlay.PIVOTS.rank(), 0);
        Vector3f milieu = bras.bodyRotation().transform(new Vector3f(0f, BRAS_PIED + BRAS_SEGMENT / 2, 0f));
        float[] ecran = projeter(vue, bras.position().add(milieu.x, milieu.y, milieu.z), avec.largeur(), avec.hauteur());
        int cx = (int) Math.floor(ecran[0]);
        int cy = (int) Math.floor(ecran[1]);
        int changes = 0;
        for (int y = Math.max(0, cy - 1); y <= Math.min(avec.hauteur() - 1, cy + 1); y++) {
            for (int x = Math.max(0, cx - 1); x <= Math.min(avec.largeur() - 1, cx + 1); x++) {
                if (avec.pixel(x, y) != brasPlie.pixel(x, y)) {
                    changes++;
                }
            }
        }
        RAPPORT.noter("ADR-155, overlays", os > 0 && nodes > 0 && pivots > 0 && changes > 0, String.format(Locale.ROOT,
                "plages reçues du rendu : bones %d, nodes %d, pivots %d ; au milieu de l'os, à travers la peau, %d pixel(s)"
                        + " changé(s) sur 9 autour de (%d, %d)", os, nodes, pivots, changes, cx, cy));
    }

    /**
     * T-493 (R-742) : au-delà de {@code render.vanilla_max_skinned_vertices}, les bras les plus lointains restent au
     * repos ; les sommets skinnés de la frame tiennent sous le plafond, et la métrique compte les bras laissés.
     */
    private static void jugerT493() {
        long plafond = AxionRenderPass.vanillaMaxSkinnedVertices();
        long attendus = brasSkinnesVanilla();
        long skinnes = skinnes();
        long sommets = sommetsSkinnesVanilla();
        long laisses = plafonnesVanilla() - plafonnesVanillaAvant;
        boolean vert = attendus > 0 && attendus < PLAFOND_SKINNES && skinnes == attendus
                && sommets == skinnes * sommetsParBras && sommets <= plafond && laisses > 0;
        RAPPORT.noter("T-493", vert, String.format(Locale.ROOT,
                "%d bras pliés en vue, %d sommets skinnés par bras : %d skinnés sur le CPU (%d attendus), %d sommets"
                        + " (render.vanilla_max_skinned_vertices = %d) ; %d laissé(s) au repos depuis le relevé",
                PLAFOND_SKINNES + 1, sommetsParBras, skinnes, attendus, sommets, plafond, laisses));
    }

    /**
     * T-541 (ADR-155 D7) : le repli UBO paginé donne l'image du chemin TBO, pixel pour pixel — sur 65 bras
     * pliés, dont les 64 palettes s'étalent dans la table, chacune dans sa page.
     */
    private static void jugerT541(CapturesDuBanc.Capture ubo) {
        int ecarts = CapturesDuBanc.masque(brasTbo, ubo).nombre();
        boolean replie = AxionRenderPass.paletteUbo();
        RAPPORT.noter("T-541", replie && ecarts == 0, String.format(Locale.ROOT,
                "palettes lues par pages d'UBO : %s ; %d pixel(s) différent(s) du chemin TBO (0 attendu)",
                replie ? "oui" : "non", ecarts));
    }

    /** T-542 (ADR-155 D7, R-791) : au-delà du plafond, la pose de repos s'applique et la métrique le compte. */
    private static void jugerT542() {
        long skinnes = skinnes();
        long plafonnes = plafonnes() - plafonnesAvant;
        RAPPORT.noter("T-542", skinnes == PLAFOND_SKINNES && plafonnes > 0, String.format(Locale.ROOT,
                "%d bras pliés en vue : %d skinnés (render.max_skinned_instances = %d), %d laissé(s) au repos"
                        + " depuis le relevé", PLAFOND_SKINNES + 1, skinnes, PLAFOND_SKINNES, plafonnes));
    }

    /** {@return la couche 0 de la glissière au client, telle que son plus récent snapshot la porte} */
    private static dev.axion.net.AnimationLayers.Layer coucheAuClient(Minecraft minecraft) {
        return minecraft.level.getEntity(glissiere.getId()) instanceof AxionEntity auClient
                ? auClient.animationState().layer(0)
                : dev.axion.net.AnimationLayers.Layer.EMPTY;
    }

    /**
     * ADR-155, tranche 4 : poussé, le coulisseau élargit la silhouette de la glissière d'au moins
     * {@link #ELARGISSEMENT} pixels ; arrêtée, la silhouette retrouve sa boîte du repos à un pixel près, et
     * l'image ne s'en écarte que des arrondis des matrices du natif — un cinquantième de la silhouette au plus.
     */
    private static void jugerAnimation(CapturesDuBanc.Capture arretee) {
        CapturesDuBanc.Masque repos = CapturesDuBanc.masque(fondGlissiere, reposGlissiere);
        CapturesDuBanc.Masque animee = CapturesDuBanc.masque(fondGlissiere, animeeGlissiere);
        CapturesDuBanc.Masque retour = CapturesDuBanc.masque(fondGlissiere, arretee);
        int restes = CapturesDuBanc.masque(reposGlissiere, arretee).nombre();
        int largeurRepos = repos.maxX() - repos.minX();
        int largeurAnimee = animee.maxX() - animee.minX();
        boolean visible = repos.nombre() > 0;
        boolean poussee = largeurAnimee >= largeurRepos + ELARGISSEMENT;
        boolean revenue = retour.nombre() > 0 && ecart(retour, repos.boite()) <= 1 && restes <= repos.nombre() / 50;
        RAPPORT.noter("ADR-155, animation", visible && poussee && revenue, String.format(Locale.ROOT,
                "au repos : %d px, boîte %s ; poussé : %d px, boîte %s (largeur %d → %d, %d attendus de plus) ;"
                        + " arrêtée : boîte %s, %d px différents du repos (%d tolérés)",
                repos.nombre(), Arrays.toString(repos.boite()), animee.nombre(), Arrays.toString(animee.boite()),
                largeurRepos, largeurAnimee, ELARGISSEMENT, Arrays.toString(retour.boite()), restes,
                repos.nombre() / 50));
    }

    /** {@return vrai si l'assembly {@code id} est au monde du client, et toutes dessinées avec leur asset} */
    private static boolean rendu(Minecraft minecraft, int attendues, int id) {
        AxionRenderPass.FrameStats frame = AxionRenderPass.lastFrame();
        return minecraft.level.getEntity(id) != null
                && frame.assemblies() == attendues
                && frame.withAsset() == attendues;
    }

    /** {@return le pied d'un panneau, à côté du cube, décalé le long de z} */
    private static Vec3 positionPanneau(double dz) {
        return positionCube().add(0.0, 0.0, dz);
    }

    /** {@return où lâcher l'assembly de T-410 : six blocs au-dessus du sol, à l'écart de la scène} */
    private static Vec3 positionChute() {
        return new Vec3(6.5, surface + 6.0, 6.5);
    }

    private static Vec3 positionCube() {
        return new Vec3(-3.5, surface, 0.5);
    }

    /** {@return où poser la charnière : plus loin que le cube, sur le côté, à un bloc et quart du sol} */
    private static Vec3 positionCharniere() {
        return new Vec3(-6.5, surface + 1.25, 3.0);
    }

    /** {@return vrai si b est à la butée au serveur : un demi-radian de rotation relative à la racine} */
    private static boolean aLaButee() {
        List<AxionEntity.BodyPose> corps = charniere.secondaryBodies();
        if (corps.size() != 1) {
            return false;
        }
        Quaternionf relative = charniere.bodyRotation().conjugate().mul(corps.get(0).rotation());
        double angle = 2 * Math.acos(Math.min(1.0, Math.abs(relative.w)));
        return Math.abs(angle - 0.5) < 0.03;
    }

    /** {@return vrai si le client tient le second corps de la charnière, prêt à le dessiner} */
    private static boolean corpsDessines(Minecraft minecraft) {
        return minecraft.level.getEntity(charniere.getId()) instanceof AxionEntity client
                && client.renderPose(0f).bodies().size() == 1;
    }

    /**
     * Les poses de la charnière au serveur : celle de son corps racine, et celle de {@code b}.
     *
     * @param racine position monde du corps racine
     * @param rotationRacine son orientation
     * @param b position monde de {@code b}
     * @param rotationB son orientation
     */
    private record PosesCharniere(Vec3 racine, Quaternionf rotationRacine, Vec3 b, Quaternionf rotationB) {

        static PosesCharniere de(AxionEntity charniere) {
            AxionEntity.BodyPose b = charniere.secondaryBodies().get(0);
            return new PosesCharniere(charniere.position(), charniere.bodyRotation(), new Vec3(b.x(), b.y(), b.z()),
                    new Quaternionf(b.rotation()));
        }
    }

    private static void viser(float nouveauLacet, float nouveauTangage) {
        lacet = nouveauLacet;
        tangage = nouveauTangage;
        cameraFixee = true;
    }

    private static void fixerLaCamera(Minecraft minecraft) {
        if (cameraFixee && minecraft.player != null) {
            minecraft.player.setYRot(lacet);
            minecraft.player.yRotO = lacet;
            minecraft.player.setXRot(tangage);
            minecraft.player.xRotO = tangage;
            minecraft.player.setYHeadRot(lacet);
            minecraft.player.yHeadRotO = lacet;
        }
    }

    // --- Jugements -------------------------------------------------------------------------------

    private static void jugerBackend() {
        BackendSelection.Kind actif = AxionRenderPass.activeBackend();
        BackendSelection.Kind attendu = switch (BACKEND_DEMANDE == null ? "auto" : BACKEND_DEMANDE) {
            case "native" -> BackendSelection.Kind.NATIVE;
            case "vanilla" -> BackendSelection.Kind.VANILLA;
            default -> null;
        };
        RAPPORT.noter("backend", actif != null && (attendu == null || actif == attendu), String.format(Locale.ROOT,
                "backend %s actif, %s demandé", actif, BACKEND_DEMANDE == null ? "auto" : BACKEND_DEMANDE));
    }

    /**
     * T-490 : Oculus est chargé et son shaderpack en usage ; la sélection automatique — aucun backend
     * n'est imposé — a retenu le vanilla, pour cette raison, et le natif n'a jamais démarré.
     */
    private static void jugerT490() {
        String raison = raisonDuChoix();
        String attendue = BackendSelection.select("auto", true, true).reason();
        boolean oculus = ModList.get().isLoaded("oculus");
        boolean pack = shaderpackEnUsage();
        boolean vert = oculus && pack && BACKEND_DEMANDE == null
                && AxionRenderPass.activeBackend() == BackendSelection.Kind.VANILLA
                && attendue.equals(raison) && AxionRenderPass.nativeGlObjects() == 0;
        RAPPORT.noter("T-490", vert, String.format(Locale.ROOT,
                "Oculus %s, shaderpack %s ; backend %s demandé, %s retenu (« %s ») ; %d objet(s) GL du natif",
                oculus ? "chargé" : "ABSENT", pack ? "en usage" : "INACTIF",
                BACKEND_DEMANDE == null ? "auto" : BACKEND_DEMANDE, AxionRenderPass.activeBackend(), raison,
                AxionRenderPass.nativeGlObjects()));
    }

    /**
     * {@return vrai si Oculus dit un shaderpack en usage} Lu par réflexion sur l'API v0 d'Iris,
     * qu'Oculus embarque : le banc n'est pas compilé contre lui, et AXION n'en dépend pas.
     */
    private static boolean shaderpackEnUsage() {
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object instance = api.getMethod("getInstance").invoke(null);
            return (Boolean) api.getMethod("isShaderPackInUse").invoke(instance);
        } catch (ReflectiveOperationException absente) {
            return false;
        }
    }

    /**
     * Active ou coupe le shaderpack par l'API v0 d'Iris, comme le ferait le joueur (ADR-129). Par
     * réflexion : le banc n'est pas compilé contre Oculus.
     */
    private static void activerLeShaderpack(boolean actif) {
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object instance = api.getMethod("getInstance").invoke(null);
            Object config = api.getMethod("getConfig").invoke(instance);
            Class.forName("net.irisshaders.iris.api.v0.IrisApiConfig")
                    .getMethod("setShadersEnabledAndApply", boolean.class)
                    .invoke(config, actif);
        } catch (ReflectiveOperationException injoignable) {
            throw new IllegalStateException("API d'Iris injoignable", injoignable);
        }
    }

    /** {@return la raison du dernier choix de backend, telle que le runtime la tient} */
    private static String raisonDuChoix() {
        AxionRuntime runtime = RuntimeAccess.get();
        RenderCapabilities matrice = runtime == null ? null : runtime.renderCapabilities();
        return matrice == null ? "aucune" : matrice.selection().reason();
    }

    /**
     * R-1490, ADR-129 : pack coupé, le choix refait retient le natif pour la raison d'un
     * {@code auto} ordinaire, le natif tient ses objets GL et dessine le cube — à sa place, hors du
     * pack, donc avec son bleu ; pack rétabli, le vanilla revient pour la raison du shaderpack, et
     * le natif n'a plus aucun objet GL (R-744).
     */
    private static void jugerChangementDePack() {
        CapturesDuBanc.Masque masque = CapturesDuBanc.masque(packCoupeSans, packCoupeAvec);
        int[] attendu = boiteAttendue(vuePackCoupe, HUITIEME_DE_TOUR, packCoupeAvec.largeur(), packCoupeAvec.hauteur());
        int ecart = ecart(masque, attendu);
        int bleu = CapturesDuBanc.bleuMaximal(packCoupeAvec, masque);
        boolean natif = !packCoupeEnUsage && backendPackCoupe == BackendSelection.Kind.NATIVE
                && BackendSelection.select("auto", true, false).reason().equals(raisonPackCoupe) && objetsPackCoupe > 0;
        boolean dessine = masque.nombre() >= aire(attendu) / 2 && ecart <= ECART_BOITE && bleu > BLEU_DU_PACK;
        String raisonRetablie = raisonDuChoix();
        boolean retabli = shaderpackEnUsage() && AxionRenderPass.activeBackend() == BackendSelection.Kind.VANILLA
                && BackendSelection.select("auto", true, true).reason().equals(raisonRetablie)
                && AxionRenderPass.nativeGlObjects() == 0;
        RAPPORT.noter("R-1490, changement de shaderpack", natif && dessine && retabli, String.format(Locale.ROOT,
                "pack coupé (%s) : %s retenu (« %s »), %d objet(s) GL du natif ; cube : %d px, boîte %s pour %s"
                        + " attendue (écart %d), bleu le plus fort %d (hors du pack : plus de %d) ; pack rétabli (%s) :"
                        + " %s retenu (« %s »), %d objet(s) GL du natif",
                packCoupeEnUsage ? "ENCORE EN USAGE" : "plus en usage", backendPackCoupe, raisonPackCoupe,
                objetsPackCoupe, masque.nombre(), Arrays.toString(masque.boite()), Arrays.toString(attendu), ecart,
                bleu, BLEU_DU_PACK, shaderpackEnUsage() ? "en usage" : "INACTIF", AxionRenderPass.activeBackend(),
                raisonRetablie, AxionRenderPass.nativeGlObjects()));
    }

    /**
     * T-480 : sous le shaderpack, le cube est visible et orienté — sa silhouette tombe dans la boîte
     * de sa projection, droit puis tourné —, et c'est le shaderpack qui l'éclaire : ses pixels sortent
     * de ses programmes, au bleu nul, et la nuit l'assombrit par sa lightmap.
     */
    private static void jugerT480() {
        CapturesDuBanc.Masque masqueDroit = CapturesDuBanc.masque(fond, droit);
        CapturesDuBanc.Masque masqueTourne = CapturesDuBanc.masque(fond, tourne);
        int[] attenduDroit = boiteAttendue(vueDroit, IDENTITE, droit.largeur(), droit.hauteur());
        int[] attenduTourne = boiteAttendue(vueTourne, HUITIEME_DE_TOUR, tourne.largeur(), tourne.hauteur());
        int ecartDroit = ecart(masqueDroit, attenduDroit);
        int ecartTourne = ecart(masqueTourne, attenduTourne);
        boolean visible = masqueDroit.nombre() >= aire(attenduDroit) / 2 && ecartDroit <= ECART_BOITE;
        boolean orientee = masqueTourne.nombre() >= aire(attenduTourne) / 2 && ecartTourne <= ECART_BOITE;
        int bleuDroit = CapturesDuBanc.bleuMaximal(droit, masqueDroit);
        int bleuTourne = CapturesDuBanc.bleuMaximal(tourne, masqueTourne);
        boolean parLePack = bleuDroit >= 0 && bleuDroit <= BLEU_DU_PACK && bleuTourne >= 0 && bleuTourne <= BLEU_DU_PACK;
        double jour = CapturesDuBanc.luminance(tourne, masqueTourne);
        double nuit = CapturesDuBanc.luminance(minuit, masqueTourne);
        boolean eclairee = jour >= 20.0 && nuit < 0.8 * jour;
        RAPPORT.noter("T-480", visible && orientee && parLePack && eclairee, String.format(Locale.ROOT,
                "droit : %d px, boîte %s pour %s attendue (écart %d) ; tourné : %d px, boîte %s pour %s attendue"
                        + " (écart %d) ; bleu le plus fort du cube %d puis %d (%d au plus : sorti du shaderpack) ;"
                        + " midi %.1f, minuit %.1f",
                masqueDroit.nombre(), Arrays.toString(masqueDroit.boite()), Arrays.toString(attenduDroit), ecartDroit,
                masqueTourne.nombre(), Arrays.toString(masqueTourne.boite()), Arrays.toString(attenduTourne),
                ecartTourne, bleuDroit, bleuTourne, BLEU_DU_PACK, jour, nuit));
    }

    private static void jugerT470(CapturesDuBanc.Capture encore) {
        int instables = CapturesDuBanc.masque(avec, encore).nombre();
        int ecarts = CapturesDuBanc.masque(avec, sans).nombre();
        boolean dessinees = passesAvec.assemblies() == DERRIERE.size() && passesAvec.withAsset() == DERRIERE.size();
        RAPPORT.noter("T-470", dessinees && instables == 0 && ecarts == 0, String.format(Locale.ROOT,
                "%d assembly(s) dessinée(s) hors champ avec leur asset ; %d pixel(s) différent(s) avec et sans"
                        + " les passes d'AXION, %d entre deux captures avec (scène stable : 0 attendu)",
                passesAvec.withAsset(), ecarts, instables));
    }

    private static void jugerT474() {
        CapturesDuBanc.Masque masqueDroit = CapturesDuBanc.masque(fond, droit);
        CapturesDuBanc.Masque masqueTourne = CapturesDuBanc.masque(fond, tourne);
        int[] attenduDroit = boiteAttendue(vueDroit, IDENTITE, droit.largeur(), droit.hauteur());
        int[] attenduTourne = boiteAttendue(vueTourne, HUITIEME_DE_TOUR, tourne.largeur(), tourne.hauteur());
        int ecartDroit = ecart(masqueDroit, attenduDroit);
        int ecartTourne = ecart(masqueTourne, attenduTourne);
        boolean visible = masqueDroit.nombre() >= aire(attenduDroit) / 2 && ecartDroit <= ECART_BOITE;
        boolean orientee = masqueTourne.nombre() >= aire(attenduTourne) / 2 && ecartTourne <= ECART_BOITE;

        // Éclairée : le dessus et les côtés visibles du cube tourné n'ont pas la même luminance,
        // et la nuit l'assombrit.
        double[] faces = luminancesDesFaces(tourne, vueTourne);
        boolean ombree = faces.length > 1;
        for (int i = 1; i < faces.length; i++) {
            ombree &= Math.abs(faces[0] - faces[i]) >= 4.0;
        }
        double jour = CapturesDuBanc.luminance(tourne, masqueTourne);
        double nuit = CapturesDuBanc.luminance(minuit, masqueTourne);
        boolean assombrie = nuit < 0.8 * jour;

        RAPPORT.noter("T-474", visible && orientee && ombree && assombrie, String.format(Locale.ROOT,
                "droit : %d px, boîte %s pour %s attendue (écart %d) ; tourné : %d px, boîte %s pour %s attendue"
                        + " (écart %d) ; luminance du dessus puis des côtés %s ; midi %.1f, minuit %.1f",
                masqueDroit.nombre(), Arrays.toString(masqueDroit.boite()), Arrays.toString(attenduDroit), ecartDroit,
                masqueTourne.nombre(), Arrays.toString(masqueTourne.boite()), Arrays.toString(attenduTourne),
                ecartTourne, Arrays.toString(arrondir(faces)), jour, nuit));
    }

    /**
     * ADR-151 D9 : la silhouette de la charnière est la boîte des deux cubes à leurs poses du serveur, b penché.
     * Celle de l'asset au repos, b à l'horizontale, doit s'en écarter : sans quoi le jugement ne départagerait
     * pas un dessin par corps d'un dessin rigide.
     */
    private static void jugerD9(CapturesDuBanc.Capture capture, Vue vue) {
        CapturesDuBanc.Masque masque = CapturesDuBanc.masque(fondCharniere, capture);
        PosesCharniere p = posesCharniere;
        Vector3f decalage = new Quaternionf(p.rotationRacine()).transform(new Vector3f(2f, 0f, 0f));
        Vec3 bAuRepos = p.racine().add(decalage.x, decalage.y, decalage.z);
        int[] attendue = boiteDesCubes(vue, capture, p.racine(), p.rotationRacine(), p.b(), p.rotationB());
        int[] rigide = boiteDesCubes(vue, capture, p.racine(), p.rotationRacine(), bAuRepos, p.rotationRacine());
        int ecart = ecart(masque, attendue);
        int ecartRigide = ecart(masque, rigide);
        // ADR-151 D8 : le client tient aussi l'état du joint, ni rompu ni grippé.
        dev.axion.net.AssemblyState.JointBits joints =
                Minecraft.getInstance().level.getEntity(charniere.getId()) instanceof AxionEntity client
                        ? client.jointBits()
                        : dev.axion.net.AssemblyState.JointBits.NONE;
        boolean jointRecu = joints.count() == 1 && joints.flags(0) == 0;
        boolean vert = masque.nombre() >= aire(attendue) / 4 && ecart <= ECART_BOITE && ecartRigide > ECART_BOITE
                && jointRecu;
        RAPPORT.noter("ADR-151 D9", vert, String.format(Locale.ROOT,
                "%d px, boîte %s pour %s attendue, b à sa pose (écart %d) ; au repos, b à l'horizontale, %s"
                        + " (écart %d) ; joints au client : %s",
                masque.nombre(), Arrays.toString(masque.boite()), Arrays.toString(attendue), ecart,
                Arrays.toString(rigide), ecartRigide, joints));
    }

    private static void jugerT471() {
        long pendantLesPasses = GlStateCheck.errors() - erreursPassesAvant;
        long avantLesPasses = GlStateCheck.errorsBeforePasses() - erreursAvantPassesAvant;
        long total = erreursFrames + pendantLesPasses + avantLesPasses;
        RAPPORT.noter("T-471", framesComptees >= FRAMES_T471 && total == 0, String.format(Locale.ROOT,
                "%d frame(s) avec AXION, %d erreur(s) GL (%d dans ses passes) ; %d frame(s) sans AXION avant,"
                        + " %d erreur(s)",
                framesComptees, total, pendantLesPasses, FRAMES_SANS_AXION, erreursSansAxion));
    }

    private static void jugerT503EtT510() {
        int fermees = AxionRenderPass.nativeCloses() - fermetures;
        int laisses = AxionRenderPass.nativeGlObjectsLeftAtLastClose();
        int nouveaux = AxionRenderPass.backendStarts() - demarrages;
        int avant = objetsNatif.get(0);
        int apres = AxionRenderPass.nativeGlObjects();
        objetsNatif.add(apres);
        AxionRenderPass.FrameStats passe = AxionRenderPass.lastFrame();
        RAPPORT.noter("T-503", fermees == 1 && laisses == 0 && apres == avant && passe.withAsset() == passe.assemblies(),
                String.format(Locale.ROOT,
                        "rechargement des ressources : %d fermeture(s) du natif, %d objet(s) GL laissé(s) en vie ;"
                                + " %d objet(s) avant, %d reconstruit(s) ; %d assembly(s) sur %d redessinée(s) avec leur"
                                + " asset",
                        fermees, laisses, avant, apres, passe.withAsset(), passe.assemblies()));
        statsRelecture = AxionRenderPass.nativeShaderStats();
        BackendSelection.Kind actif = AxionRenderPass.activeBackend();
        RAPPORT.noter("T-510", nouveaux == 1 && actif == BackendSelection.Kind.NATIVE, String.format(Locale.ROOT,
                "%d démarrage(s) du backend au rechargement, shaders relus du JAR et recompilés ; backend %s",
                nouveaux, actif));
    }

    /**
     * ADR-146 §2 (§31.1) : sous le natif, le temps GPU des deux passes qui dessinent en GL est connu,
     * lu quelques frames après elles sans attendre le GPU. Les durées sont celles du poste ou du
     * rendu logiciel de la CI : elles ne se publient pas (R-2252), seul leur caractère connu est jugé.
     */
    private static void jugerTempsGpu() {
        long opaque = AxionRenderPass.gpuPassNanos(GpuPassTimings.Pass.OPAQUE);
        long translucide = AxionRenderPass.gpuPassNanos(GpuPassTimings.Pass.TRANSLUCENT);
        RAPPORT.noter("temps GPU par passe", opaque > 0 && translucide > 0, String.format(Locale.ROOT,
                "backend %s ; passe opaque : %s ; passe translucide : %s",
                AxionRenderPass.activeBackend(), connu(opaque), connu(translucide)));
    }

    /**
     * T-550 (amendement A1 d'ADR-121) : chacun des sept overlays a rendu au moins une plage au
     * dernier tick — colliders, AABB, axes et centre de masse du cube, ses contacts au sol et
     * leurs normales, les tuiles du monde autour de lui.
     */
    private static void jugerOverlays() {
        java.util.Map<Integer, Integer> plages = AxionRenderPass.debugRangesByOverlay();
        StringBuilder detail = new StringBuilder();
        boolean vert = true;
        for (dev.axion.debug.DebugOverlays.Overlay overlay : dev.axion.debug.DebugOverlays.Overlay.values()) {
            if (overlay == dev.axion.debug.DebugOverlays.Overlay.JOINTS
                    || overlay == dev.axion.debug.DebugOverlays.Overlay.LIMITS) {
                // Ils tracent des joints : jugés sur la charnière (ADR-151 D7).
                continue;
            }
            if (overlay.source() == dev.axion.debug.DebugOverlays.Source.RENDER) {
                // Ils tracent l'animation : jugés sur le bras plié (ADR-155, tranche 6b).
                continue;
            }
            int n = plages.getOrDefault(overlay.rank(), 0);
            vert &= n > 0;
            detail.append(detail.length() == 0 ? "" : ", ").append(overlay.commandName()).append(' ').append(n);
        }
        RAPPORT.noter("overlays de debug", vert, "plages reçues : " + detail);
    }

    /**
     * ADR-151 D7 : allumés sur la charnière, {@code joints} et {@code limits} reçoivent chacun sa plage du
     * natif, et la dessinent ; une erreur au dessin se journalise, et le verdict « erreurs AXION » la voit.
     */
    private static void jugerD7() {
        java.util.Map<Integer, Integer> plages = AxionRenderPass.debugRangesByOverlay();
        int joints = plages.getOrDefault(dev.axion.debug.DebugOverlays.Overlay.JOINTS.rank(), 0);
        int limits = plages.getOrDefault(dev.axion.debug.DebugOverlays.Overlay.LIMITS.rank(), 0);
        RAPPORT.noter("ADR-151 D7", joints > 0 && limits > 0, String.format(Locale.ROOT,
                "plages reçues sur la charnière : joints %d, limits %d", joints, limits));
    }

    /** {@return ce qu'on sait d'un temps GPU de passe, sans le chiffre} */
    private static String connu(long nanos) {
        return nanos < 0 ? "hors du natif" : nanos == 0 ? "inconnu" : "connu";
    }

    /**
     * T-477 (§19.13) : 200 copies en au plus 3 appels de dessin — l'écart d'appels avec la scène sans elles,
     * toutes dessinées.
     */
    private static void jugerT477() {
        int appels = AxionRenderPass.nativeDrawCalls();
        int dessinees = AxionRenderPass.lastFrame().assemblies() - dessineesSansCopies;
        int ecart = appels - appelsSansCopies;
        boolean vert = dessinees == copies.size() && appelsSansCopies >= 0 && ecart <= 3;
        RAPPORT.noter("T-477", vert, String.format(Locale.ROOT,
                "%d copies posées, %d dessinées ; %d appels de dessin sans elles, %d avec : %+d (au plus 3)",
                copies.size(), dessinees, appelsSansCopies, appels, ecart));
    }

    /**
     * T-478 (§19.13, R-781) : le chemin non instancié, forcé par un seuil plus grand que toute série, rend
     * l'image du chemin instancié, pixel pour pixel.
     */
    private static void jugerT478(CapturesDuBanc.Capture direct) {
        CapturesDuBanc.Masque ecart = CapturesDuBanc.masque(copiesInstanciees, direct);
        int appels = AxionRenderPass.nativeDrawCalls();
        boolean vert = ecart.nombre() == 0 && appels - appelsSansCopies >= copies.size();
        RAPPORT.noter("T-478", vert, String.format(Locale.ROOT,
                "chemin non instancié : %d appels de dessin (%+d sur la scène sans copies) ; %d pixel(s) différent(s)"
                        + " de l'image instanciée",
                appels, appels - appelsSansCopies, ecart.nombre()));
    }

    /**
     * T-907 (§19.13, R-782) : le chemin indirect (GL 4.3) rend l'image du chemin instancié direct, pixel pour
     * pixel.
     */
    private static void jugerT907(CapturesDuBanc.Capture direct) {
        CapturesDuBanc.Masque ecart = CapturesDuBanc.masque(copiesIndirectes, direct);
        int appels = AxionRenderPass.nativeDrawCalls();
        RAPPORT.noter("T-907", ecart.nombre() == 0, String.format(Locale.ROOT,
                "chemin indirect : %d appels de dessin ; direct instancié : %d ; %d pixel(s) différent(s)",
                appelsIndirects, appels, ecart.nombre()));
    }

    /**
     * T-485 (§19.13) : 100 assemblies visibles — les appels de dessin et les triangles de la frame mesurés, publiés,
     * et tenus sous {@code budgets.max_triangles_frame} et {@code budgets.max_visible_instances}.
     */
    private static void jugerT485() {
        AxionRenderPass.FrameStats frame = AxionRenderPass.lastFrame();
        long triangles = AxionRenderPass.lastTriangles();
        long[] plafonds = AxionRenderPass.frameLimits();
        int appels = AxionRenderPass.nativeDrawCalls();
        boolean vert = frame.assemblies() - dessineesSansCopies == 100
                && appels > 0
                && triangles > 0 && triangles <= plafonds[0]
                && frame.assemblies() <= plafonds[1];
        RAPPORT.noter("T-485", vert, String.format(Locale.ROOT,
                "100 copies et %d assemblies de la scène : %d appels de dessin, %d triangles (plafond %d),"
                        + " %d assemblies dessinées (plafond %d)",
                dessineesSansCopies, appels, triangles, plafonds[0], frame.assemblies(), plafonds[1]));
    }

    /**
     * T-484 (R-750, palier 2) : sous le LOD forcé, les textures d'AXION tiennent au plus la moitié de leurs
     * octets — un mip retiré en rend les trois quarts —, toutes les assemblies dessinées avec leur asset.
     */
    private static void jugerT484() {
        long apres = AxionRenderPass.textureBytes();
        texturesForcees = apres;
        AxionRenderPass.FrameStats frame = AxionRenderPass.lastFrame();
        boolean vert = texturesAvant > 0 && apres > 0 && apres * 2 <= texturesAvant
                && frame.withAsset() == frame.assemblies();
        RAPPORT.noter("T-484", vert, String.format(Locale.ROOT,
                "LOD forcé : textures d'AXION %d → %d octets (%.0f %%) ; %d assemblies dessinées, %d avec leur asset",
                texturesAvant, apres, texturesAvant == 0 ? 0.0 : 100.0 * apres / texturesAvant,
                frame.assemblies(), frame.withAsset()));
    }

    /** {@return la décision du culling pour une assembly posée, côté client, ou {@code null}} */
    private static Integer niveau(Minecraft minecraft, AxionEntity posee) {
        return minecraft.level.getEntity(posee.getId()) instanceof AxionEntity client
                ? AxionRenderPass.decisionOf(client) : null;
    }

    /** {@return la décision du culling pour chaque assembly du client, par identifiant} */
    private static java.util.Map<Integer, Integer> decisions(Minecraft minecraft) {
        java.util.Map<Integer, Integer> decisions = new java.util.TreeMap<>();
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity instanceof AxionEntity assembly && AxionRenderPass.decisionOf(assembly) != null) {
                decisions.put(entity.getId(), AxionRenderPass.decisionOf(assembly));
            }
        }
        return decisions;
    }

    /**
     * T-475 (§19.13) : le LOD aux distances attendues — trois ballons retenus, de plus en plus loin, à des niveaux
     * qui ne décroissent pas, le plus lointain plus grossier que le plus proche. Les seuils au pixel près sont
     * jugés par T-522 : la taille apparente dépend de la fenêtre du banc.
     */
    private static void jugerT475(Minecraft minecraft) {
        Integer pres = niveau(minecraft, ballonPres);
        Integer milieu = niveau(minecraft, ballonMilieu);
        Integer loin = niveau(minecraft, ballonLoin);
        boolean vert = pres != null && milieu != null && loin != null && pres >= 0 && pres <= milieu
                && milieu <= loin && pres < loin;
        RAPPORT.noter("T-475", vert, String.format(Locale.ROOT,
                "fenêtre de %d px de haut ; ballon à %.0f bloc(s) : niveau %s ; à %.0f : niveau %s ; à %.0f : niveau %s",
                minecraft.getWindow().getHeight(),
                minecraft.player.position().distanceTo(ballonPres.position()), pres,
                minecraft.player.position().distanceTo(ballonMilieu.position()), milieu,
                minecraft.player.position().distanceTo(ballonLoin.position()), loin));
    }

    /** T-906 (C-64, R-770) : caméra immobile, le culling et le LOD ne changent pas d'un relevé à l'autre. */
    private static void jugerT906() {
        int changements = 0;
        for (int i = 1; i < releves.size(); i++) {
            if (!releves.get(i).equals(releves.get(i - 1))) {
                changements++;
            }
        }
        boolean vert = releves.size() >= 60 && !releves.get(0).isEmpty() && changements == 0;
        RAPPORT.noter("T-906", vert, String.format(Locale.ROOT,
                "%d relevés de %d assemblies décidées : %d changement(s) de décision", releves.size(),
                releves.isEmpty() ? 0 : releves.get(0).size(), changements));
    }

    private static void jugerT479() {
        BackendSelection.Kind actif = AxionRenderPass.activeBackend();
        List<String> provoquees = journal.erreursAttendues();
        AxionRenderPass.FrameStats passe = AxionRenderPass.lastFrame();
        boolean vert = actif == BackendSelection.Kind.VANILLA
                && provoquees.size() == 1
                && passe.assemblies() == DERRIERE.size() + 1
                && passe.withAsset() == passe.assemblies();
        RAPPORT.noter("T-479", vert, String.format(Locale.ROOT,
                "backend %s après le rechargement aux shaders refusés ; %d E-4001 au journal%s ; %d assembly(s)"
                        + " dessinée(s), %d avec leur asset",
                actif, provoquees.size(), provoquees.isEmpty() ? "" : " (« " + provoquees.get(0) + " »)",
                passe.assemblies(), passe.withAsset()));
        // ADR-127 §8 attribue T-511 à R-761, que T-479 mesure ici même.
        RAPPORT.noter("T-511", vert, "R-761, mesuré par T-479 : " + (vert ? "bascule sur vanilla sans crash" : "ÉCHEC"));
    }

    /**
     * T-491, T-491b, T-491c : la même scène dans les deux backends (§19.2bis) — mêmes instances, mêmes
     * boîtes à l'écran, couverture en pixels sous {@code render.backend_silhouette_tolerance} — et un
     * serveur que la bascule n'a pas touché (R-1494).
     */
    private static void jugerT491(AxionRenderPass.FrameStats passesVanilla) {
        CapturesDuBanc.Masque natif = CapturesDuBanc.masque(fond, silhouetteNatif);
        CapturesDuBanc.Masque vanilla = CapturesDuBanc.masque(fond, silhouetteVanilla);
        boolean memesInstances = passesNatif.assemblies() == passesVanilla.assemblies()
                && passesNatif.withAsset() == passesVanilla.withAsset();
        int ecartBoites = vanilla.nombre() == 0 ? Integer.MAX_VALUE : ecart(natif, vanilla.boite());
        RAPPORT.noter("T-491", memesInstances && ecartBoites <= ECART_BACKENDS, String.format(Locale.ROOT,
                "natif : %d assembly(s), %d avec leur asset, boîte %s ; vanilla : %d, %d, boîte %s (écart %d px)",
                passesNatif.assemblies(), passesNatif.withAsset(), Arrays.toString(natif.boite()),
                passesVanilla.assemblies(), passesVanilla.withAsset(), Arrays.toString(vanilla.boite()), ecartBoites));

        double tolerance = toleranceSilhouette();
        int pixelsNatif = natif.nombre();
        int pixelsVanilla = vanilla.nombre();
        double ecartRelatif = Math.abs(pixelsNatif - pixelsVanilla)
                / (double) Math.max(1, Math.max(pixelsNatif, pixelsVanilla));
        RAPPORT.noter("T-491c", pixelsNatif > 0 && ecartRelatif <= tolerance, String.format(Locale.ROOT,
                "couverture du cube : %d px en natif, %d px en vanilla, écart %.2f %% pour %.2f %% tolérés",
                pixelsNatif, pixelsVanilla, ecartRelatif * 100.0, tolerance * 100.0));

        boolean memeEtat = etatAvant != null && etatAvant.length > 4 && Arrays.equals(etatAvant, etatApres);
        RAPPORT.noter("T-491b", memeEtat, String.format(Locale.ROOT,
                "%d assembly(s) du serveur, NBT de %d octets avant la bascule, %d après : %s",
                assembliesComparees, etatAvant == null ? 0 : etatAvant.length, etatApres == null ? 0 : etatApres.length,
                memeEtat ? "identiques octet pour octet" : "DIFFÉRENTS"));
    }

    private static void jugerT492() {
        boolean natifConstant = objetsNatif.size() >= 3 && objetsNatif.get(0) > 0
                && objetsNatif.stream().allMatch(objets -> objets.equals(objetsNatif.get(0)));
        boolean vanillaVide = objetsVanilla.size() == 2 && objetsVanilla.stream().allMatch(objets -> objets == 0);
        RAPPORT.noter("T-492", natifConstant && vanillaVide, String.format(Locale.ROOT,
                "objets GL du natif à chaque passage sur lui %s, en vanilla %s ; %d fermeture(s) du natif",
                objetsNatif, objetsVanilla, AxionRenderPass.nativeCloses() - fermetures));
    }

    /**
     * T-481 : une bascule de backend à chaud ne laisse rien sur le GPU. T-492 compte les objets GL
     * du natif ; T-481 y ajoute les textures d'AXION, que les deux backends tiennent : à chaque
     * passage sur un même backend, une même scène en tient le même nombre, non nul — une fuite le
     * ferait croître d'un aller-retour à l'autre. Le natif en tient plus que vanilla : ses cartes
     * de normales et ORM (ADR-127 §5).
     */
    private static void jugerT481() {
        boolean natifConstant = texturesNatif.size() >= 2 && texturesNatif.get(0) > 0
                && texturesNatif.stream().allMatch(n -> n.equals(texturesNatif.get(0)));
        boolean vanillaConstant = texturesVanilla.size() >= 2 && texturesVanilla.get(0) > 0
                && texturesVanilla.stream().allMatch(n -> n.equals(texturesVanilla.get(0)));
        RAPPORT.noter("T-481", natifConstant && vanillaConstant && objetsVanilla.stream().allMatch(n -> n == 0),
                String.format(Locale.ROOT,
                        "textures d'AXION vivantes à chaque passage sur le natif %s, sur vanilla %s ;"
                                + " objets GL du natif en vanilla %s",
                        texturesNatif, texturesVanilla, objetsVanilla));
    }

    /** {@return {@code render.backend_silhouette_tolerance}, telle que le client la lit (§19.2bis)} */
    private static double toleranceSilhouette() {
        return ConfigLoader.load(Scope.CLIENT, FMLPaths.CONFIGDIR.get(), System.getProperties())
                .getFloat("render.backend_silhouette_tolerance");
    }

    /**
     * T-512 : au premier démarrage, cache neuf, la base et l'émission compilées au démarrage, la
     * variante CUTOUT compilée à la demande ; rien de refusé, rien en attente.
     */
    private static void jugerT512(AxionRenderPass.ShaderStats stats) {
        statsDemarrage = stats;
        boolean vert = stats != null
                && stats.cutoutReady()
                && stats.onDemand() == 1
                && stats.compiled() == 3
                && stats.cacheHits() == 0
                && stats.pending() == 0
                && stats.refused() == 0;
        RAPPORT.noter("T-512", vert, String.format(Locale.ROOT,
                "premier démarrage, cache neuf : %s", stats));
    }

    /**
     * T-905 : relu au rechargement — base, émission et CUTOUT, sans compilation ; puis, le cache
     * corrompu, tout recompilé et les fichiers remplacés, relisibles de nouveau.
     */
    private static void jugerT905(Minecraft minecraft) {
        boolean relu = statsRelecture != null
                && statsRelecture.cacheHits() == 3
                && statsRelecture.compiled() == 0
                && statsRelecture.cutoutReady();
        boolean recompile = statsRecompile != null
                && fichiersCorrompus == 3
                && statsRecompile.cacheHits() == 0
                && statsRecompile.cacheMisses() == 3
                && statsRecompile.compiled() == 3;
        int relisibles = cacheRelisible(minecraft);
        RAPPORT.noter("T-905", relu && recompile && relisibles == fichiersCorrompus, String.format(Locale.ROOT,
                "au rechargement %s ; %d fichier(s) corrompu(s), puis %s, %d fichier(s) relisible(s) de nouveau",
                statsRelecture, fichiersCorrompus, statsRecompile, relisibles));
    }

    /** R-761 : la variante refusée à la demande a fait basculer sur vanilla, une E-4001 de plus. */
    private static void jugerVarianteRefusee() {
        int provoquees = journal.erreursAttendues().size() - provoqueesAvant;
        BackendSelection.Kind actif = AxionRenderPass.activeBackend();
        AxionRenderPass.FrameStats passe = AxionRenderPass.lastFrame();
        boolean vert = actif == BackendSelection.Kind.VANILLA && provoquees == 1
                && passe.withAsset() == passe.assemblies();
        RAPPORT.noter("T-511", vert, String.format(Locale.ROOT,
                "R-761, variante refusée à la demande : backend %s, %d E-4001, %d assembly(s) sur %d dessinée(s)",
                actif, provoquees, passe.withAsset(), passe.assemblies()));
    }

    /** {@return le dossier du cache binaire des shaders du client} */
    private static Path dossierDuCache(Minecraft minecraft) {
        return minecraft.gameDirectory.toPath().resolve("axion").resolve("cache").resolve("shaders");
    }

    /** {@return les fichiers du cache des shaders, chacun un octet changé au milieu} */
    private static int corrompreLeCache(Minecraft minecraft) {
        int corrompus = 0;
        try (var fichiers = Files.list(dossierDuCache(minecraft))) {
            for (Path fichier : fichiers.filter(f -> f.toString().endsWith(".bin")).toList()) {
                byte[] octets = Files.readAllBytes(fichier);
                octets[octets.length / 2] ^= (byte) 0x5A;
                Files.write(fichier, octets);
                corrompus++;
            }
        } catch (IOException echec) {
            throw new UncheckedIOException("cache des shaders inaccessible", echec);
        }
        return corrompus;
    }

    /** {@return les fichiers du cache des shaders qui se relisent : magic, schéma, CRC justes} */
    private static int cacheRelisible(Minecraft minecraft) {
        int relisibles = 0;
        try (var fichiers = Files.list(dossierDuCache(minecraft))) {
            for (Path fichier : fichiers.filter(f -> f.toString().endsWith(".bin")).toList()) {
                if (ShaderBinaryFile.decode(Files.readAllBytes(fichier)).isPresent()) {
                    relisibles++;
                }
            }
        } catch (IOException echec) {
            return -1;
        }
        return relisibles;
    }

    /**
     * Les cartes de matériau : en natif, l'écart-type de luminance du panneau à relief domine
     * nettement celui du plat ; en vanilla, ils se confondent (R-1513).
     */
    private static void jugerCartes(CapturesDuBanc.Capture capture, Vue vue) {
        double relief = ecartDansLePanneau(capture, vue, Z_RELIEF);
        double plat = ecartDansLePanneau(capture, vue, Z_PLAT);
        boolean vert = NATIF ? relief >= 3.0 * plat + 4.0 : Math.abs(relief - plat) <= 2.0;
        RAPPORT.noter("cartes de matériau", vert, String.format(Locale.ROOT,
                "%s : écart-type de luminance %.1f sur le panneau à relief, %.1f sur le plat (%s)",
                NATIF ? "natif" : "vanilla", relief, plat,
                NATIF ? "le relief doit dominer : au moins 3 × le plat + 4" : "R-1513, sans effet : écart de 2 au plus"));
    }

    /**
     * Active ou retire le pack de test, recharge les ressources, et attend que la scène soit
     * redessinée par un backend neuf, le modèle ressource compris (T-483).
     */
    private static void changerDePack(boolean actif, String quoi) {
        une(quoi, mc -> {
            PackRepository depot = mc.getResourcePackRepository();
            depot.reload();
            packDisponible = depot.getAvailableIds().contains(PACK_DE_TEST);
            List<String> choisis = new ArrayList<>(depot.getSelectedIds());
            choisis.remove(PACK_DE_TEST);
            if (actif) {
                choisis.add(PACK_DE_TEST);
            }
            depot.setSelected(choisis);
            demarragesAvantRechargement = AxionRenderPass.backendStarts();
            rechargement = mc.reloadResourcePacks();
        });
        jusqua("le rechargement fini — " + quoi, 300, mc -> rechargement.isDone() && mc.getOverlay() == null);
        jusqua("la scène redessinée — " + quoi, 120, mc -> AxionRenderPass.backendStarts() > demarragesAvantRechargement
                && AxionRenderPass.lastFrame().assemblies() == DERRIERE.size() + 3
                && AxionRenderPass.lastFrame().withAsset() == DERRIERE.size() + 3);
    }

    /**
     * {@return la couleur moyenne au centre de la face est d'un cube unité posé en {@code positionPanneau(dz)},
     * tournée vers la caméra}
     */
    private static double[] couleurDeFace(CapturesDuBanc.Capture capture, Vue vue, double dz) {
        float[] centre = projeter(vue, positionPanneau(dz).add(0.5, 0.5, 0.0), capture.largeur(), capture.hauteur());
        return CapturesDuBanc.couleurAutour(capture, Math.round(centre[0]), Math.round(centre[1]), 3);
    }

    /**
     * ADR-150 D3 : chaque client compile ses modèles, ses resource packs d'abord. Le cube du mod est rouge,
     * celui du pack bleu : avant le pack et après, le rouge l'emporte ; avec, le bleu. Le serveur, qui ne lit
     * que {@code data/}, garde le rouge : seul le registre du client peut donner le bleu.
     */
    /**
     * T-410 : l'assembly est tombée chez le serveur, elle a quitté sa hauteur d'apparition chez le client,
     * et elle y finit où le serveur la pose, au pas de la quantification près (1/1024 de bloc).
     */
    private static void jugerT410(Minecraft minecraft, net.minecraft.world.entity.Entity client) {
        double depart = positionChute().y;
        boolean tombee = depart - chuteServeur >= 2.0;
        boolean deplacee = client != null && chuteClientDepart - client.getY() >= 2.0;
        boolean rejointe = client != null && Math.abs(client.getY() - chuteServeur) <= 0.01;
        RAPPORT.noter("T-410", tombee && deplacee && rejointe, String.format(Locale.ROOT,
                "lâchée à y = %.3f ; chez le serveur %.3f après %d tick(s) du serveur ; chez le client %.3f à"
                        + " l'apparition, %s à la fin ; jeu en pause : %s — elle doit tomber d'au moins 2 blocs, et le"
                        + " client la suivre à 0,01 près",
                depart, chuteServeur, chuteTickFin - chuteTickDepart, chuteClientDepart,
                client == null ? "absente" : String.format(Locale.ROOT, "%.3f", client.getY()), minecraft.isPaused()));
    }

    /**
     * ADR-150 D7 : ce que le rendu dessine de l'assembly lâchée vient de l'interpolation de 21.7. La pose
     * rendue y descend de deux blocs au moins ; d'une frame à l'autre, elle ne va jamais plus vite que la
     * chute ne le permet — {@code √(2 g h)}, à 50 % près pour la mesure des frames : un saut, une
     * téléportation le dépasseraient ; elle finit où le serveur pose l'assembly.
     */
    private static void jugerD7(Minecraft minecraft, net.minecraft.world.entity.Entity client) {
        double gravite = Math.abs(RuntimeAccess.get().outcome().config().getFloat("physics.gravity"));
        double vitesseMax = Math.sqrt(2 * gravite * Math.max(0, positionChute().y - chuteServeur));
        int premiere = -1;
        int sansInterpolation = 0;
        double pireVitesse = 0;
        for (int index = 0; index < chuteRendue.size(); index++) {
            double[] releve = chuteRendue.get(index);
            if (releve[2] >= 0 && premiere < 0) {
                premiere = index;
            } else if (releve[2] < 0 && premiere >= 0) {
                sansInterpolation++;
            }
            if (index > 0 && premiere >= 0 && premiere < index) {
                double[] avant = chuteRendue.get(index - 1);
                double secondes = (releve[0] - avant[0]) / 1e9;
                if (secondes > 0) {
                    pireVitesse = Math.max(pireVitesse, Math.abs(releve[1] - avant[1]) / secondes);
                }
            }
        }
        boolean vivante = premiere >= 0 && sansInterpolation == 0;
        double descente = vivante ? chuteRendue.get(premiere)[1] - chuteRendue.get(chuteRendue.size() - 1)[1] : 0;
        AxionEntity.RenderPose fin = client instanceof AxionEntity rendue
                ? rendue.renderPose(minecraft.getPartialTick()) : null;
        boolean rejointe = fin != null && fin.mode() != null && Math.abs(fin.y() - chuteServeur) <= 0.01;
        boolean sansSaut = pireVitesse <= 1.5 * vitesseMax;
        RAPPORT.noter("ADR-150 D7", vivante && descente >= 2.0 && sansSaut && rejointe, String.format(Locale.ROOT,
                "%d frame(s) relevée(s), interpolées dès la %d-e, %d sans interpolation ensuite ; descente rendue"
                        + " %.3f bloc ; vitesse rendue au plus %.2f m/s pour %.2f m/s de chute au plus ; à la fin"
                        + " %s pour %.3f chez le serveur — elle doit être interpolée, descendre de 2 blocs, ne"
                        + " jamais dépasser 1,5 fois la vitesse de chute, et finir à 0,01 près",
                chuteRendue.size(), premiere + 1, sansInterpolation, descente, pireVitesse, vitesseMax,
                fin == null ? "absente" : String.format(Locale.ROOT, "%.3f (%s)", fin.y(), fin.mode()),
                chuteServeur));
    }

    private static void jugerModeleDuPack(double[] apres) {
        boolean rougeAvant = duPackAvant[0] >= duPackAvant[2] + MARGE_D3;
        boolean bleuAvecPack = duPackAvecPack[2] >= duPackAvecPack[0] + MARGE_D3;
        boolean rougeApres = apres[0] >= apres[2] + MARGE_D3;
        RAPPORT.noter("ADR-150 D3", packDisponible && rougeAvant && bleuAvecPack && rougeApres, String.format(Locale.ROOT,
                "%s : %s ; face du modèle du pack avant %s, avec le pack %s, après %s — rouge, puis bleu"
                        + " (le modèle du resource pack l'emporte chez le client), puis rouge, à %d près",
                NATIF ? "natif" : "vanilla",
                packDisponible ? "pack de test disponible" : "PACK DE TEST INTROUVABLE (" + PACK_DE_TEST + ")",
                rgb(duPackAvant), rgb(duPackAvecPack), rgb(apres), MARGE_D3));
    }

    /**
     * T-713f : écrit le datapack du monde qui livre la definition et le modèle absents chez le client — le
     * cube du mod sous un autre nom —, puis l'active ou le retire, comme {@code /datapack enable|disable}.
     */
    private static void donneesDuServeur(MinecraftServer serveur, boolean actif) {
        Path pack = serveur.getWorldPath(LevelResource.DATAPACK_DIR).resolve(PACK_ABSENT);
        if (actif) {
            try (var cube = BancDeRendu.class.getResourceAsStream(CUBE_DU_MOD)) {
                if (cube == null) {
                    throw new IllegalStateException("cube du mod introuvable : " + CUBE_DU_MOD);
                }
                Path donnees = pack.resolve("data/axion/axion");
                Files.createDirectories(donnees.resolve("definitions/test"));
                Files.createDirectories(donnees.resolve("models/test"));
                Files.writeString(pack.resolve("pack.mcmeta"),
                        "{\"pack\": {\"pack_format\": 15, \"description\": \"AXION, banc de rendu : T-713f\"}}");
                Files.writeString(donnees.resolve("definitions/test/absent_chez_le_client.json"),
                        "{\"schema\": 1, \"asset\": \"" + ASSET_ABSENT + "\", \"kind\": \"rigid_object\"}");
                Files.write(donnees.resolve("models/test/absent_chez_le_client.gltf"), cube.readAllBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        PackRepository depot = serveur.getPackRepository();
        depot.reload();
        List<String> choisis = new ArrayList<>(depot.getSelectedIds());
        choisis.remove("file/" + PACK_ABSENT);
        if (actif) {
            choisis.add("file/" + PACK_ABSENT);
        }
        serveur.reloadResources(choisis);
    }

    /**
     * {@return vrai quand le serveur tient la definition du datapack et son modèle compilé} Le modèle se
     * nomme par l'asset de la definition chargée, comme le rendu le cherche.
     */
    private static boolean modeleAbsentPret() {
        AxionRuntime runtime = RuntimeAccess.get();
        return runtime.definitions().get(ABSENT_CHEZ_LE_CLIENT)
                .map(definition -> definition.asset().equals(runtime.assets().resolve(definition.asset())))
                .orElse(false);
    }

    /** T-713f : ce que le serveur tient de l'assembly, relevé sur son thread. */
    private static void releverModeleAbsent(MinecraftServer serveur) {
        modeleAbsentLie = !modeleAbsent.isInert();
        modeleAbsentSimule = modeleAbsent.netState() != null;
        long attendue = RuntimeAccess.get().definitions().get(ABSENT_CHEZ_LE_CLIENT).orElseThrow().hash();
        modeleAbsentPersiste = modeleAbsent.saveWithoutId(new CompoundTag()).getLong("axion:def") == attendue;
    }

    /**
     * T-713f (cas 4 de 22.5.3, R-1640) : le client dessine l'assembly sans son modèle — la boîte de repli, une
     * assembly de plus sans une de plus avec son asset — et reste connecté ; le serveur la tient liée, en
     * rapporte l'état simulé, et la persiste avec sa definition.
     */
    private static void jugerT713f(Minecraft mc) {
        AxionRenderPass.FrameStats frame = AxionRenderPass.lastFrame();
        boolean repli = frame.assemblies() == avantModeleAbsent.assemblies() + 1
                && frame.withAsset() == avantModeleAbsent.withAsset();
        boolean connecte = mc.getConnection() != null && mc.player != null;
        AssetRegistry chezLeClient = RuntimeAccess.get().clientAssets();
        String asset = RuntimeAccess.get().definitions().get(ABSENT_CHEZ_LE_CLIENT).orElseThrow().asset();
        boolean absent = chezLeClient != null && !asset.equals(chezLeClient.resolve(asset));
        RAPPORT.noter("T-713f", repli && connecte && absent && modeleAbsentLie && modeleAbsentSimule
                && modeleAbsentPersiste, String.format(Locale.ROOT,
                "%s : modèle %s chez le client ; frame de %d assemblies dont %d avec leur asset (avant : %d et %d) ;"
                        + " client %s ; serveur : assembly %s, état simulé %s, NBT %s",
                NATIF ? "natif" : "vanilla", absent ? "absent" : "PRÉSENT", frame.assemblies(), frame.withAsset(),
                avantModeleAbsent.assemblies(), avantModeleAbsent.withAsset(), connecte ? "connecté" : "DÉCONNECTÉ",
                modeleAbsentLie ? "liée" : "INERTE", modeleAbsentSimule ? "rapporté" : "ABSENT",
                modeleAbsentPersiste ? "avec sa definition" : "SANS SA DEFINITION"));
    }

    /** {@return la couleur moyenne au centre de la face est du modèle ressource, tournée vers la caméra} */
    private static double[] couleurDeRessource(CapturesDuBanc.Capture capture, Vue vue) {
        float[] centre = projeter(vue, positionPanneau(Z_RELIEF).add(0.5, 0.5, 0.0), capture.largeur(), capture.hauteur());
        return CapturesDuBanc.couleurAutour(capture, Math.round(centre[0]), Math.round(centre[1]), 3);
    }

    /**
     * T-483 (§19.13, R-752) : un changement de resource pack reconstruit les ressources d'AXION.
     * Avec le pack de test, la texture de bois devient magenta : le bleu de la face monte ; le pack
     * retiré, la face revient au bois d'avant.
     */
    private static void jugerT483(double[] boisApres) {
        boolean change = boisAvecPack[2] >= boisAvant[2] + MONTEE_T483;
        boolean revenu = true;
        for (int canal = 0; canal < 3; canal++) {
            revenu &= Math.abs(boisApres[canal] - boisAvant[canal]) <= RETOUR_T483;
        }
        RAPPORT.noter("T-483", packDisponible && change && revenu, String.format(Locale.ROOT,
                "%s : %s ; face du modèle ressource avant le pack %s, avec %s, après %s — le bleu doit monter"
                        + " d'au moins %d avec le pack, et chaque canal revenir à %d près",
                NATIF ? "natif" : "vanilla",
                packDisponible ? "pack de test disponible" : "PACK DE TEST INTROUVABLE (" + PACK_DE_TEST + ")",
                rgb(boisAvant), rgb(boisAvecPack), rgb(boisApres), MONTEE_T483, RETOUR_T483));
    }

    private static String rgb(double[] couleur) {
        return String.format(Locale.ROOT, "(%.1f, %.1f, %.1f)", couleur[0], couleur[1], couleur[2]);
    }

    /**
     * T-476 (§19.13, R-1580) : la transparence ordonnée. Rouge devant, bleu derrière, sur l'axe de la
     * caméra : triés du plus loin au plus près, le bleu se dessine d'abord, le rouge s'y mêle
     * par-dessus, et au centre du panneau du fond le rouge l'emporte sur le bleu. Dans l'ordre
     * inverse, le bleu l'emporterait : c'est ce que le verdict distingue, dans chaque backend.
     */
    private static void jugerT476(CapturesDuBanc.Capture capture, Vue vue) {
        float[] centre = projeter(vue, positionCube().add(X_BLEU, 0.5, 0.0), capture.largeur(), capture.hauteur());
        int x = Math.round(centre[0]);
        int y = Math.round(centre[1]);
        double[] couleur = CapturesDuBanc.couleurAutour(capture, x, y, 3);
        RAPPORT.noter("T-476", couleur[0] >= couleur[2] + MARGE_T476, String.format(Locale.ROOT,
                "%s : au centre du panneau du fond (%d, %d), rouge %.1f, vert %.1f, bleu %.1f — le rouge du"
                        + " panneau de devant doit l'emporter d'au moins %d",
                NATIF ? "natif" : "vanilla", x, y, couleur[0], couleur[1], couleur[2], MARGE_T476));
    }

    /**
     * {@return l'écart-type de luminance au cœur de la face avant d'un panneau, telle qu'elle se
     * projette à l'écran — la moitié centrale de sa boîte, loin des bords}
     */
    private static double ecartDansLePanneau(CapturesDuBanc.Capture capture, Vue vue, double dz) {
        Vec3 pied = positionPanneau(dz);
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        // La face avant, tournée vers l'est : x = +0,05, y de 0 à 1, z de -0,5 à +0,5 autour du pied.
        for (double y : new double[] {0.0, 1.0}) {
            for (double z : new double[] {-0.5, 0.5}) {
                float[] ecran = projeter(vue, pied.add(0.05, y, z), capture.largeur(), capture.hauteur());
                minX = Math.min(minX, ecran[0]);
                minY = Math.min(minY, ecran[1]);
                maxX = Math.max(maxX, ecran[0]);
                maxY = Math.max(maxY, ecran[1]);
            }
        }
        float marqueX = (maxX - minX) / 4f;
        float marqueY = (maxY - minY) / 4f;
        return CapturesDuBanc.ecartTypeDeLuminance(capture,
                Math.round(minX + marqueX), Math.round(minY + marqueY),
                Math.round(maxX - marqueX), Math.round(maxY - marqueY));
    }

    /**
     * T-491d (R-1493) : chaque démarrage de backend depuis l'installation du journal y a laissé une
     * entrée, la dernière décrit le backend actif, et /axion status signale chacune des capacités
     * que ce backend n'a pas — indisponible ou pas encore livrée —, avec son repli.
     */
    private static void jugerT491d() {
        List<String> entrees = journal.entreesDuRendu();
        int demarres = AxionRenderPass.backendStarts() - demarragesALInstallation;
        AxionRuntime runtime = RuntimeAccess.get();
        RenderCapabilities matrice = runtime == null ? null : runtime.renderCapabilities();
        if (matrice == null) {
            RAPPORT.noter("T-491d, journal et statut", false, "aucune matrice de capacités au runtime");
            return;
        }
        List<String> description = matrice.describe();
        boolean actif = matrice.selection().kind() == AxionRenderPass.activeBackend();
        boolean journalComplet = demarres >= 1 && entrees.size() == demarres;
        boolean derniere = !entrees.isEmpty()
                && entrees.get(entrees.size() - 1).equals(JournalDuBanc.ENTREE_RENDU + String.join("\n  ", description));
        String statut = String.join("\n", sortieDuStatut);
        int absentes = 0;
        int signalees = 0;
        for (RenderCapabilities.Entry ligne : matrice.entries()) {
            if (ligne.availability() == RenderCapabilities.Availability.AVAILABLE) {
                continue;
            }
            signalees++;
            if (!statut.contains(ligne.capability().label() + " — " + ligne.detail())) {
                absentes++;
            }
        }
        boolean rapporte = statut.contains("  rendu : " + description.get(0))
                && description.subList(1, description.size()).stream().allMatch(l -> statut.contains("    " + l));
        RAPPORT.noter("T-491d, journal et statut", actif && journalComplet && derniere && rapporte && signalees > 0 && absentes == 0,
                String.format(Locale.ROOT,
                        "overlay : C-73 (ADR-128) ; backend %s ; journal : %d entrée(s) pour %d démarrage(s), la dernière %s ; /axion status :"
                                + " %d ligne(s), matrice %s, %d capacité(s) sans repli dit sur %d non disponible(s)",
                        matrice.selection().kind(), entrees.size(), demarres,
                        derniere ? "conforme à la matrice active" : "DIFFÉRENTE de la matrice active",
                        sortieDuStatut.size(), rapporte ? "reproduite" : "INCOMPLÈTE", absentes, signalees));
    }

    /**
     * T-904 (R-762) : une variante remise en service à la demande ne fait pas d'à-coup mesurable.
     * Deux critères, pour la remise en service compilée comme pour celle relue du cache :
     * <ul>
     *   <li>ce qu'AXION y ajoute lui-même — le temps que les variantes prennent au render thread —
     *       ne dépasse, à aucune frame, {@code budgets.render_prep_ns}, tout le budget de préparation
     *       du rendu d'une frame : mesuré directement, il ne dépend pas du bruit de la scène ;
     *   <li>aucune frame ne dure plus que la plus lente des frames témoins augmentée de ce budget.
     * </ul>
     * Le second seul ne suffit pas : au premier relevé, une relecture du cache prenait 3,6 ms au
     * render thread et passait sous des frames témoins bruitées (banc du 2026-10-12). La remise en
     * service compilée sans parallèle est relevée et jugée par les mêmes critères, sans entrer dans
     * le verdict : c'est le chemin d'un pilote sans compilation parallèle (ADR-127 §4).
     *
     * <p>Jugé sur un GPU matériel seulement (décision de Killian, 2026-10-12, ADR-127 §4) : sous un
     * rendu logiciel, la compilation se fait sur le CPU dans l'appel même, et une seule liaison y
     * dépasse le budget (llvmpipe en CI : 2,4 à 3,4 ms) — un chiffre de performance ne vaut que sur
     * du matériel (R-2230). Là, T-904 est mesuré et rapporté, non jugé.
     */
    private static void jugerT904() {
        long budget = ConfigLoader.load(Scope.COMMON, FMLPaths.CONFIGDIR.get(), System.getProperties())
                .getInt("budgets.render_prep_ns");
        long temoinMax = maximum(temoinT904.intervalles);
        long plafond = temoinMax + budget;
        boolean compileeSansACoup = sansACoup(compileeT904, budget, plafond);
        boolean relueSansACoup = sansACoup(relueT904, budget, plafond);
        String renderer = GL11.glGetString(GL11.GL_RENDERER);
        String detail = String.format(Locale.ROOT,
                "%s ; frames témoins : %d, médiane %.2f ms, la plus lente %.2f ms ; budget des variantes %.2f ms par"
                        + " frame (budgets.render_prep_ns), plafond des frames %.2f ms ; %s ; %s ; %s (hors verdict)",
                renderer, temoinT904.intervalles.size(), millis(mediane(temoinT904.intervalles)), millis(temoinMax),
                millis(budget), millis(plafond), decrire("compilée", compileeT904, budget, plafond),
                decrire("relue du cache", relueT904, budget, plafond),
                decrire("compilée sans parallèle", serielleT904, budget, plafond));
        if (logiciel(renderer)) {
            RAPPORT.nonJuge("T-904", "rendu logiciel, mesuré sans être jugé (ADR-127 §4) — " + detail);
        } else {
            RAPPORT.noter("T-904", compileeSansACoup && relueSansACoup, detail);
        }
    }

    /** {@return vrai si le renderer OpenGL est un rendu logiciel : llvmpipe, softpipe, SwiftShader} */
    private static boolean logiciel(String renderer) {
        String nom = renderer == null ? "" : renderer.toLowerCase(Locale.ROOT);
        return nom.contains("llvmpipe") || nom.contains("softpipe") || nom.contains("swiftshader");
    }

    /** {@return vrai si une série de T-904 tient ses deux critères} */
    private static boolean sansACoup(SerieT904 serie, long budget, long plafond) {
        return !serie.intervalles.isEmpty() && maximum(serie.variantes) <= budget && maximum(serie.intervalles) <= plafond;
    }

    /** {@return une série de T-904, dite en millisecondes, avec son jugement} */
    private static String decrire(String quoi, SerieT904 serie, long budget, long plafond) {
        return String.format(Locale.ROOT, "%s : %d frame(s), variantes au render thread %.3f ms au plus, frame la plus"
                        + " lente %.2f ms — %s", quoi, serie.intervalles.size(), millis(maximum(serie.variantes)),
                millis(maximum(serie.intervalles)), sansACoup(serie, budget, plafond) ? "sans à-coup" : "À-COUP");
    }

    private static long maximum(List<Long> valeurs) {
        long maximum = 0;
        for (long valeur : valeurs) {
            maximum = Math.max(maximum, valeur);
        }
        return maximum;
    }

    private static long mediane(List<Long> valeurs) {
        if (valeurs.isEmpty()) {
            return 0;
        }
        List<Long> triees = new ArrayList<>(valeurs);
        triees.sort(null);
        return triees.get(triees.size() / 2);
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static void jugerT472() {
        boolean actif = GlStateCheck.ENABLED && GlStateCheck.checks() > 0;
        RAPPORT.noter("T-472", actif && GlStateCheck.differences() == 0 && GlStateCheck.errors() == 0,
                String.format(Locale.ROOT,
                        "contrôle %s, %d passe(s) contrôlée(s), %d état(s) non restauré(s), %d erreur(s) GL"
                                + " dans les passes",
                        GlStateCheck.ENABLED ? "actif" : "INACTIF (axion.debug.gl absent)",
                        GlStateCheck.checks(), GlStateCheck.differences(), GlStateCheck.errors()));
    }

    private static void jugerT473() {
        List<String> horsDuFil = journal.horsDuFil();
        RAPPORT.noter("T-473", horsDuFil.isEmpty(), horsDuFil.isEmpty()
                ? "aucun appel GL signalé hors du render thread"
                : String.join(" | ", horsDuFil.subList(0, Math.min(5, horsDuFil.size()))));
        List<String> erreurs = journal.erreursAxion();
        RAPPORT.noter("erreurs AXION", erreurs.isEmpty(), erreurs.isEmpty()
                ? "aucune erreur d'AXION au journal"
                : erreurs.size() + " erreur(s), dont : " + erreurs.get(0));
    }

    // --- Projection ------------------------------------------------------------------------------

    /** {@return la boîte, en pixels inclus, que couvre le cube posé en {@link #positionCube()}} */
    private static int[] boiteAttendue(Vue vue, float[] rotation, int largeur, int hauteur) {
        Quaternionf q = new Quaternionf(rotation[0], rotation[1], rotation[2], rotation[3]);
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        // Le maillage du cube de test : [-0,5 ; 0,5] × [0 ; 1] × [-0,5 ; 0,5], origine au bas.
        for (float dx : new float[] {-0.5f, 0.5f}) {
            for (float dy : new float[] {0f, 1f}) {
                for (float dz : new float[] {-0.5f, 0.5f}) {
                    Vector3f coin = q.transform(new Vector3f(dx, dy, dz));
                    float[] ecran = projeter(vue, positionCube().add(coin.x, coin.y, coin.z), largeur, hauteur);
                    minX = Math.min(minX, ecran[0]);
                    minY = Math.min(minY, ecran[1]);
                    maxX = Math.max(maxX, ecran[0]);
                    maxY = Math.max(maxY, ecran[1]);
                }
            }
        }
        // Un pixel est couvert si son centre l'est.
        return new int[] {
            (int) Math.ceil(minX - 0.5f), (int) Math.ceil(minY - 0.5f),
            (int) Math.floor(maxX - 0.5f), (int) Math.floor(maxY - 0.5f)
        };
    }

    /**
     * {@return la boîte, en pixels inclus, que couvrent deux cubes de {@code test/deux_corps} :
     * {@code [-0,5 ; 0,5] × [0 ; 1] × [-0,5 ; 0,5]} autour de l'origine de chacun, à sa pose}
     */
    private static int[] boiteDesCubes(
            Vue vue, CapturesDuBanc.Capture capture, Vec3 a, Quaternionf rotationA, Vec3 b, Quaternionf rotationB) {
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        Vec3[] origines = {a, b};
        Quaternionf[] rotations = {rotationA, rotationB};
        for (int cube = 0; cube < 2; cube++) {
            for (float dx : new float[] {-0.5f, 0.5f}) {
                for (float dy : new float[] {0f, 1f}) {
                    for (float dz : new float[] {-0.5f, 0.5f}) {
                        Vector3f coin = new Quaternionf(rotations[cube]).transform(new Vector3f(dx, dy, dz));
                        float[] ecran = projeter(vue, origines[cube].add(coin.x, coin.y, coin.z), capture.largeur(),
                                capture.hauteur());
                        minX = Math.min(minX, ecran[0]);
                        minY = Math.min(minY, ecran[1]);
                        maxX = Math.max(maxX, ecran[0]);
                        maxY = Math.max(maxY, ecran[1]);
                    }
                }
            }
        }
        return new int[] {
            (int) Math.ceil(minX - 0.5f), (int) Math.ceil(minY - 0.5f),
            (int) Math.floor(maxX - 0.5f), (int) Math.floor(maxY - 0.5f)
        };
    }

    /** {@return la position à l'écran d'un point du monde, en pixels depuis le coin haut gauche} */
    private static float[] projeter(Vue vue, Vec3 point, int largeur, int hauteur) {
        Vector4f v = new Vector4f(
                (float) (point.x - vue.camera().x),
                (float) (point.y - vue.camera().y),
                (float) (point.z - vue.camera().z),
                1f);
        vue.pose().transform(v);
        vue.projection().transform(v);
        return new float[] {(v.x / v.w * 0.5f + 0.5f) * largeur, (0.5f - v.y / v.w * 0.5f) * hauteur};
    }

    /**
     * {@return la luminance au centre du dessus, puis de chaque face latérale tournée vers la
     * caméra, du cube tourné}
     */
    private static double[] luminancesDesFaces(CapturesDuBanc.Capture capture, Vue vue) {
        Quaternionf q = new Quaternionf(HUITIEME_DE_TOUR[0], HUITIEME_DE_TOUR[1], HUITIEME_DE_TOUR[2], HUITIEME_DE_TOUR[3]);
        Vector3f[] normales = {
            new Vector3f(0, 1, 0), new Vector3f(1, 0, 0), new Vector3f(-1, 0, 0), new Vector3f(0, 0, 1), new Vector3f(0, 0, -1)
        };
        List<Double> luminances = new ArrayList<>();
        for (Vector3f normale : normales) {
            Vector3f n = q.transform(new Vector3f(normale));
            Vector3f centre = q.transform(new Vector3f(normale).mul(0.5f)).add(0f, 0.5f, 0f);
            Vec3 point = positionCube().add(centre.x, centre.y, centre.z);
            Vec3 versCamera = vue.camera().subtract(point);
            if (n.x * versCamera.x + n.y * versCamera.y + n.z * versCamera.z <= 0) {
                continue;
            }
            float[] ecran = projeter(vue, point, capture.largeur(), capture.hauteur());
            luminances.add(CapturesDuBanc.luminanceAutour(capture, (int) ecran[0], (int) ecran[1], 2));
        }
        return luminances.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /** {@return le plus grand écart, en pixels, entre la boîte d'un masque et la boîte attendue} */
    private static int ecart(CapturesDuBanc.Masque masque, int[] attendue) {
        if (masque.nombre() == 0) {
            return Integer.MAX_VALUE;
        }
        int[] mesuree = masque.boite();
        int pire = 0;
        for (int i = 0; i < 4; i++) {
            pire = Math.max(pire, Math.abs(mesuree[i] - attendue[i]));
        }
        return pire;
    }

    private static int aire(int[] boite) {
        return Math.max(0, boite[2] - boite[0] + 1) * Math.max(0, boite[3] - boite[1] + 1);
    }

    private static double[] arrondir(double[] valeurs) {
        double[] arrondies = new double[valeurs.length];
        for (int i = 0; i < valeurs.length; i++) {
            arrondies[i] = Math.round(valeurs[i] * 10) / 10.0;
        }
        return arrondies;
    }

    // --- Outils ----------------------------------------------------------------------------------

    private static CapturesDuBanc.Capture capturer(Minecraft minecraft, String nom) {
        return CapturesDuBanc.capturer(minecraft, dossier(minecraft).resolve(nom));
    }

    private static Path dossier(Minecraft minecraft) {
        return minecraft.gameDirectory.toPath().resolve("axion-rendertest");
    }

    /** {@return les erreurs GL en attente, qu'on vide} */
    private static int viderErreursGl() {
        int erreurs = 0;
        while (erreurs < 64 && GL11.glGetError() != GL11.GL_NO_ERROR) {
            erreurs++;
        }
        return erreurs;
    }

    /** Exécute des commandes avec les droits du serveur, sans écho. Sur le thread du serveur. */
    private static void commandes(MinecraftServer serveur, String... lignes) {
        CommandSourceStack source = serveur.createCommandSourceStack().withSuppressedOutput();
        for (String ligne : lignes) {
            serveur.getCommands().performPrefixedCommand(source, ligne);
        }
    }

    /** {@return ce qu'une commande, exécutée avec les droits du serveur, répond ligne à ligne} Sur le thread du serveur. */
    private static List<String> sortieDe(MinecraftServer serveur, String ligne) {
        List<String> sortie = new ArrayList<>();
        CommandSource recueil = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                sortie.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        serveur.getCommands().performPrefixedCommand(serveur.createCommandSourceStack().withSource(recueil), ligne);
        return List.copyOf(sortie);
    }

    private static void terminer(Minecraft minecraft, String echec) {
        fini = true;
        compter = false;
        if (echec != null) {
            RAPPORT.noter("banc", false, echec);
        }
        RAPPORT.ecrire(
                dossier(minecraft).resolve("rapport.json"),
                (System.currentTimeMillis() - debut) / 1000.0,
                journal == null ? List.of() : journal.erreursAxion());
        LOGGER.info("AXION banc de rendu : rapport {}, arrêt du client", RAPPORT.vert() ? "vert" : "ROUGE");
        minecraft.stop();
    }

    // --- Construction du scénario ----------------------------------------------------------------

    private static void etape(String nom, Pas pas) {
        SCENARIO.add(new Etape(nom, pas));
    }

    private static void une(String nom, Consumer<Minecraft> action) {
        etape(nom, mc -> {
            action.accept(mc);
            return true;
        });
    }

    /** Laisse passer {@code ticksAttendus} ticks du client. */
    private static void pendant(String quoi, int ticksAttendus) {
        pendant(quoi, ticksAttendus, mc -> {});
    }

    /** Laisse passer {@code ticksAttendus} ticks du client, en jouant {@code aChaqueFrame} à chacune de leurs frames. */
    private static void pendant(String quoi, int ticksAttendus, Consumer<Minecraft> aChaqueFrame) {
        long[] depuis = {-1};
        etape("attendre " + quoi, mc -> {
            if (depuis[0] < 0) {
                depuis[0] = ticks;
            }
            aChaqueFrame.accept(mc);
            return ticks - depuis[0] >= ticksAttendus;
        });
    }

    private static void frames(int nombre) {
        etape(nombre + " frame(s)", mc -> framesDansLEtape >= nombre);
    }

    /** Attend que l'image ne change plus : voir {@link #RELEVES_EGAUX}. */
    private static void stabiliser(String quoi) {
        CapturesDuBanc.Capture[] precedent = new CapturesDuBanc.Capture[1];
        int[] egaux = new int[1];
        int[] differents = new int[1];
        long[] dernierReleve = {Long.MIN_VALUE};
        etape("stabiliser " + quoi, mc -> {
            if (dernierReleve[0] != Long.MIN_VALUE && ticks - dernierReleve[0] < TICKS_ENTRE_RELEVES) {
                return false;
            }
            dernierReleve[0] = ticks;
            CapturesDuBanc.Capture releve = CapturesDuBanc.capturer(mc, null);
            int ecarts = precedent[0] == null ? -1 : CapturesDuBanc.masque(precedent[0], releve).nombre();
            precedent[0] = releve;
            if (ecarts == 0) {
                egaux[0]++;
            } else {
                egaux[0] = 0;
                differents[0] = ecarts;
            }
            if (egaux[0] >= RELEVES_EGAUX) {
                return true;
            }
            if (System.nanoTime() - debutEtape > STABILITE_MAX_S * 1_000_000_000L) {
                throw new IllegalStateException("l'image change encore au bout de " + STABILITE_MAX_S
                        + " s (" + differents[0] + " pixel(s) au dernier écart)");
            }
            return false;
        });
    }

    /**
     * T-904 : oublie la variante CUTOUT et relève les frames jusqu'à sa remise en service, puis
     * {@value #FRAMES_APRES_T904} de plus.
     */
    private static void remettreEnService(String quoi, boolean duCache, boolean serielle, Consumer<SerieT904> garder) {
        une("oublier CUTOUT — " + quoi, mc -> {
            AxionRenderPass.forceSerialShaderCompile(serielle);
            if (!AxionRenderPass.forgetCutoutShaderVariant(duCache)) {
                throw new IllegalStateException("la variante CUTOUT n'est pas prête : rien à remettre en service");
            }
            serieT904 = new SerieT904();
            garder.accept(serieT904);
        });
        jusqua("CUTOUT de nouveau prête — " + quoi, 60, mc -> {
            AxionRenderPass.ShaderStats stats = AxionRenderPass.nativeShaderStats();
            return stats != null && stats.cutoutReady();
        });
        frames(FRAMES_APRES_T904);
        une("fermer la série — " + quoi, mc -> {
            serieT904 = null;
            AxionRenderPass.forceSerialShaderCompile(false);
        });
    }

    /** Recharge les ressources du client, et attend la fin du rechargement. */
    private static void recharger(String quoi) {
        une("recharger " + quoi, mc -> {
            demarragesAvantRechargement = AxionRenderPass.backendStarts();
            rechargement = mc.reloadResourcePacks();
        });
        jusqua("le rechargement fini — " + quoi, 300, mc -> rechargement.isDone() && mc.getOverlay() == null);
    }

    /** Bascule sur vanilla par des shaders refusés au rechargement (T-479), puis relève le natif. */
    private static void basculerSurVanilla(String quoi) {
        une("refuser les shaders — " + quoi, mc -> {
            journal.attendre(E_4001);
            AxionRenderPass.forceShaderFailure(true);
        });
        recharger(quoi);
        jusqua("la scène redessinée en vanilla — " + quoi, 120, mc -> redessinee(BackendSelection.Kind.VANILLA));
        une("relever la bascule — " + quoi, mc -> {
            journal.attendre(null);
            AxionRenderPass.forceShaderFailure(false);
            objetsVanilla.add(AxionRenderPass.nativeGlObjects());
            texturesVanilla.add(AxionRenderPass.liveTextures());
        });
    }

    /** Revient au natif par un rechargement, puis relève ses objets GL (T-492). */
    private static void revenirAuNatif(String quoi) {
        recharger(quoi);
        jusqua("la scène redessinée en natif — " + quoi, 120, mc -> redessinee(BackendSelection.Kind.NATIVE));
        une("relever le natif — " + quoi, mc -> {
            objetsNatif.add(AxionRenderPass.nativeGlObjects());
            texturesNatif.add(AxionRenderPass.liveTextures());
        });
    }

    /** {@return vrai quand un backend neuf, du type attendu, a redessiné toute la scène, assets compris} */
    private static boolean redessinee(BackendSelection.Kind attendu) {
        AxionRenderPass.FrameStats frame = AxionRenderPass.lastFrame();
        return AxionRenderPass.backendStarts() > demarragesAvantRechargement
                && AxionRenderPass.activeBackend() == attendu
                && frame.assemblies() == DERRIERE.size() + 1
                && frame.withAsset() == frame.assemblies();
    }

    /**
     * {@return les assemblies du monde, dans l'ordre de leurs identifiants, telles qu'elles seraient
     * persistées : leur NBT, écrit octet par octet (T-491b)} Sur le thread du serveur.
     */
    private static byte[] etatDuServeur(MinecraftServer serveur) {
        List<AxionEntity> assemblies = new ArrayList<>();
        for (Entity entite : serveur.overworld().getAllEntities()) {
            if (entite instanceof AxionEntity assembly) {
                assemblies.add(assembly);
            }
        }
        assemblies.sort(Comparator.comparingInt(Entity::getId));
        assembliesComparees = assemblies.size();
        ByteArrayOutputStream octets = new ByteArrayOutputStream();
        try (DataOutputStream sortie = new DataOutputStream(octets)) {
            sortie.writeInt(assemblies.size());
            for (AxionEntity assembly : assemblies) {
                sortie.writeInt(assembly.getId());
                NbtIo.write(assembly.saveWithoutId(new CompoundTag()), sortie);
            }
        } catch (IOException echec) {
            throw new UncheckedIOException("état du serveur illisible", echec);
        }
        return octets.toByteArray();
    }

    private static void jusqua(String nom, int secondes, Predicate<Minecraft> condition) {
        etape(nom, mc -> {
            if (condition.test(mc)) {
                return true;
            }
            if (System.nanoTime() - debutEtape > secondes * 1_000_000_000L) {
                throw new IllegalStateException("toujours pas au bout de " + secondes + " s");
            }
            return false;
        });
    }

    /** Confie une action au thread du serveur intégré, et attend qu'elle soit faite. */
    private static void serveur(String nom, Consumer<MinecraftServer> action) {
        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] tache = new CompletableFuture[1];
        etape(nom, mc -> {
            MinecraftServer serveur = mc.getSingleplayerServer();
            if (tache[0] == null) {
                tache[0] = serveur.submit(() -> action.accept(serveur));
            }
            if (!tache[0].isDone()) {
                if (System.nanoTime() - debutEtape > 60_000_000_000L) {
                    throw new IllegalStateException("le serveur n'a pas répondu en 60 s");
                }
                return false;
            }
            tache[0].join();
            return true;
        });
    }
}
