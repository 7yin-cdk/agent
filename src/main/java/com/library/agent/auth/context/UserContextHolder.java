package com.library.agent.auth.context;

/**
 * 当前请求线程的用户上下文持有器
 */
public final class UserContextHolder {

    private static final ThreadLocal<UserContext> USER_CONTEXT = new ThreadLocal<>();

    private UserContextHolder() {
    }

    /**
     * 设置当前请求线程的用户上下文。
     */
    public static void set(UserContext userContext) {
        USER_CONTEXT.set(userContext);
    }

    /**
     * 获取当前请求线程的用户上下文。
     */
    public static UserContext get() {
        return USER_CONTEXT.get();
    }

    /**
     * 获取当前登录用户 ID。
     */
    public static Long getUserId() {
        UserContext userContext = get();
        return userContext == null ? null : userContext.getUserId();
    }

    /**
     * 清理当前请求线程的用户上下文，避免线程复用导致用户串号。
     */
    public static void clear() {
        USER_CONTEXT.remove();
    }
}
