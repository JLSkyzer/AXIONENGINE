package dev.axion.render;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Dit si un shaderpack est en usage (R-1490, fiche 5.48, ADR-129) — logique pure, sans Forge.
 *
 * <p>Le backend vanilla n'est obligatoire que sous un shaderpack <b>en usage</b> : un mod de
 * shaders installé, pack coupé, laisse le pipeline de Minecraft intact, et le natif y dessine.
 * L'état se lit par l'API v0 d'Iris, qu'Oculus embarque aussi, trouvée par réflexion : AXION ne
 * dépend d'aucun mod de shaders, il les détecte (H-09). Cette API n'annonce pas les changements ;
 * l'appelant la relit à chaque frame, et sans mod de shaders, ce n'est qu'un test de drapeau.
 *
 * <p>Un mod de shaders dont l'état ne se lit pas — API absente, ou qui a levé une exception — est
 * tenu pour actif : le vanilla est le seul choix sûr sous un pack qu'on ne voit pas. Une API qui a
 * échoué n'est plus interrogée. Fil de rendu seul.
 */
public final class ShaderpackProbe {

    /** L'API v0 d'Iris : {@code getInstance()}, puis {@code isShaderPackInUse()}. */
    public static final String IRIS_API = "net.irisshaders.iris.api.v0.IrisApi";

    private final boolean modPresent;
    private MethodHandle inUse;
    private String failure;

    private ShaderpackProbe(boolean modPresent, MethodHandle inUse, String failure) {
        this.modPresent = modPresent;
        this.inUse = inUse;
        this.failure = failure;
    }

    /**
     * {@return la sonde}
     *
     * @param modPresent vrai si un mod de shaders est chargé ; faux, l'API n'est pas cherchée
     * @param api nom de la classe de l'API, {@link #IRIS_API} hors des tests
     */
    public static ShaderpackProbe of(boolean modPresent, String api) {
        if (!modPresent) {
            return new ShaderpackProbe(false, null, null);
        }
        try {
            Class<?> type = Class.forName(api, true, ShaderpackProbe.class.getClassLoader());
            Object instance = type.getMethod("getInstance").invoke(null);
            MethodHandle handle = MethodHandles.publicLookup()
                    .findVirtual(type, "isShaderPackInUse", MethodType.methodType(boolean.class))
                    .bindTo(instance);
            return new ShaderpackProbe(true, handle, null);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException absent) {
            return new ShaderpackProbe(true, null, api + " illisible : " + absent);
        }
    }

    /** {@return vrai si un shaderpack est en usage, ou si un mod de shaders l'est sans que son état se lise} */
    public boolean inUse() {
        if (!modPresent) {
            return false;
        }
        if (inUse == null) {
            return true;
        }
        try {
            return (boolean) inUse.invokeExact();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable echec) {
            inUse = null;
            failure = "isShaderPackInUse a échoué : " + echec;
            return true;
        }
    }

    /** {@return vrai si l'état du shaderpack se lit : un mod de shaders est là, et son API répond} */
    public boolean readable() {
        return modPresent && inUse != null;
    }

    /** {@return pourquoi l'état ne se lit pas, ou {@code null}} */
    public String failure() {
        return failure;
    }
}
