package com.hoddmimes.emporia.server.audit;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class FileAuditLogTest {

    @TempDir
    Path mDir;

    private static final LocalDate DAY = LocalDate.of(2026, 10, 18);

    private static long millis(LocalDate pDate, int pHour, int pMinute, int pSecond, int pMillis) {
        return LocalDateTime.of(pDate.getYear(), pDate.getMonth(), pDate.getDayOfMonth(), pHour, pMinute, pSecond, pMillis * 1000000)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static JsonObject data(String pKey, String pValue) {
        JsonObject tData = new JsonObject();
        tData.addProperty(pKey, pValue);
        return tData;
    }

    @Test
    void writesAndReadsMessages() throws Exception {
        AtomicLong tClock = new AtomicLong(millis(DAY, 9, 5, 7, 42));
        FileAuditLog tLog = new FileAuditLog(mDir, tClock::get);
        tLog.start();
        tLog.queue(AuditLog.REQUEST, "NORDAGRI", "anna", data("rid", "r1"), "{\"instrumentId\":\"4294967297\",\"price\":10100000}");
        tClock.addAndGet(3);
        tLog.queue(AuditLog.RESPONSE, "NORDAGRI", "anna", data("rid", "r1"), "not json\nsecond line");
        tLog.stopLogging(5000);

        List<AuditMessage> tMessages = tLog.read(DAY);
        assertEquals(2, tMessages.size());
        AuditMessage tRequest = tMessages.get(0);
        assertEquals("09:05:07.042", tRequest.time());
        assertEquals(AuditLog.REQUEST, tRequest.type());
        assertEquals("NORDAGRI", tRequest.firm());
        assertEquals("anna", tRequest.user());
        assertEquals("r1", tRequest.json().get("rid").getAsString());
        assertEquals(10100000, tRequest.json().getAsJsonObject("body").get("price").getAsLong());
        assertEquals("09:05:07.045", tMessages.get(1).time());
        assertEquals("not json\nsecond line", tMessages.get(1).json().get("body").getAsString());

        // One line per message, starting with time and type
        List<String> tLines = Files.readAllLines(tLog.file(DAY));
        assertEquals(2, tLines.size());
        assertTrue(tLines.get(0).startsWith("{\"time\":\"09:05:07.042\",\"type\":\"REQUEST\",\"firm\":\"NORDAGRI\",\"user\":\"anna\""),
                tLines.get(0));
    }

    @Test
    void masksPasswords() throws Exception {
        FileAuditLog tLog = new FileAuditLog(mDir, () -> millis(DAY, 10, 0, 0, 0));
        tLog.start();
        tLog.queue(AuditLog.REQUEST, null, null, null, "{\"firm\":\"F\",\"userId\":\"u\",\"password\":\"secret\"}");
        tLog.queue(AuditLog.RESPONSE, null, null, null, "[{\"userId\":\"u\",\"password\":\"abc123\"}]");
        tLog.queue(AuditLog.REQUEST, null, null, null, "{\"currentPassword\":\"a\",\"newPassword\":\"b\"}");
        tLog.queue(AuditLog.RESPONSE, null, null, null, "{\"success\":true,\"sessionId\":\"node01abc\"}");
        tLog.stopLogging(5000);

        String tContent = Files.readString(tLog.file(DAY));
        assertFalse(tContent.contains("secret"));
        assertFalse(tContent.contains("abc123"));
        List<AuditMessage> tMessages = tLog.read(DAY);
        assertEquals("***", tMessages.get(0).json().getAsJsonObject("body").get("password").getAsString());
        assertEquals("u",   tMessages.get(0).json().getAsJsonObject("body").get("userId").getAsString());
        assertEquals("***", tMessages.get(1).json().getAsJsonArray("body").get(0).getAsJsonObject().get("password").getAsString());
        assertEquals("***", tMessages.get(2).json().getAsJsonObject("body").get("newPassword").getAsString());
        assertEquals("***", tMessages.get(3).json().getAsJsonObject("body").get("sessionId").getAsString());
    }

    @Test
    void restartAppendsAndEndsCutOffLine() throws Exception {
        FileAuditLog tFirst = new FileAuditLog(mDir, () -> millis(DAY, 8, 0, 0, 0));
        tFirst.start();
        tFirst.queue(AuditLog.START, null, null, null, null);
        tFirst.stopLogging(5000);
        // A crash in the middle of a line
        Files.writeString(tFirst.file(DAY), "{\"time\":\"08:00:01.000\",\"ty", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        FileAuditLog tSecond = new FileAuditLog(mDir, () -> millis(DAY, 8, 30, 0, 0));
        tSecond.start();
        tSecond.queue(AuditLog.START, null, null, null, null);
        tSecond.stopLogging(5000);

        List<AuditMessage> tMessages = tSecond.read(DAY);
        assertEquals(2, tMessages.size());
        assertEquals("08:00:00.000", tMessages.get(0).time());
        assertEquals("08:30:00.000", tMessages.get(1).time());
        assertEquals(3, Files.readAllLines(tSecond.file(DAY)).size());
    }

    @Test
    void oneFilePerDay() throws Exception {
        AtomicLong tClock = new AtomicLong(millis(DAY, 23, 59, 59, 999));
        FileAuditLog tLog = new FileAuditLog(mDir, tClock::get);
        // Queued before the thread runs, so both are written in one batch
        tLog.queue(AuditLog.REQUEST, null, null, data("rid", "r1"), null);
        tClock.addAndGet(2);
        tLog.queue(AuditLog.RESPONSE, null, null, data("rid", "r1"), null);
        tLog.start();
        tLog.stopLogging(5000);

        assertEquals(List.of("23:59:59.999"), tLog.read(DAY).stream().map(AuditMessage::time).toList());
        assertEquals(List.of("00:00:00.001"), tLog.read(DAY.plusDays(1)).stream().map(AuditMessage::time).toList());
        assertTrue(tLog.read(DAY.minusDays(1)).isEmpty());
    }

    @Test
    void unscaled() {
        assertEquals("101.5", AuditLog.unscaled(10150000).toString());
        assertEquals("100",   AuditLog.unscaled(10000000).toString());
        assertEquals("0.00001", AuditLog.unscaled(1).toString());
    }
}
