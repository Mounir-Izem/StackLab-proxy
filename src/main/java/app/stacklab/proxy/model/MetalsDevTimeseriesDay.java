package app.stacklab.proxy.model;

import java.util.Map;


public record MetalsDevTimeseriesDay(
    Map<String, Double> metals,
    Map<String, Double> currencies
) {}
