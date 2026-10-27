package dev.axion.persist;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Le journal latéral d'un monde, écrit par un fil à lui (R-1744, ADR-153 D6).
 *
 * <p>Le thread autoritatif ne fait que remettre une demande à une file, sans attendre ni toucher au
 * disque : {@link #put} prend un fournisseur de blob, que le fil du journal appelle — l'appelant lui confie
 * une copie immuable, le fil l'encode. La file ne garde que la dernière demande de chaque assembly : un
 * {@code PUT} remplace le précédent, un {@code REMOVE} l'annule.
 *
 * <p>Le fil commence par lire le journal ; ce qu'il y trouve est rendu par {@link #loaded()}. Un journal
 * qui ne s'ouvre pas ne restaure rien, n'écrit rien, et le dit une fois (R-1746) : son absence n'empêche
 * jamais de jouer.
 */
public final class SideJournal implements AutoCloseable {

    /** Ouvre le journal sur le disque : appelé par le fil du journal, une fois. */
    @FunctionalInterface
    public interface Opener {
        JournalStore open() throws IOException;
    }

    /** Une demande en attente : un {@code REMOVE}, ou un {@code PUT} dont le blob reste à encoder. */
    private record Request(UUID uuid, String dimension, double x, double y, double z, long definition,
            Supplier<byte[]> blob) {

        boolean isRemove() {
            return blob == null;
        }
    }

    private final Opener opener;
    private final Consumer<String> report;
    private final Thread thread;
    private final CompletableFuture<List<JournalRecord.Put>> loaded = new CompletableFuture<>();

    private final Object lock = new Object();

    /** Les demandes en attente, une par assembly, dans l'ordre de leur dernière remise. */
    private final LinkedHashMap<UUID, Request> pending = new LinkedHashMap<>();

    /** Fermé, ou illisible à l'ouverture : les demandes sont sans effet. */
    private boolean closing;
    private boolean disabled;

    /** Lots écrits, pour les tests et le diagnostic. */
    private volatile long batches;

    private SideJournal(Opener opener, Consumer<String> report) {
        this.opener = opener;
        this.report = report;
        this.thread = new Thread(this::run, "AXION journal latéral");
        this.thread.setDaemon(true);
    }

    /**
     * Démarre le fil du journal, qui l'ouvre et le lit avant d'écrire quoi que ce soit.
     *
     * @param opener ouvre le journal sur le disque
     * @param report où dire ce que le journal trouve d'anormal ; appelé depuis le fil du journal
     */
    public static SideJournal start(Opener opener, Consumer<String> report) {
        SideJournal journal = new SideJournal(opener, report);
        journal.thread.start();
        return journal;
    }

    /** {@return les assemblies vivantes lues au démarrage, de la plus anciennement écrite à la plus récente} */
    public CompletableFuture<List<JournalRecord.Put>> loaded() {
        return loaded;
    }

    /**
     * Demande l'écriture de l'état d'une assembly. Sans attente ; le blob est encodé par le fil du journal.
     *
     * @param blob rend ses clés {@code axion:*} en NBT binaire ; appelé depuis le fil du journal
     */
    public void put(UUID uuid, String dimension, double x, double y, double z, long definition, Supplier<byte[]> blob) {
        Objects.requireNonNull(blob, "blob");
        submit(new Request(Objects.requireNonNull(uuid), Objects.requireNonNull(dimension), x, y, z, definition, blob));
    }

    /** Demande l'effacement d'une assembly supprimée explicitement. Sans attente. */
    public void remove(UUID uuid) {
        submit(new Request(Objects.requireNonNull(uuid), "", 0, 0, 0, 0, null));
    }

    private void submit(Request request) {
        synchronized (lock) {
            if (closing || disabled) {
                return;
            }
            pending.remove(request.uuid());
            pending.put(request.uuid(), request);
            lock.notifyAll();
        }
    }

    /** {@return le nombre de demandes en attente} */
    public int pending() {
        synchronized (lock) {
            return pending.size();
        }
    }

    /** {@return les lots écrits depuis le démarrage} */
    public long batches() {
        return batches;
    }

    /**
     * Écrit ce qui attend, puis arrête le fil. Les demandes remises après n'ont pas d'effet.
     *
     * @param timeoutMs attente maximale du fil, en millisecondes
     * @return vrai si le fil a fini dans le délai
     */
    public boolean close(long timeoutMs) {
        synchronized (lock) {
            closing = true;
            lock.notifyAll();
        }
        try {
            thread.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !thread.isAlive();
    }

    @Override
    public void close() {
        close(30_000);
    }

    private void run() {
        JournalStore store;
        try {
            store = opener.open();
        } catch (IOException | RuntimeException e) {
            synchronized (lock) {
                disabled = true;
                pending.clear();
            }
            report.accept("journal latéral illisible, rien ne sera restauré ni écrit pendant cette session : " + e);
            loaded.complete(List.of());
            return;
        }
        loaded.complete(store.live());
        boolean failing = false;
        try {
            while (true) {
                List<Request> batch;
                synchronized (lock) {
                    while (pending.isEmpty() && !closing) {
                        lock.wait();
                    }
                    if (pending.isEmpty()) {
                        break;
                    }
                    batch = new ArrayList<>(pending.values());
                    pending.clear();
                }
                try {
                    store.apply(records(batch));
                    batches++;
                    if (failing) {
                        report.accept("journal latéral : l'écriture reprend");
                        failing = false;
                    }
                } catch (IOException e) {
                    if (!failing) {
                        report.accept("journal latéral : écriture impossible, " + batch.size()
                                + " assemblies non journalisées : " + e);
                        failing = true;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                store.close();
            } catch (IOException e) {
                report.accept("journal latéral : fermeture impossible : " + e);
            }
        }
    }

    private List<JournalRecord> records(List<Request> batch) {
        List<JournalRecord> records = new ArrayList<>(batch.size());
        for (Request request : batch) {
            if (request.isRemove()) {
                records.add(new JournalRecord.Remove(request.uuid()));
                continue;
            }
            try {
                records.add(new JournalRecord.Put(request.uuid(), request.dimension(), request.x(), request.y(),
                        request.z(), request.definition(), request.blob().get()));
            } catch (RuntimeException e) {
                report.accept("journal latéral : assembly " + request.uuid() + " non journalisée, blob illisible : " + e);
            }
        }
        return records;
    }
}
