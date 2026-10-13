package com.hoddmimes.emporia.simulator;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.net.ssl.SSLContext;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The simulator's connection to the server's REST API: one HttpClient with a cookie store, so all
 * requests share one HTTP session. A request answered 401 (the session timed out, or the server was
 * restarted) logs in again and is sent once more.
 */
class Api {

    /** A reply: HTTP status and the body parsed as JSON (null if it is not JSON). */
    record Reply(int status, JsonElement body, String text) {
        boolean ok() { return status >= 200 && status < 300; }

        JsonObject object() { return body.getAsJsonObject(); }

        /** The server's {"error": ...} message, or the body text. */
        String error() {
            if (body != null && body.isJsonObject() && body.getAsJsonObject().has("error")) {
                return "HTTP " + status + ": " + body.getAsJsonObject().get("error").getAsString();
            }
            return "HTTP " + status + (text.isBlank() ? "" : ": " + text.strip());
        }
    }

    private final String     mUrl;
    private final String     mFirm;
    private final String     mUser;
    private final String     mPassword;
    private final HttpClient mHttp;

    Api(String pUrl, String pFirm, String pUser, String pPassword, SSLContext pSslContext) {
        mUrl      = pUrl;
        mFirm     = pFirm;
        mUser     = pUser;
        mPassword = pPassword;
        mHttp     = HttpClient.newBuilder()
                .cookieHandler(new CookieManager())
                .sslContext(pSslContext)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    HttpClient http() { return mHttp; }
    String     url()  { return mUrl; }

    /** Logs in; throws if the server refuses. */
    synchronized void login() throws Exception {
        JsonObject tBody = new JsonObject();
        tBody.addProperty("firm",     mFirm);
        tBody.addProperty("userId",   mUser);
        tBody.addProperty("password", mPassword);
        Reply tReply = send(request("/api/login").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(tBody.toString())));
        if (!tReply.ok()) throw new IllegalStateException("Login as " + mUser + "@" + mFirm + " failed: " + tReply.error());
        Events.print("LOGIN", mUser + "@" + mFirm + " logged in to " + mUrl);
    }

    Reply get(String pPath) throws Exception {
        return call(() -> request(pPath).GET());
    }

    Reply post(String pPath, JsonObject pBody) throws Exception {
        return call(() -> request(pPath).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(pBody.toString())));
    }

    Reply delete(String pPath) throws Exception {
        return call(() -> request(pPath).DELETE());
    }

    private interface RequestFactory { HttpRequest.Builder build(); }

    private Reply call(RequestFactory pRequest) throws Exception {
        Reply tReply = send(pRequest.build());
        if (tReply.status() == 401) {
            Events.print("SESSION", "not logged in (" + tReply.error() + "), logging in again");
            login();
            tReply = send(pRequest.build());
        }
        return tReply;
    }

    private HttpRequest.Builder request(String pPath) {
        return HttpRequest.newBuilder(URI.create(mUrl + pPath)).timeout(Duration.ofSeconds(30));
    }

    private Reply send(HttpRequest.Builder pRequest) throws Exception {
        HttpResponse<String> tRsp = mHttp.send(pRequest.build(), HttpResponse.BodyHandlers.ofString());
        JsonElement tBody = null;
        try { tBody = JsonParser.parseString(tRsp.body()); } catch (Exception e) { /* not JSON */ }
        return new Reply(tRsp.statusCode(), tBody, tRsp.body());
    }
}
