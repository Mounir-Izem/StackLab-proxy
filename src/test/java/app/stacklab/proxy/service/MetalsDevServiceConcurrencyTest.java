package app.stacklab.proxy.service;

import app.stacklab.proxy.model.PricesResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Vérifie le verrou anti-ruée de MetalsDevService.getPrices : sur cache
 * froid, deux appels concurrents ne doivent déclencher qu'un seul appel
 * réel vers metals.dev (l'autre attend le verrou puis réutilise le cache
 * fraîchement posé).
 */
class MetalsDevServiceConcurrencyTest {

    @Test
    void concurrentColdCacheTriggersSingleUpstreamCall() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        CountDownLatch release = new CountDownLatch(1);
        builder.requestInterceptor((request, body, execution) -> {
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return execution.execute(request, body);
        });
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String json = "{\"status\":\"success\",\"metals\":{\"gold\":2000.0,\"silver\":25.0},"
            + "\"currencies\":{\"EUR\":0.9},\"timestamps\":{\"metal\":\"t\",\"currency\":\"t\"}}";
        server.expect(ExpectedCount.once(), anything())
            .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        MetalsDevService service = new MetalsDevService(builder);
        ReflectionTestUtils.setField(service, "cacheTtlMinutes", 60);
        ReflectionTestUtils.setField(service, "baseUrl", "http://fake");
        ReflectionTestUtils.setField(service, "apiKey", "key");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<PricesResponse> task = () -> service.getPrices("USD");
        Future<PricesResponse> first = pool.submit(task);
        Future<PricesResponse> second = pool.submit(task);
        Thread.sleep(200); // laisse les deux threads atteindre getPrices() avant de libérer le call HTTP
        release.countDown();

        PricesResponse r1 = first.get(2, TimeUnit.SECONDS);
        PricesResponse r2 = second.get(2, TimeUnit.SECONDS);
        pool.shutdown();

        server.verify(); // échoue si un deuxième appel HTTP a été tenté
        assertThat(r1.gold()).isEqualTo(r2.gold());
        // un seul des deux threads a déclenché le fetch (cached=false),
        // l'autre a trouvé le cache déjà chaud en sortant du verrou.
        assertThat(r1.cached()).isNotEqualTo(r2.cached());
    }
}
