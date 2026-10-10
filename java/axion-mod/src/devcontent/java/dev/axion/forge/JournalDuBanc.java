package dev.axion.forge;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/**
 * Le journal vu par le banc de rendu (ADR-126) : les erreurs d'AXION, et tout message qui signale
 * un appel GL hors du render thread (T-473) — celui de {@code RenderSystem}, ceux de LWJGL —, d'où
 * qu'il vienne. Une erreur qu'une étape provoque exprès, annoncée par {@link #attendre}, est comptée
 * à part.
 *
 * <p>Contenu de développement, jamais empaqueté (R-1790). Appelé depuis n'importe quel thread.
 */
final class JournalDuBanc extends AbstractAppender {

    /** {@code RenderSystem.assertOnRenderThread} ; LWJGL sans contexte GL courant. */
    private static final Pattern HORS_DU_FIL = Pattern.compile(
            "(?i)called from wrong thread|no opengl context|no glcapabilities|context current in the current thread");

    private final List<String> erreursAxion = new ArrayList<>();
    private final List<String> horsDuFil = new ArrayList<>();

    /** Erreur qu'une étape provoque exprès (T-479) : comptée à part, pas parmi celles d'AXION. */
    private Pattern attendue;
    private final List<String> erreursAttendues = new ArrayList<>();

    private JournalDuBanc() {
        super("AxionBancDeRendu", null, null, true, Property.EMPTY_ARRAY);
    }

    /** {@return le journal, branché sur l'enregistreur racine de Log4j} */
    static JournalDuBanc installer() {
        LoggerContext contexte = (LoggerContext) LogManager.getContext(false);
        JournalDuBanc journal = new JournalDuBanc();
        journal.start();
        contexte.getConfiguration().getRootLogger().addAppender(journal, Level.ALL, null);
        contexte.updateLoggers();
        return journal;
    }

    @Override
    public void append(LogEvent event) {
        String texte = event.getMessage().getFormattedMessage();
        if (event.getThrown() != null) {
            texte += " — " + event.getThrown();
        }
        String nom = event.getLoggerName();
        synchronized (this) {
            if (HORS_DU_FIL.matcher(texte).find()) {
                horsDuFil.add(nom + " : " + texte);
            }
            boolean axion = "axion".equals(nom) || nom.startsWith("dev.axion");
            if (axion && event.getLevel().isMoreSpecificThan(Level.ERROR)) {
                if (attendue != null && attendue.matcher(texte).find()) {
                    erreursAttendues.add(texte);
                } else {
                    erreursAxion.add(texte);
                }
            }
        }
    }

    /**
     * Annonce l'erreur qu'une étape va provoquer, ou plus aucune.
     *
     * @param motif ce qui la reconnaît dans son message, ou {@code null}
     */
    synchronized void attendre(Pattern motif) {
        attendue = motif;
    }

    /** {@return les erreurs provoquées exprès, reconnues depuis l'installation} */
    synchronized List<String> erreursAttendues() {
        return List.copyOf(erreursAttendues);
    }

    /** {@return les erreurs journalisées par AXION depuis l'installation} */
    synchronized List<String> erreursAxion() {
        return List.copyOf(erreursAxion);
    }

    /** {@return les messages qui signalent un appel GL hors du render thread} */
    synchronized List<String> horsDuFil() {
        return List.copyOf(horsDuFil);
    }
}
