package com.hoddmimes.emporia.server.endpoints;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hoddmimes.emporia.server.RestApiContext;
import com.hoddmimes.emporia.server.db.Firm;
import io.javalin.Javalin;

import java.util.Set;

public class AdminFirmEndpoints implements RestEndpoint {

    private final RestApiContext mCtx;

    public AdminFirmEndpoints(RestApiContext ctx) {
        mCtx = ctx;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/admin/firms", ctx -> {
            JsonArray tArray = new JsonArray();
            for (var tFirm : mCtx.database.listFirms()) {
                tArray.add(Firm.toJson(tFirm));
            }
            ctx.json(tArray.toString());
        });

        app.post("/api/admin/firms", ctx -> {
            JsonObject tBody = JsonParser.parseString(ctx.body()).getAsJsonObject();
            String tName = tBody.has("name") ? tBody.get("name").getAsString().trim() : "";
            if (tName.isEmpty()) {
                ctx.status(400).json("{\"error\":\"Name is required\"}");
                return;
            }
            String tId = mCtx.database.createFirm(tName,
                    RestApiContext.field(tBody, "status",  "INACTIVE"),
                    RestApiContext.field(tBody, "address", ""),
                    RestApiContext.field(tBody, "phone",   ""),
                    RestApiContext.field(tBody, "contact", ""),
                    RestApiContext.field(tBody, "mail",    ""));
            ctx.status(201).json("{\"id\":\"" + tId + "\"}");
        });

        app.get("/api/admin/firms/{id}", ctx -> {
            try {
                var tFirm = mCtx.database.getFirm(ctx.pathParam("id"));
                if (tFirm == null) { ctx.status(404).json("{\"error\":\"Firm not found\"}"); return; }
                ctx.json(Firm.toJson(tFirm).toString());
            } catch (IllegalArgumentException e) {
                ctx.status(400).json("{\"error\":\"Invalid firm ID\"}");
            }
        });

        app.patch("/api/admin/firms/{id}", ctx -> {
            JsonObject tBody   = JsonParser.parseString(ctx.body()).getAsJsonObject();
            String tStatus = RestApiContext.field(tBody, "status", "INACTIVE");
            if (!Set.of("ACTIVE", "SUSPENDED", "INACTIVE").contains(tStatus)) {
                ctx.status(400).json("{\"error\":\"Invalid status value\"}"); return;
            }
            try {
                mCtx.database.updateFirm(ctx.pathParam("id"), tStatus,
                        RestApiContext.field(tBody, "address", ""),
                        RestApiContext.field(tBody, "phone",   ""),
                        RestApiContext.field(tBody, "contact", ""),
                        RestApiContext.field(tBody, "mail",    ""));
                ctx.json("{\"success\":true}");
            } catch (IllegalArgumentException e) {
                ctx.status(400).json("{\"error\":\"Invalid firm ID\"}");
            }
        });

        app.delete("/api/admin/firms/{id}", ctx -> {
            try {
                // Also deletes the firm's authorizations
                if (mCtx.database.deleteFirm(ctx.pathParam("id")) != null) mCtx.reloadAuthorizations();
                ctx.json("{\"success\":true}");
            } catch (IllegalArgumentException e) {
                ctx.status(400).json("{\"error\":\"Invalid firm ID\"}");
            }
        });
    }
}
