package app.stacklab.proxy.controller;

import app.stacklab.proxy.model.MetalsDevResponse;
import app.stacklab.proxy.model.MetalsDevTimestamps;
import app.stacklab.proxy.ratelimit.RateLimiter;
import app.stacklab.proxy.service.MetalsDevService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Couvre le gate audit V2-P1 : devise invalide -> 400, conversion via cache,
 * rate limiting -> 429, panne upstream avec/sans cache -> 503, /health.
 *
 * MetalsDevService est instancié hors contexte Spring (pas de @SpringBootTest) :
 * le cache est peuplé directement par réflexion, ce qui évite toute dépendance
 * au jour/heure réel (isMarketClosed() lit l'horloge système) et à un vrai
 * réseau pour les scénarios qui n'ont pas besoin d'appeler metals.dev.
 */
class PricesControllerTest {

    private MetalsDevService newSeededService(Instant cacheUpdatedAt) {
        MetalsDevService service = new MetalsDevService(RestClient.builder());
        ReflectionTestUtils.setField(service, "cacheTtlMinutes", 60);
        ReflectionTestUtils.setField(service, "baseUrl", "http://localhost:1"); // connexion refusée immédiatement
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        MetalsDevResponse seed = new MetalsDevResponse(
            "success",
            Map.of("gold", 2000.0, "silver", 25.0),
            Map.of("EUR", 0.9, "GBP", 0.8, "CAD", 1.35, "AUD", 1.5),
            new MetalsDevTimestamps("2026-08-19T12:00:00Z", "2026-08-19T12:00:00Z")
        );
        ReflectionTestUtils.setField(service, "cache", seed);
        ReflectionTestUtils.setField(service, "cacheUpdatedAt", cacheUpdatedAt);
        return service;
    }

    private MockMvc mockMvc(MetalsDevService service) {
        PricesController controller = new PricesController(service, new RateLimiter());
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void invalidCurrencyReturns400() throws Exception {
        MockMvc mockMvc = mockMvc(newSeededService(Instant.now()));
        mockMvc.perform(get("/prices").param("currency", "XXX"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_CURRENCY"));
    }

    @Test
    void validNonUsdCurrencyConvertsUsingCache() throws Exception {
        MockMvc mockMvc = mockMvc(newSeededService(Instant.now()));
        mockMvc.perform(get("/prices").param("currency", "EUR"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currency").value("EUR"))
            .andExpect(jsonPath("$.gold").value(2000.0 / 0.9))
            .andExpect(jsonPath("$.cached").value(true));
    }

    @Test
    void hammeringPastBurstReturns429() throws Exception {
        MockMvc mockMvc = mockMvc(newSeededService(Instant.now()));
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/prices").param("currency", "USD")).andExpect(status().isOk());
        }
        mockMvc.perform(get("/prices").param("currency", "USD"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void upstreamErrorWithWarmCacheReturns503WithLastKnown() throws Exception {
        // cacheUpdatedAt = null => isCacheValid() false => un vrai fetch est
        // tenté, échoue (localhost:1 refuse la connexion) => le cache posé
        // directement (jamais écrasé par un fetch raté) reste exploitable.
        MockMvc mockMvc = mockMvc(newSeededService(null));
        mockMvc.perform(get("/prices").param("currency", "USD"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("SPOT_UNAVAILABLE"))
            .andExpect(jsonPath("$.last_known").exists())
            .andExpect(jsonPath("$.last_known.gold").value(2000.0));
    }

    @Test
    void upstreamErrorWithoutCacheReturns503WithNullLastKnown() throws Exception {
        MetalsDevService service = new MetalsDevService(RestClient.builder());
        ReflectionTestUtils.setField(service, "cacheTtlMinutes", 60);
        ReflectionTestUtils.setField(service, "baseUrl", "http://localhost:1");
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        MockMvc mockMvc = mockMvc(service);
        mockMvc.perform(get("/prices").param("currency", "USD"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("SPOT_UNAVAILABLE"))
            .andExpect(jsonPath("$.last_known").doesNotExist());
    }

    @Test
    void healthReturns200WithoutTouchingUpstream() throws Exception {
        MetalsDevService serviceMock = mock(MetalsDevService.class);
        PricesController controller = new PricesController(serviceMock, new RateLimiter());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"));

        verifyNoInteractions(serviceMock);
    }
}
