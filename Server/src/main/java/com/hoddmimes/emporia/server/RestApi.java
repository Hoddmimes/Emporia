package com.hoddmimes.emporia.server;

import com.google.gson.JsonObject;
import com.hoddmimes.emporia.server.endpoints.*;
import io.javalin.Javalin;
import io.javalin.community.ssl.SslPlugin;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.http.staticfiles.Location;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

public class RestApi {

    private static final Logger cLog = LogManager.getLogger(RestApi.class);

    // Paths reachable without being logged in
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/login", "/api/session", "/api/admin/login", "/api/admin/session");

    private final RestApiContext mCtx;
    private final JsonObject     jHttpIfConfig;

    public RestApi(Database pDatabase, InstrumentCache pInstrumentCache, JsonObject pConfiguration) {
        jHttpIfConfig = pConfiguration.getAsJsonObject("http_interface");

        int tSessionInactivitySeconds = 300;
        if (pConfiguration.has("sessionInactivityTime")) {
            String tTime    = pConfiguration.get("sessionInactivityTime").getAsString();
            String[] tParts = tTime.split(":");
            tSessionInactivitySeconds = Integer.parseInt(tParts[0]) * 60 + Integer.parseInt(tParts[1]);
            cLog.info("Session inactivity timeout: {} seconds", tSessionInactivitySeconds);
        }

        mCtx = new RestApiContext(pDatabase, pInstrumentCache, pConfiguration, tSessionInactivitySeconds);
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
            // Javalin refuses to start if an external static file directory is missing
            if (Files.isDirectory(Paths.get(tWebRoot))) {
                config.staticFiles.add(tWebRoot, Location.EXTERNAL);
            } else {
                cLog.warn("Web root '{}' not found, no static files are served", tWebRoot);
            }
        });

        declareAuthFilter(tApp);
        declareEndpoints(tApp);

        tApp.start();

        if (tHttpsEnabled) cLog.info("HTTPS listening on {}:{}", tInterface, tHttpsPort);
        if (tHttpEnabled)  cLog.info("HTTP  listening on {}:{}", tInterface, tHttpPort);
    }

    /** Where price sources publish updates for the trading clients on /ws. */
    public PriceFeed priceFeed() {
        return mCtx.priceFeed;
    }

    // ── Auth filter ───────────────────────────────────────────────────────

    private void declareAuthFilter(Javalin pApp) {
        // The trading interface is served from the web root (index.html), the admin interface from /admin/
        pApp.get("/admin",  ctx -> ctx.redirect("/admin/login.html"));
        pApp.get("/admin/", ctx -> ctx.redirect("/admin/login.html"));

        pApp.before("/api/*", ctx -> {
            if (PUBLIC_PATHS.contains(ctx.path())) return;
            var tSession = ctx.req().getSession(false);
            String tAttribute = ctx.path().startsWith("/api/admin/")
                    ? RestApiContext.ADMIN_USER : RestApiContext.USER;
            String tUser = (tSession != null) ? (String) tSession.getAttribute(tAttribute) : null;
            if (tUser == null) throw new UnauthorizedResponse("Not authenticated");
        });
    }

    // ── REST endpoints ────────────────────────────────────────────────────

    private void declareEndpoints(Javalin pApp) {
        List<RestEndpoint> tEndpoints = List.of(
            new SessionEndpoint(mCtx),
            new LoginEndpoint(mCtx),
            new LogoutEndpoint(mCtx),
            new MarketEndpoints(mCtx),
            new InstrumentEndpoints(mCtx),
            new PriceFeedEndpoint(mCtx),
            new AdminSessionEndpoint(mCtx),
            new AdminLoginEndpoint(mCtx),
            new AdminLogoutEndpoint(mCtx),
            new AdminPasswordEndpoint(mCtx),
            new AdminFirmEndpoints(mCtx),
            new AdminUserEndpoints(mCtx),
            new AdminMarketEndpoints(mCtx),
            new AdminInstrumentEndpoints(mCtx),
            new AdminAuthorizationEndpoints(mCtx)
        );
        tEndpoints.forEach(e -> e.register(pApp));
    }
}
