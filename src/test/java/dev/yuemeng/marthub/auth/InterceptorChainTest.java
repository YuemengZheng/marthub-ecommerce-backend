package dev.yuemeng.marthub.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Two interceptors with one job each: the first resolves whoever is calling and never
 * refuses anyone, the second refuses anyone who was not resolved. Splitting them is
 * what lets public and protected routes share the same session lookup.
 */
class InterceptorChainTest {

    private static final SessionUser USER = new SessionUser(7L, "Demo");

    private AuthService auth;
    private RefreshTokenInterceptor refresh;
    private LoginInterceptor login;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        auth = mock(AuthService.class);
        refresh = new RefreshTokenInterceptor(auth);
        login = new LoginInterceptor();
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void aBearerTokenIsResolvedAndPlacedInTheRequestContext() {
        request.addHeader("Authorization", "Bearer abc123");
        when(auth.resolveAndRefresh("abc123")).thenReturn(USER);

        assertTrue(refresh.preHandle(request, response, null));
        assertEquals(USER, UserContext.get());
    }

    @Test
    void theFirstInterceptorLetsAnonymousRequestsThroughSoPublicRoutesStillWork() {
        assertTrue(refresh.preHandle(request, response, null), "resolving is not authorising");
        assertNull(UserContext.get());
        verify(auth).resolveAndRefresh(null);
    }

    @Test
    void aHeaderWithoutTheBearerPrefixIsNotTreatedAsAToken() {
        request.addHeader("Authorization", "abc123");

        refresh.preHandle(request, response, null);

        verify(auth).resolveAndRefresh(null);
        verify(auth, never()).resolveAndRefresh("abc123");
    }

    @Test
    void theContextIsClearedAfterTheRequestSoItCannotLeakOntoTheNextOne() {
        UserContext.set(USER);

        refresh.afterCompletion(request, response, null, null);

        assertNull(UserContext.get(), "a pooled thread would carry this into someone else's request");
    }

    @Test
    void theSecondInterceptorAdmitsAResolvedUser() throws Exception {
        UserContext.set(USER);

        assertTrue(login.preHandle(request, response, null));
    }

    @Test
    void theSecondInterceptorRefusesWhenNobodyWasResolved() throws Exception {
        assertFalse(login.preHandle(request, response, null));
        assertEquals(401, response.getStatus());
    }

    @Test
    void anUnknownTokenIsRefusedByTheSecondInterceptor() throws Exception {
        request.addHeader("Authorization", "Bearer expired-or-forged");
        when(auth.resolveAndRefresh(anyString())).thenReturn(null);

        refresh.preHandle(request, response, null);

        assertFalse(login.preHandle(request, response, null));
        assertEquals(401, response.getStatus());
    }
}
