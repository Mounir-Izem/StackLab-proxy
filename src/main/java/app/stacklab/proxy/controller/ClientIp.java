package app.stacklab.proxy.controller;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Render est derrière un proxy TLS : l'IP vue par Render est AJOUTÉE en
 * FIN de X-Forwarded-For. Le premier élément est contrôlable par le client
 * (en-tête forgé) — le prendre permettrait de contourner la limite par IP
 * en tournant des adresses inventées. Le DERNIER élément est celui que
 * Render a réellement constaté.
 */
final class ClientIp {

    private ClientIp() {}

    static String from(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            String[] parts = forwardedFor.split(",");
            return parts[parts.length - 1].trim();
        }
        return request.getRemoteAddr();
    }
}
