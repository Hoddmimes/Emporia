package com.hoddmimes.emporia.simulator;

import com.google.gson.JsonObject;

/** Reading values from the configuration file's JSON. */
final class Config {

    private static final long SCALE = 100000L;

    private Config() {}

    static String string(JsonObject pConfig, String pName) {
        if (!pConfig.has(pName)) throw new IllegalArgumentException("'" + pName + "' is required in " + pConfig);
        return pConfig.get(pName).getAsString();
    }

    static int integer(JsonObject pConfig, String pName, int pDefault) {
        return pConfig.has(pName) ? pConfig.get(pName).getAsInt() : pDefault;
    }

    static double decimal(JsonObject pConfig, String pName, double pDefault) {
        return pConfig.has(pName) ? pConfig.get(pName).getAsDouble() : pDefault;
    }

    /** A required decimal (price or volume), scaled like the API's values. */
    static long scaled(JsonObject pConfig, String pName) {
        if (!pConfig.has(pName)) throw new IllegalArgumentException("'" + pName + "' is required in " + pConfig);
        return Math.round(pConfig.get(pName).getAsDouble() * SCALE);
    }

    /** An optional decimal, scaled; 0 if absent. */
    static long scaled(JsonObject pConfig, String pName, long pDefault) {
        return pConfig.has(pName) ? scaled(pConfig, pName) : pDefault;
    }
}
