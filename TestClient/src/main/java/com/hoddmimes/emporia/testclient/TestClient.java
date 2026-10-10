package com.hoddmimes.emporia.testclient;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Test program that logs in to the Emporia server through the REST API and sends orders,
 * as instructed by a JSON file:
 *
 * <pre>
 * {
 *   "url": "http://localhost:8080",
 *   "trustAllCertificates": false,      // accept any HTTPS certificate (expired, self-signed, wrong host)
 *   "verbose": false,                   // print every request and response
 *   "sessions": [
 *     { "firm": "ThinAir", "user": "Geek", "password": "secret",
 *       "repeat": 10,                    // times the directives are run (default 1)
 *       "txRate": 5,                     // directives per second, 0 = as fast as possible
 *       "directives": [
 *         { "type": "AddOrder", "ref": "b1", "instrument": "ABB", "side": "BUY",
 *           "price": 101.50, "quantity": 100, "userRef": "optional" },
 *         { "type": "DeleteOrder", "ref": "b1" } ] } ]
 * }
 * </pre>
 *
 * AddOrder takes the instrument by name ("instrument") or id ("instrumentId"); price and quantity
 * are decimals. DeleteOrder deletes the order last added with the same "ref". Sessions run
 * concurrently, each with its own HTTP session.
 */
public class TestClient {

    public static void main(String[] args) throws Exception {
        String tConfigPath = null;
        for (int i = 0; i < args.length - 1; i++) {
            if ("-config".equals(args[i])) tConfigPath = args[i + 1];
        }
        if (tConfigPath == null) {
            System.err.println("Usage: TestClient -config <file>");
            System.exit(1);
        }

        JsonObject tConfig = JsonParser.parseString(Files.readString(Path.of(tConfigPath))).getAsJsonObject();
        String  tUrl     = tConfig.get("url").getAsString().replaceAll("/+$", "");
        boolean tVerbose = tConfig.has("verbose") && tConfig.get("verbose").getAsBoolean();
        SSLContext tSslContext = (tConfig.has("trustAllCertificates") && tConfig.get("trustAllCertificates").getAsBoolean())
                ? trustAllContext() : SSLContext.getDefault();

        List<TestSession> tSessions = new ArrayList<>();
        int tIndex = 0;
        for (JsonElement tSession : tConfig.getAsJsonArray("sessions")) {
            tSessions.add(new TestSession(++tIndex, tSession.getAsJsonObject(), tUrl, tSslContext, tVerbose));
        }

        long tStart = System.currentTimeMillis();
        List<Thread> tThreads = new ArrayList<>();
        for (TestSession tSession : tSessions) {
            Thread t = new Thread(tSession, "session-" + tSession.name());
            t.start();
            tThreads.add(t);
        }
        for (Thread t : tThreads) t.join();

        System.out.printf("%n%-28s %8s %8s %8s %10s%n", "Session", "Sent", "OK", "Failed", "Avg ms");
        boolean tAllOk = true;
        for (TestSession tSession : tSessions) {
            System.out.println(tSession.summary());
            tAllOk &= tSession.failed() == 0 && tSession.completed();
        }
        System.out.printf("Elapsed %.1f s%n", (System.currentTimeMillis() - tStart) / 1000.0);
        System.exit(tAllOk ? 0 : 2);
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
