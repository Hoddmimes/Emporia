package com.hoddmimes.emporia.server.db;

import com.google.gson.JsonObject;

/** A firm (collection/table Firm). Null fields are stored as "". */
public record Firm(String name, String status, String address, String phone, String contact, String mail) {

    public Firm {
        name    = Database.orEmpty(name);
        status  = Database.orEmpty(status);
        address = Database.orEmpty(address);
        phone   = Database.orEmpty(phone);
        contact = Database.orEmpty(contact);
        mail    = Database.orEmpty(mail);
    }

    /** The admin API's JSON; its id is the databaseId. */
    public static JsonObject toJson(Stored<Firm> pFirm) {
        Firm f = pFirm.value();
        JsonObject tJson = new JsonObject();
        tJson.addProperty("id",      pFirm.databaseId());
        tJson.addProperty("name",    f.name);
        tJson.addProperty("status",  f.status);
        tJson.addProperty("address", f.address);
        tJson.addProperty("phone",   f.phone);
        tJson.addProperty("contact", f.contact);
        tJson.addProperty("mail",    f.mail);
        return tJson;
    }
}
