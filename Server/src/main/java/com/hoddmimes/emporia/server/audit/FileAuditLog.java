package com.hoddmimes.emporia.server.audit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.LongSupplier;

/**
 * The audit log in files, one per day: audit-yyyy-MM-dd.log in the audit directory, one message per line
 * as a JSON object (JSON Lines), starting with "time" (HH:mm:ss.SSS, when the message was queued), "type"
 * and, if there is one, the trading user's "firm" and "user", followed by the message's fields, e.g.
 *
 *   {"time":"14:03:27.512","type":"REQUEST","firm":"NORDAGRI","user":"anna","rid":"r17","method":"POST",...}
 *
 * A line is read with one JSON parse; a body never spans lines (JSON escapes its line breaks).
 *
 * queue() stamps the message with the time and puts it on a LinkedBlockingQueue; the writer thread
 * (started by start()) takes everything queued so far, appends it to the file of the day the messages were
 * queued on and fsyncs it (one fsync per batch), then takes the next batch. A message queued just before
 * midnight goes to that day's file even if it is written after midnight. A restarted server appends to the
 * day's existing file; a line cut off by a crash is ended before new lines are appended, and is skipped
 * when reading. A failed write is logged and retried every second, the messages kept in memory meanwhile.
 * Bodies are turned into JSON and their password and sessionId fields masked on the writer thread, not by the caller.
 */
public class FileAuditLog extends Thread implements AuditLog {

    private static final Logger cLog = LogManager.getLogger(FileAuditLog.class);

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final int    MAX_BATCH = 10000;
    private static final long   RETRY_MS  = 1000;
    private static final String MASK      = "***";

    /** A queued message; the time in epoch ms, formatted by the writer thread. */
    private record Queued(long time, String type, String firm, String user, JsonObject data, String body) {}

    private static final Queued STOP_MARKER = new Queued(0, null, null, null, null, null);

    private final Path                  mDirectory;
    private final ZoneId                mZone = ZoneId.systemDefault();
    private final LongSupplier          mClock;
    private final BlockingQueue<Queued> mQueue = new LinkedBlockingQueue<>();
    private volatile boolean            mStopped;

    private LocalDate   mFileDate;   // the date of the open file, writer thread only
    private FileChannel mChannel;

    public FileAuditLog(Path pDirectory) throws IOException {
        this(pDirectory, System::currentTimeMillis);
    }

    /** With a clock giving epoch ms (tests: messages on several days). */
    FileAuditLog(Path pDirectory, LongSupplier pClock) throws IOException {
        super("AuditLog");
        setDaemon(true);
        mDirectory = pDirectory;
        mClock     = pClock;
        Files.createDirectories(mDirectory);
        if (!Files.isWritable(mDirectory)) throw new IOException("Audit directory " + mDirectory.toAbsolutePath() + " is not writable");
        cLog.info("Audit log in {}", mDirectory.toAbsolutePath());
    }

    /** The day's audit file. */
    public Path file(LocalDate pDate) {
        return mDirectory.resolve("audit-" + pDate + ".log");
    }

    @Override
    public void queue(String pType, String pFirm, String pUser, JsonObject pData, String pBody) {
        if (mStopped) {
            cLog.warn("Audit log stopped, {} message dropped", pType);
            return;
        }
        // Stamped and queued under one lock, so the times in the file never go backwards
        synchronized (mQueue) {
            mQueue.add(new Queued(mClock.getAsLong(), pType, pFirm, pUser, pData, pBody));
        }
    }

    /** Writes what is queued, then ends the thread; waits at most pTimeoutMs. Later messages are dropped. */
    public void stopLogging(long pTimeoutMs) {
        synchronized (mQueue) {
            mStopped = true;
            mQueue.add(STOP_MARKER);
        }
        try {
            join(pTimeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (isAlive()) cLog.error("Audit log did not finish within {} ms, {} message(s) not written", pTimeoutMs, mQueue.size());
    }

    @Override
    public void run() {
        List<Queued> tBatch = new ArrayList<>();
        boolean tStop = false;
        while (!tStop) {
            try {
                tBatch.add(mQueue.take());
            } catch (InterruptedException e) {
                cLog.warn("Audit log interrupted, {} queued message(s) not written", mQueue.size());
                break;
            }
            mQueue.drainTo(tBatch, MAX_BATCH - 1);
            tStop = tBatch.remove(STOP_MARKER);
            if (!tBatch.isEmpty()) write(tBatch);
            tBatch.clear();
        }
        closeFile();
        cLog.info("Audit log stopped");
    }

    /** Writes the batch, retrying until it succeeds. */
    private void write(List<Queued> pBatch) {
        for (int tAttempt = 1; ; tAttempt++) {
            try {
                writeBatch(pBatch);
                if (tAttempt > 1) cLog.info("Wrote {} audit message(s) after {} attempts", pBatch.size(), tAttempt);
                return;
            } catch (IOException | RuntimeException e) {
                cLog.error("Failed to write {} audit message(s) (attempt {}), retrying in {} ms: {}",
                        pBatch.size(), tAttempt, RETRY_MS, e.toString());
                closeFile();
                try {
                    Thread.sleep(RETRY_MS);
                } catch (InterruptedException ie) {
                    cLog.error("Audit log interrupted, {} message(s) not written", pBatch.size());
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    // Appends the batch, one write and fsync per day it spans (normally one)
    private void writeBatch(List<Queued> pBatch) throws IOException {
        StringBuilder tLines = new StringBuilder();
        LocalDate tDate = null;
        for (Queued tMessage : pBatch) {
            LocalDateTime tTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(tMessage.time()), mZone);
            if (tDate != null && !tDate.equals(tTime.toLocalDate())) {
                append(tDate, tLines);
                tLines.setLength(0);
            }
            tDate = tTime.toLocalDate();
            tLines.append(line(tTime.format(TIME_FORMAT), tMessage)).append('\n');
        }
        append(tDate, tLines);
    }

    private void append(LocalDate pDate, StringBuilder pLines) throws IOException {
        if (mChannel == null || !pDate.equals(mFileDate)) openFile(pDate);
        ByteBuffer tBuffer = ByteBuffer.wrap(pLines.toString().getBytes(StandardCharsets.UTF_8));
        while (tBuffer.hasRemaining()) mChannel.write(tBuffer);
        mChannel.force(false);
    }

    private void openFile(LocalDate pDate) throws IOException {
        closeFile();
        Path tFile = file(pDate);
        FileChannel tChannel = FileChannel.open(tFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        // A line cut off by a crash: end it, so the next message starts on a line of its own
        // (a channel opened for appending can't read)
        long tSize = tChannel.size();
        if (tSize > 0) {
            ByteBuffer tLast = ByteBuffer.allocate(1);
            try (FileChannel tReader = FileChannel.open(tFile, StandardOpenOption.READ)) {
                tReader.read(tLast, tSize - 1);
            }
            if (tLast.get(0) != '\n') tChannel.write(ByteBuffer.wrap(new byte[] {'\n'}));
        }
        mChannel  = tChannel;
        mFileDate = pDate;
        cLog.info("Audit file {} ({})", tFile, (tSize > 0) ? "appending" : "new");
    }

    private void closeFile() {
        if (mChannel == null) return;
        try {
            mChannel.close();
        } catch (IOException e) {
            cLog.warn("Failed to close the audit file: {}", e.toString());
        }
        mChannel = null;
    }

    private static String line(String pTime, Queued pMessage) {
        JsonObject tLine = new JsonObject();
        tLine.addProperty("time", pTime);
        tLine.addProperty("type", pMessage.type());
        if (pMessage.firm() != null) tLine.addProperty("firm", pMessage.firm());
        if (pMessage.user() != null) tLine.addProperty("user", pMessage.user());
        if (pMessage.data() != null) {
            for (Map.Entry<String, JsonElement> tField : pMessage.data().entrySet()) tLine.add(tField.getKey(), tField.getValue());
        }
        if (pMessage.body() != null) tLine.add("body", body(pMessage.body()));
        return tLine.toString();   // JsonElement.toString: compact, one line, no HTML escaping
    }

    /** A body as JSON with its password fields masked, or as a string if it is no JSON. */
    static JsonElement body(String pBody) {
        String tTrimmed = pBody.trim();
        if (tTrimmed.startsWith("{") || tTrimmed.startsWith("[")) {
            try {
                return masked(JsonParser.parseString(tTrimmed));
            } catch (RuntimeException e) {
                // not JSON after all
            }
        }
        return new JsonPrimitive(pBody);
    }

    private static JsonElement masked(JsonElement pElement) {
        if (pElement.isJsonObject()) {
            JsonObject tObject = pElement.getAsJsonObject();
            for (Map.Entry<String, JsonElement> tField : tObject.entrySet()) {
                if (secret(tField.getKey())) tField.setValue(new JsonPrimitive(MASK));
                else masked(tField.getValue());
            }
        } else if (pElement.isJsonArray()) {
            JsonArray tArray = pElement.getAsJsonArray();
            for (JsonElement tItem : tArray) masked(tItem);
        }
        return pElement;
    }

    // Passwords, and the session id (the login response's), which would let a reader of the file take over the session
    private static boolean secret(String pKey) {
        return pKey.toLowerCase().contains("password") || pKey.equalsIgnoreCase("sessionId");
    }

    @Override
    public List<AuditMessage> read(LocalDate pDate) throws IOException {
        Path tFile = file(pDate);
        List<AuditMessage> tMessages = new ArrayList<>();
        if (!Files.exists(tFile)) return tMessages;
        try (BufferedReader tReader = Files.newBufferedReader(tFile, StandardCharsets.UTF_8)) {
            String tLine;
            int tLineNo = 0;
            while ((tLine = tReader.readLine()) != null) {
                tLineNo++;
                if (tLine.isBlank()) continue;
                try {
                    JsonObject tJson = JsonParser.parseString(tLine).getAsJsonObject();
                    tMessages.add(new AuditMessage(string(tJson, "time"), string(tJson, "type"),
                            string(tJson, "firm"), string(tJson, "user"), tJson));
                } catch (RuntimeException e) {
                    cLog.warn("{} line {}: not an audit message, skipped", tFile, tLineNo);
                }
            }
        }
        return tMessages;
    }

    private static String string(JsonObject pJson, String pKey) {
        JsonElement tValue = pJson.get(pKey);
        return (tValue != null && tValue.isJsonPrimitive()) ? tValue.getAsString() : null;
    }
}
