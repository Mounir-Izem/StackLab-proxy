package app.stacklab.proxy.controller;

import app.stacklab.proxy.model.ErrorResponse;
import app.stacklab.proxy.model.LastKnown;
import app.stacklab.proxy.model.PricesResponse;
import app.stacklab.proxy.ratelimit.RateLimiter;
import app.stacklab.proxy.service.MetalsDevService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

@RestController
public class PricesController {

    private static final Set<String> SUPPORTED_CURRENCIES = Set.of("USD", "EUR", "GBP", "CAD", "AUD");

    private final MetalsDevService metalsDevService;
    private final RateLimiter rateLimiter;

    public PricesController(MetalsDevService metalsDevService, RateLimiter rateLimiter) {
        this.metalsDevService = metalsDevService;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/prices")
    public ResponseEntity<?> getPrices(
            @RequestParam(defaultValue = "USD") String currency,
            HttpServletRequest request) {
        if (!SUPPORTED_CURRENCIES.contains(currency)) {
            return ResponseEntity.status(400).body(
                new ErrorResponse("Unsupported currency", "UNSUPPORTED_CURRENCY", null));
        }
        if (!rateLimiter.allow(ClientIp.from(request))) {
            return ResponseEntity.status(429).body(
                new ErrorResponse("Too many requests", "RATE_LIMITED", null));
        }
        try {
            PricesResponse response = metalsDevService.getPrices(currency);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            // Le secours ne doit jamais faire pire que la panne : si le cache
            // upstream est incomplet (devise whitelistée absente de `currencies`),
            // getLastKnown lèverait ici même — un 503 propre deviendrait un 500
            // brut. Un lastKnown null est une réponse honnête ; une exception
            // dans le chemin de secours n'en est pas une.
            LastKnown lastKnown;
            try {
                lastKnown = metalsDevService.getLastKnown(currency);
            } catch (Exception ignored) {
                lastKnown = null;
            }
            ErrorResponse error = new ErrorResponse(
                "Spot prices temporarily unavailable",
                "SPOT_UNAVAILABLE",
                lastKnown
            );
            return ResponseEntity.status(503).body(error);
        }
    }

    /** Cible du pinger anti-veille (Render) : ne touche ni metals.dev ni le cache. */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
