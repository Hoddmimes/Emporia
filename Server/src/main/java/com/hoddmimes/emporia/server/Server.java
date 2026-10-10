package com.hoddmimes.emporia.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

        PriceFeed      tPriceFeed      = new PriceFeed(tInstrumentCache);
        MatchingEngine tMatchingEngine = new MatchingEngine(tInstrumentCache, tPriceFeed);
        new AuctionScheduler(tMatchingEngine, tInstrumentCache).start();

        new RestApi(mDatabase, tInstrumentCache, tPriceFeed, tMatchingEngine, mConfig).start();
    }

    private void parseArgs(String[] args) {
        mConfigPath = null;
        for (int i = 0; i < args.length - 1; i++) {
            if ("-config".equals(args[i])) { mConfigPath = args[i + 1]; }
        }
        if (mConfigPath == null) {
            throw new RuntimeException("Usage: Server -config <file>");
        }
    }

    private JsonObject loadConfig(String pConfigPath) throws IOException {
        String tContent = new String(Files.readAllBytes(Paths.get(pConfigPath)));
        cLog.info("Loaded config from {}", pConfigPath);
        return JsonParser.parseString(tContent).getAsJsonObject();
    }
}
