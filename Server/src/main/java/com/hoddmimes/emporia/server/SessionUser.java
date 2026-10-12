package com.hoddmimes.emporia.server;

import io.javalin.http.Context;

/**
 * The trading user logged in on the request's HTTP session. RestApi's beforeMatched handler puts it
 * on the Javalin context of every matched /api request (except /api/admin/*) whose session has a
 * trading user; endpoints read it with {@link #of(Context)}.
 */
public record SessionUser(String userId, String firm) {

    /** The Javalin context attribute holding the SessionUser. */
    public static final String CTX_ATTRIBUTE = "sessionUser";

    /** The request's trading user, or null if the session has none. */
    public static SessionUser of(Context pCtx) {
        return pCtx.attribute(CTX_ATTRIBUTE);
    }
}
