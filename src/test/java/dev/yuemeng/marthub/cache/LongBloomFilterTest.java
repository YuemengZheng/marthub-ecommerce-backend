package dev.yuemeng.marthub.cache;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LongBloomFilterTest {
    @Test void insertedIdsAreNeverRejected(){
        LongBloomFilter f=new LongBloomFilter(1000,0.01);
        for(long i=1;i<=500;i++) f.put(i);
        for(long i=1;i<=500;i++) assertTrue(f.mightContain(i));
    }
    @Test void mostUnknownIdsAreRejected(){
        LongBloomFilter f=new LongBloomFilter(1000,0.01);
        for(long i=1;i<=500;i++) f.put(i);
        int rejected=0; for(long i=10000;i<10500;i++) if(!f.mightContain(i)) rejected++;
        assertTrue(rejected>450,"false-positive rate unexpectedly high: rejected="+rejected);
    }
}
