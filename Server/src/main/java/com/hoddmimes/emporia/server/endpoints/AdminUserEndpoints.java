package com.hoddmimes.emporia.server.endpoints;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hoddmimes.emporia.server.RestApiContext;
import io.javalin.Javalin;

import java.util.Set;

public class AdminUserEndpoints implements RestEndpoint {

    private final RestApiContext mCtx;

    public AdminUserEndpoints(RestApiContext ctx) {
        mCtx = ctx;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/admin/users", ctx -> {
            JsonArray tArray = new JsonArray();
            for (var tDoc : mCtx.database.listUsers()) {
                tArray.add(mCtx.database.userToJson(tDoc));
            }
            ctx.json(tArray.toString());
        });

        app.post("/api/admin/users", ctx -> {
            JsonObject tBody   = JsonParser.parseString(ctx.body()).getAsJsonObject();
            String tUserId = RestApiContext.field(tBody, "userId", "").trim();
            String tName   = RestApiContext.field(tBody, "name",   "").trim();
            if (tUserId.isEmpty()) { ctx.status(400).json("{\"error\":\"ID is required\"}"); return; }
            if (tName.isEmpty())   { ctx.status(400).json("{\"error\":\"Name is required\"}"); return; }
            String tMongoId = mCtx.database.createUser(tUserId,
                    RestApiContext.field(tBody, "firm",     ""),
                    tName,
                    RestApiContext.field(tBody, "mail",     ""),
                    RestApiContext.field(tBody, "phone",    ""),
                    RestApiContext.field(tBody, "status",   "INACTIVE"),
                    RestApiContext.field(tBody, "password", ""));
            ctx.status(201).json("{\"mongoId\":\"" + tMongoId + "\"}");
        });

        app.get("/api/admin/users/{id}", ctx -> {
            try {
                var tDoc = mCtx.database.getUser(ctx.pathParam("id"));
                if (tDoc == null) { ctx.status(404).json("{\"error\":\"User not found\"}"); return; }
                ctx.json(mCtx.database.userToJson(tDoc).toString());
            } catch (IllegalArgumentException e) {
                ctx.status(400).json("{\"error\":\"Invalid user ID\"}");
            }
        });

        app.patch("/api/admin/users/{id}", ctx -> {
            JsonObject tBody   = JsonParser.parseString(ctx.body()).getAsJsonObject();
            String tStatus = RestApiContext.field(tBody, "status", "INACTIVE");
            if (!Set.of("ACTIVE", "SUSPENDED", "INACTIVE").contains(tStatus)) {
                ctx.status(400).json("{\"error\":\"Invalid status value\"}"); return;
            }
            try {
                String tPassword = tBody.has("password") ? tBody.get("password").getAsString() : null;
                mCtx.database.updateUser(ctx.pathParam("id"),
                        RestApiContext.field(tBody, "firm",  ""),
                        RestApiContext.field(tBody, "name",  ""),
                        RestApiContext.field(tBody, "mail",  ""),
                        RestApiContext.field(tBody, "phone", ""),
                        tStatus,
                        tPassword);
                ctx.json("{\"success\":true}");
            } catch (IllegalArgumentException e) {
                ctx.status(400).json("{\"error\":\"Invalid user ID\"}");
            }
        });

        app.delete("/api/admin/users/{id}", ctx -> {
            try {
                mCtx.database.deleteUser(ctx.pathParam("id"));
                ctx.json("{\"success\":true}");
            } catch (IllegalArgumentException e) {
                ctx.status(400).json("{\"error\":\"Invalid user ID\"}");
            }
        });
    }
}
