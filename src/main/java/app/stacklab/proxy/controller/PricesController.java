package app.stacklab.proxy.controller;

import app.stacklab.proxy.model.ErrorResponse;
import app.stacklab.proxy.model.LastKnown;
import app.stacklab.proxy.model.PricesResponse;
import app.stacklab.proxy.service.MetalsDevService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PricesController {

    private final MetalsDevService metalsDevService;

    public PricesController(MetalsDevService metalsDevService) {
        this.metalsDevService = metalsDevService;
    }

    @GetMapping("/prices")
    public ResponseEntity<?> getPrices(
            @RequestParam(defaultValue = "USD") String currency) {
        try {
            PricesResponse response = metalsDevService.getPrices(currency);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LastKnown lastKnown = metalsDevService.getLastKnown(currency);
            ErrorResponse error = new ErrorResponse(
                "Spot prices temporarily unavailable",
                "SPOT_UNAVAILABLE",
                lastKnown
            );
            return ResponseEntity.status(503).body(error);
        }
    }
}
