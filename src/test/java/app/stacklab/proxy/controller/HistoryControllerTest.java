package app.stacklab.proxy.controller;

import app.stacklab.proxy.ratelimit.RateLimiter;
import app.stacklab.proxy.service.HistoryStore;
import app.stacklab.proxy.service.MetalsDevService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Couvre les lots P-1/P-1b : validations -> 400 typés AVANT toute
 * consommation de jeton, réduction de la réponse (or/argent USD + 4 devises,
 * jamais le reste), rate limiting partagé -> 429, panne upstream -> 503
 * sans last_known, et le gardien (P-1b) : une fenêtre n'est demandée qu'une
 * fois à l'upstream, un trou long répond 503 HISTORY_WARMING.
 */
class HistoryControllerTest {

    /** Forme réelle Timeseries (vérifiée owner 2026-01) : métaux et devises
     *  surnuméraires présents pour prouver la réduction de la réponse. */
    private static final String TIMESERIES_JSON = """
        {"status":"success","currency":"USD","unit":"toz",
         "start_date":"2017-01-01","end_date":"2017-01-31",
         "rates":{
           "2017-01-01":{"date":"2017-01-01",
             "metals":{"gold":1151.9808,"silver":15.9287,"palladium":681.4682},
             "currencies":{"EUR":1.053287,"GBP":1.234368,"CAD":0.744242,"AUD":0.721527,"BRL":0.307317,"USD":1}},
           "2017-01-02":{"date":"2017-01-02",
             "metals":{"gold":1150.5097,"silver":15.971},
             "currencies":{"EUR":1.045834,"GBP":1.227839,"CAD":0.744045,"AUD":0.719025}}
         }}
        """;

    private MetalsDevService newService(RestClient.Builder builder, String baseUrl) {
        MetalsDevService service = new MetalsDevService(builder);
        ReflectionTestUtils.setField(service, "cacheTtlMinutes", 60);
        ReflectionTestUtils.setField(service, "baseUrl", baseUrl);
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        return service;
    }

    private MockMvc mockMvc(MetalsDevService service) {
        // warmup-on-boot=false : les tests contrôlent chaque remplissage.
        HistoryController controller = new HistoryController(new HistoryStore(service, false, 0), new RateLimiter());
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    /** localhost:1 refuse la connexion : tout appel upstream échoue immédiatement. */
    private MockMvc unreachableUpstreamMvc() {
        return mockMvc(newService(RestClient.builder(), "http://localhost:1"));
    }

    @Test
    void missingParamsReturn400() throws Exception {
        unreachableUpstreamMvc().perform(get("/history"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_DATE"));
    }

    @Test
    void malformedDateReturns400() throws Exception {
        unreachableUpstreamMvc().perform(get("/history")
                .param("start", "2017-1-1").param("end", "2017-01-30"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_DATE"));
    }

    @Test
    void startAfterEndReturns400() throws Exception {
        unreachableUpstreamMvc().perform(get("/history")
                .param("start", "2017-02-01").param("end", "2017-01-01"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_RANGE"));
    }

    @Test
    void coldStoreLongGapReturnsWarmingNotAnEndlessWait() throws Exception {
        // P-1b : plus de plafond de plage — mais un magasin froid ne fait pas
        // attendre la requête pendant ~117 fenêtres : 503 HISTORY_WARMING,
        // le réchauffage part en fond, le client réessaie.
        unreachableUpstreamMvc().perform(get("/history")
                .param("start", "2017-01-01").param("end", "2017-06-30"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("HISTORY_WARMING"));
    }

    @Test
    void startBeforeHistoryFloorReturns400() throws Exception {
        unreachableUpstreamMvc().perform(get("/history")
                .param("start", "2016-12-31").param("end", "2017-01-15"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BEFORE_HISTORY_START"));
    }

    @Test
    void endTodayOrLaterReturns400OnlySettledDaysAreServed() throws Exception {
        MockMvc mockMvc = unreachableUpstreamMvc();
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        mockMvc.perform(get("/history").param("start", today).param("end", today))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("END_NOT_SETTLED"));

        // La borne exacte : hier UTC passe les validations — le 503 vient du
        // magasin froid (HISTORY_WARMING), pas d'un refus de validation.
        String yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();
        mockMvc.perform(get("/history").param("start", yesterday).param("end", yesterday))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("HISTORY_WARMING"));
    }

    @Test
    void windowPassesThroughReducedToAppNeeds() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), anything())
            .andRespond(withSuccess(TIMESERIES_JSON, MediaType.APPLICATION_JSON));
        MockMvc mockMvc = mockMvc(newService(builder, "http://fake"));

        mockMvc.perform(get("/history")
                .param("start", "2017-01-01").param("end", "2017-01-02"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currency").value("USD"))
            .andExpect(jsonPath("$.source").value("metals.dev"))
            .andExpect(jsonPath("$.end").value("2017-01-02")) // borne servie = borne demandée
            .andExpect(jsonPath("$.days['2017-01-01'].gold").value(1151.9808))
            .andExpect(jsonPath("$.days['2017-01-01'].silver").value(15.9287))
            .andExpect(jsonPath("$.days['2017-01-01'].rates.EUR").value(1.053287))
            .andExpect(jsonPath("$.days['2017-01-01'].rates.AUD").value(0.721527))
            .andExpect(jsonPath("$.days['2017-01-01'].rates.BRL").doesNotExist())
            .andExpect(jsonPath("$.days['2017-01-01'].rates.USD").doesNotExist())
            .andExpect(jsonPath("$.days['2017-01-02'].gold").value(1150.5097));

        server.verify();
    }

    @Test
    void truncatedWindowReturns503RatherThanPhantomCoverage() throws Exception {
        // L'upstream répond success mais SANS la dernière date demandée :
        // l'accepter marquerait ces jours couverts à tort (revue P-1b, C2).
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), anything())
            .andRespond(withSuccess(TIMESERIES_JSON, MediaType.APPLICATION_JSON));
        mockMvc(newService(builder, "http://fake"))
            .perform(get("/history").param("start", "2017-01-01").param("end", "2017-01-03"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("HISTORY_UNAVAILABLE"));
    }

    @Test
    void successWithEmptyRatesReturns503() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), anything())
            .andRespond(withSuccess("{\"status\":\"success\",\"rates\":{}}", MediaType.APPLICATION_JSON));
        mockMvc(newService(builder, "http://fake"))
            .perform(get("/history").param("start", "2017-01-01").param("end", "2017-01-02"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("HISTORY_UNAVAILABLE"));
    }

    @Test
    void secondIdenticalRequestIsServedFromTheStore() throws Exception {
        // P-1b : le gardien renverse l'ancien « pas de cache proxy » — une
        // fenêtre n'est demandée qu'UNE fois à l'upstream, quel que soit le
        // nombre d'appareils qui la lisent ensuite.
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), anything())
            .andRespond(withSuccess(TIMESERIES_JSON, MediaType.APPLICATION_JSON));
        MockMvc mockMvc = mockMvc(newService(builder, "http://fake"));

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/history")
                    .param("start", "2017-01-01").param("end", "2017-01-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days['2017-01-01'].gold").value(1151.9808));
        }
        server.verify(); // échoue si un second appel upstream a été tenté
    }

    @Test
    void upstreamBodyFailureReturns503NeverAnEmpty200() throws Exception {
        // metals.dev signale quota/clé/plan DANS le corps (status=failure) :
        // le proxy doit refuser, jamais rendre un 200 aux days vides que le
        // client graverait comme « pas de spot pour ces dates ».
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), anything())
            .andRespond(withSuccess(
                "{\"status\":\"failure\",\"error_code\":1203,\"error_message\":\"quota\"}",
                MediaType.APPLICATION_JSON));
        mockMvc(newService(builder, "http://fake"))
            .perform(get("/history").param("start", "2017-01-01").param("end", "2017-01-02"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("HISTORY_UNAVAILABLE"));
    }

    @Test
    void successWithNullRatesReturns503() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), anything())
            .andRespond(withSuccess("{\"status\":\"success\",\"rates\":null}", MediaType.APPLICATION_JSON));
        mockMvc(newService(builder, "http://fake"))
            .perform(get("/history").param("start", "2017-01-01").param("end", "2017-01-02"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("HISTORY_UNAVAILABLE"));
    }

    @Test
    void incompleteDayIsDroppedEntirely() throws Exception {
        // Jour présent = jour complet : un jour sans AUD disparaît plutôt que
        // d'être gravé partiel côté client (prime au mauvais taux sinon).
        String json = """
            {"status":"success","rates":{
              "2017-01-01":{"metals":{"gold":1151.98,"silver":15.93},
                "currencies":{"EUR":1.053,"GBP":1.234,"CAD":0.744,"AUD":0.721}},
              "2017-01-02":{"metals":{"gold":1150.51,"silver":15.97},
                "currencies":{"EUR":1.046,"GBP":1.228,"CAD":0.744}}
            }}
            """;
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), anything())
            .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
        mockMvc(newService(builder, "http://fake"))
            .perform(get("/history").param("start", "2017-01-01").param("end", "2017-01-02"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.end").value("2017-01-02")) // servi : le jour omis est connu-absent
            .andExpect(jsonPath("$.days['2017-01-01'].gold").value(1151.98))
            .andExpect(jsonPath("$.days['2017-01-02']").doesNotExist());
    }

    @Test
    void upstreamFailureReturns503WithoutLastKnown() throws Exception {
        unreachableUpstreamMvc().perform(get("/history")
                .param("start", "2017-01-01").param("end", "2017-01-30"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("HISTORY_UNAVAILABLE"))
            .andExpect(jsonPath("$.last_known").doesNotExist());
    }

    @Test
    void hammeringPastBurstReturns429() throws Exception {
        MockMvc mockMvc = unreachableUpstreamMvc();
        // 10 requêtes valides consomment la rafale (elles échouent en 503,
        // mais le jeton est bien consommé avant l'appel upstream).
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/history")
                    .param("start", "2017-01-01").param("end", "2017-01-30"))
                .andExpect(status().isServiceUnavailable());
        }
        mockMvc.perform(get("/history")
                .param("start", "2017-01-01").param("end", "2017-01-30"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void historyBurnsTheSameBucketAsPrices() throws Exception {
        // « Mêmes seaux » est une promesse du dossier : le limiteur est partagé
        // entre les deux endpoints — un remplissage d'historique consomme le
        // budget du spot vivant (conséquence assumée, voir chantier P-1b).
        RateLimiter shared = new RateLimiter();
        MetalsDevService service = newService(RestClient.builder(), "http://localhost:1");
        MockMvc history = MockMvcBuilders.standaloneSetup(
            new HistoryController(new HistoryStore(service, false, 0), shared)).build();
        MockMvc prices = MockMvcBuilders.standaloneSetup(new PricesController(service, shared)).build();

        for (int i = 0; i < 10; i++) {
            history.perform(get("/history").param("start", "2017-01-01").param("end", "2017-01-30"))
                .andExpect(status().isServiceUnavailable());
        }
        prices.perform(get("/prices").param("currency", "USD"))
            .andExpect(status().isTooManyRequests());
    }

    @Test
    void forgedForwardedForPrefixCannotEvadeTheLimit() throws Exception {
        // Le premier élément de X-Forwarded-For est forgeable ; seul le dernier
        // (posé par Render) identifie. Tourner le préfixe ne crée pas de seau neuf.
        MockMvc mockMvc = unreachableUpstreamMvc();
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/history")
                    .param("start", "2017-01-01").param("end", "2017-01-30")
                    .header("X-Forwarded-For", "203.0.113." + i + ", 198.51.100.7"))
                .andExpect(status().isServiceUnavailable());
        }
        mockMvc.perform(get("/history")
                .param("start", "2017-01-01").param("end", "2017-01-30")
                .header("X-Forwarded-For", "203.0.113.99, 198.51.100.7"))
            .andExpect(status().isTooManyRequests());
    }

    @Test
    void invalidRequestsDoNotConsumeRateTokens() throws Exception {
        MockMvc mockMvc = unreachableUpstreamMvc();
        for (int i = 0; i < 15; i++) {
            mockMvc.perform(get("/history")
                    .param("start", "bad").param("end", "worse"))
                .andExpect(status().isBadRequest());
        }
        // La rafale de 10 est intacte : la requête valide passe le limiteur
        // (503 upstream, pas 429).
        mockMvc.perform(get("/history")
                .param("start", "2017-01-01").param("end", "2017-01-30"))
            .andExpect(status().isServiceUnavailable());
    }
}
