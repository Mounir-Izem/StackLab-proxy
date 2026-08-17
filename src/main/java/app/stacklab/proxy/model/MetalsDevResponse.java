package app.stacklab.proxy.model;

import java.util.Map;


public record MetalsDevResponse(
    String status,
    Map<String, Double> metals,
    Map<String, Double> currencies,
    MetalsDevTimestamps timestamps
) {}