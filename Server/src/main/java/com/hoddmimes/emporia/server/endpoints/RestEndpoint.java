package com.hoddmimes.emporia.server.endpoints;

import io.javalin.Javalin;

/**
 * Each REST endpoint registers itself against the Javalin application.
 */
public interface RestEndpoint {
    void register(Javalin app);
}
