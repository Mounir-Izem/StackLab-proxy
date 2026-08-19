package app.stacklab.proxy.model;

import java.util.Map;


public record HistoryResponse(
    String start,
    String end,
    String currency,
    String source,
    Map<String, HistoryDay> days
) {}
