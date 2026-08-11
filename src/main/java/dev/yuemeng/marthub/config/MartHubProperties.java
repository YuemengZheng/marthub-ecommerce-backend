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
    public static class Auth { private long ttlMinutes=30; public long getTtlMinutes(){return ttlMinutes;} public void setTtlMinutes(long v){ttlMinutes=v;} }
    public static class Cache {
        private long l1MaxSize=10000, l1TtlSeconds=300, l2TtlSeconds=600, delayedEvictionMs=500;
        public long getL1MaxSize(){return l1MaxSize;} public void setL1MaxSize(long v){l1MaxSize=v;}
        public long getL1TtlSeconds(){return l1TtlSeconds;} public void setL1TtlSeconds(long v){l1TtlSeconds=v;}
        public long getL2TtlSeconds(){return l2TtlSeconds;} public void setL2TtlSeconds(long v){l2TtlSeconds=v;}
        public long getDelayedEvictionMs(){return delayedEvictionMs;} public void setDelayedEvictionMs(long v){delayedEvictionMs=v;}
    }
    public static class FlashSale {
        private long tokenTtlSeconds=300; private int gateMultiplier=5; private double ratePerSecond=200; private int burstCapacity=200;
        public long getTokenTtlSeconds(){return tokenTtlSeconds;} public void setTokenTtlSeconds(long v){tokenTtlSeconds=v;}
        public int getGateMultiplier(){return gateMultiplier;} public void setGateMultiplier(int v){gateMultiplier=v;}
        public double getRatePerSecond(){return ratePerSecond;} public void setRatePerSecond(double v){ratePerSecond=v;}
        public int getBurstCapacity(){return burstCapacity;} public void setBurstCapacity(int v){burstCapacity=v;}
    }
    public static class Benchmark { private boolean enabled=false; public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;} }
}
