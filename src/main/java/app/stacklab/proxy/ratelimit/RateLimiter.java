package app.stacklab.proxy.ratelimit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Rate limiting fait maison : seau à jetons en mémoire par IP (rafale 10,
 * 30 req/min) + un seau global partagé (300 req/min) qui plafonne le trafic
 * total quel que soit le nombre d'IP distinctes.
 *
 * Tradeoff assumé : le jeton IP est consommé avant de vérifier le seau
 * global ; si le global refuse, ce jeton IP n'est pas remboursé. Un appelant
 * dans son quota individuel peut donc perdre un jeton pour une requête
 * rejetée par le plafond global — acceptable pour un proxy à faible trafic,
 * évite un verrouillage à deux seaux pour un cas marginal.
 */
@Component
public class RateLimiter {

    private static final int PER_IP_CAPACITY = 10;
    private static final double PER_IP_REFILL_PER_MS = 30.0 / 60_000;
    private static final int GLOBAL_CAPACITY = 300;
    private static final double GLOBAL_REFILL_PER_MS = 300.0 / 60_000;
    private static final long EVICT_AFTER_MS = Duration.ofMinutes(10).toMillis();

    private final Map<String, Bucket> perIp = new ConcurrentHashMap<>();
    private final Bucket global = new Bucket(GLOBAL_CAPACITY, GLOBAL_REFILL_PER_MS);

    public boolean allow(String ip) {
        Bucket bucket = perIp.computeIfAbsent(ip, k -> new Bucket(PER_IP_CAPACITY, PER_IP_REFILL_PER_MS));
        return bucket.tryConsume() && global.tryConsume();
    }

    /** Purge les seaux IP inactifs depuis plus de 10 min pour ne pas fuir la mémoire. */
    @Scheduled(fixedRate = 5, timeUnit = TimeUnit.MINUTES)
    void evictStale() {
        long threshold = System.currentTimeMillis() - EVICT_AFTER_MS;
        perIp.entrySet().removeIf(entry -> entry.getValue().lastAccessMillis < threshold);
    }

    private static final class Bucket {
        private final double capacity;
        private final double refillPerMs;
        private double tokens;
        private long lastRefillMillis = System.currentTimeMillis();
        private volatile long lastAccessMillis = System.currentTimeMillis();

        Bucket(double capacity, double refillPerMs) {
            this.capacity = capacity;
            this.refillPerMs = refillPerMs;
            this.tokens = capacity;
        }

        synchronized boolean tryConsume() {
            long now = System.currentTimeMillis();
            tokens = Math.min(capacity, tokens + (now - lastRefillMillis) * refillPerMs);
            lastRefillMillis = now;
            lastAccessMillis = now;
            if (tokens < 1) return false;
            tokens -= 1;
            return true;
        }
    }
}
