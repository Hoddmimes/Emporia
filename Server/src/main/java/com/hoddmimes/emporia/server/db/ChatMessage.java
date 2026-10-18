package com.hoddmimes.emporia.server.db;

/**
 * A chat message (collection/table Chat); time in epoch millis, tag "" if none. A system message (system true)
 * is sent by an admin from the admin interface: firm "", user the admin.
 */
public record ChatMessage(String chatId, long time, String firm, String user, String text, String tag, boolean system) {

    /** A trading user's message (no system message). */
    public ChatMessage(String chatId, long time, String firm, String user, String text, String tag) {
        this(chatId, time, firm, user, text, tag, false);
    }
}
