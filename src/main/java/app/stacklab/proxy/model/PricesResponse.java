package app.stacklab.proxy.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record PricesResponse(
    double gold,
    double silver,
    String currency,
    @JsonProperty("updated_at") String updatedAt,
    String source,
    boolean cached,
    Map<String, Double> rates
) {}
