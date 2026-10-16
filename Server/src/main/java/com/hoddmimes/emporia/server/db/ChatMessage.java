package com.hoddmimes.emporia.server.db;

/** A chat message (collection/table Chat); time in epoch millis, tag "" if none. */
public record ChatMessage(String chatId, long time, String firm, String user, String text, String tag) {
}
