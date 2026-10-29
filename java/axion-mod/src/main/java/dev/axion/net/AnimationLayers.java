package dev.axion.net;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * L'état d'animation d'une assembly, tel que le descripteur d'états v3 le porte (ADR-155, tranche 4) : la
 * dernière commande de chacune des quatre couches, que le client rejoue.
 *
 * <p>Une couche joue {@code clip} depuis le tick {@code tick}, où son temps de clip valait {@code origin}
 * secondes, à la vitesse {@code speed}, de poids {@code weight}, entrée par un fondu de {@code fade}
 * secondes. Un clip {@link Layer#STOPPED} dit une couche arrêtée au tick {@code tick} par un fondu de
 * {@code fade} secondes ; la couche vide, {@link Layer#EMPTY}, est l'arrêt au tick 0 sans fondu, ce qui
 * revient au même.
 *
 * <p>Un joueur qui arrive pendant un fondu le voit partir de la pose neutre, faute du clip sortant que ce
 * descripteur ne porte pas (décision de Killian) ; tout le reste est exact.
 *
 * <p>Un changement de vitesse garde le tick et le fondu de la couche pendant son fondu d'entrée, et y
 * ramène le temps de clip : {@link #changesFrom} retrouve l'instant du changement. Le fondu fini, il
 * repart du tick du changement, sans fondu.
 *
 * @param layers les quatre couches, dans leur ordre : {@code BASE}, {@code ADDITIVE_1}, {@code ADDITIVE_2},
 *     {@code OVERRIDE}
 */
public record AnimationLayers(List<Layer> layers) {

    /** Le nombre de couches, figé par R-1440. */
    public static final int LAYERS = 4;

    /** Plus grand rang de clip : un asset a au plus 128 animations (C-22). */
    public static final int MAX_CLIP = 127;

    /** Aucune animation. */
    public static final AnimationLayers NONE =
            new AnimationLayers(List.of(Layer.EMPTY, Layer.EMPTY, Layer.EMPTY, Layer.EMPTY));

    /** Ticks de jeu par seconde. */
    private static final double TICKS_PER_SECOND = 20.0;

    /**
     * La dernière commande d'une couche.
     *
     * @param clip rang du clip dans l'asset, ou {@link #STOPPED}
     * @param tick tick du serveur de la commande
     * @param origin temps de clip à ce tick, en secondes
     * @param speed vitesse
     * @param weight poids, de 0 à 1
     * @param fade durée du fondu, en secondes
     */
    public record Layer(int clip, long tick, float origin, float speed, float weight, float fade) {

        /** Clip d'une couche arrêtée. */
        public static final int STOPPED = -1;

        /** La couche vide : arrêtée au tick 0, sans fondu. */
        public static final Layer EMPTY = new Layer(STOPPED, 0L, 0f, 0f, 0f, 0f);

        /** Refuse un clip, un tick ou un nombre hors de leurs bornes. */
        public Layer {
            if (clip < STOPPED || clip > MAX_CLIP) {
                throw new IllegalArgumentException("clip " + clip);
            }
            if (tick < 0) {
                throw new IllegalArgumentException("tick négatif : " + tick);
            }
            if (!Float.isFinite(origin) || !Float.isFinite(speed) || !(weight >= 0f && weight <= 1f)
                    || !(fade >= 0f && Float.isFinite(fade))) {
                throw new IllegalArgumentException("couche hors bornes : origine " + origin + ", vitesse " + speed
                        + ", poids " + weight + ", fondu " + fade);
            }
        }

        /**
         * @param tick tick du serveur
         * @param fade durée du fondu, en secondes
         * @return la couche arrêtée à ce tick par ce fondu
         */
        public static Layer stopped(long tick, float fade) {
            return new Layer(STOPPED, tick, 0f, 0f, 0f, fade);
        }

        /** {@return vrai si la couche joue un clip} */
        public boolean playing() {
            return clip != STOPPED;
        }

        /**
         * @param now tick du serveur, fraction comprise
         * @return vrai si la couche montre quelque chose à cet instant : elle joue, ou son fondu d'arrêt
         *     n'est pas fini
         */
        public boolean visibleAt(double now) {
            return playing() || now < tick + fade * TICKS_PER_SECOND;
        }

        /**
         * @param now tick du serveur, fraction comprise
         * @return le temps de clip non ramené dans sa durée à cet instant, en secondes
         */
        public double clipTimeAt(double now) {
            return origin + (now - tick) / TICKS_PER_SECOND * speed;
        }
    }

    /** Refuse un nombre de couches autre que quatre, ou une couche nulle. */
    public AnimationLayers {
        if (layers.size() != LAYERS) {
            throw new IllegalArgumentException(layers.size() + " couches, " + LAYERS + " attendues");
        }
        layers = List.copyOf(layers);
    }

    /**
     * @param layer rang de la couche
     * @return sa dernière commande
     */
    public Layer layer(int layer) {
        return layers.get(layer);
    }

    /**
     * @param layer rang de la couche
     * @param state sa nouvelle commande
     * @return cet état, la couche remplacée
     */
    public AnimationLayers with(int layer, Layer state) {
        Layer[] copy = layers.toArray(new Layer[0]);
        copy[layer] = state;
        return new AnimationLayers(List.of(copy));
    }

    /**
     * @param now tick du serveur, fraction comprise
     * @return vrai si une couche montre quelque chose : l'assembly ne se dessine plus à son repos, et son
     *     emprise de repos ne borne plus ce qu'elle dessine
     */
    public boolean visibleAt(double now) {
        for (Layer layer : layers) {
            if (layer.visibleAt(now)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Une commande d'animation qui mène un natif d'un état à l'autre.
     *
     * @param action {@link #PLAY}, {@link #STOP} ou {@link #SET_SPEED}, les codes d'{@code anim_action}
     *     (DM-10)
     * @param layer rang de la couche
     * @param tick instant de la commande, en ticks du serveur
     * @param clip rang du clip ({@code PLAY})
     * @param weight poids ({@code PLAY})
     * @param fade durée du fondu, en secondes ({@code PLAY}, {@code STOP})
     * @param origin temps de clip à l'instant {@code tick}, en secondes ({@code PLAY})
     * @param speed vitesse ({@code PLAY}, {@code SET_SPEED})
     */
    public record Change(
            int action, int layer, double tick, int clip, float weight, float fade, float origin, float speed) {

        /** Jouer un clip. */
        public static final int PLAY = 1;

        /** Arrêter une couche. */
        public static final int STOP = 2;

        /** Régler la vitesse d'une couche. */
        public static final int SET_SPEED = 3;
    }

    /**
     * Les commandes qui mènent un natif de l'état {@code previous} à celui-ci, par tick croissant : ce que
     * le serveur pousse à son natif, et le client au sien (ADR-155, tranche 4). Depuis {@link #NONE}, elles
     * rejouent l'état entier.
     *
     * <p>Une couche inchangée ne donne rien. Arrêtée, elle donne {@code STOP}, sauf depuis la couche vide.
     * Jouant le même clip au même tick, au même poids et au même fondu, à une autre vitesse, elle donne
     * {@code SET_SPEED} à l'instant où les deux temps de clip se rejoignent. Toute autre donne {@code PLAY}.
     *
     * @param previous l'état que le natif a reçu
     * @return les commandes, vides si les états sont égaux
     */
    public List<Change> changesFrom(AnimationLayers previous) {
        List<Change> changes = new ArrayList<>();
        for (int index = 0; index < LAYERS; index++) {
            Layer before = previous.layer(index);
            Layer after = layer(index);
            if (after.equals(before)) {
                continue;
            }
            if (!after.playing()) {
                if (!before.equals(Layer.EMPTY)) {
                    changes.add(new Change(Change.STOP, index, after.tick(), 0, 0f, after.fade(), 0f, 1f));
                }
            } else if (before.playing() && before.clip() == after.clip() && before.tick() == after.tick()
                    && before.weight() == after.weight() && before.fade() == after.fade()
                    && before.speed() != after.speed()) {
                // Les deux temps de clip se rejoignent à l'instant du changement de vitesse.
                double at = before.tick()
                        + TICKS_PER_SECOND * ((double) after.origin() - before.origin())
                                / ((double) before.speed() - after.speed());
                changes.add(new Change(Change.SET_SPEED, index, Double.isFinite(at) ? Math.max(at, before.tick())
                        : before.tick(), 0, 0f, 0f, 0f, after.speed()));
            } else {
                changes.add(new Change(Change.PLAY, index, after.tick(), after.clip(), after.weight(), after.fade(),
                        after.origin(), after.speed()));
            }
        }
        changes.sort(Comparator.comparingDouble(Change::tick));
        return changes;
    }

    /**
     * @param writer écrivain
     */
    void write(WireWriter writer) {
        int mask = 0;
        for (int index = 0; index < LAYERS; index++) {
            if (!layers.get(index).equals(Layer.EMPTY)) {
                mask |= 1 << index;
            }
        }
        writer.u8(mask);
        for (int index = 0; index < LAYERS; index++) {
            Layer layer = layers.get(index);
            if ((mask & (1 << index)) != 0) {
                writer.varUInt(layer.clip() + 1).varULong(layer.tick()).f32(layer.origin()).f32(layer.speed())
                        .f32(layer.weight()).f32(layer.fade());
            }
        }
    }

    /**
     * @return la taille de {@link #write} sur le fil, en octets
     */
    int encodedSize() {
        int size = 1;
        for (Layer layer : layers) {
            if (!layer.equals(Layer.EMPTY)) {
                size += varSize(layer.clip() + 1) + varSize(layer.tick()) + 4 * Float.BYTES;
            }
        }
        return size;
    }

    /**
     * @param reader lecteur
     * @param handle entité de l'entrée, pour le message d'erreur
     * @return l'état lu
     * @throws MalformedPacketException sur un masque, un clip ou un nombre hors de leurs bornes
     */
    static AnimationLayers read(WireReader reader, int handle) throws MalformedPacketException {
        int mask = reader.u8();
        if ((mask & ~0xF) != 0) {
            throw new MalformedPacketException("entité " + handle + " : masque de couches " + mask);
        }
        Layer[] layers = {Layer.EMPTY, Layer.EMPTY, Layer.EMPTY, Layer.EMPTY};
        for (int index = 0; index < LAYERS; index++) {
            if ((mask & (1 << index)) == 0) {
                continue;
            }
            int clip = reader.varUInt() - 1;
            long tick = reader.varULong();
            float origin = reader.f32();
            float speed = reader.f32();
            float weight = reader.f32();
            float fade = reader.f32();
            try {
                layers[index] = new Layer(clip, tick, origin, speed, weight, fade);
            } catch (IllegalArgumentException outOfBounds) {
                throw new MalformedPacketException("entité " + handle + ", couche " + index + " : "
                        + outOfBounds.getMessage());
            }
        }
        return new AnimationLayers(List.of(layers));
    }

    private static int varSize(long value) {
        int size = 1;
        for (long rest = value >>> 7; rest != 0; rest >>>= 7) {
            size++;
        }
        return size;
    }
}
