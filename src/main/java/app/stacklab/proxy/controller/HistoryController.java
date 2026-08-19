package app.stacklab.proxy.controller;

import app.stacklab.proxy.model.ErrorResponse;
import app.stacklab.proxy.model.HistoryResponse;
import app.stacklab.proxy.ratelimit.RateLimiter;
import app.stacklab.proxy.service.MetalsDevService;
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
import java.time.temporal.ChronoUnit;

/**
 * Historique spot (metals.dev Timeseries) : passthrough sans cache proxy.
 * Seuls les jours RÉGLÉS sont servis (≤ hier UTC) — le jour en cours vient
 * du flux vivant (/prices), jamais d'ici : c'est cette borne qui rend
 * l'historique réellement immuable, donc gravable côté client (spot_history)
 * sans jamais être redemandé. La mémoire du proxy reste réservée au spot vivant.
 */
@RestController
public class HistoryController {

    private static final Logger log = LoggerFactory.getLogger(HistoryController.class);

    /** Vérifié empiriquement (2026-08-19) : avant cette date, Timeseries répond rates=null. */
    private static final LocalDate HISTORY_FLOOR = LocalDate.of(2017, 1, 1);
    /** Fenêtre maximale Timeseries : 31 jours inclusifs (janvier 2017 entier vérifié). */
    private static final int MAX_RANGE_DAYS = 31;

    private final MetalsDevService metalsDevService;
    private final RateLimiter rateLimiter;

    public HistoryController(MetalsDevService metalsDevService, RateLimiter rateLimiter) {
        this.metalsDevService = metalsDevService;
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
        if (ChronoUnit.DAYS.between(startDate, endDate) + 1 > MAX_RANGE_DAYS) {
            return badRequest("Range must cover at most 31 days", "RANGE_TOO_LARGE");
        }
        if (startDate.isBefore(HISTORY_FLOOR)) {
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
            HistoryResponse response = metalsDevService.getHistory(startDate, endDate);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            // Pas de last_known ici : sans cache proxy il n'existe aucun secours
            // honnête, et un historique approximé serait une donnée fausse que le
            // client graverait définitivement dans spot_history.
            // Classe seule au log — le message RestClient peut contenir l'URL
            // upstream, api_key incluse.
            log.warn("history {}..{} failed: {}", start, end, e.getClass().getSimpleName());
            return ResponseEntity.status(503).body(
                new ErrorResponse("Spot history temporarily unavailable", "HISTORY_UNAVAILABLE", null));
        }
    }

    private ResponseEntity<ErrorResponse> badRequest(String message, String code) {
        return ResponseEntity.status(400).body(new ErrorResponse(message, code, null));
    }
}
