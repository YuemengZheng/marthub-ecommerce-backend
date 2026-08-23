package dev.yuemeng.marthub.auth;

import java.io.Serializable;
import java.security.Principal;

/**
 * Implements two interfaces, both because Spring Session needs them.
 *
 * <p>{@link Principal}: sessions are indexed by {@code Authentication#getName()}, which returns
 * the principal's name when the principal is a {@code Principal} and otherwise falls back to
 * {@code toString()} -- putting "SessionUser[id=2, name=...]" in the index key. Returning the bare
 * id keeps "find every session for this user" a clean lookup.
 *
 * <p>{@link Serializable}: the security context is stored as a session attribute, and Spring
 * Session's Redis serializer is JDK serialization by default. A record is not serializable unless
 * it says so, and without this the login request fails at the point of writing the session.
 */
public record SessionUser(long id, String name) implements Principal, Serializable {
    private static final long serialVersionUID = 1L;
    @Override public String getName() { return Long.toString(id); }
}
