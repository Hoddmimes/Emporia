package com.hoddmimes.emporia.server.db;

import com.google.gson.JsonObject;

/**
 * A value read from the database with its databaseId: the record's id in the database (a MongoDB
 * ObjectId, or an id of the same form in SQLite: 24 hex digits), used by the admin pages and the admin
 * API to address it.
 */
public record Stored<T>(String databaseId, T value) {

    /** The JSON object with the databaseId added. */
    public JsonObject withId(JsonObject pJson) {
        pJson.addProperty("databaseId", databaseId);
        return pJson;
    }
}
