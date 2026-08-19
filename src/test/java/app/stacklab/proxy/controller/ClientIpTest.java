package app.stacklab.proxy.controller;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La décision de sécurité X-Forwarded-For (dernier élément seulement, celui
 * que Render a réellement constaté) vit dans un seul fichier ; ce test la
 * fige indépendamment des contrôleurs qui la consomment.
 */
class ClientIpTest {

    @Test
    void lastForwardedForElementWinsTrimmed() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "6.6.6.6, 203.0.113.9, 198.51.100.7 ");
        assertThat(ClientIp.from(request)).isEqualTo("198.51.100.7");
    }

    @Test
    void withoutHeaderFallsBackToRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.1.2.3");
        assertThat(ClientIp.from(request)).isEqualTo("10.1.2.3");
    }
}
