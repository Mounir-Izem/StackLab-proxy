package app.stacklab.proxy.service;

import app.stacklab.proxy.model.HistoryDay;
import app.stacklab.proxy.model.LastKnown;
import app.stacklab.proxy.model.MetalsDevResponse;
import app.stacklab.proxy.model.MetalsDevTimeseriesDay;
import app.stacklab.proxy.model.MetalsDevTimeseriesResponse;
import app.stacklab.proxy.model.PricesResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class MetalsDevService {

    private static final Logger log = LoggerFactory.getLogger(MetalsDevService.class);

    @Value("${metals-dev.api-key}")
    private String apiKey;

    @Value("${metals-dev.base-url}")
    private String baseUrl;

    @Value("${metals-dev.cache-ttl-minutes}")
    private int cacheTtlMinutes;

    private final RestClient restClient;

    private volatile MetalsDevResponse cache;
    private volatile Instant cacheUpdatedAt;

    public MetalsDevService(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    public PricesResponse getPrices(String currency) {
        boolean triggeredFetch = false;
        if (!isCacheValid()) {
            // Verrou anti-ruée : sur cache froid, deux requêtes simultanées ne
            // doivent déclencher qu'un seul appel upstream. Double-check à
            // l'entrée du bloc synchronized pour ne pas re-fetcher si un thread
            // concurrent vient de rafraîchir le cache pendant l'attente du verrou.
            synchronized (this) {
                if (!isCacheValid()) {
                    cache = callMetalsDev();
                    cacheUpdatedAt = Instant.now();
                    triggeredFetch = true;
                }
            }
        }
        return toResponse(cache, currency, !triggeredFetch);
    }

    /**
     * Une fenêtre Timeseries (≤ 31 jours inclusifs), réduite au besoin de
     * l'app. Aucun état ici : le stockage vit dans {@link HistoryStore}
     * (décision P-1b) ; le cache mémoire de ce service reste réservé au spot
     * vivant.
     */
    public Map<String, HistoryDay> fetchWindow(LocalDate start, LocalDate end) {
        MetalsDevTimeseriesResponse raw = callTimeseries(start, end);
        if (raw == null || !"success".equals(raw.status()) || raw.rates() == null || raw.rates().isEmpty()) {
            // metals.dev signale ses pannes DANS le corps (status=failure :
            // quota 1203, clé 1101, plan 1201). Un résultat vide serait un
            // mensonge que le client graverait comme un fait (« pas de spot »).
            // Le status est sûr à logger — jamais le corps entier ni l'URL.
            log.warn("metals.dev timeseries unusable: status={}", raw == null ? "no-body" : raw.status());
            throw new UpstreamUnavailableException();
        }
        if (!raw.rates().containsKey(end.toString())) {
            // Fenêtre tronquée : la dernière date demandée n'est pas dans la
            // réponse (jour pas encore publié, ou borne de fenêtre plus basse
            // que prévu). L'accepter marquerait ces jours couverts à tort —
            // on échoue, la frontière n'avance pas, on redemandera plus tard.
            // Fait empirique owner : une plage publiée n'a pas de trous.
            log.warn("metals.dev timeseries window truncated");
            throw new UpstreamUnavailableException();
        }
        Map<String, HistoryDay> days = new TreeMap<>();
        raw.rates().forEach((date, day) -> {
            HistoryDay mapped = toHistoryDay(day);
            if (mapped != null) days.put(date, mapped);
        });
        return days;
    }

    public LastKnown getLastKnown(String currency) {
        if (cache == null) return null;
        double gold = convertPrice(cache.metals().get("gold"), currency, cache.currencies());
        double silver = convertPrice(cache.metals().get("silver"), currency, cache.currencies());
        return new LastKnown(gold, silver, currency, cache.timestamps().metal());
    }

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final LocalTime MARKET_CLOSE  = LocalTime.of(17, 0); // vendredi 17h00 ET
    private static final LocalTime MARKET_REOPEN = LocalTime.of(18, 0); // dimanche 18h00 ET

    private boolean isMarketClosed() {
        ZonedDateTime now = ZonedDateTime.now(NEW_YORK);
        DayOfWeek day = now.getDayOfWeek();
        LocalTime time = now.toLocalTime();
        return day == DayOfWeek.SATURDAY
            || day == DayOfWeek.SUNDAY && time.isBefore(MARKET_REOPEN)
            || day == DayOfWeek.FRIDAY && !time.isBefore(MARKET_CLOSE);
    }

    private boolean isCacheValid() {
        if (cache == null || cacheUpdatedAt == null) return false;
        if (isMarketClosed()) return true;
        return Duration.between(cacheUpdatedAt, Instant.now()).toMinutes() < cacheTtlMinutes;
    }

    private MetalsDevResponse callMetalsDev() {
        try {
            return restClient.get()
                    .uri(baseUrl + "/latest?api_key={key}&currency=USD&unit=toz", apiKey)
                    .retrieve()
                    .body(MetalsDevResponse.class);
        } catch (Exception e) {
            // Les exceptions levées par le RestClient embarquent l'URL complète
            // appelée, API key en clair dans le query param : ne jamais logger
            // ni propager leur message brut, ni chaîner la cause.
            log.warn("metals.dev unreachable: {}", e.getClass().getSimpleName());
            throw new UpstreamUnavailableException();
        }
    }

    private MetalsDevTimeseriesResponse callTimeseries(LocalDate start, LocalDate end) {
        try {
            return restClient.get()
                    .uri(baseUrl + "/timeseries?api_key={key}&start_date={start}&end_date={end}&currency=USD&unit=toz",
                            apiKey, start.toString(), end.toString())
                    .retrieve()
                    .body(MetalsDevTimeseriesResponse.class);
        } catch (Exception e) {
            // Même piège que callMetalsDev : le message d'exception embarque
            // l'URL complète, API key incluse — classe seule au log, exception
            // neutre sans cause chaînée.
            log.warn("metals.dev timeseries unreachable: {}", e.getClass().getSimpleName());
            throw new UpstreamUnavailableException();
        }
    }

    /**
     * Un jour incomplet (or, argent OU l'un des quatre taux absent) n'est pas
     * restitué du tout : jour présent = jour complet. Le client grave chaque
     * jour reçu définitivement dans spot_history sans jamais le redemander —
     * un jour partiel produirait une prime calculée au mauvais taux, présentée
     * comme un fait de registre.
     */
    private HistoryDay toHistoryDay(MetalsDevTimeseriesDay day) {
        if (day == null || day.metals() == null || day.currencies() == null) return null;
        Double gold = day.metals().get("gold");
        Double silver = day.metals().get("silver");
        if (gold == null || silver == null) return null;

        Map<String, Double> rates = new HashMap<>();
        for (String c : List.of("EUR", "GBP", "CAD", "AUD")) {
            Double rate = day.currencies().get(c);
            if (rate == null) return null;
            rates.put(c, rate);
        }
        return new HistoryDay(gold, silver, rates);
    }

    private PricesResponse toResponse(MetalsDevResponse raw, String currency, boolean cached) {
        double gold = convertPrice(raw.metals().get("gold"), currency, raw.currencies());
        double silver = convertPrice(raw.metals().get("silver"), currency, raw.currencies());

        Map<String, Double> rates = new HashMap<>();
        for (String c : List.of("EUR", "GBP", "CAD", "AUD")) {
            Double rate = raw.currencies().get(c);
            if (rate != null) rates.put(c, rate);
        }

        return new PricesResponse(gold, silver, currency, raw.timestamps().metal(), "metals.dev", cached, rates);
    }

    private double convertPrice(double usdPrice, String currency, Map<String, Double> rates) {
        if ("USD".equals(currency)) return usdPrice;
        Double rate = rates.get(currency);
        if (rate == null) throw new IllegalArgumentException("Unsupported currency: " + currency);
        return usdPrice / rate;
    }
}
