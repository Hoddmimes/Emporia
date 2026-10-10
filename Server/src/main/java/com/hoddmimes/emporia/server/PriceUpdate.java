package com.hoddmimes.emporia.server;

import com.google.gson.JsonObject;

/**
 * Price information for one instrument, pushed to trading clients over the /ws WebSocket.
 * Each update is a full snapshot: a null field (e.g. an empty book side, or no trades yet)
 * is sent as JSON null so the client clears its previous value.
 * Prices and volumes are in display units (not scaled).
 */
public record PriceUpdate(long instrumentId,
                          Double bidVol, Double bidPx,
                          Double askVol, Double askPx,
                          Double open, Double high, Double last,
                          Double volume) {

    public JsonObject toJson() {
        JsonObject j = new JsonObject();
        j.addProperty("type",         "PriceUpdate");
        j.addProperty("instrumentId", instrumentId);
        j.addProperty("bidVol",       bidVol);
        j.addProperty("bidPx",        bidPx);
        j.addProperty("askVol",       askVol);
        j.addProperty("askPx",        askPx);
        j.addProperty("open",         open);
        j.addProperty("high",         high);
        j.addProperty("last",         last);
        j.addProperty("volume",       volume);
        return j;
    }
}
