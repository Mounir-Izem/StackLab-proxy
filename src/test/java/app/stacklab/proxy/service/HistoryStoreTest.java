package app.stacklab.proxy.service;

import app.stacklab.proxy.model.HistoryDay;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Le gardien (P-1b) : frontière de couverture, remplissage minimal, jours
 * connus-absents jamais redemandés, service partiel (le `end` servi fait
 * foi), refroidissement après échec upstream, honnêteté à froid (panne =
 * panne, jamais un « réessayez » qui ment).
 */
class HistoryStoreTest {

    private static final LocalDate FLOOR = HistoryStore.HISTORY_FLOOR;

    private static Map<String, HistoryDay> fullWindow(LocalDate from, LocalDate to) {
        Map<String, HistoryDay> days = new HashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            days.put(d.toString(), new HistoryDay(1000.0, 15.0,
                Map.of("EUR", 1.0, "GBP", 1.2, "CAD", 0.75, "AUD", 0.7)));
        }
        return days;
    }

    private MetalsDevService fullUpstream() {
        MetalsDevService upstream = mock(MetalsDevService.class);
        when(upstream.fetchWindow(any(), any()))
            .thenAnswer(inv -> fullWindow(inv.getArgument(0), inv.getArgument(1)));
        return upstream;
    }

    @Test
    void aWindowIsFetchedOnceThenServedFromMemory() {
        MetalsDevService upstream = fullUpstream();
        HistoryStore store = new HistoryStore(upstream, false);

        HistoryStore.Served first = store.getRange(FLOOR, FLOOR.plusDays(29));
        HistoryStore.Served second = store.getRange(FLOOR, FLOOR.plusDays(29));

        assertThat(first.days()).hasSize(30);
        assertThat(first.end()).isEqualTo(FLOOR.plusDays(29));
        assertThat(second.days()).isEqualTo(first.days());
        verify(upstream, times(1)).fetchWindow(any(), any());
    }

    @Test
    void topUpFetchesOnlyTheMissingTail() {
        MetalsDevService upstream = fullUpstream();
        HistoryStore store = new HistoryStore(upstream, false);

        store.getRange(FLOOR, FLOOR.plusDays(29));
        HistoryStore.Served extended = store.getRange(FLOOR, FLOOR.plusDays(32));

        assertThat(extended.days()).hasSize(33);
        // Le cas quotidien : seule la queue manquante part vers l'upstream.
        verify(upstream).fetchWindow(FLOOR.plusDays(30), FLOOR.plusDays(32));
        verify(upstream, times(2)).fetchWindow(any(), any());
    }

    @Test
    void frontierAdvancesPastDaysTheUpstreamOmitted() {
        LocalDate omitted = FLOOR.plusDays(3);
        MetalsDevService upstream = mock(MetalsDevService.class);
        when(upstream.fetchWindow(any(), any())).thenAnswer(inv -> {
            Map<String, HistoryDay> window = fullWindow(inv.getArgument(0), inv.getArgument(1));
            window.remove(omitted.toString()); // jour publié incomplet, omis par fetchWindow
            return window;
        });
        HistoryStore store = new HistoryStore(upstream, false);

        HistoryStore.Served first = store.getRange(FLOOR, FLOOR.plusDays(10));
        HistoryStore.Served second = store.getRange(FLOOR, FLOOR.plusDays(10));

        assertThat(first.days()).hasSize(10).doesNotContainKey(omitted.toString());
        // Connu-absent : le trou ne déclenche jamais un nouvel appel upstream,
        // et le `end` servi couvre bien toute la plage demandée.
        assertThat(first.end()).isEqualTo(FLOOR.plusDays(10));
        assertThat(second.days()).isEqualTo(first.days());
        verify(upstream, times(1)).fetchWindow(any(), any());
    }

    @Test
    void tailFailureServesWhatIsCoveredAndCoolsDown() {
        MetalsDevService upstream = mock(MetalsDevService.class);
        when(upstream.fetchWindow(any(), any()))
            .thenAnswer(inv -> fullWindow(inv.getArgument(0), inv.getArgument(1)))
            .thenThrow(new UpstreamUnavailableException());
        HistoryStore store = new HistoryStore(upstream, false);

        store.getRange(FLOOR, FLOOR.plusDays(29)); // couvre la première fenêtre

        // La queue (jour le plus récent) échoue : on sert le couvert, `end` fait foi.
        HistoryStore.Served partial = store.getRange(FLOOR, FLOOR.plusDays(32));
        assertThat(partial.end()).isEqualTo(FLOOR.plusDays(29));
        assertThat(partial.days()).hasSize(30);

        // Refroidissement : la requête suivante ne retente pas l'upstream.
        store.getRange(FLOOR, FLOOR.plusDays(32));
        verify(upstream, times(2)).fetchWindow(any(), any());
    }

    @Test
    void coldStoreWithDeadUpstreamEndsUpHonest() throws Exception {
        MetalsDevService upstream = mock(MetalsDevService.class);
        when(upstream.fetchWindow(any(), any())).thenThrow(new UpstreamUnavailableException());
        HistoryStore store = new HistoryStore(upstream, false);
        LocalDate end = FLOOR.plusDays(200);

        // Premier appel : le réchauffage démarre réellement -> HISTORY_WARMING.
        assertThatThrownBy(() -> store.getRange(FLOOR, end))
            .isInstanceOf(HistoryWarmingException.class);

        // Le réchauffage échoue vite ; ensuite, magasin froid + upstream mort
        // = panne annoncée comme telle, jamais un « réessayez » perpétuel (C1).
        Class<?> finalFailure = null;
        for (int i = 0; i < 100 && finalFailure == null; i++) {
            try {
                store.getRange(FLOOR, end);
            } catch (HistoryWarmingException e) {
                Thread.sleep(50);
            } catch (UpstreamUnavailableException e) {
                finalFailure = e.getClass();
            }
        }
        assertThat(finalFailure).isEqualTo(UpstreamUnavailableException.class);
    }

    @Test
    void longGapWarmsInBackgroundThenServesWhole() throws Exception {
        MetalsDevService upstream = fullUpstream();
        HistoryStore store = new HistoryStore(upstream, false);
        LocalDate end = FLOOR.plusDays(200); // ~7 fenêtres : jamais pendant une requête

        try {
            store.getRange(FLOOR, end); // déclenche le réchauffage ; peut déjà servir partiel
        } catch (HistoryWarmingException expectedWhenCold) {
            // rien à servir tant que la première fenêtre n'est pas arrivée
        }

        // Le réchauffage avance jusqu'à hier UTC ; on attend qu'il dépasse la
        // plage demandée (mock instantané, garde-fou 5 s). Pendant ce temps,
        // les appels servent un partiel dont le `end` fait foi.
        HistoryStore.Served served = null;
        for (int i = 0; i < 100; i++) {
            try {
                served = store.getRange(FLOOR, end);
                if (served.end().equals(end)) break;
            } catch (HistoryWarmingException stillCold) {
                // premier tour éventuel
            }
            Thread.sleep(50);
        }
        assertThat(served).isNotNull();
        assertThat(served.end()).isEqualTo(end);
        assertThat(served.days()).hasSize(201);
    }
}
