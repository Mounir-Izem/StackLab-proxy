package app.stacklab.proxy.service;

import app.stacklab.proxy.model.LastKnown;
import app.stacklab.proxy.model.MetalsDevResponse;
import app.stacklab.proxy.model.PricesResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MetalsDevService {

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
        boolean fromCache = isCacheValid();
        if (!fromCache) {
            MetalsDevResponse fresh = callMetalsDev();
            cache = fresh;
            cacheUpdatedAt = Instant.now();
        }
        return toResponse(cache, currency, fromCache);
    }

    public LastKnown getLastKnown(String currency) {
        if (cache == null) return null;
        double gold = convertPrice(cache.metals().get("gold"), currency, cache.currencies());
        double silver = convertPrice(cache.metals().get("silver"), currency, cache.currencies());
        return new LastKnown(gold, silver, currency, cache.timestamps().metal());
    }

    private boolean isCacheValid() {
        return cache != null && cacheUpdatedAt != null &&
               Duration.between(cacheUpdatedAt, Instant.now()).toMinutes() < cacheTtlMinutes;
    }

    private MetalsDevResponse callMetalsDev() {
        return restClient.get()
                .uri(baseUrl + "/latest?api_key={key}&currency=USD&unit=toz", apiKey)
                .retrieve()
                .body(MetalsDevResponse.class);
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
