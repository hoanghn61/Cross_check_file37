package airtable;

import logs.Logs;
import com.google.gson.JsonObject;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.io.entity.StringEntity;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public class Field {
    private final String name;
    private final String type;

    protected Field(JsonObject field) {
        this.name = field.get("name").getAsString();
        this.type = field.get("type").getAsString();
    }

    protected String getName() {
        return this.name;
    }
    protected String getType() {
        return this.type;
    }


    // API Methods
    protected static String createField(JsonObject field, String tableId, String baseId, String token) {
        URI uri = URI.create("https://api.airtable.com/v0/meta/bases/" + baseId + "/tables/" + tableId + "/fields");
        try(CloseableHttpClient client = HttpClientBuilder.create().build()) {
            HttpPost post = new HttpPost(uri);
            post.setHeader("Authorization", "Bearer " + token);
            post.setHeader("Content-Type", "application/json");
            post.setEntity(new StringEntity(field.toString(), StandardCharsets.UTF_8));

            return client.execute(post, AirTable.responseHandler);

        } catch (IOException e) {
            Logs.writeLog("Error creating field: " + field);
            return null;
        }


    }

}
