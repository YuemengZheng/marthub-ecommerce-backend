package dev.yuemeng.marthub.auth;

import dev.yuemeng.marthub.config.MartHubProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Sessions live in Redis, which is the only reason three instances can share them. */
class AuthServiceTest {

    private StringRedisTemplate redis;
    private HashOperations<String, Object, Object> hash;
    private AuthService auth;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        hash = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hash);
        auth = new AuthService(redis, new MartHubProperties());
    }

    @Test
    void loginStoresTheSessionInRedisUnderAnOpaqueToken() {
        String token = auth.login(7L, "Demo");

        assertEquals(32, token.length(), "the token should be a compact hex id, not a JWT");
        assertTrue(token.matches("[0-9a-f]{32}"));
        verify(hash).putAll(eq("auth:token:" + token), anyMap());
        verify(redis).expire("auth:token:" + token, Duration.ofMinutes(30));
    }

    @Test
    void twoLoginsNeverProduceTheSameToken() {
        assertNotEquals(auth.login(1L, "A"), auth.login(1L, "A"));
    }

    @Test
    void aMissingOrBlankTokenIsResolvedWithoutCallingRedis() {
        assertNull(auth.resolveAndRefresh(null));
        assertNull(auth.resolveAndRefresh("   "));

        verifyNoInteractions(hash);
    }

    @Test
    void anUnknownTokenResolvesToNobodyAndDoesNotRefreshAnything() {
        when(hash.entries("auth:token:ghost")).thenReturn(Map.of());

        assertNull(auth.resolveAndRefresh("ghost"));

        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void aKnownTokenResolvesToItsUserAndPushesTheTtlBackOut() {
        when(hash.entries("auth:token:live")).thenReturn(Map.of("id", "7", "name", "Demo"));

        SessionUser user = auth.resolveAndRefresh("live");

        assertNotNull(user);
        assertEquals(7L, user.id());
        assertEquals("Demo", user.name());
        verify(redis).expire("auth:token:live", Duration.ofMinutes(30));
    }
}
