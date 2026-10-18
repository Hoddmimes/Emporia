package com.hoddmimes.emporia.server.endpoints;

import com.google.gson.JsonArray;
import com.hoddmimes.emporia.server.RestApiContext;
import com.hoddmimes.emporia.server.audit.AuditMessage;
import io.javalin.Javalin;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * The audit log in the admin interface (page audit.html):
 *
 * GET /api/admin/audit?date=yyyy-MM-dd   all audit messages of the day (default today), in the order they were
 *                                        queued, each the JSON object of its line (AuditLog.read); [] if the day
 *                                        has no audit file. The page filters them itself.
 *
 * Its own RESPONSE audit message has no body (RestApi.NO_AUDIT_RESPONSE_BODY), or every view would copy the day
 * into the audit log again.
 */
public class AdminAuditEndpoints implements RestEndpoint {

    public static final String PATH = "/api/admin/audit";

    private final RestApiContext mCtx;

    public AdminAuditEndpoints(RestApiContext ctx) {
        mCtx = ctx;
    }

    @Override
    public void register(Javalin app) {
        app.get(PATH, ctx -> {
            String tDateParam = ctx.queryParam("date");
            LocalDate tDate;
            try {
                tDate = (tDateParam == null || tDateParam.isBlank()) ? LocalDate.now() : LocalDate.parse(tDateParam);
            } catch (DateTimeParseException e) {
                ctx.status(400).json("{\"error\":\"Invalid date, expected yyyy-MM-dd: " + tDateParam.replace("\"", "") + "\"}");
                return;
            }
            JsonArray tResult = new JsonArray();
            try {
                for (AuditMessage tMessage : mCtx.audit.read(tDate)) tResult.add(tMessage.json());
            } catch (IOException e) {
                ctx.status(500).json("{\"error\":\"Failed to read the audit log\"}");
                return;
            }
            ctx.json(tResult.toString());
        });
    }
}
