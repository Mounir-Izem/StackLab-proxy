package app.stacklab.proxy.service;

import app.stacklab.proxy.model.HistoryDay;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Le gardien de l'historique (décision P-1b, owner 2026-08-19) : la totalité
 * des jours réglés (2017 → hier UTC, ~200 Ko) vit en mémoire, remplie auprès
 * de metals.dev par fenêtres de 30 jours (~120 fenêtres par démarrage
 * d'instance, puis un jour à la fois). Renverse « pas de cache proxy » du
 * PRIME_DOSSIER : une requête client suffit à tout servir, le quota upstream
 * ne dépend plus du nombre d'appareils, et le proxy ne journalise jamais les
 * plages demandées — l'uniformité des plages (téléchargement complet puis
 * delta) est une règle du client StackLab, la non-journalisation est la
 * garantie du proxy.
 */
@Component
public class HistoryStore {

    private static final Logger log = LoggerFactory.getLogger(HistoryStore.class);

    /** Plancher empirique metals.dev, vérifié owner : avant, rates=null. */
    public static final LocalDate HISTORY_FLOOR = LocalDate.of(2017, 1, 1);
    /**
     * Fenêtre upstream : 30 dates inclusives. L'owner a vérifié 31 (janvier
     * 2017 entier) mais la doc metals.dev dit « 30 jours max » — 30 est sûr
     * sous les deux lectures, et la détection de troncature de fetchWindow
     * fait tripwire si la borne réelle était plus basse encore.
     */
    private static final int WINDOW_DAYS = 30;
    /** Après un échec upstream, aucune nouvelle tentative synchrone pendant ce délai. */
    private static final long UPSTREAM_COOLDOWN_MS = 15_000;
    /** Un réchauffage de fond ne se retente pas plus d'une fois par minute. */
    private static final long MIN_WARM_RETRY_MS = 60_000;

    private final long warmupPauseMs;

    /** Ce que le magasin a réellement pu servir : end ≤ end demandé, et il fait foi. */
    public record Served(LocalDate end, NavigableMap<String, HistoryDay> days) {}

    private final MetalsDevService metalsDevService;
    private final boolean warmupOnBoot;
    private final ExecutorService warmer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "history-warmup");
        t.setDaemon(true);
        return t;
    });

    /** Clés ISO (YYYY-MM-DD) : l'ordre lexicographique est l'ordre chronologique. */
    private final NavigableMap<String, HistoryDay> days = new TreeMap<>();
    /**
     * Frontière de couverture : toutes les dates de HISTORY_FLOOR à cette date
     * (incluse) ont été OBTENUES de l'upstream (fetchWindow garantit que la
     * fenêtre n'est pas tronquée). Un jour sous la frontière absent de `days`
     * est connu-absent (publié incomplet par metals.dev) : le redemander ne le
     * complétera pas. null = rien encore.
     */
    private LocalDate coveredUntil;

    private volatile boolean warming = false;
    private volatile long lastWarmAttemptMillis = 0;
    private volatile long lastUpstreamFailureMillis = 0;

    public HistoryStore(MetalsDevService metalsDevService,
                        @Value("${history.warmup-on-boot:true}") boolean warmupOnBoot,
                        @Value("${history.warmup-pause-ms:1500}") long warmupPauseMs) {
        this.metalsDevService = metalsDevService;
        this.warmupOnBoot = warmupOnBoot;
        this.warmupPauseMs = warmupPauseMs;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onBoot() {
        if (warmupOnBoot) requestWarmUp();
    }

    /**
     * Sert [start..end] depuis la mémoire, en avançant d'abord la frontière si
     * la queue manquante tient dans UNE fenêtre (le cas quotidien). Un trou
     * plus long part en réchauffage de fond. Dans tous les cas on sert ce qui
     * est couvert — le `end` du résultat fait foi — et on n'échoue que quand
     * il n'y a RIEN à servir : {@link HistoryWarmingException} si une
     * constitution est réellement en cours, panne upstream sinon.
     */
    public synchronized Served getRange(LocalDate start, LocalDate end) {
        if (coveredUntil == null || coveredUntil.isBefore(end)) {
            LocalDate from = coveredUntil == null ? HISTORY_FLOOR : coveredUntil.plusDays(1);
            long gapDays = ChronoUnit.DAYS.between(from, end) + 1;
            boolean cooling = System.currentTimeMillis() - lastUpstreamFailureMillis < UPSTREAM_COOLDOWN_MS;
            if (gapDays > WINDOW_DAYS) {
                requestWarmUp();
            } else if (!cooling) {
                try {
                    fillTo(end);
                } catch (Exception e) {
                    // L'échec est mémorisé (refroidissement) : sous panne, le
                    // magasin ne martèle pas l'upstream à chaque requête.
                    lastUpstreamFailureMillis = System.currentTimeMillis();
                }
            }
        }
        if (coveredUntil == null) {
            if (warming) throw new HistoryWarmingException();
            // Rien à servir et aucune constitution en cours : c'est une panne,
            // pas une attente — jamais un « réessayez » qui ment (revue P-1b, C1).
            throw new UpstreamUnavailableException();
        }
        if (coveredUntil.isBefore(start)) {
            // Le monde n'a RIEN de plus récent que la frontière du client (la
            // veille pas encore publiée par metals.dev — le cas quotidien
            // nominal, revue P-2 C1). Ce n'est pas une panne : on répond 200
            // avec le `end` réellement couvert, même s'il précède le `start`
            // demandé — « voilà jusqu'où va le monde, tu l'as déjà ».
            return new Served(coveredUntil, new TreeMap<>());
        }
        LocalDate servedEnd = coveredUntil.isBefore(end) ? coveredUntil : end;
        return new Served(servedEnd,
            new TreeMap<>(days.subMap(start.toString(), true, servedEnd.toString(), true)));
    }

    /**
     * Avance la frontière vers target, fenêtre par fenêtre. Appeler sous
     * verrou. La frontière avance jusqu'au dernier jour RÉELLEMENT répondu
     * (`coveredThrough`) : si une fenêtre revient courte (la queue pas encore
     * publiée), on s'arrête là honnêtement — la suite sera redemandée quand
     * metals.dev l'aura publiée, et rien de fantôme n'est marqué couvert.
     */
    private void fillTo(LocalDate target) {
        LocalDate from = coveredUntil == null ? HISTORY_FLOOR : coveredUntil.plusDays(1);
        while (!from.isAfter(target)) {
            LocalDate to = from.plusDays(WINDOW_DAYS - 1);
            if (to.isAfter(target)) to = target;
            MetalsDevService.FetchedWindow window = metalsDevService.fetchWindow(from, to);
            days.putAll(window.days());
            coveredUntil = window.coveredThrough();
            if (window.coveredThrough().isBefore(to)) return;
            from = to.plusDays(1);
        }
    }

    /**
     * Réchauffage de fond, single-flight : le fetch upstream se fait HORS
     * verrou (une fenêtre à la fois) pour que les lectures déjà couvertes
     * restent servies — en partiel, `end` faisant foi — pendant la
     * constitution (~1-2 min).
     */
    private void requestWarmUp() {
        long now = System.currentTimeMillis();
        if (warming || now - lastWarmAttemptMillis < MIN_WARM_RETRY_MS) return;
        warming = true;
        lastWarmAttemptMillis = now;
        warmer.submit(() -> {
            try {
                LocalDate target = LocalDate.now(ZoneOffset.UTC).minusDays(1);
                while (true) {
                    LocalDate from;
                    synchronized (this) {
                        from = coveredUntil == null ? HISTORY_FLOOR : coveredUntil.plusDays(1);
                    }
                    if (from.isAfter(target)) break;
                    LocalDate to = from.plusDays(WINDOW_DAYS - 1);
                    if (to.isAfter(target)) to = target;
                    MetalsDevService.FetchedWindow window = metalsDevService.fetchWindow(from, to);
                    synchronized (this) {
                        // Un remplissage synchrone concurrent a pu dépasser cette
                        // fenêtre pendant le fetch (double fetch bénin, borné) :
                        // la frontière n'avance que vers l'avant.
                        if (coveredUntil == null || coveredUntil.isBefore(window.coveredThrough())) {
                            days.putAll(window.days());
                            coveredUntil = window.coveredThrough();
                        }
                    }
                    // Fenêtre courte = la queue n'est pas encore publiée : le
                    // réchauffage a fini son travail POSSIBLE, il s'arrête là.
                    if (window.coveredThrough().isBefore(to)) break;
                    // Courtoisie upstream : ~120 fenêtres dos à dos ont déclenché
                    // la limite par minute de metals.dev (constaté en prod,
                    // 2026-08-19). Espacées, la constitution prend ~3 min — le
                    // service partiel couvre l'attente côté client.
                    if (warmupPauseMs > 0) Thread.sleep(warmupPauseMs);
                }
                log.info("history warm-up complete up to {}", target);
            } catch (Exception e) {
                // Même piège à clé qu'ailleurs : classe seule au log, jamais
                // de plage. Prochaine tentative : au plus dans une minute.
                lastUpstreamFailureMillis = System.currentTimeMillis();
                log.warn("history warm-up aborted: {}", e.getClass().getSimpleName());
            } finally {
                warming = false;
            }
        });
    }

    @PreDestroy
    void shutdown() {
        warmer.shutdownNow();
    }
}
