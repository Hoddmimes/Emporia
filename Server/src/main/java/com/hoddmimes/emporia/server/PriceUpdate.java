package com.hoddmimes.emporia.server;

import com.google.gson.JsonObject;

/**
 * Price information for one instrument, pushed to trading clients over the /ws WebSocket.
 * Fields left null are omitted from the message; the client keeps its previous value for them.
 */
public record PriceUpdate(long instrumentId,
                          Long bidVol, Double bidPx,
                          Long askVol, Double askPx,
                          Double open, Double high, Double last,
                          Long volume) {

    public JsonObject toJson() {
        JsonObject j = new JsonObject();
        j.addProperty("type",         "PriceUpdate");
        j.addProperty("instrumentId", instrumentId);
        if (bidVol != null) j.addProperty("bidVol", bidVol);
        if (bidPx  != null) j.addProperty("bidPx",  bidPx);
        if (askVol != null) j.addProperty("askVol", askVol);
        if (askPx  != null) j.addProperty("askPx",  askPx);
        if (open   != null) j.addProperty("open",   open);
        if (high   != null) j.addProperty("high",   high);
        if (last   != null) j.addProperty("last",   last);
        if (volume != null) j.addProperty("volume", volume);
        return j;
    }
}
