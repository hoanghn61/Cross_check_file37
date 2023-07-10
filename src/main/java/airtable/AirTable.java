package airtable;

import logs.Logs;
import slack.Channel;
import slack.SlackUser;
import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.EntityUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AirTable {
    private  static final String USERS_TABLE_NAME = "Users";
    private boolean isActive = true;
    private String token;
    private String base;
    static final HttpClientResponseHandler<String> responseHandler = httpResponse -> {
        int status = httpResponse.getCode();
        if (status != 200) {
            return null;
        }

        try {
            return EntityUtils.toString(httpResponse.getEntity());
        } catch (ParseException e) {
            return null;
        }
    };


    private Table channelTable = null;
    private Table userTable = null;
    private Table taskTable = null;

    public AirTable() {
        try {
            FileReader fileReader = new FileReader("src/main/resources/data/config.json");
            JsonObject jsonObject = new Gson().fromJson(new JsonReader(fileReader), JsonObject.class);
            this.token = jsonObject.get("airtable").getAsString();
            this.base = jsonObject.get("base").getAsString();
        } catch (FileNotFoundException e) {
            Logs.writeLog("Error: Could not find config.json");
            this.isActive = false;
            return;
        }

        long time = System.currentTimeMillis();
        String listTables = Table.listTables(base, token);
        if (listTables == null) {
            this.isActive = false;
            return;
        }else {
            Logs.writeLog("List tables: " + (System.currentTimeMillis() - time));
        }
        JsonArray tables = JsonParser.parseString(listTables).getAsJsonObject().getAsJsonArray("tables");
        for (JsonElement table : tables) {
            JsonObject tableJson = table.getAsJsonObject();
            String tableName = tableJson.get("name").getAsString();
            switch (tableName) {
                case "Channels" -> channelTable = new Table(tableJson);
                case USERS_TABLE_NAME -> userTable = new Table(tableJson);
                case "Tasks" -> taskTable = new Table(tableJson);
                default -> Logs.writeLog("Error: Unknown table " + tableName);
            }
        }

        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            executor.submit(() -> {
               channelTable = validTable(channelTable, "Channels");
               if (channelTable == null) {
                   isActive = false;
               }
            });
            executor.submit(() -> {
            userTable = validTable(userTable, USERS_TABLE_NAME);
            if (userTable == null) {
                 isActive = false;
            }
            });
            executor.submit(() -> {
                taskTable = validTable(taskTable, "Tasks");
                if (taskTable == null) {
                     isActive = false;
                }
            });

            executor.shutdown();
        }


        Field linkField = channelTable.getField(USERS_TABLE_NAME);
        if (linkField == null) {
           JsonObject newField = new JsonObject();
           newField.addProperty("name", USERS_TABLE_NAME);
           newField.addProperty("type", "multipleRecordLinks");

           JsonObject options = new JsonObject();
           options.addProperty("linkedTableId", userTable.getId());
           newField.add("options", options);
           if (channelTable.addField(newField, base, token)) {
               Logs.writeLog("Error: Could not add field Users to Channels table.");
               isActive = false;
           }
        }

        long time2 = System.currentTimeMillis();
        Logs.writeLog("AirTable initialized in " + (time2 - time) + "ms.");
    }
    public boolean isActive() {
       return isActive;
    }
    private Table validTable(Table table, String name) {
        try (FileReader fileReader = new FileReader("src/main/resources/data/fields.json")) {

            JsonObject jsonObject = new Gson().fromJson(new JsonReader(fileReader), JsonObject.class);

            JsonArray fields = jsonObject.getAsJsonArray(name);


            if (table == null) {
                String createTable = Table.createTable(name, fields, base, token);
                if (createTable == null) {
                    return null;
                }
                Logs.writeLog("Created table " + name + ".");
                return new Table(JsonParser.parseString(createTable).getAsJsonObject());
            }

            for (JsonElement field : fields) {
                JsonObject fieldJson = field.getAsJsonObject();
                String fieldName = fieldJson.get("name").getAsString();
                Field tableField = table.getField(fieldName);

                if (tableField == null){
                    Logs.writeLog("Adding field " + fieldName + " to table " + name + ".");
                    if(table.addField(fieldJson, base, token)) return null;
                }
            }
            return table;
        } catch (IOException e) {
            return null;
        }
    }
    private boolean pushChannels(List<Channel> channels) {
        List<JsonObject> fields = new ArrayList<>();
        for (Channel channel: channels){
            JsonObject field = channel.toJson();

            JsonArray membersId = field.getAsJsonArray("Members Id");
            JsonArray membersRecordId = new JsonArray();
            for (JsonElement member : membersId) {
                String memberId = member.getAsString();
                Record userRecord = userTable.getRecord(memberId);
                if (userRecord == null) {
                    Logs.writeLog("Error: Could not find user with id " + memberId + ".");
                    return false;
                }
                membersRecordId.add(userRecord.getRecordId());
            }
            field.add(USERS_TABLE_NAME, membersRecordId);
            field.remove("Members Id");

            fields.add(field);
        }
        if (channelTable.pullMultipleRecord(fields, base, token)) {
            Logs.writeLog("Error: Could not push channels to AirTable.");
            return false;
        }
        return true;
    }
    private boolean pushUsers(List<SlackUser> users){
        List<JsonObject> fields = new ArrayList<>();
        for (SlackUser user: users){
            JsonObject field = user.toJson();
            field.remove("Channels Id");

            fields.add(field);
        }
        if (userTable.pullMultipleRecord(fields, base, token)) {
            Logs.writeLog("Error: Could not push users to AirTable.");
            return false;
        }
        return true;
    }
    public boolean pushData(List<Channel> channels, List<SlackUser> users, boolean isManual) {
        reSync();
        if (!pushUsers(users)) return false;
        if (!pushChannels(channels)) return false;


        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("Id", UUID.randomUUID().toString());
        jsonObject.addProperty("Is Manual", isManual);
        jsonObject.addProperty("Num of additions", channelTable.getNumAdditions()+userTable.getNumAdditions());
        jsonObject.addProperty("Num of updates", channelTable.getNumUpdates()+userTable.getNumUpdates());
        jsonObject.addProperty("Num of deletions", channelTable.getNumDeletions()+userTable.getNumDeletions());

        long time = System.currentTimeMillis();
        Instant instant = Instant.ofEpochMilli(time);
        DateTimeFormatter formatter = DateTimeFormatter.ISO_INSTANT;


        jsonObject.addProperty("Update Time", formatter.format(instant));


        if (!taskTable.addMultipleRecords(List.of(jsonObject), base, token)) {
            Logs.writeLog("Error: Could not push task to AirTable.");
            return false;
        }
        return true;
    }
    public void exportToXlsx(String path) {
        reSync();
        File file = new File(path);
        if (!file.exists() && !file.mkdirs()) {
            Logs.writeLog("Error: Could not create directory " + path + ".");
            return;
        }
        channelTable.writeTableToXlsx(path + "/channels.xlsx");
        userTable.writeTableToXlsx(path + "/users.xlsx");
        taskTable.writeTableToXlsx(path + "/tasks.xlsx");
    }

    public void reSync() {
        channelTable.syncRecord(base, token);
        userTable.syncRecord(base, token);
    }
}
