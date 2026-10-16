package com.hoddmimes.emporia.server.db;

import com.google.gson.JsonObject;

/**
 * A trading user (collection/table User); password is the SHA-512 hex of the password ("" if none).
 * Null fields are stored as "".
 */
public record User(String userId, String firm, String name, String mail, String phone, String status, String password) {

    public User {
        userId   = Database.orEmpty(userId);
        firm     = Database.orEmpty(firm);
        name     = Database.orEmpty(name);
        mail     = Database.orEmpty(mail);
        phone    = Database.orEmpty(phone);
        status   = Database.orEmpty(status);
        password = Database.orEmpty(password);
    }

    /** The admin API's JSON: databaseId, and the userId as id. */
    public static JsonObject toJson(Stored<User> pUser) {
        User u = pUser.value();
        JsonObject tJson = new JsonObject();
        tJson.addProperty("id",       u.userId);
        tJson.addProperty("firm",     u.firm);
        tJson.addProperty("password", u.password);
        tJson.addProperty("name",     u.name);
        tJson.addProperty("mail",     u.mail);
        tJson.addProperty("phone",    u.phone);
        tJson.addProperty("status",   u.status);
        return pUser.withId(tJson);
    }
}
