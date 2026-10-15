package com.hoddmimes.emporia.server.endpoints;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hoddmimes.emporia.server.Database;
import com.hoddmimes.emporia.server.RestApiContext;
import com.hoddmimes.emporia.server.SessionUser;
import com.hoddmimes.emporia.server.bdx.BdxMessage;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/**
 * Chat between the trading users of all firms. POST /api/chat stores a message (free text, at most
 * MAX_TEXT_BYTES in UTF-8, and an optional tag of at most MAX_TAG_BYTES) in the Chat collection with
 * the sender's firm and user and the time, journaled, and then broadcasts it as a global Chat bdx message
 * to every connected trading client. GET /api/chat lists the stored messages up to and including a date,
 * optionally from a date and with a tag containing a text.
 */
public class ChatEndpoints implements RestEndpoint {

    private static final Logger cLog = LogManager.getLogger(ChatEndpoints.class);

    static final int MAX_TEXT_BYTES = 512;
    static final int MAX_TAG_BYTES  = 64;
    static final int DEFAULT_LIMIT  = 1000;
    static final int MAX_LIMIT      = 5000;

    private final RestApiContext mCtx;

    public ChatEndpoints(RestApiContext ctx) {
        mCtx = ctx;
    }

    @Override
    public void register(Javalin app) {
        app.post("/api/chat", ctx -> {
            JsonObject tBody;
            try { tBody = JsonParser.parseString(ctx.body()).getAsJsonObject(); }
            catch (Exception e) { OrderEndpoints.error(ctx, 400, "Request body must be a JSON object"); return; }
            SessionUser tUser = SessionUser.of(ctx);

            String tText, tTag;
            try {
                tText = string(tBody, "text");
                tTag  = string(tBody, "tag");
            } catch (IllegalArgumentException e) { OrderEndpoints.error(ctx, 400, e.getMessage()); return; }
            String tErr  = validate(tText, tTag);
            if (tErr != null) { OrderEndpoints.error(ctx, 400, tErr); return; }
            tText = tText.strip();
            tTag  = (tTag == null) ? "" : tTag.strip();

            long tNow = System.currentTimeMillis();
            String tChatId = new ObjectId().toHexString();
            // Stored (journaled) before anyone is told about it
            mCtx.database.saveChatMessage(new Document("chatId", tChatId)
                    .append("time", Database.formatTradeTime(tNow))
                    .append("firm", tUser.firm())
                    .append("user", tUser.userId())
                    .append("text", tText)
                    .append("tag",  tTag));
            mCtx.bdx.publish(BdxMessage.global(BdxMessage.CHAT)
                    .put("chatId", tChatId)
                    .put("time",   tNow)
                    .put("firm",   tUser.firm())
                    .put("user",   tUser.userId())
                    .put("text",   tText)
                    .put("tag",    tTag));
            cLog.info("Chat {} from {}/{}, tag '{}', {} bytes", tChatId, tUser.firm(), tUser.userId(), tTag,
                    tText.getBytes(StandardCharsets.UTF_8).length);

            ctx.status(201).json(messageJson(tChatId, tNow, tUser.firm(), tUser.userId(), tText, tTag).toString());
        });

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
            List<Document> tDocs;
            try { tDocs = mCtx.database.listChatMessages(tFromDate, tDate, tTag, tLimit + 1); }
            catch (IllegalArgumentException e) { OrderEndpoints.error(ctx, 400, "date and fromDate must be yyyy-MM-dd"); return; }

            boolean tMore = tDocs.size() > tLimit;   // older messages left out
            JsonArray tMessages = new JsonArray();
            for (Document tDoc : tMore ? tDocs.subList(1, tDocs.size()) : tDocs) {
                tMessages.add(messageJson(tDoc.getString("chatId"), Database.parseTradeTime(tDoc.getString("time")),
                        tDoc.getString("firm"), tDoc.getString("user"), tDoc.getString("text"), tDoc.getString("tag")));
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

    private static JsonObject messageJson(String pChatId, long pTime, String pFirm, String pUser, String pText, String pTag) {
        JsonObject tJson = new JsonObject();
        tJson.addProperty("chatId", pChatId);
        tJson.addProperty("time",   pTime);
        tJson.addProperty("firm",   pFirm);
        tJson.addProperty("user",   pUser);
        tJson.addProperty("text",   pText);
        tJson.addProperty("tag",    (pTag != null) ? pTag : "");
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
