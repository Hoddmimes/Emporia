package com.hoddmimes.emporia.server.db;

import com.google.gson.JsonObject;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.Assumptions;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * The Database contract on MongoDbDatabase, in the database emporia_unittest (emptied before each
 * test) of a MongoDB on 127.0.0.1:27017; skipped when there is none.
 */
class MongoDbDatabaseContractTest extends DatabaseContractTest {

    private static final String HOST = "127.0.0.1";
    private static final int    PORT = 27017;
    private static final String NAME = "emporia_unittest";

    @Override
    Database open() {
        Assumptions.assumeTrue(mongoDbRuns(), "no MongoDB on " + HOST + ":" + PORT);
        try (MongoClient tClient = MongoClients.create("mongodb://" + HOST + ":" + PORT)) {
            tClient.getDatabase(NAME).drop();
        }
        JsonObject tConfig = new JsonObject();
        tConfig.addProperty("type", "mongodb");
        tConfig.addProperty("host", HOST);
        tConfig.addProperty("port", PORT);
        tConfig.addProperty("name", NAME);
        return Database.open(tConfig);
    }

    private static boolean mongoDbRuns() {
        try (Socket tSocket = new Socket()) {
            tSocket.connect(new InetSocketAddress(HOST, PORT), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
