package app.stacklab.proxy.service;

/**
 * Le magasin d'historique n'a pas encore couvert la plage demandée et le trou
 * est trop long pour être rempli pendant la requête : le réchauffage part en
 * fond, le client reçoit 503 HISTORY_WARMING et réessaie. Message fixe —
 * jamais de détail upstream.
 */
public class HistoryWarmingException extends RuntimeException {
    public HistoryWarmingException() {
        super("history store warming");
    }
}
