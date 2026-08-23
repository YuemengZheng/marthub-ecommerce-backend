package dev.yuemeng.marthub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "marthub")
public class MartHubProperties {
    private String instanceId = "local";
    private final Auth auth = new Auth();
    private final Cache cache = new Cache();
    private final FlashSale flashSale = new FlashSale();
    private final Benchmark benchmark = new Benchmark();
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public Auth getAuth() { return auth; }
    public Cache getCache() { return cache; }
    public FlashSale getFlashSale() { return flashSale; }
    public Benchmark getBenchmark() { return benchmark; }
    /**
     * Idle timeout is not here -- it is Spring Session's {@code spring.session.timeout}. This is
     * the one session policy the framework does not provide: the hard ceiling past which an active
     * session must re-authenticate anyway.
     */
    public static class Auth {
        private java.time.Duration absoluteLifetime=java.time.Duration.ofHours(12);
        public java.time.Duration getAbsoluteLifetime(){return absoluteLifetime;}
        public void setAbsoluteLifetime(java.time.Duration v){absoluteLifetime=v;}
    }
    public static class Cache {
        // l1TtlSeconds is deliberately short: cross-instance L1 invalidation rides on Redis
        // pub/sub, which is fire-and-forget, so TTL -- not the broadcast -- is what bounds how
        // long a missed instance can serve stale data. Hot keys are read every second, so a
        // 10s TTL costs almost no hit rate.
        private long l1MaxSize=10000, l1TtlSeconds=10, l2TtlSeconds=600, l2TtlJitterSeconds=120, delayedEvictionMs=500;
        public long getL1MaxSize(){return l1MaxSize;} public void setL1MaxSize(long v){l1MaxSize=v;}
        public long getL1TtlSeconds(){return l1TtlSeconds;} public void setL1TtlSeconds(long v){l1TtlSeconds=v;}
        public long getL2TtlSeconds(){return l2TtlSeconds;} public void setL2TtlSeconds(long v){l2TtlSeconds=v;}
        public long getL2TtlJitterSeconds(){return l2TtlJitterSeconds;} public void setL2TtlJitterSeconds(long v){l2TtlJitterSeconds=v;}
        public long getDelayedEvictionMs(){return delayedEvictionMs;} public void setDelayedEvictionMs(long v){delayedEvictionMs=v;}
    }
    public static class FlashSale {
        private long tokenTtlSeconds=300; private int gateMultiplier=5; private double ratePerSecond=200; private int burstCapacity=200;
        // Per-user bucket, checked before the per-item one. Without it a single user holding a
        // valid token can drain the shared per-item bucket and starve everyone else -- the token
        // stays valid on a SOLD_OUT retry, because markBought only runs after a successful order.
        // 5/s is loose for a human clicking and tight for a script.
        private double userRatePerSecond=5; private int userBurstCapacity=5;
        public long getTokenTtlSeconds(){return tokenTtlSeconds;} public void setTokenTtlSeconds(long v){tokenTtlSeconds=v;}
        public int getGateMultiplier(){return gateMultiplier;} public void setGateMultiplier(int v){gateMultiplier=v;}
        public double getRatePerSecond(){return ratePerSecond;} public void setRatePerSecond(double v){ratePerSecond=v;}
        public int getBurstCapacity(){return burstCapacity;} public void setBurstCapacity(int v){burstCapacity=v;}
        public double getUserRatePerSecond(){return userRatePerSecond;} public void setUserRatePerSecond(double v){userRatePerSecond=v;}
        public int getUserBurstCapacity(){return userBurstCapacity;} public void setUserBurstCapacity(int v){userBurstCapacity=v;}
    }
    public static class Benchmark { private boolean enabled=false; public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;} }
}
