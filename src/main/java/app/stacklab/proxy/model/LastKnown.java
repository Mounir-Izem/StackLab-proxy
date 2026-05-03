package app.stacklab.proxy.model;

import com.fasterxml.jackson.annotation.JsonProperty;


public record LastKnown(
    double gold,
    double silver,
    String currency,
    @JsonProperty("updated_at") String updatedAt
) {}