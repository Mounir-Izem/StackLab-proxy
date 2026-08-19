package app.stacklab.proxy.controller;

import app.stacklab.proxy.model.ErrorResponse;
import app.stacklab.proxy.model.HistoryResponse;
import app.stacklab.proxy.ratelimit.RateLimiter;
import app.stacklab.proxy.service.HistoryStore;
import app.stacklab.proxy.service.HistoryWarmingException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * Historique spot, servi depuis le gardien ({@link HistoryStore}) : n'importe
 * quelle plage en une requête, y compris 2017 → hier. Seuls les jours RÉGLÉS
 * sont servis (≤ hier UTC) — le jour en cours vient du flux vivant (/prices),
 * jamais d'ici : c'est cette borne qui rend l'historique réellement immuable,
 * donc gravable côté client (spot_history) sans jamais être redemandé.
 */
@RestController
public class HistoryController {

    private static final Logger log = LoggerFactory.getLogger(HistoryController.class);

    private final HistoryStore historyStore;
    private final RateLimiter rateLimiter;

    public HistoryController(HistoryStore historyStore, RateLimiter rateLimiter) {
        this.historyStore = historyStore;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/history")
    public ResponseEntity<?> getHistory(
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            HttpServletRequest request) {
        if (start == null || end == null) {
            return badRequest("start and end are required (YYYY-MM-DD)", "INVALID_DATE");
        }
        LocalDate startDate;
        LocalDate endDate;
        try {
            startDate = LocalDate.parse(start);
            endDate = LocalDate.parse(end);
        } catch (DateTimeParseException e) {
            return badRequest("Malformed date, expected YYYY-MM-DD", "INVALID_DATE");
        }
        if (startDate.isAfter(endDate)) {
            return badRequest("start must be on or before end", "INVALID_RANGE");
        }
        if (startDate.isBefore(HistoryStore.HISTORY_FLOOR)) {
            return badRequest("No spot history before 2017-01-01", "BEFORE_HISTORY_START");
        }
        if (endDate.isAfter(LocalDate.now(ZoneOffset.UTC).minusDays(1))) {
            return badRequest("end must be a settled day (yesterday UTC or earlier); today's spot comes from /prices",
                "END_NOT_SETTLED");
        }
        if (!rateLimiter.allow(ClientIp.from(request))) {
            return ResponseEntity.status(429).body(
                new ErrorResponse("Too many requests", "RATE_LIMITED", null));
        }
        try {
            HistoryStore.Served served = historyStore.getRange(startDate, endDate);
            // Le `end` de la réponse est la borne réellement servie : elle peut
            // être antérieure au `end` demandé (jour le plus récent pas encore
            // publié, constitution en cours). Le client grave ce qu'il reçoit
            // et redemande le delta plus tard.
            return ResponseEntity.ok(
                new HistoryResponse(start, served.end().toString(), "USD", "metals.dev", served.days()));
        } catch (HistoryWarmingException e) {
            // Une constitution est RÉELLEMENT en cours (le magasin le garantit —
            // panne upstream à froid => HISTORY_UNAVAILABLE, jamais ce code).
            return ResponseEntity.status(503).body(
                new ErrorResponse("Spot history is being assembled, retry shortly", "HISTORY_WARMING", null));
        } catch (Exception e) {
            // Pas de last_known : un historique approximé serait une donnée
            // fausse gravée définitivement côté client. Classe seule au log —
            // le message RestClient peut contenir l'URL upstream (api_key), et
            // la plage demandée ne se journalise pas (PRIVACY_MATRIX).
            log.warn("history request failed: {}", e.getClass().getSimpleName());
            return ResponseEntity.status(503).body(
                new ErrorResponse("Spot history temporarily unavailable", "HISTORY_UNAVAILABLE", null));
        }
    }

    private ResponseEntity<ErrorResponse> badRequest(String message, String code) {
        return ResponseEntity.status(400).body(new ErrorResponse(message, code, null));
    }
}
