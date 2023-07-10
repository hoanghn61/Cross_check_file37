package airtable;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.hc.client5.http.classic.methods.HttpDelete;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPatch;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.io.entity.StringEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class Record{
    private static final String AIRTABLE_API_URL_PREFIX = "https://api.airtable.com/v0/";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String AUTHORIZATION_VALUE_PREFIX = "Bearer ";
    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String CONTENT_TYPE_VALUE = "application/json";
    private final String recordId;
    private final JsonObject fields;
    private final String id;

    // Constructor
    Record(JsonObject jsonRecord) {
        this.recordId = jsonRecord.get("id").getAsString();
        this.fields = jsonRecord.get("fields").getAsJsonObject();
        if (this.fields.has("Id"))
            this.id = this.fields.get("Id").getAsString();
        else
            this.id = null;
    }

    protected boolean equals(JsonObject fields, List<Field> fieldsList) {
        for (Field field : fieldsList) {
            if (fields.has(field.getName())) {
                String newVal = fields.get(field.getName()).toString();
                String oldVal;
                if (!this.fields.has(field.getName()))
                    oldVal = null;
                else
                    oldVal = this.fields.get(field.getName()).toString();

                if (!fieldEqual(field.getType(), newVal, oldVal)) {

                    return false;
                }
            }
        }
        return true;
    }
    private boolean fieldEqual(String fieldType, String newVal, String oldVal) {
        if (newVal.equals("false") && oldVal == null)
            return true;
        if (newVal.equals("null") && oldVal == null)
            return true;
        if (newVal.equals("[]") && oldVal == null)
            return true;
        if (newVal.equals("\"\"") && oldVal == null)
            return true;
        if (newVal.equals(oldVal))
            return true;
        if (fieldType.equals("multipleRecordLinks")){
            JsonArray oldValArray = new Gson().fromJson(oldVal, JsonArray.class);
            JsonArray newValArray = new Gson().fromJson(newVal, JsonArray.class);
            return multipleRecordLinksEqual(newValArray, oldValArray);
        }
        return fieldType.contains("date") && oldVal.replaceAll(".000Z", "Z").equals(newVal);
    }
    private boolean multipleRecordLinksEqual(JsonArray newValArray, JsonArray oldValArray) {
        if (oldValArray == null && newValArray == null)
            return true;
        if (oldValArray == null || newValArray == null)
            return false;
        if (oldValArray.size() != newValArray.size())
            return false;
        for (var element : oldValArray){
            if (!newValArray.contains(element))
                return false;
        }
        return true;
    }

    // Getters
    protected String getRecordId() {
        return this.recordId;
    }
    protected String getId() {
        return this.id;
    }
    protected JsonObject getFields() {
        return this.fields;
    }


    // API Methods
    protected static String listRecords(String tableId, String offset, String baseId, String token) {
        String url = AIRTABLE_API_URL_PREFIX + baseId + "/" + tableId + "?maxRecords=100000";
        if (offset != null)
            url += "&offset=" + offset;

        try(CloseableHttpClient client = HttpClientBuilder.create().build()) {
            HttpGet get = new HttpGet(url);
            get.setHeader(AUTHORIZATION_HEADER, AUTHORIZATION_VALUE_PREFIX + token);
            get.setHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_VALUE);

            return client.execute(get, AirTable.responseHandler);
        } catch (IOException e) {
            return null;
        }
    }
    protected static boolean dropRecord(String recordId, String tableId, String baseId, String token){
        String url = AIRTABLE_API_URL_PREFIX + baseId + "/" + tableId + "/" + recordId;

        try (CloseableHttpClient client = HttpClientBuilder.create().build()) {
            HttpDelete delete = new HttpDelete(url);
            delete.setHeader(AUTHORIZATION_HEADER, AUTHORIZATION_VALUE_PREFIX + token);

            String response = client.execute(delete, AirTable.responseHandler);

            JsonObject responseJson = new Gson().fromJson(response, JsonObject.class);
            return responseJson.get("deleted").getAsBoolean();
        } catch (IOException e) {
            return false;
        }
    }
    protected static String updateMultipleRecords(JsonObject records, String tableId, String baseId, String token){
        String url = AIRTABLE_API_URL_PREFIX + baseId + "/" + tableId;

        try (CloseableHttpClient client = HttpClientBuilder.create().build()) {
            HttpPatch patch = new HttpPatch(url);
            patch.setHeader(AUTHORIZATION_HEADER, AUTHORIZATION_VALUE_PREFIX + token);
            patch.setHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_VALUE);

            patch.setEntity(new StringEntity(records.toString(), StandardCharsets.UTF_8));

            return client.execute(patch, AirTable.responseHandler);
        } catch (IOException e) {

            return null;
        }
    }
    protected static String addMultipleRecords(JsonObject records, String tableId, String baseId, String token){
        String url = AIRTABLE_API_URL_PREFIX + baseId + "/" + tableId;

        try (CloseableHttpClient client = HttpClientBuilder.create().build()) {
            HttpPost post = new HttpPost(url);
            post.setHeader(AUTHORIZATION_HEADER, AUTHORIZATION_VALUE_PREFIX + token);
            post.setHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_VALUE);

            post.setEntity(new StringEntity(records.toString(), StandardCharsets.UTF_8));

            return client.execute(post, AirTable.responseHandler);
        } catch (IOException e) {

            return null;
        }
    }
}
