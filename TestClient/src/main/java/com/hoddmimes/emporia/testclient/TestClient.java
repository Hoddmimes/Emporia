package com.hoddmimes.emporia.testclient;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Test program that logs in to the Emporia server through the REST API and sends orders,
 * as instructed by a JSON file:
 *
 * <pre>
 * {
 *   "url": "http://localhost:8080",
 *   "trustAllCertificates": false,      // accept any HTTPS certificate (expired, self-signed, wrong host)
 *   "verbose": false,                   // print every request and response and every bdx message
 *   "sessions": [
 *     { "firm": "ThinAir", "user": "Geek", "password": "secret",
 *       "repeat": 10,                    // times the directives are run (default 1)
 *       "txRate": 5,                     // directives per second, 0 = as fast as possible
 *       "directives": [
 *         { "type": "AddOrder", "instrument": "ABB", "side": "BUY",
 *           "price": 101.50, "quantity": 100, "userRef": "b1",     // userRef is optional
 *           "clientAccount": "c1" },                               // clientAccount is optional
 *         { "type": "DeleteOrder", "userRef": "b1" } ] } ]
 * }
 * </pre>
 *
 * AddOrder takes the instrument by name ("instrument") or id ("instrumentId"); price and quantity
 * are decimals. The orderId in an AddOrder's response is remembered under its "userRef";
 * DeleteOrder deletes the order last added with the same "userRef", by that orderId. Sessions run
 * concurrently, each with its own HTTP session.
 *
 * Command line: -config file (default ./testclient.json) and -bdx true|false (default true). With
 * -bdx true each session opens a WebSocket to the server's /ws after logging in and counts the
 * broadcast (bdx) messages it receives, printing each one with "verbose"; the server sends all
 * messages for the firm's authorized markets and the firm's own orders and trades, so there is
 * nothing to subscribe to. No session
 * sends a transaction before every session has logged in and its WebSocket is registered by the
 * server.
 */
public class TestClient {

    public static void main(String[] args) throws Exception {
        String  tConfigPath = null;
        boolean tBdx        = true;
        for (int i = 0; i < args.length - 1; i++) {
            if ("-config".equals(args[i])) tConfigPath = args[i + 1];
            if ("-bdx".equals(args[i])) {
                if (!args[i + 1].equals("true") && !args[i + 1].equals("false")) {
                    System.err.println("Invalid -bdx value '" + args[i + 1] + "', expected true or false");
                    System.exit(1);
                }
                tBdx = Boolean.parseBoolean(args[i + 1]);
            }
        }
        if (tConfigPath == null) {
            tConfigPath = "./tests/testclient.json";
        }

        JsonObject tConfig;
        try {
            tConfig = readConfig(tConfigPath);
        } catch (Exception e) {
            // Gson's message reads "... malformed JSON at line L column C path P <troubleshooting link>"
            String tMsg = String.valueOf(e.getMessage()).replaceAll("(?s).*?(at line \\d+ column \\d+ path \\S+).*", "malformed JSON $1");
            System.err.println("Invalid config file " + tConfigPath + ": " + tMsg);
            System.exit(1);
            return;
        }
        String  tUrl     = tConfig.get("url").getAsString().replaceAll("/+$", "");
        boolean tVerbose = tConfig.has("verbose") && tConfig.get("verbose").getAsBoolean();
        SSLContext tSslContext = (tConfig.has("trustAllCertificates") && tConfig.get("trustAllCertificates").getAsBoolean())
                ? trustAllContext() : SSLContext.getDefault();

        List<TestSession> tSessions = new ArrayList<>();
        JsonArray tSessionConfigs = tConfig.getAsJsonArray("sessions");
        CountDownLatch tAllReady = new CountDownLatch(tSessionConfigs.size());   // all logged in and connected
        int tIndex = 0;
        for (JsonElement tSession : tSessionConfigs) {
            tSessions.add(new TestSession(++tIndex, tSession.getAsJsonObject(), tUrl, tSslContext, tVerbose, tBdx, tAllReady));
        }

        long tStart = System.currentTimeMillis();
        List<Thread> tThreads = new ArrayList<>();
        for (TestSession tSession : tSessions) {
            Thread t = new Thread(tSession, "session-" + tSession.name());
            t.start();
            tThreads.add(t);
        }
        for (Thread t : tThreads) t.join();

        System.out.printf("%n%-28s %8s %8s %8s %10s %10s %10s %10s%n", "Session", "Sent", "OK", "Failed",
                "Avg ms", "Median ms", "96% min", "96% max");
        boolean tAllOk = true;
        for (TestSession tSession : tSessions) {
            System.out.println(tSession.summary());
            tAllOk &= tSession.failed() == 0 && tSession.completed();
        }
        System.out.printf("Elapsed %.1f s%n", (System.currentTimeMillis() - tStart) / 1000.0);
        System.exit(tAllOk ? 0 : 2);
    }

    /**
     * Parses the config as strict JSON, so mistakes such as a trailing comma are reported with
     * their position instead of being read as null entries (Gson's default is lenient).
     */
    private static JsonObject readConfig(String pPath) throws Exception {
        try (JsonReader tReader = new JsonReader(new StringReader(Files.readString(Path.of(pPath))))) {
            tReader.setStrictness(Strictness.STRICT);
            JsonObject tConfig = JsonParser.parseReader(tReader).getAsJsonObject();
            if (tReader.peek() != JsonToken.END_DOCUMENT) throw new IllegalStateException("unexpected data after the JSON object");
            return tConfig;
        }
    }

    /** An SSL context that accepts any certificate; host name verification is disabled too. For testing only. */
    private static SSLContext trustAllContext() throws Exception {
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        TrustManager tTrustAll = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] pChain, String pAuthType) {}
            public void checkServerTrusted(X509Certificate[] pChain, String pAuthType) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext tContext = SSLContext.getInstance("TLS");
        tContext.init(null, new TrustManager[] { tTrustAll }, null);
        return tContext;
    }
}
