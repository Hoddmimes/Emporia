package com.hoddmimes.emporia.server.audit;

import com.google.gson.JsonObject;

/**
 * One audit log message as read back: its queue time (HH:mm:ss.SSS), type, the trading user's firm and
 * user (null if none) and the whole line as JSON (these fields included).
 */
public record AuditMessage(String time, String type, String firm, String user, JsonObject json) {
}
