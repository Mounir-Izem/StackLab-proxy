package app.stacklab.proxy.service;

/**
 * Levée quand metals.dev est injoignable ou répond en erreur.
 *
 * Message FIXE et sans cause chaînée volontairement : les exceptions levées
 * par le RestClient de Spring embarquent l'URL complète appelée, API key
 * comprise (query param api_key=...). Ne jamais laisser leur message brut
 * remonter dans une réponse HTTP ou un log — voir MetalsDevService.callMetalsDev.
 */
public class UpstreamUnavailableException extends RuntimeException {
    public UpstreamUnavailableException() {
        super("metals.dev unavailable");
    }
}
