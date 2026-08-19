package app.stacklab.proxy.model;

import java.util.Map;


public record HistoryDay(
    double gold,
    double silver,
    Map<String, Double> rates
) {}
