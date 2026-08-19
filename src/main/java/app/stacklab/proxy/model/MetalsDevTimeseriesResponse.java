package app.stacklab.proxy.model;

import java.util.Map;


public record MetalsDevTimeseriesResponse(
    String status,
    Map<String, MetalsDevTimeseriesDay> rates
) {}
