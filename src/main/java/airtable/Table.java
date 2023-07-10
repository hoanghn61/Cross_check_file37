package airtable;

import logs.Logs;
import com.google.gson.*;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class Table {
    private static final String FIELDS_KEY = "fields";
    private static final String ERROR_MESSAGE = " with message: ";
    private static final String IN_TABLE_MESSAGE = " in table: ";
    private static final String RECORDS_KEY = "records";
    private int numDeletions = 0;
    private int numAdditions = 0;
    private int numUpdates = 0;
    private final String id;
    private final String name;
    private final List<Field> fields = new ArrayList<>();
    private final List<Record> records = new ArrayList<>();

    // Constructors
    protected Table(JsonObject table) {
        this.id = table.get("id").getAsString();
        this.name = table.get("name").getAsString();
        table.get(FIELDS_KEY).getAsJsonArray().forEach(field -> this.fields.add(new Field(field.getAsJsonObject())));
    }

    protected void syncRecord(String baseId, String token) {
        records.clear();
        String offset = null;
        while (true){
            String response = Record.listRecords(id, offset, baseId, token);
            if (response == null) {
                Logs.writeLog("Error: Could not get records for table: " + name);
            } else {
                JsonObject recordsJson = new Gson().fromJson(response, JsonObject.class);
                JsonArray listRecords = recordsJson.get(RECORDS_KEY).getAsJsonArray();
                listRecords.forEach(rec -> this.records.add(new Record(rec.getAsJsonObject())));
                if (recordsJson.has("offset"))
                    offset = recordsJson.get("offset").getAsString();
                else
                    break;
            }
        }
        Logs.writeLog("Info: Synced " + records.size() + " records for table: " + name);
    }

    // Getters
    protected String getId() {
        return this.id;
    }

    protected int getNumDeletions() {
        return numDeletions;
    }

    protected int getNumAdditions() {
        return numAdditions;
    }

    protected int getNumUpdates() {
        return numUpdates;
    }

    protected Record getRecord(String id) {
        for (Record rec : this.records) {
            if (rec.getId() != null && rec.getId().equals(id)) {
                return rec;
            }
        }
        return null;
    }



    // Handle Fields
    protected Field getField(String name) {
        for (Field field : this.fields) {
            if (field.getName().equals(name)) {
                return field;
            }
        }
        return null;
    }

    protected boolean addField(JsonObject field, String baseId, String token) {
        String fieldCreate = Field.createField(field, id, baseId, token);
        if (fieldCreate == null) {
            Logs.writeLog("Error: Could not create field: " + field.get("name").getAsString() + IN_TABLE_MESSAGE + name);
            return true;
        }
        JsonObject fieldJson = JsonParser.parseString(fieldCreate).getAsJsonObject();
        fields.add(new Field(fieldJson));
        Logs.writeLog("Created field: " + field.get("name").getAsString() + IN_TABLE_MESSAGE + name);
        return false;
    }


    // Handle Records

    protected boolean pullMultipleRecord(List<JsonObject> listNewRecords, String baseId, String token) {
        // Get records to delete, add and update
        // Define all records to need to delete
        List<Record> listDelete = new ArrayList<>(records);
        // Define all new records to need to add
        List<JsonObject> listAdd = new ArrayList<>(listNewRecords);
        // Define no records need to update
        Map<Record, JsonObject> listUpdate = new HashMap<>();

        for (JsonObject field : listNewRecords) {
            // Check if new record exists in current records
            String fieldId = field.get("Id").getAsString();
            Record rec = getRecord(fieldId);
            if (rec != null) {
                // If record exists, remove from delete list and add list
                listDelete.remove(rec);
                listAdd.remove(field);
                // Check if record needs to be updated
                if(!rec.equals(field, this.fields)){
                    listUpdate.put(rec, field);
                }
            }
        }

        numAdditions = listAdd.size();
        numDeletions = listDelete.size();
        numUpdates = listUpdate.size();
        if (numAdditions + numDeletions + numUpdates == 0) {
            Logs.writeLog("Info: No changes in table: " + name);
            return false;
        }
        try(ExecutorService executorService = Executors.newFixedThreadPool(3)){
            List<Future<Boolean>> futures = executorService.invokeAll(Arrays.asList(
                    () -> deleteMultipleRecords(listDelete, baseId, token),
                    () -> addMultipleRecords(listAdd, baseId, token),
                    () -> updateMultipleRecords(listUpdate, baseId, token)
            ));
            for (Future<Boolean> future : futures) {
                if (Boolean.FALSE.equals(future.get())) {
                    return true;
                }
            }
            return false;
        } catch (InterruptedException | ExecutionException e) {
            Logs.writeLog("Error: Could not pull multiple records in table: " + name + ERROR_MESSAGE + e.getMessage());
            Thread.currentThread().interrupt();
            return true;
        }
    }


    private boolean updateMultipleRecords(Map<Record, JsonObject> listUpdate, String baseId, String token) {
        if (listUpdate.isEmpty()) {
            return true;
        }

        try (ExecutorService executorService = Executors.newFixedThreadPool(10)) { // Number of parallel threads
            JsonArray newRecords = new JsonArray();
            List<Record> recordsUpdate = new ArrayList<>();
            int sized = listUpdate.size();

            for (Map.Entry<Record, JsonObject> entry : listUpdate.entrySet()) {
                // Get record needed to update and the fields to update
                Record rec = entry.getKey();
                JsonObject updateFields = entry.getValue();

                // Create the json object to update the record
                JsonObject recordJson = new JsonObject();
                recordJson.addProperty("id", rec.getRecordId());
                recordJson.add(FIELDS_KEY, updateFields);

                // Add the record to the list of records to update
                newRecords.add(recordJson);
                recordsUpdate.add(rec);

                // If the list of records to update is 10 or the last record, submit the update task
                if (newRecords.size() >= 10 || newRecords.size() == sized) {
                    submitUpdateTask(executorService, newRecords, recordsUpdate, baseId, token);
                    sized -= newRecords.size();
                    newRecords = new JsonArray();
                    recordsUpdate = new ArrayList<>();
                }
            }

            executorService.shutdown();
        }catch (Exception e){
            Logs.writeLog("Error: Could not update multiple records in table: " + name + ERROR_MESSAGE + e.getMessage());
            return false;
        }

        return true;
    }
    private void submitUpdateTask(ExecutorService executorService, JsonArray records, List<Record> recordsUpdate, String baseId, String token) {
        executorService.submit(() -> {

            // Create the body to update the records
            JsonObject body = new JsonObject();
            body.add(RECORDS_KEY, records);

            // Update the records
            String response = Record.updateMultipleRecords(body, id, baseId, token);

            // Check if the update was successful
            if (response == null) {
                Logs.writeLog("Error: Could not update multiple records in table: " + name);
            } else {
                Logs.writeLog("Updated " + records.size() + " records in table: " + name);

                // Remove the records that were updated
                for (Record recordUpdate : recordsUpdate) {
                    this.records.remove(recordUpdate);
                }

                // Add the updated records to the table
                JsonArray recordsResponse = JsonParser.parseString(response)
                        .getAsJsonObject()
                        .get(RECORDS_KEY)
                        .getAsJsonArray();
                for (JsonElement recordResponse : recordsResponse) {
                    this.records.add(new Record(recordResponse.getAsJsonObject()));
                }
            }
        });
    }

    protected boolean addMultipleRecords(List<JsonObject> listAdd, String baseId, String token) {
        if (listAdd.isEmpty()) {
            return true;
        }

        try (ExecutorService executorService = Executors.newFixedThreadPool(10)) {
            JsonArray newRecords = new JsonArray();
            int sized = listAdd.size();

            for (JsonObject addFields : listAdd) {
                // Create the json object to add the record
                JsonObject recordJson = new JsonObject();
                recordJson.add(FIELDS_KEY, addFields);

                // Add the record to the list of records to add
                newRecords.add(recordJson);

                if (newRecords.size() >= 10 || newRecords.size() == sized) {
                    submitRecordsTask(executorService, newRecords, baseId, token);
                    sized -= newRecords.size();
                    newRecords = new JsonArray();
                }
            }

            executorService.shutdown();
        }catch (Exception e){
            Logs.writeLog("Error: Could not add multiple records in table: " + name + ERROR_MESSAGE + e.getMessage());
            return false;
        }

        return true;
    }
    private void submitRecordsTask(ExecutorService executorService, JsonArray records, String baseId, String token) {
        executorService.submit(() -> {
            // Create the body to add the records
            JsonObject body = new JsonObject();
            body.add(RECORDS_KEY, records);

            // Add the records
            String response = Record.addMultipleRecords(body, id, baseId, token);

            // Check if the add was successful
            if (response == null) {
                Logs.writeLog("Error: Could not add multiple records in table: " + name);
            } else {
                Logs.writeLog("Added " + body.get(RECORDS_KEY).getAsJsonArray().size() + " records in table: " + name);

                // Add the added records to the table
                JsonArray recordsResponse = JsonParser.parseString(response)
                        .getAsJsonObject()
                        .get(RECORDS_KEY)
                        .getAsJsonArray();
                for (JsonElement rec : recordsResponse) {
                    this.records.add(new Record(rec.getAsJsonObject()));
                }
            }
        });
    }

    private boolean deleteMultipleRecords(List<Record> listDelete, String baseId, String token) {
        if (listDelete.isEmpty()) {
            return true;
        }
        try (ExecutorService executorService = Executors.newFixedThreadPool(listDelete.size())) {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (Record rec : listDelete) {
                futures.add(executorService.submit(() -> {
                    if (!Record.dropRecord(rec.getRecordId(), id, baseId, token)) {
                        records.remove(rec);
                        Logs.writeLog("Error: Could not delete record: " + rec.getRecordId() + IN_TABLE_MESSAGE + name);
                        return false;
                    }
                    Logs.writeLog("Deleted record: " + rec.getRecordId() + IN_TABLE_MESSAGE + name);
                    return true;
                }));
            }
            for (Future<Boolean> future : futures) {
                if (Boolean.FALSE.equals(future.get())) {
                    return false;
                }
            }
        } catch (ExecutionException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return true;
    }

    // API Methods
    protected static String listTables(String baseId, String token) {
        String url = "https://api.airtable.com/v0/meta/bases/" + baseId + "/tables";
        try (CloseableHttpClient client = HttpClientBuilder.create().build()) {
            HttpGet get = new HttpGet(url);
            get.setHeader("Authorization", "Bearer " + token);
            return client.execute(get, AirTable.responseHandler);

        } catch (IOException e) {
            Logs.writeLog("Error: Could not list tables due to exception: " + e.getMessage());
            return null;
        }
    }
    protected static String createTable(String name, JsonArray fields, String baseId, String token){
        String url = "https://api.airtable.com/v0/meta/bases/" + baseId + "/tables";
        try (CloseableHttpClient client = HttpClientBuilder.create().build()) {

            HttpPost post = new HttpPost(url);
            post.setHeader("Authorization", "Bearer " + token);
            post.setHeader("Content-Type", "application/json");

            JsonObject body = new JsonObject();
            body.addProperty("name", name);
            body.add(FIELDS_KEY, fields);

            post.setEntity(new StringEntity(body.toString(), StandardCharsets.UTF_8));

            return client.execute(post, AirTable.responseHandler);
        } catch (IOException e) {
            Logs.writeLog("Error: Could not create table: " + name + ERROR_MESSAGE + e.getMessage());
            return null;
        }
    }

    // write to xlsx file
    protected void writeTableToXlsx(String path) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             FileOutputStream outputStream = new FileOutputStream(path)) {

            XSSFSheet sheet = workbook.createSheet(name);

            List<String> headers = new ArrayList<>();
            for (Field field : fields) {
                headers.add(field.getName());
            }

            // write headers
            Row headerRow = sheet.createRow(0);
            for (String header : headers) {
                Cell cell = headerRow.createCell(headers.indexOf(header));
                cell.setCellValue(header);
            }

            // write records
            for (Record rec : records) {
                Row row = sheet.createRow(records.indexOf(rec) + 1);
                JsonObject recordFields = rec.getFields();
                for (String header : headers) {
                    Cell cell = row.createCell(headers.indexOf(header));
                    if (recordFields.has(header)) {
                        JsonElement field = recordFields.get(header);
                        cell.setCellValue(field.toString());
                    }
                }
            }

            // write to file
            workbook.write(outputStream);
            Logs.writeLog("Wrote table: " + name + " to file: " + path);
        } catch (IOException e) {
            Logs.writeLog("Error: Could not write table: " + name + " to file: " + path + ERROR_MESSAGE + e.getMessage());
        }
    }
}