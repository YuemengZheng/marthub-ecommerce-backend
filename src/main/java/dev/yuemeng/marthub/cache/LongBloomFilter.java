package dev.yuemeng.marthub.cache;

import java.nio.ByteBuffer;
import java.util.BitSet;

/** Small dependency-free Bloom filter for long IDs. False positives are possible; false negatives are not. */
public class LongBloomFilter {
    private final BitSet bits;
    private final int bitSize;
    private final int hashes;
    public LongBloomFilter(int expectedInsertions, double falsePositiveRate) {
        double ln2 = Math.log(2.0);
        this.bitSize = Math.max(64, (int)Math.ceil(-expectedInsertions * Math.log(falsePositiveRate) / (ln2 * ln2)));
        this.hashes = Math.max(1, (int)Math.round((double) bitSize / expectedInsertions * ln2));
        this.bits = new BitSet(bitSize);
    }
    public synchronized void put(long value) { for (int i=0;i<hashes;i++) bits.set(index(value,i)); }
    public synchronized boolean mightContain(long value) {
        for (int i=0;i<hashes;i++) if (!bits.get(index(value,i))) return false;
        return true;
    }
    private int index(long value, int salt) {
        long x = value + 0x9E3779B97F4A7C15L * (salt + 1L);
        x ^= (x >>> 33); x *= 0xff51afd7ed558ccdl; x ^= (x >>> 33); x *= 0xc4ceb9fe1a85ec53l; x ^= (x >>> 33);
        return (int)Math.floorMod(x, bitSize);
    }
}
