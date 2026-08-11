package dev.yuemeng.marthub.auth;
public final class UserContext {
    private static final ThreadLocal<SessionUser> CURRENT = new ThreadLocal<>();
    private UserContext() {}
    public static void set(SessionUser user){CURRENT.set(user);} public static SessionUser get(){return CURRENT.get();} public static void clear(){CURRENT.remove();}
}
