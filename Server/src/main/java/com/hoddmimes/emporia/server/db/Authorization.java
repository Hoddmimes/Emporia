package com.hoddmimes.emporia.server.db;

import com.google.gson.JsonObject;

/** A firm's authorization for a market (collection/table Authorization): a marketId, or "*" for all markets. */
public record Authorization(String firm, String market) {

    public static JsonObject toJson(Stored<Authorization> pAuthorization) {
        JsonObject tJson = new JsonObject();
        tJson.addProperty("firm",   pAuthorization.value().firm());
        tJson.addProperty("market", pAuthorization.value().market());
        return pAuthorization.withId(tJson);
    }
}
