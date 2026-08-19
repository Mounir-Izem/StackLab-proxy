package app.stacklab.proxy.config;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;

import java.time.Duration;

/**
 * Timeouts upstream (metals.dev). Sans eux, un appel qui pend bloque le thread
 * indéfiniment — un seul fournisseur lent suffit à geler le proxy sous charge.
 *
 * Appliqué via un {@link RestClientCustomizer} plutôt qu'en dur dans
 * MetalsDevService : le customizer ne s'applique qu'au RestClient.Builder
 * auto-configuré par Spring en production, et laisse les builders "nus"
 * utilisés en test (MockRestServiceServer) intacts.
 */
@Configuration
public class RestClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(8);

    @Bean
    RestClientCustomizer timeoutCustomizer() {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(CONNECT_TIMEOUT)
                .withReadTimeout(READ_TIMEOUT);
        ClientHttpRequestFactory factory = ClientHttpRequestFactoryBuilder.detect().build(settings);
        return builder -> builder.requestFactory(factory);
    }
}
