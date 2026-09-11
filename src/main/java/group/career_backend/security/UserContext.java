package group.career_backend.security;

import jakarta.servlet.http.HttpServletRequest;

public final class UserContext {
    public static final long DEFAULT_USER_ID = 23L;
    public static final String USER_ID_ATTRIBUTE = UserContext.class.getName() + ".userId";

    private UserContext() {
    }

    public static Long getUserId(HttpServletRequest request) {
        Object userId = request.getAttribute(USER_ID_ATTRIBUTE);
        return userId instanceof Long value ? value : DEFAULT_USER_ID;
    }

    public static boolean isAuthenticated(HttpServletRequest request) {
        return request.getAttribute(USER_ID_ATTRIBUTE) != null;
    }
}
