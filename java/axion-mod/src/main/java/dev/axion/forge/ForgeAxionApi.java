package dev.axion.forge;

import dev.axion.api.ApiVersion;
import dev.axion.api.AxionApi;
import dev.axion.api.assembly.Assembly;
import dev.axion.api.assembly.AnimationControl;
import dev.axion.api.assembly.AssemblyService;
import dev.axion.api.assembly.CustomData;
import dev.axion.api.assembly.Transform;
import dev.axion.api.content.DefinitionRef;
import dev.axion.entity.AssemblySpawns;
import dev.axion.lifecycle.AxionRuntime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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
 * <p>Il ne rend que ce que le moteur fait vraiment : la version de l'API et le service des assemblies, qui passe
 * par le même chemin que {@code /axion spawn} (definition vérifiée, entité créée, liée, ajoutée au monde).
 */
public final class ForgeAxionApi implements AxionApi {

    private final AssemblyService assemblies = new Assemblies();

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
