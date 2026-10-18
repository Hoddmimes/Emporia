package com.hoddmimes.emporia.server.audit;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The audit log: every REST request and response, every trade and the server's start and stop, one file
 * per day (FileAuditLog). Queuing is cheap and never waits for the disk; the messages are written by a
 * thread of their own.
 */
public interface AuditLog {

    // Message types
    String START          = "START";            // server started: version, mode
    String STOP           = "STOP";             // server stopped
    String REQUEST        = "REQUEST";          // a REST request: rid, method, path, query, remote, body
    String RESPONSE       = "RESPONSE";         // its response: rid, status, ms, body
    String TRADE          = "TRADE";            // an order book trade or reversal, both sides
    String BULLETIN_TRADE = "BULLETIN_TRADE";   // a bulletin trade or reversal, both sides

    /**
     * Queues a message, time stamped now. pFirm and pUser are the trading user's (null if none), pData the
     * message's fields, pBody a request or response body (null if none; JSON is stored as JSON, with the
     * values of password and sessionId fields masked, anything else as a string).
     */
    void queue(String pType, String pFirm, String pUser, JsonObject pData, String pBody);

    /** Opens the day's audit file and reads all its messages, in the order they were queued (none without a file). */
    List<AuditMessage> read(LocalDate pDate) throws IOException;

    /** A scaled price or quantity (MatchingEngine.SCALE) in display units, exactly, e.g. 101.5. */
    static JsonPrimitive unscaled(long pScaled) {
        return new JsonPrimitive(new BigDecimal(BigDecimal.valueOf(pScaled, 5).stripTrailingZeros().toPlainString()));
    }

    /** An audit log that drops everything (unit tests). */
    AuditLog NONE = new AuditLog() {
        @Override public void queue(String pType, String pFirm, String pUser, JsonObject pData, String pBody) {}
        @Override public List<AuditMessage> read(LocalDate pDate) { return List.of(); }
    };
}
