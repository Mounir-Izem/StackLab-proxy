package app.stacklab.proxy.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;


@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
    String error,
    String code,
    @JsonProperty("last_known") LastKnown lastKnown
) {}