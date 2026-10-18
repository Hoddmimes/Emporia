package com.hoddmimes.emporia.server.endpoints;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hoddmimes.emporia.server.RestApiContext;
import com.hoddmimes.emporia.server.SessionUser;
import com.hoddmimes.emporia.server.bdx.BdxDistributor;
import com.hoddmimes.emporia.server.bdx.BdxMessage;
import com.hoddmimes.emporia.server.db.ChatMessage;
import com.hoddmimes.emporia.server.db.Database;
import com.hoddmimes.emporia.server.db.DatabaseIds;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/**
 * Chat between the trading users of all firms. POST /api/chat stores a message (free text, at most
 * MAX_TEXT_BYTES in UTF-8, and an optional tag of at most MAX_TAG_BYTES) in the Chat collection with
 * the sender's firm and user and the time, durably, and then broadcasts it as a global Chat bdx message
 * to every connected trading client. GET /api/chat lists the stored messages up to and including a date,
 * optionally from a date and with a tag containing a text.
 *
 * System messages (system true) are sent by an admin: POST /api/admin/chat (same fields and rules; firm "",
 * user the admin), stored and broadcast like the others. GET /api/chat/system and GET /api/admin/chat/system
 * return the latest ones of any day (?limit=, default SYSTEM_LIMIT), oldest first, which the trading page and
 * the admin interface load at startup.
 */
public class ChatEndpoints implements RestEndpoint {

    private static final Logger cLog = LogManager.getLogger(ChatEndpoints.class);

    static final int MAX_TEXT_BYTES = 512;
    static final int MAX_TAG_BYTES  = 64;
    static final int DEFAULT_LIMIT  = 1000;
    static final int MAX_LIMIT      = 5000;
    static final int SYSTEM_LIMIT   = 10;
    static final int MAX_SYSTEM_LIMIT = 100;

    private final RestApiContext mCtx;

    public ChatEndpoints(RestApiContext ctx) {
        mCtx = ctx;
    }

    @Override
    public void register(Javalin app) {
        app.post("/api/chat", ctx -> {
            SessionUser tUser = SessionUser.of(ctx);
            post(ctx, tUser.firm(), tUser.userId(), false);
        });

        app.post("/api/admin/chat", ctx -> post(ctx, "", ctx.sessionAttribute(RestApiContext.ADMIN_USER), true));

        app.get("/api/chat/system",       this::listSystem);
        app.get("/api/admin/chat/system", this::listSystem);

        // ?date=yyyy-MM-dd (default today: up to and including that day), ?fromDate=yyyy-MM-dd (from that day),
        // ?tag= (tag contains, case-insensitive), ?limit= (the latest ones, default DEFAULT_LIMIT)
        app.get("/api/chat", ctx -> {
            String tDate     = param(ctx, "date");
            String tFromDate = param(ctx, "fromDate");
            String tTag      = param(ctx, "tag");
            if (tDate == null) tDate = LocalDate.now().toString();
            int tLimit = DEFAULT_LIMIT;
            String tLimitParam = param(ctx, "limit");
            if (tLimitParam != null) {
                try { tLimit = Integer.parseInt(tLimitParam); }
                catch (NumberFormatException e) { tLimit = 0; }
                if (tLimit < 1 || tLimit > MAX_LIMIT) {
                    OrderEndpoints.error(ctx, 400, "limit must be 1.." + MAX_LIMIT); return;
                }
            }
            List<ChatMessage> tStored;
            try { tStored = mCtx.database.listChatMessages(tFromDate, tDate, tTag, tLimit + 1); }
            catch (IllegalArgumentException e) { OrderEndpoints.error(ctx, 400, "date and fromDate must be yyyy-MM-dd"); return; }

            boolean tMore = tStored.size() > tLimit;   // older messages left out
            JsonArray tMessages = new JsonArray();
            for (ChatMessage m : tMore ? tStored.subList(1, tStored.size()) : tStored) {
                tMessages.add(messageJson(m));
            }
            JsonObject tOut = new JsonObject();
            tOut.addProperty("date", tDate);
            if (tFromDate != null) tOut.addProperty("fromDate", tFromDate);
            if (tTag != null) tOut.addProperty("tag", tTag);
            tOut.addProperty("more", tMore);
            tOut.add("messages", tMessages);
            ctx.json(tOut.toString());
        });
    }

    // Stores and broadcasts a message from the request body's text and tag; pSystem: a system message (admin)
    private void post(Context ctx, String pFirm, String pUser, boolean pSystem) {
        JsonObject tBody;
        try { tBody = JsonParser.parseString(ctx.body()).getAsJsonObject(); }
        catch (Exception e) { OrderEndpoints.error(ctx, 400, "Request body must be a JSON object"); return; }

        String tText, tTag;
        try {
            tText = string(tBody, "text");
            tTag  = string(tBody, "tag");
        } catch (IllegalArgumentException e) { OrderEndpoints.error(ctx, 400, e.getMessage()); return; }
        String tErr  = validate(tText, tTag);
        if (tErr != null) { OrderEndpoints.error(ctx, 400, tErr); return; }
        tText = tText.strip();
        tTag  = (tTag == null) ? "" : tTag.strip();

        ChatMessage tMessage = send(mCtx.database, mCtx.bdx,
                new ChatMessage(DatabaseIds.newId(), System.currentTimeMillis(), pFirm, pUser, tText, tTag, pSystem));
        ctx.status(201).json(messageJson(tMessage).toString());
    }

    /**
     * Stores the message durably, then broadcasts it as a global Chat bdx message to every connected trading
     * client; also used by the server for its startup system message (Server.sendStartupMessage).
     */
    public static ChatMessage send(Database pDatabase, BdxDistributor pBdx, ChatMessage pMessage) {
        // Stored (durably) before anyone is told about it
        pDatabase.saveChatMessage(pMessage);
        pBdx.publish(BdxMessage.global(BdxMessage.CHAT)
                .put("chatId", pMessage.chatId())
                .put("time",   pMessage.time())
                .put("firm",   pMessage.firm())
                .put("user",   pMessage.user())
                .put("text",   pMessage.text())
                .put("tag",    pMessage.tag())
                .put("system", pMessage.system()));
        cLog.info("{} {} from {}/{}, tag '{}', {} bytes", pMessage.system() ? "System chat" : "Chat", pMessage.chatId(),
                pMessage.firm(), pMessage.user(), pMessage.tag(), pMessage.text().getBytes(StandardCharsets.UTF_8).length);
        return pMessage;
    }

    // The latest system messages: ?limit= (1..MAX_SYSTEM_LIMIT, default SYSTEM_LIMIT)
    private void listSystem(Context ctx) {
        int tLimit = SYSTEM_LIMIT;
        String tLimitParam = param(ctx, "limit");
        if (tLimitParam != null) {
            try { tLimit = Integer.parseInt(tLimitParam); }
            catch (NumberFormatException e) { tLimit = 0; }
            if (tLimit < 1 || tLimit > MAX_SYSTEM_LIMIT) {
                OrderEndpoints.error(ctx, 400, "limit must be 1.." + MAX_SYSTEM_LIMIT); return;
            }
        }
        JsonArray tMessages = new JsonArray();
        for (ChatMessage m : mCtx.database.listSystemChatMessages(tLimit)) tMessages.add(messageJson(m));
        JsonObject tOut = new JsonObject();
        tOut.add("messages", tMessages);
        ctx.json(tOut.toString());
    }

    /** Null if the text and tag are valid, otherwise the error. */
    static String validate(String pText, String pTag) {
        if (pText == null || pText.isBlank()) return "text is required";
        if (pText.strip().getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            return "text must be at most " + MAX_TEXT_BYTES + " bytes (UTF-8)";
        }
        if (pTag != null && pTag.strip().getBytes(StandardCharsets.UTF_8).length > MAX_TAG_BYTES) {
            return "tag must be at most " + MAX_TAG_BYTES + " bytes (UTF-8)";
        }
        return null;
    }

    private static JsonObject messageJson(ChatMessage m) {
        JsonObject tJson = new JsonObject();
        tJson.addProperty("chatId", m.chatId());
        tJson.addProperty("time",   m.time());
        tJson.addProperty("firm",   m.firm());
        tJson.addProperty("user",   m.user());
        tJson.addProperty("text",   m.text());
        tJson.addProperty("tag",    (m.tag() != null) ? m.tag() : "");
        tJson.addProperty("system", m.system());
        return tJson;
    }

    /** A string field, null if absent or JSON null; a number or boolean is taken as its text. */
    private static String string(JsonObject pBody, String pName) {
        JsonElement tValue = pBody.get(pName);
        if (tValue == null || tValue.isJsonNull()) return null;
        if (!tValue.isJsonPrimitive()) throw new IllegalArgumentException(pName + " must be a string");
        return tValue.getAsString();
    }

    /** A query parameter, trimmed; null if absent or blank. */
    private static String param(Context ctx, String pName) {
        String tValue = ctx.queryParam(pName);
        return (tValue == null || tValue.isBlank()) ? null : tValue.trim();
    }
}
