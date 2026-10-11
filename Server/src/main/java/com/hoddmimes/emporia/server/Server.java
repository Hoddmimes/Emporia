package com.hoddmimes.emporia.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hoddmimes.emporia.server.bdx.BdxDistributor;
import com.hoddmimes.emporia.server.me.AuctionScheduler;
import com.hoddmimes.emporia.server.me.MatchingEngine;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class Server {

    private static final Logger cLog = LogManager.getLogger(Server.class);

    private JsonObject mConfig;
    private String     mConfigPath;
    private boolean    mTestMode;
    private Database   mDatabase;

    public static void main(String[] args) throws Exception {
        new Server().start(args);
    }

    private void start(String[] args) throws Exception {
        parseArgs(args);
        mConfig = loadConfig(mConfigPath);

        mDatabase = new Database();
        mDatabase.connect(mConfig.get("database").getAsJsonObject());
        mDatabase.initializeAdminCollection();

        InstrumentCache tInstrumentCache = new InstrumentCache();
        tInstrumentCache.load(mDatabase);

        BdxDistributor tBdx            = new BdxDistributor(tInstrumentCache);
        tBdx.start();


        MatchingEngine tMatchingEngine = new MatchingEngine(tInstrumentCache, mDatabase, tBdx);
        new AuctionScheduler(tMatchingEngine, tInstrumentCache).start();
        new MarketStateScheduler(mDatabase, tInstrumentCache).start(mTestMode);

        new RestApi(mDatabase, tInstrumentCache, tBdx, tMatchingEngine, mConfig).start();
    }

    private void parseArgs(String[] args) {
        mConfigPath = null;
        String tMode = "normal";
        for (int i = 0; i < args.length - 1; i++) {
            if ("-config".equals(args[i])) { mConfigPath = args[i + 1]; }
            if ("-mode".equals(args[i]))   { tMode       = args[i + 1]; }
        }
        if (mConfigPath == null || !(tMode.equals("normal") || tMode.equals("test"))) {
            throw new RuntimeException("Usage: Server -config <file> [-mode normal|test]");
        }
        // test: all markets are opened at startup and the market hours are not applied
        mTestMode = tMode.equals("test");
    }

    private JsonObject loadConfig(String pConfigPath) throws IOException {
        String tContent = new String(Files.readAllBytes(Paths.get(pConfigPath)));
        cLog.info("Loaded config from {}", pConfigPath);
        return JsonParser.parseString(tContent).getAsJsonObject();
    }
}
