package com.hoddmimes.emporia.server;

import com.hoddmimes.emporia.server.db.Database;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hoddmimes.emporia.server.audit.AuditLog;
import com.hoddmimes.emporia.server.audit.FileAuditLog;
import com.hoddmimes.emporia.server.bdx.BdxDistributor;
import com.hoddmimes.emporia.server.bulletin.BulletinBoard;
import com.hoddmimes.emporia.server.me.AuctionScheduler;
import com.hoddmimes.emporia.server.me.MatchingEngine;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.UncheckedIOException;

/**
 * The server: the database, instrument cache, authorization cache, broadcast distributor, matching engine, bulletin board and
 * the schedulers. Started either standalone (main, with its own Jetty web server, RestApi.start) or as a
 * web application in Tomcat (webapp.EmporiaWebApp, with Tomcat as the web server).
 */
public class Server {

    private static final Logger cLog = LogManager.getLogger(Server.class);

    public final JsonObject         config;
    public final FileAuditLog       audit;
    public final Database           database;
    public final InstrumentCache    instrumentCache;
    public final AuthorizationCache authorizations;
    public final BdxDistributor     bdx;
    public final MatchingEngine     matchingEngine;
    public final BulletinBoard      bulletinBoard;
    private final AuctionScheduler     mAuctionScheduler;
    private final MarketStateScheduler mMarketStateScheduler;

    public static void main(String[] args) throws Exception {
        String tConfigPath = null;
        String tMode = "normal";
        for (int i = 0; i < args.length - 1; i++) {
            if ("-config".equals(args[i])) { tConfigPath = args[i + 1]; }
            if ("-mode".equals(args[i]))   { tMode       = args[i + 1]; }
        }
        if (tConfigPath == null || !(tMode.equals("normal") || tMode.equals("test"))) {
            throw new RuntimeException("Usage: Server -config <file> [-mode normal|test]");
        }
        Server tServer = new Server(loadConfig(tConfigPath), tMode.equals("test"));
        RestApi tRestApi = tServer.restApi();
        tRestApi.start();
        // SIGTERM (kill, Ctrl-C, emporia.sh stop): stop taking requests, then store the queued trades
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            cLog.info("Shutting down");
            tRestApi.stop();
            tServer.stop();
            LogManager.shutdown();   // log4j2.xml disables Log4j's own hook, so the lines above are logged
        }, "shutdown"));
    }

    /**
     * Starts everything but the web server. pTestMode: all markets are opened at startup and the market
     * hours are not applied.
     */
    public Server(JsonObject pConfig, boolean pTestMode) {
        cLog.info("Emporia {}", BuildInfo.text());
        config = pConfig;

        audit = openAudit(config, pTestMode);

        database = Database.open(config.get("database").getAsJsonObject());
        database.initializeAdmin();

        instrumentCache = new InstrumentCache();
        instrumentCache.load(database);

        authorizations = new AuthorizationCache();
        authorizations.load(database);

        bdx = new BdxDistributor(instrumentCache);
        bdx.start();

        matchingEngine = new MatchingEngine(instrumentCache, database, bdx, audit);
        mAuctionScheduler = new AuctionScheduler(matchingEngine, instrumentCache);
        mAuctionScheduler.start();
        bulletinBoard = new BulletinBoard(database, bdx::publish, audit);
        bulletinBoard.load();
        mMarketStateScheduler = new MarketStateScheduler(database, instrumentCache, bulletinBoard);
        mMarketStateScheduler.start(pTestMode);
    }

    public RestApi restApi() {
        return new RestApi(database, instrumentCache, authorizations, bdx, matchingEngine, bulletinBoard, audit, config);
    }

    /**
     * Starts the audit log (FileAuditLog) in the config's audit.dir (default ./audit) and writes the START
     * message. The server does not start without it.
     */
    private static FileAuditLog openAudit(JsonObject pConfig, boolean pTestMode) {
        String tDir = "./audit";
        if (pConfig.has("audit") && pConfig.getAsJsonObject("audit").has("dir")) {
            tDir = pConfig.getAsJsonObject("audit").get("dir").getAsString();
        }
        FileAuditLog tAudit;
        try {
            tAudit = new FileAuditLog(Paths.get(tDir));
        } catch (IOException e) {
            throw new UncheckedIOException("Can't open the audit log in " + tDir + ": " + e.getMessage(), e);
        }
        tAudit.start();
        JsonObject tData = new JsonObject();
        tData.addProperty("version", BuildInfo.text());
        tData.addProperty("mode", pTestMode ? "test" : "normal");
        tData.addProperty("pid", ProcessHandle.current().pid());
        tAudit.queue(AuditLog.START, null, null, tData, null);
        return tAudit;
    }

    /** Stops the threads and closes the database (web application undeploy). */
    public void stop() {
        mMarketStateScheduler.stop();
        mAuctionScheduler.stop();
        bulletinBoard.stop();
        matchingEngine.stop();   // stores the queued trades, publishes the queued messages
        bdx.interrupt();
        audit.queue(AuditLog.STOP, null, null, null, null);
        audit.stopLogging(10000);   // last, so everything above is in it
        database.close();
        cLog.info("Server stopped");
    }

    public static JsonObject loadConfig(String pConfigPath) throws IOException {
        String tContent = new String(Files.readAllBytes(Paths.get(pConfigPath)));
        cLog.info("Loaded config from {}", pConfigPath);
        return JsonParser.parseString(tContent).getAsJsonObject();
    }
}
