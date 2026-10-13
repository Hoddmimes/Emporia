package com.hoddmimes.emporia.server;

import com.google.gson.JsonObject;
import com.hoddmimes.emporia.server.bdx.BdxDistributor;
import com.hoddmimes.emporia.server.bdx.BdxEndpoint;
import com.hoddmimes.emporia.server.bulletin.BulletinBoard;
import com.hoddmimes.emporia.server.endpoints.*;
import com.hoddmimes.emporia.server.me.MatchingEngine;
import io.javalin.Javalin;
import io.javalin.community.ssl.SslPlugin;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.http.staticfiles.Location;
import jakarta.servlet.http.HttpSessionEvent;
import org.eclipse.jetty.server.session.DefaultSessionIdManager;
import org.eclipse.jetty.server.session.HouseKeeper;
import jakarta.servlet.http.HttpSessionListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;

import java.nio.file.Files;
import java.time.Duration;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public class RestApi {

    private static final Logger cLog = LogManager.getLogger(RestApi.class);

    // Paths reachable without being logged in
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/login", "/api/session", "/api/admin/login", "/api/admin/session", "/api/version");

    // Upper bound on how often timed-out HTTP sessions are detected and removed
    private static final int SESSION_SCAVENGE_SECONDS = 30;

    // A WebSocket without traffic for this long is closed (Jetty's default is 30 s). The server pings every
    // BdxEndpoint.PING_SECONDS, so only a dead connection reaches it; the margin covers a browser that
    // answers late, e.g. while the page is in the background.
    public static final Duration WS_IDLE_TIMEOUT = Duration.ofMinutes(2);

    // Request ids: a counter, restarting at 1 when the server restarts
    public  static final String REQUEST_ID     = "rid";   // Log4j ThreadContext key, %X{rid} in the log pattern
    private final AtomicLong    mNextRequestId = new AtomicLong(1);

    private final RestApiContext mCtx;
    private final JsonObject     jHttpIfConfig;

    public RestApi(Database pDatabase, InstrumentCache pInstrumentCache, BdxDistributor pBdx,
                   MatchingEngine pMatchingEngine, BulletinBoard pBulletinBoard, JsonObject pConfiguration) {
        jHttpIfConfig = pConfiguration.getAsJsonObject("http_interface");

        int tSessionInactivitySeconds = 300;
        if (pConfiguration.has("sessionInactivityTime")) {
            String tTime    = pConfiguration.get("sessionInactivityTime").getAsString();
            String[] tParts = tTime.split(":");
            tSessionInactivitySeconds = Integer.parseInt(tParts[0]) * 60 + Integer.parseInt(tParts[1]);
            cLog.info("Session inactivity timeout: {} seconds", tSessionInactivitySeconds);
        }

        mCtx = new RestApiContext(pDatabase, pInstrumentCache, pBdx, pMatchingEngine, pBulletinBoard,
                pConfiguration, tSessionInactivitySeconds);
    }

    public void start() {
        String tWebRoot   = jHttpIfConfig.get("root").getAsString();
        String tInterface = jHttpIfConfig.get("interface").getAsString();

        boolean tHttpsEnabled = jHttpIfConfig.has("https_port");
        boolean tHttpEnabled  = jHttpIfConfig.has("http_port");

        int    tHttpsPort = tHttpsEnabled ? Integer.parseInt(jHttpIfConfig.get("https_port").getAsString()) : -1;
        int    tHttpPort  = tHttpEnabled  ? Integer.parseInt(jHttpIfConfig.get("http_port").getAsString())  : -1;
        String tCertPath  = tHttpsEnabled ? jHttpIfConfig.getAsJsonObject("ssl").get("cert").getAsString()  : null;
        String tKeyPath   = tHttpsEnabled ? jHttpIfConfig.getAsJsonObject("ssl").get("key").getAsString()   : null;

        SslPlugin tSslPlugin = new SslPlugin(ssl -> {
            ssl.host         = tInterface;
            ssl.sniHostCheck = false;
            // No HTTP/2: over HTTP/2 Jetty offers browsers WebSockets over HTTP/2 (RFC 8441, no
            // Sec-WebSocket-Key header), which Javalin does not recognize as an upgrade, so /ws failed
            // with 1006 in the browser. With HTTP/1.1 only, the /ws upgrade works on both ports.
            ssl.http2        = false;
            if (tHttpsEnabled) {
                ssl.pemFromPath(tCertPath, tKeyPath);
                ssl.securePort = tHttpsPort;
                ssl.secure     = true;
            } else {
                ssl.secure = false;
            }
            if (tHttpEnabled) {
                ssl.insecurePort = tHttpPort;
                ssl.insecure     = true;
            } else {
                ssl.insecure = false;
            }
        });

        Javalin tApp = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.registerPlugin(tSslPlugin);
            // Jetty only detects timed-out sessions when its housekeeper runs (default every 10 minutes);
            // run it often so a timed-out session's WebSockets are closed soon after the timeout
            config.jetty.modifyServer(server -> {
                HouseKeeper tHouseKeeper = new HouseKeeper();
                try {
                    tHouseKeeper.setIntervalSec(Math.min(SESSION_SCAVENGE_SECONDS, mCtx.sessionInactivitySeconds));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                DefaultSessionIdManager tIdManager = new DefaultSessionIdManager(server);
                tIdManager.setSessionHouseKeeper(tHouseKeeper);
                server.setSessionIdManager(tIdManager);
            });
            config.jetty.modifyWebSocketServletFactory(factory -> factory.setIdleTimeout(WS_IDLE_TIMEOUT));
            // A session that times out (or is invalidated) takes its bdx WebSockets with it
            config.jetty.modifyServletContextHandler(handler -> handler.addEventListener(new HttpSessionListener() {
                @Override
                public void sessionDestroyed(HttpSessionEvent pEvent) {
                    mCtx.bdx.closeClients(pEvent.getSession().getId(), "Session expired");
                }
            }));
            // Javalin refuses to start if an external static file directory is missing
            if (Files.isDirectory(Paths.get(tWebRoot))) {
                config.staticFiles.add(tWebRoot, Location.EXTERNAL);
            } else {
                cLog.warn("Web root '{}' not found, no static files are served", tWebRoot);
            }
        });

        declareHandlers(tApp, true);

        tApp.start();

        if (tHttpsEnabled) cLog.info("HTTPS listening on {}:{}", tInterface, tHttpsPort);
        if (tHttpEnabled)  cLog.info("HTTP  listening on {}:{}", tInterface, tHttpPort);
    }

    public RestApiContext context() { return mCtx; }

    /**
     * The REST API as a servlet, for a servlet container (Tomcat), deployed at pContextPath ("" for the
     * root, e.g. "/emporia"; the routes are matched below it): the container is the web server, so
     * there are no ports, SSL, static files or Jetty settings here, and no /ws route, since Javalin's
     * WebSockets need Jetty (the web application serves /ws itself, webapp.BdxWebSocket). The servlet is
     * built after all routes are registered.
     */
    public jakarta.servlet.Servlet createServlet(String pContextPath) {
        Javalin tApp = Javalin.create(config -> {
            config.showJavalinBanner = false;
            if (!pContextPath.isEmpty()) config.router.contextPath = pContextPath;
        });
        declareHandlers(tApp, false);
        return new io.javalin.http.servlet.JavalinServlet(tApp.unsafeConfig());
    }

    private void declareHandlers(Javalin pApp, boolean pJettyWebSocket) {
        declareRequestId(pApp);
        declareAuthFilter(pApp);
        declareEndpoints(pApp, pJettyWebSocket);
        declareAfterHandlers(pApp);
    }

    // ── Request id ────────────────────────────────────────────────────────

    // Gives every /api request an id (r1, r2, ...), put in the Log4j ThreadContext so that every line
    // logged on the request's thread shows it (%X{rid}), and returned in the X-Request-Id header.
    // Registered first, so it runs before the auth filter; cleared in declareAfterHandlers.
    private void declareRequestId(Javalin pApp) {
        pApp.before("/api/*", ctx -> {
            String tRequestId = "r" + mNextRequestId.getAndIncrement();
            ThreadContext.put(REQUEST_ID, tRequestId);
            ctx.header("X-Request-Id", tRequestId);
        });
    }

    // ── Auth filter ───────────────────────────────────────────────────────

    private void declareAuthFilter(Javalin pApp) {
        // The trading interface is served from the web root (index.html), the admin interface from /admin/
        pApp.get("/admin",  ctx -> ctx.redirect(ctx.req().getContextPath() + "/admin/login.html"));
        pApp.get("/admin/", ctx -> ctx.redirect(ctx.req().getContextPath() + "/admin/login.html"));

        pApp.before("/api/*", ctx -> {
            String tPath = apiPath(ctx);
            if (PUBLIC_PATHS.contains(tPath)) return;
            var tSession = ctx.req().getSession(false);
            String tAttribute = tPath.startsWith("/api/admin/")
                    ? RestApiContext.ADMIN_USER : RestApiContext.USER;
            String tUser = (tSession != null) ? (String) tSession.getAttribute(tAttribute) : null;
            if (tUser == null) throw new UnauthorizedResponse("Not authenticated");
        });

        // Runs after the auth filter, only for requests that matched an endpoint: puts the trading
        // user on the context (see SessionUser.of), so endpoints need not read the session
        pApp.beforeMatched("/api/*", ctx -> {
            if (apiPath(ctx).startsWith("/api/admin/")) return;
            var tSession = ctx.req().getSession(false);
            if (tSession == null) return;
            String tUser = (String) tSession.getAttribute(RestApiContext.USER);
            if (tUser != null) {
                ctx.attribute(SessionUser.CTX_ATTRIBUTE,
                        new SessionUser(tUser, (String) tSession.getAttribute(RestApiContext.FIRM)));
            }
        });
    }

    // The request's path within the application: without the context path (e.g. /emporia in Tomcat)
    private static String apiPath(io.javalin.http.Context ctx) {
        return ctx.path().substring(ctx.req().getContextPath().length());
    }

    // ── After handlers ────────────────────────────────────────────────────

    private void declareAfterHandlers(Javalin pApp) {
        // Runs after every request to exactly /api/orders (GET list, POST add), also when the request
        // failed; /api/orders/{orderId} (PATCH, DELETE) is a different path and not included
        pApp.after("/api/orders", ctx -> {
            if (cLog.isTraceEnabled()) {
                cLog.trace("{} -> {} user: {}",  ctx.path(), ctx.statusCode(), SessionUser.of(ctx));
            }
        });

        pApp.after("/api/orders/*", ctx -> {
            if (cLog.isTraceEnabled()) {
                cLog.trace("{} -> {} user: {}",  ctx.path(), ctx.statusCode(), SessionUser.of(ctx));
            }
        });

        // Registered last, so it runs after the other after handlers: Jetty reuses its threads, so the
        // request id must not stay in the thread's context for the next request
        pApp.after("/api/*", ctx -> ThreadContext.remove(REQUEST_ID));
    }

    // ── REST endpoints ────────────────────────────────────────────────────

    private void declareEndpoints(Javalin pApp, boolean pJettyWebSocket) {
        List<RestEndpoint> tEndpoints = new ArrayList<>(List.of(
            new VersionEndpoint(mCtx),
            new SessionEndpoint(mCtx),
            new LoginEndpoint(mCtx),
            new LogoutEndpoint(mCtx),
            new MarketEndpoints(mCtx),
            new InstrumentEndpoints(mCtx),
            new OrderEndpoints(mCtx),
            new TradeEndpoints(mCtx),
            new PortfolioEndpoints(mCtx),
            new BulletinEndpoints(mCtx),
            new AdminSessionEndpoint(mCtx),
            new AdminLoginEndpoint(mCtx),
            new AdminLogoutEndpoint(mCtx),
            new AdminPasswordEndpoint(mCtx),
            new AdminFirmEndpoints(mCtx),
            new AdminUserEndpoints(mCtx),
            new AdminMarketEndpoints(mCtx),
            new AdminBulletinMarketEndpoints(mCtx),
            new AdminInstrumentEndpoints(mCtx),
            new AdminAuthorizationEndpoints(mCtx)
        ));
        if (pJettyWebSocket) tEndpoints.add(new BdxEndpoint(mCtx));
        tEndpoints.forEach(e -> e.register(pApp));
    }
}
