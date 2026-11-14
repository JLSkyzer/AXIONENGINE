package dev.axion.forge;

import dev.axion.api.ApiVersion;
import dev.axion.api.AxionApi;
import dev.axion.api.assembly.Assembly;
import dev.axion.api.assembly.AnimationControl;
import dev.axion.api.assembly.AssemblyService;
import dev.axion.api.assembly.CustomData;
import dev.axion.api.assembly.Modifier;
import dev.axion.api.assembly.Parameters;
import dev.axion.api.assembly.Transform;
import dev.axion.api.assembly.Variables;
import dev.axion.api.content.ContentQuery;
import dev.axion.api.content.DefinitionRef;
import dev.axion.definition.DefinitionRegistry;
import dev.axion.entity.AssemblySpawns;
import dev.axion.lifecycle.AxionRuntime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Le fournisseur d'{@link AxionApi} (ADR-162 D1), déclaré à {@code ServiceLoader} par
 * {@code META-INF/services/dev.axion.api.AxionApi}.
 *
 * <p>Il ne rend que ce que le moteur fait vraiment : la version de l'API, le service des assemblies, qui passe
 * par le même chemin que {@code /axion spawn} (definition vérifiée, entité créée, liée, ajoutée au monde), et la lecture
 * du contenu chargé (ADR-166 D2).
 */
public final class ForgeAxionApi implements AxionApi {

    private final AssemblyService assemblies = new Assemblies();
    private final ContentQuery content = new Content();

    /** Constructeur public, exigé par {@code ServiceLoader}. */
    public ForgeAxionApi() {}

    @Override
    public ApiVersion version() {
        return ApiVersion.CURRENT;
    }

    @Override
    public AssemblyService assemblies() {
        return assemblies;
    }

    @Override
    public ContentQuery content() {
        return content;
    }

    /**
     * ADR-166 D2 : le registre effectif des definitions — celles que leur compilation a refusées en sont retirées —,
     * lu tel qu'il est à l'appel. Avant le chargement d'un monde, il est vide.
     */
    private static final class Content implements ContentQuery {

        private static DefinitionRegistry registry() {
            AxionRuntime runtime = RuntimeAccess.get();
            return runtime == null ? DefinitionRegistry.empty() : runtime.definitions();
        }

        @Override
        public boolean isLoaded(String definitionId) {
            return registry().get(definitionId).isPresent();
        }

        @Override
        public Optional<String> refusal(String definitionId) {
            return registry().refusal(definitionId);
        }

        @Override
        public Set<String> definitions() {
            return Set.copyOf(registry().ids());
        }
    }

    /** Le service des assemblies. */
    private static final class Assemblies implements AssemblyService {

        @Override
        public Assembly spawn(Level level, DefinitionRef def, Vec3 pos, Quaternionf rot) {
            AuthoritativeThread.require();
            if (!(level instanceof ServerLevel server)) {
                throw new IllegalStateException("une assembly n'apparaît que dans un monde du serveur");
            }
            AxionRuntime runtime = RuntimeAccess.get();
            if (runtime == null || runtime.outcome() == null) {
                throw new IllegalStateException("AXION n'est pas chargé");
            }
            AssemblySpawns.Decision decision = AssemblySpawns.check(
                    runtime.definitions(),
                    def.id().toString(),
                    1,
                    runtime.outcome().config().getInt("limits.max_spawn_per_command"));
            if (!decision.accepted()) {
                throw new IllegalArgumentException("definition " + def.id() + " : " + decision.refusal());
            }
            AxionEntity entity = AxionEntities.ASSEMBLY.get().create(server);
            if (entity == null) {
                throw new IllegalStateException("l'entité d'assembly n'a pas pu être construite");
            }
            entity.moveTo(pos.x, pos.y, pos.z, 0f, 0f);
            entity.setBodyRotation(rot.x, rot.y, rot.z, rot.w);
            entity.bind(decision.definition());
            if (!server.addFreshEntity(entity)) {
                throw new IllegalStateException("le monde refuse l'entité de " + def.id());
            }
            return new View(entity);
        }

        @Override
        public Optional<Assembly> byEntity(Entity e) {
            return e instanceof AxionEntity entity ? Optional.of(new View(entity)) : Optional.empty();
        }

        @Override
        public Collection<Assembly> inRange(Level level, Vec3 center, double radius) {
            AABB box = new AABB(center, center).inflate(radius);
            double squared = radius * radius;
            List<Assembly> found = level.getEntitiesOfClass(AxionEntity.class, box,
                            entity -> entity.position().distanceToSqr(center) <= squared)
                    .stream().map(entity -> (Assembly) new View(entity)).toList();
            return found;
        }
    }

    /**
     * Les variables d'une assembly vues par l'API (ADR-163 D2) : une fenêtre sur celles de son entité, lues et écrites
     * sur le thread autoritatif ; une écriture se signe « API ».
     */
    private record VariablesView(AxionEntity entity) implements Variables {

        private dev.axion.entity.AssemblyVariables held() {
            AuthoritativeThread.require();
            return entity.variables();
        }

        private int index(String name) {
            dev.axion.entity.AssemblyVariables held = held();
            int index = held == null ? -1 : held.declared().indexOf(name);
            if (index < 0) {
                throw new IllegalArgumentException("variable « " + name + " » que la definition "
                        + entity.definitionId() + " ne déclare pas" + (held == null ? ", ou pas encore compilée" : ""));
            }
            return index;
        }

        @Override
        public java.util.List<String> names() {
            dev.axion.entity.AssemblyVariables held = held();
            return held == null ? java.util.List.of()
                    : held.declared().declared().stream().map(dev.axion.definition.Variables.Variable::name).toList();
        }

        @Override
        public boolean has(String name) {
            dev.axion.entity.AssemblyVariables held = held();
            return held != null && held.declared().indexOf(name) >= 0;
        }

        @Override
        public double get(String name) {
            return held().get(index(name));
        }

        @Override
        public boolean getBoolean(String name) {
            return get(name) != 0.0;
        }

        @Override
        public void set(String name, double value) {
            held().set(index(name), value, "API");
        }

        @Override
        public void setBoolean(String name, boolean value) {
            set(name, value ? 1.0 : 0.0);
        }
    }

    /**
     * Les paramètres d'une assembly vus par l'API (ADR-167) : une fenêtre sur ceux de son entité, lus et écrits sur le
     * thread autoritatif ; un modificateur posé se signe « api ».
     */
    private record ParametersView(AxionEntity entity) implements Parameters {

        /** Au serveur, sur le thread autoritatif ; au client, le miroir des snapshots (ADR-167 D8). */
        private dev.axion.entity.AssemblyParameters table() {
            if (!entity.level().isClientSide()) {
                AuthoritativeThread.require();
            }
            return entity.parameters();
        }

        /** Ce qui écrit : au serveur seulement, sur le thread autoritatif (R-1755). */
        private dev.axion.entity.AssemblyParameters writable() {
            if (entity.level().isClientSide()) {
                throw new IllegalStateException("R-1755 : au client, les paramètres d'une assembly se lisent sans"
                        + " s'écrire ; le serveur les règle");
            }
            return table();
        }

        private dev.axion.definition.Tuning.Parameter parameter(String name) {
            dev.axion.entity.AssemblyParameters table = table();
            int index = table == null ? -1 : table.tuning().indexOf(name);
            if (index < 0) {
                throw new IllegalArgumentException("paramètre « " + name + " » que l'assembly de "
                        + entity.definitionId() + " n'a pas" + (table == null ? ", ou pas encore compilée" : ""));
            }
            return table.tuning().get(index);
        }

        @Override
        public java.util.List<String> names() {
            dev.axion.entity.AssemblyParameters table = table();
            return table == null ? java.util.List.of() : table.tuning().names();
        }

        @Override
        public boolean has(String name) {
            dev.axion.entity.AssemblyParameters table = table();
            return table != null && table.tuning().indexOf(name) >= 0;
        }

        @Override
        public double base(String name) {
            return parameter(name).base();
        }

        @Override
        public double value(String name) {
            parameter(name);
            dev.axion.entity.AssemblyParameters table = table();
            return table.value(table.tuning().indexOf(name));
        }

        @Override
        public Range range(String name) {
            return parameter(name).bounds();
        }

        @Override
        public String unit(String name) {
            return parameter(name).unit();
        }

        @Override
        public void modify(Modifier modifier) {
            writable();
            parameter(modifier.parameter());
            table().modify(modifier, dev.axion.entity.AssemblyParameters.API);
        }

        @Override
        public boolean remove(String cause) {
            dev.axion.entity.AssemblyParameters table = writable();
            return table != null && table.remove(cause);
        }

        @Override
        public boolean remove(String cause, String parameter) {
            dev.axion.entity.AssemblyParameters table = writable();
            return table != null && table.remove(cause, parameter);
        }

        @Override
        public java.util.List<Applied> modifiers() {
            dev.axion.entity.AssemblyParameters table = table();
            return table == null ? java.util.List.of() : table.applied();
        }

        @Override
        public java.util.List<Applied> modifiers(String parameter) {
            return modifiers().stream().filter(applied -> applied.modifier().parameter().equals(parameter)).toList();
        }
    }

    /**
     * Le véhicule d'une assembly vu par l'API (ADR-159 D11, ADR-167 D9) : une fenêtre sur son entité. Au serveur, sur
     * le thread autoritatif ; au client, ses contrôleurs se lisent, le reste attend la prédiction (C-33).
     */
    private record VehicleView(AxionEntity entity) implements dev.axion.api.vehicle.VehicleControl {

        /** Ce qui lit l'état ou écrit : au serveur seulement, sur le thread autoritatif (R-1755). */
        private void authoritative() {
            if (entity.level().isClientSide()) {
                throw new IllegalStateException("R-1755 : au client, l'état d'un véhicule ne se lit pas encore et ne"
                        + " s'écrit pas ; le serveur le tient");
            }
            AuthoritativeThread.require();
        }

        @Override
        public dev.axion.api.vehicle.VehicleView state() {
            if (entity.level().isClientSide()) {
                // ADR-168 D6 : au client, l'état du véhicule que le joueur conduit et prédit, au début de son tick.
                dev.axion.entity.VehicleSnapshot predicted = entity.predictedView();
                if (predicted == null) {
                    throw new IllegalStateException("R-1755 : au client, seul l'état du véhicule que le joueur conduit"
                            + " et prédit se lit ; le serveur tient celui des autres");
                }
                return predicted;
            }
            authoritative();
            dev.axion.entity.VehicleSnapshot state = entity.vehicleSnapshot();
            if (state == null) {
                throw new IllegalStateException("l'assembly de " + entity.definitionId()
                        + " n'est pas encore compilée");
            }
            return state;
        }

        @Override
        public java.util.List<String> controllers() {
            if (!entity.level().isClientSide()) {
                AuthoritativeThread.require();
            }
            return entity.controllers();
        }

        @Override
        public void attach(String controller) {
            authoritative();
            entity.attachController(java.util.Objects.requireNonNull(controller, "controller"));
        }

        @Override
        public boolean detach(String controller) {
            authoritative();
            return entity.detachController(java.util.Objects.requireNonNull(controller, "controller"));
        }

        @Override
        public void drive(dev.axion.api.vehicle.DriverInput input) {
            authoritative();
            entity.drive(java.util.Objects.requireNonNull(input, "input"));
        }
    }

    /** Une assembly vue par l'API : une fenêtre sur son entité, sans état propre. */
    private record View(AxionEntity entity) implements Assembly {

        @Override
        public UUID id() {
            return entity.getUUID();
        }

        @Override
        public DefinitionRef definition() {
            return new DefinitionRef(ResourceLocation.tryParse(entity.definitionId()));
        }

        @Override
        public Transform transform() {
            return new Transform(entity.position(), entity.orientation(), new Vec3(1, 1, 1));
        }

        @Override
        public AnimationControl animation() {
            return entity.animation();
        }

        @Override
        public CustomData customData() {
            return entity.customData();
        }

        @Override
        public Variables variables() {
            return new VariablesView(entity);
        }

        @Override
        public Parameters parameters() {
            return new ParametersView(entity);
        }

        @Override
        public Optional<dev.axion.api.vehicle.VehicleControl> vehicle() {
            return entity.wheelNodes().isEmpty() ? Optional.empty() : Optional.of(new VehicleView(entity));
        }

        @Override
        public boolean isValid() {
            return !entity.isRemoved();
        }

        @Override
        public void remove() {
            AuthoritativeThread.require();
            if (!entity.isRemoved()) {
                entity.discard();
            }
        }
    }
}
