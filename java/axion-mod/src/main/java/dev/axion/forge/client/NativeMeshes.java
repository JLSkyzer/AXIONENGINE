package dev.axion.forge.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.axion.asset.GeometryTransfer;
import dev.axion.render.ArenaAllocator;
import dev.axion.render.DeferredRelease;
import dev.axion.render.GpuBudget;
import dev.axion.render.InstanceLayout;
import dev.axion.render.RenderAsset;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ressources GPU du backend natif (C-62, ADR-127 §3) : les meshes dans des arènes partagées, un VAO
 * par arène au format du §19.4, un tampon d'instances, la libération différée et le budget.
 *
 * <p>Un asset est téléversé à son premier dessin, d'un bloc — ses sommets tels que le natif les a
 * déposés, ses indices — dans une place de l'{@link ArenaAllocator}. Un asset que rien n'a dessiné
 * pendant {@value DeferredRelease#FRAMES_WITHOUT_USE} frames rend sa place : en M3, sans culling, ne
 * plus être dessiné, c'est ne plus être tenu par aucune assembly. Une arène vidée n'est détruite
 * qu'après {@value DeferredRelease#FRAMES_WITHOUT_USE} frames. Au-delà de {@code budgets.gpu_mem_bytes},
 * l'asset le moins récemment vu parmi les non visibles est déchargé d'abord (R-750) ; un asset
 * visible ne l'est jamais.
 *
 * <p>Les téléversements passent par {@code GL_COPY_WRITE_BUFFER}, jamais par le tampon d'indices,
 * qui appartient au VAO lié. Render thread seul (R-751, INV-12).
 */
final class NativeMeshes implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("axion");

    /** Une arène côté GL : ses deux tampons et son VAO. */
    private record ArenaGl(int vertexBuffer, int indexBuffer, int vao) {}

    /**
     * Un asset sur le GPU.
     *
     * @param allocation sa place dans son arène
     * @param vao le VAO de son arène
     */
    record GpuAsset(ArenaAllocator.Allocation allocation, int vao) {

        /** {@return le décalage de base d'un dessin de ce mesh : son premier sommet dans l'arène} */
        int baseVertex(GeometryTransfer.Mesh mesh) {
            return allocation.baseVertex() + mesh.vertexOffset();
        }

        /** {@return le début des indices de ce mesh dans le tampon d'indices de l'arène, en octets} */
        long indexByteOffset(GeometryTransfer.Mesh mesh) {
            return allocation.indexOffset() + (long) mesh.indexOffset() * ArenaAllocator.INDEX_BYTES;
        }
    }

    private final ArenaAllocator allocator = new ArenaAllocator();
    private final Map<Integer, ArenaGl> arenas = new HashMap<>();
    private final Map<RenderAsset, GpuAsset> assets = new IdentityHashMap<>();
    private final Set<RenderAsset> drawnThisFrame = Collections.newSetFromMap(new IdentityHashMap<>());
    private final DeferredRelease<RenderAsset> unused = new DeferredRelease<>();
    private final DeferredRelease<ArenaGl> emptied = new DeferredRelease<>();
    private final GpuBudget<RenderAsset> budget;
    private final int instanceBuffer;
    private long instanceCapacity;
    private long frame;
    private boolean budgetShortReported;

    /** @param budgetBytes {@code budgets.gpu_mem_bytes} */
    NativeMeshes(long budgetBytes) {
        RenderSystem.assertOnRenderThread();
        this.budget = new GpuBudget<>(budgetBytes);
        this.instanceBuffer = GL15.glGenBuffers();
        NativeGlObjects.created();
    }

    /**
     * {@return l'asset sur le GPU, téléversé à son premier dessin} L'asset compte comme vu à cette
     * frame : ni rendu, ni déchargé.
     *
     * @param asset l'asset à dessiner
     */
    GpuAsset prepare(RenderAsset asset) {
        RenderSystem.assertOnRenderThread();
        GpuAsset gpu = assets.get(asset);
        if (gpu == null) {
            gpu = upload(asset);
            assets.put(asset, gpu);
        }
        drawnThisFrame.add(asset);
        unused.use(asset);
        budget.seen(asset, frame);
        return gpu;
    }

    /**
     * Téléverse les blocs d'instance d'une passe, depuis le début du tampon : orphelin, puis rempli ;
     * agrandi par doublement.
     *
     * @param blocks blocs de {@link InstanceLayout#BYTES} octets, de la position à la limite, dans un
     *     tampon direct
     */
    void uploadInstances(ByteBuffer blocks) {
        RenderSystem.assertOnRenderThread();
        long needed = blocks.remaining();
        if (needed > instanceCapacity) {
            instanceCapacity = Math.max(needed, Math.max(InstanceLayout.BYTES * 64L, instanceCapacity * 2));
        }
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, instanceBuffer);
        GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER, instanceCapacity, GL15.GL_STREAM_DRAW);
        GL15.glBufferSubData(GL31.GL_COPY_WRITE_BUFFER, 0, blocks);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
    }

    /**
     * Pointe les attributs d'instance du VAO lié sur le bloc de rang {@code index}.
     *
     * @param index rang du bloc dans le tampon d'instances
     */
    void pointInstance(int index) {
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceBuffer);
        instanceAttributes((long) index * InstanceLayout.BYTES);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    /**
     * Passe à la frame suivante, avant tout dessin : ce que la frame qui s'achève n'a pas dessiné
     * entre en attente de libération, le budget est tenu (R-750), et ce qui attend depuis assez
     * longtemps est détruit (R-751). Une frame sans assembly n'appelle pas : elle ne fait que
     * retarder ces libérations, jamais les avancer.
     */
    void nextFrame() {
        RenderSystem.assertOnRenderThread();
        for (RenderAsset asset : assets.keySet()) {
            if (!drawnThisFrame.contains(asset)) {
                unused.release(asset, frame);
            }
        }
        for (RenderAsset asset : unused.due(frame)) {
            forget(asset);
        }
        while (budget.exceededBy(gpuBytes())) {
            RenderAsset candidate = budget.evictionCandidate(frame);
            if (candidate == null) {
                if (!budgetShortReported) {
                    budgetShortReported = true;
                    LOGGER.warn("AXION : budget GPU dépassé ({} octets pour {}) par des assets tous visibles :"
                                    + " les leviers suivants de R-750 — LOD forcé, qualité de déformation —"
                                    + " arrivent avec C-64 et la M6",
                            gpuBytes(), budget.budgetBytes());
                }
                break;
            }
            forget(candidate);
        }
        for (ArenaGl arena : emptied.due(frame)) {
            destroy(arena);
        }
        frame++;
        drawnThisFrame.clear();
    }

    /**
     * {@return la mémoire GPU tenue : arènes vivantes et tampon d'instances} Une arène vidée qui
     * attend sa destruction n'y compte plus : la compter ferait décharger d'autres assets pour une
     * place déjà rendue.
     */
    long gpuBytes() {
        return allocator.gpuBytes() + instanceCapacity;
    }

    /** Tout est rendu sans délai — rechargement de ressources (R-752), sortie du monde. */
    void releaseAll() {
        RenderSystem.assertOnRenderThread();
        for (RenderAsset asset : assets.keySet()) {
            budget.discharge(asset);
        }
        assets.clear();
        drawnThisFrame.clear();
        unused.drainAll();
        List<ArenaGl> all = new ArrayList<>(emptied.drainAll());
        for (ArenaAllocator.Arena arena : allocator.clear()) {
            ArenaGl gl = arenas.remove(arena.id());
            if (gl != null) {
                all.add(gl);
            }
        }
        for (ArenaGl arena : all) {
            destroy(arena);
        }
    }

    @Override
    public void close() {
        releaseAll();
        GL15.glDeleteBuffers(instanceBuffer);
        NativeGlObjects.deleted();
    }

    private GpuAsset upload(RenderAsset asset) {
        GeometryTransfer mesh = asset.mesh();
        long vertexBytes = (long) mesh.vertexCount() * ArenaAllocator.VERTEX_BYTES;
        long indexBytes = (long) mesh.indexCount() * ArenaAllocator.INDEX_BYTES;
        ArenaAllocator.Allocation place = allocator.allocate(vertexBytes, indexBytes);
        ArenaGl arena = arenas.computeIfAbsent(place.arena(), id -> create(allocator.arena(id)));
        write(arena.vertexBuffer(), place.vertexOffset(), mesh.vertexData());
        if (indexBytes > 0) {
            write(arena.indexBuffer(), place.indexOffset(), mesh.indexData());
        }
        budget.charge(asset, vertexBytes + indexBytes);
        return new GpuAsset(place, arena.vao());
    }

    private void forget(RenderAsset asset) {
        GpuAsset gpu = assets.remove(asset);
        budget.discharge(asset);
        unused.use(asset);
        if (gpu != null) {
            allocator.free(gpu.allocation()).ifPresent(arena -> {
                ArenaGl gl = arenas.remove(arena.id());
                if (gl != null) {
                    emptied.release(gl, frame);
                }
            });
        }
    }

    private static void write(int buffer, long offset, ByteBuffer data) {
        // LWJGL ne lit que la mémoire directe : une copie, le temps du téléversement.
        ByteBuffer direct = MemoryUtil.memAlloc(data.remaining());
        try {
            direct.put(data.duplicate()).flip();
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, buffer);
            GL15.glBufferSubData(GL31.GL_COPY_WRITE_BUFFER, offset, direct);
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
        } finally {
            MemoryUtil.memFree(direct);
        }
    }

    private ArenaGl create(ArenaAllocator.Arena arena) {
        int vertexBuffer = GL15.glGenBuffers();
        NativeGlObjects.created();
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, vertexBuffer);
        GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER, arena.vertexCapacity(), GL15.GL_STATIC_DRAW);
        int indexBuffer = GL15.glGenBuffers();
        NativeGlObjects.created();
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, indexBuffer);
        GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER, Math.max(arena.indexCapacity(), ArenaAllocator.INDEX_BYTES),
                GL15.GL_STATIC_DRAW);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);

        int vao = GL30.glGenVertexArrays();
        NativeGlObjects.created();
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexBuffer);
        int stride = ArenaAllocator.VERTEX_BYTES;
        // Le sommet du §19.4 (DM-04) : position, normale, tangente, uv0, uv1, couleur, os, poids,
        // région et poids de déformation.
        floatAttribute(0, 3, GL11.GL_FLOAT, false, stride, 0);
        floatAttribute(1, 4, GL11.GL_BYTE, true, stride, 12);
        floatAttribute(2, 4, GL11.GL_BYTE, true, stride, 16);
        floatAttribute(3, 2, GL11.GL_UNSIGNED_SHORT, true, stride, 20);
        floatAttribute(4, 2, GL11.GL_UNSIGNED_SHORT, true, stride, 24);
        floatAttribute(5, 4, GL11.GL_UNSIGNED_BYTE, true, stride, 28);
        intAttribute(6, 4, GL11.GL_UNSIGNED_BYTE, stride, 32);
        floatAttribute(7, 4, GL11.GL_UNSIGNED_BYTE, true, stride, 36);
        intAttribute(8, 2, GL11.GL_UNSIGNED_BYTE, stride, 40);
        // Le tampon d'indices est un état du VAO : lié ici, une fois.
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceBuffer);
        for (int location = InstanceLayout.MODEL_LOCATION; location <= InstanceLayout.LAST_LOCATION; location++) {
            GL20.glEnableVertexAttribArray(location);
            GL33.glVertexAttribDivisor(location, 1);
        }
        instanceAttributes(0);
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        return new ArenaGl(vertexBuffer, indexBuffer, vao);
    }

    /** Les attributs du bloc d'instance, lus à {@code base} dans le tampon d'instances lié. */
    private static void instanceAttributes(long base) {
        int stride = InstanceLayout.BYTES;
        for (int row = 0; row < 3; row++) {
            GL20.glVertexAttribPointer(InstanceLayout.MODEL_LOCATION + row, 4, GL11.GL_FLOAT, false, stride,
                    base + InstanceLayout.MODEL_OFFSET + row * 16L);
        }
        GL20.glVertexAttribPointer(InstanceLayout.TINT_LOCATION, 4, GL11.GL_FLOAT, false, stride,
                base + InstanceLayout.TINT_OFFSET);
        GL30.glVertexAttribIPointer(InstanceLayout.LIGHTMAP_LOCATION, 2, GL11.GL_INT, stride,
                base + InstanceLayout.LIGHTMAP_OFFSET);
        // Palette, déformation, décalques, drapeaux : quatre uint consécutifs, un uvec4 (ADR-127 §9).
        GL30.glVertexAttribIPointer(InstanceLayout.OFFSETS_LOCATION, 4, GL11.GL_UNSIGNED_INT, stride,
                base + InstanceLayout.PALETTE_OFFSET);
    }

    private static void floatAttribute(int location, int size, int type, boolean normalized, int stride, long offset) {
        GL20.glEnableVertexAttribArray(location);
        GL20.glVertexAttribPointer(location, size, type, normalized, stride, offset);
    }

    private static void intAttribute(int location, int size, int type, int stride, long offset) {
        GL20.glEnableVertexAttribArray(location);
        GL30.glVertexAttribIPointer(location, size, type, stride, offset);
    }

    private static void destroy(ArenaGl arena) {
        GL30.glDeleteVertexArrays(arena.vao());
        GL15.glDeleteBuffers(arena.vertexBuffer());
        GL15.glDeleteBuffers(arena.indexBuffer());
        NativeGlObjects.deleted();
        NativeGlObjects.deleted();
        NativeGlObjects.deleted();
    }
}
