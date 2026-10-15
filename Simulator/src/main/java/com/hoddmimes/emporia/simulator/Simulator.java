package com.hoddmimes.emporia.simulator;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Simulates market activity through the server's REST API, as one trader, until it is stopped (Ctrl-C).
 * It reads a JSON configuration file at startup (-config file, default ./simulator.json):
 * <pre>
 * {
 *   "url": "http://localhost:8080",
 *   "trustAllCertificates": false,          // accept any HTTPS certificate (self-signed, expired)
 *   "firm": "SimFirm", "user": "sim", "password": "secret",
 *   "txRate": 5,                            // orders (or deletes) generated and sent per second, in total
 *   "continuous": [                         // order flow per instrument of a continuous market
 *     { "instrument": "ABB",
 *       "market": "Equities",               // optional, only needed if the name is in several markets
 *       "tradeRate": 20,                    // every 20th order is a matching order (0 = never)
 *       "clientAccount": "sim",             // optional, the client account of every order
 *       "minDepth": 5, "maxDepth": 15,      // own orders per side kept within these limits
 *       "midPrice": 100.0,                  // passive orders are placed around this price
 *       "maxPriceDeviation": 2.0,           // ... at most this far from it
 *       "minVolume": 10, "maxVolume": 100 } // random order volume
 *   ],
 *   "bulletins": [                          // bulletins created and withdrawn at random times
 *     { "market": "OTC-Machinery", "instrument": "Old tractor",
 *       "side": "SELL",                     // optional: BUY, SELL or NONE (no side); random if absent
 *       "midPrice": 1000, "maxPriceDeviation": 100,  // optional, no price if absent
 *       "minVolume": 1, "maxVolume": 5,     // optional, no quantity if absent
 *       "timeToCreate": 60,                 // created after a random 10..60 s
 *       "timeToLive": 300,                  // withdrawn after a random 10..300 s
 *       "clientAccount": "sim" }            // optional, the client account of every bulletin
 *   ]
 * }
 * </pre>
 * Each transaction (1 / txRate seconds apart) is one step of a continuous instrument chosen at random,
 * so with n instruments each gets txRate / n per second on average. The bulletins are not counted
 * in txRate; they follow their own times. Every event is printed: orders created, matched and deleted, the trades, bulletins created, withdrawn,
 * traded or expired, offers received, and errors.
 */
public class Simulator {

    private static final int THREADS = 4;

    public static void main(String[] pArgs) throws Exception {
        String tConfigFile = "simulator.json";
        for (int i = 0; i < pArgs.length - 1; i++) {
            if (pArgs[i].equals("-config")) tConfigFile = pArgs[i + 1];
        }
        JsonObject tConfig = JsonParser.parseString(Files.readString(Path.of(tConfigFile))).getAsJsonObject();

        String tUrl = Config.string(tConfig, "url").replaceAll("/+$", "");
        SSLContext tSslContext = (tConfig.has("trustAllCertificates") && tConfig.get("trustAllCertificates").getAsBoolean())
                ? trustAllContext() : SSLContext.getDefault();
        Api tApi = new Api(tUrl, Config.string(tConfig, "firm"), Config.string(tConfig, "user"),
                Config.string(tConfig, "password"), tSslContext);

        // The server may not be up yet
        while (true) {
            try { tApi.login(); break; }
            catch (Exception e) {
                Events.print("ERROR", e.getMessage() + ", retrying in 5 s");
                Thread.sleep(5000);
            }
        }

        ScheduledExecutorService tScheduler = Executors.newScheduledThreadPool(THREADS, r -> {
            Thread t = new Thread(r, "simulator");
            t.setDaemon(false);
            return t;
        });

        Map<Long, String> tInstrumentNames = new ConcurrentHashMap<>();
        List<JsonObject> tInstruments = loadInstruments(tApi);
        tInstruments.forEach(i -> tInstrumentNames.put(i.get("id").getAsLong(), i.get("name").getAsString()));

        List<ContinuousSimulator> tContinuous = new ArrayList<>();
        if (tConfig.has("continuous")) {
            for (JsonElement e : tConfig.getAsJsonArray("continuous")) {
                JsonObject c = e.getAsJsonObject();
                tContinuous.add(new ContinuousSimulator(tApi, c, findInstrument(tInstruments, c)));
            }
        }
        List<BulletinSimulator> tBulletins = new ArrayList<>();
        if (tConfig.has("bulletins")) {
            JsonElement tMarkets = tApi.get("/api/bulletin-markets").body();
            for (JsonElement e : tConfig.getAsJsonArray("bulletins")) {
                JsonObject b = e.getAsJsonObject();
                JsonObject tMarket = findBulletinMarket(tMarkets, Config.string(b, "market"));
                tBulletins.add(new BulletinSimulator(tApi, tScheduler, b, tMarket.get("id").getAsInt(),
                        tMarket.get("name").getAsString()));
            }
        }
        if (tContinuous.isEmpty() && tBulletins.isEmpty()) {
            throw new IllegalArgumentException("Nothing to simulate: no 'continuous' or 'bulletins' entries");
        }

        new BdxListener(tApi, tScheduler, tInstrumentNames).connect();

        if (!tContinuous.isEmpty()) {
            double tTxRate = Config.decimal(tConfig, "txRate", 1.0);
            if (tTxRate <= 0) throw new IllegalArgumentException("txRate must be > 0");
            long tPeriodUs = Math.max(1, Math.round(1_000_000.0 / tTxRate));
            // One task, so the steps never overlap: if a step takes longer than the period, the rate drops
            tScheduler.scheduleAtFixedRate(
                    () -> tContinuous.get(ThreadLocalRandom.current().nextInt(tContinuous.size())).run(),
                    0, tPeriodUs, TimeUnit.MICROSECONDS);
            for (ContinuousSimulator s : tContinuous) {
                Events.print("START", s.name() + ": continuous order flow");
            }
            Events.print("START", "txRate " + tTxRate + " transactions/s over " + tContinuous.size() + " instrument(s)");
        }
        for (BulletinSimulator s : tBulletins) {
            s.start();
            Events.print("START", s.name() + ": bulletins");
        }
        // Runs until stopped; the scheduler's threads keep the JVM alive
    }

    /** All instruments of the continuous/auction markets the firm is authorized for, with marketName added. */
    private static List<JsonObject> loadInstruments(Api pApi) throws Exception {
        List<JsonObject> tResult = new ArrayList<>();
        Api.Reply tMarkets = pApi.get("/api/markets");
        if (!tMarkets.ok()) throw new IllegalStateException("GET /api/markets: " + tMarkets.error());
        for (JsonElement m : tMarkets.object().getAsJsonArray("markets")) {
            JsonObject tMarket = m.getAsJsonObject();
            Api.Reply tReply = pApi.get("/api/instruments?marketId=" + tMarket.get("id").getAsInt());
            if (!tReply.ok()) throw new IllegalStateException("GET /api/instruments: " + tReply.error());
            for (JsonElement i : tReply.object().getAsJsonArray("instruments")) {
                JsonObject tInstrument = i.getAsJsonObject();
                tInstrument.addProperty("marketName", tMarket.get("name").getAsString());
                tInstrument.addProperty("marketType", tMarket.get("type").getAsString());
                tResult.add(tInstrument);
            }
        }
        return tResult;
    }

    private static JsonObject findInstrument(List<JsonObject> pInstruments, JsonObject pConfig) {
        String tName   = Config.string(pConfig, "instrument");
        String tMarket = pConfig.has("market") ? pConfig.get("market").getAsString() : null;
        List<JsonObject> tFound = pInstruments.stream()
                .filter(i -> i.get("name").getAsString().equals(tName))
                .filter(i -> tMarket == null || i.get("marketName").getAsString().equals(tMarket))
                .toList();
        if (tFound.isEmpty()) {
            throw new IllegalArgumentException("Instrument '" + tName + "'" + (tMarket != null ? " in market '" + tMarket + "'" : "")
                    + " not found among the firm's authorized markets");
        }
        if (tFound.size() > 1) {
            throw new IllegalArgumentException("Instrument '" + tName + "' is in several markets; give its \"market\"");
        }
        JsonObject tInstrument = tFound.get(0);
        if (!"CONTINUOUS".equals(tInstrument.get("marketType").getAsString())) {
            throw new IllegalArgumentException("Instrument '" + tName + "' is in market '"
                    + tInstrument.get("marketName").getAsString() + "', which is not CONTINUOUS");
        }
        return tInstrument;
    }

    private static JsonObject findBulletinMarket(JsonElement pMarkets, String pName) {
        if (pMarkets != null && pMarkets.isJsonArray()) {
            for (JsonElement m : pMarkets.getAsJsonArray()) {
                if (m.getAsJsonObject().get("name").getAsString().equals(pName)) return m.getAsJsonObject();
            }
        }
        throw new IllegalArgumentException("Bulletin market '" + pName + "' not found among the firm's authorized bulletin markets");
    }

    /** Accepts any server certificate and host name (self-signed or expired test certificates). */
    private static SSLContext trustAllContext() throws Exception {
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        TrustManager tTrustAll = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] pChain, String pAuthType) {}
            public void checkServerTrusted(X509Certificate[] pChain, String pAuthType) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext tContext = SSLContext.getInstance("TLS");
        tContext.init(null, new TrustManager[] { tTrustAll }, new SecureRandom());
        return tContext;
    }
}
