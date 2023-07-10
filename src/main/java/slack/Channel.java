package slack;

import logs.Logs;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.SlackApiException;
import com.slack.api.model.Conversation;
import com.slack.api.model.ConversationType;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Channel {
    private static final String REMOVE_USER_MESSAGE = "Remove user: ";
    private static final String FROM_CHANNEL_MESSAGE = " from channel: ";
    private static final String ADD_USER_MESSAGE = "Add user: ";
    private static final String TO_CHANNEL_MESSAGE = " to channel: ";
    private static final String CREATE_CHANNEL_MESSAGE = "Create channel: ";
    private static final String FAILED_MESSAGE = " failed";
    private final String id;
    private final String name;
    private final String creatorId;
    private final String created;
    private final String topic;
    private final String purpose;
    private int numMembers;
    private final boolean isPrivate;
    private final boolean isArchive;

    private final JsonArray membersId = new JsonArray();
    public Channel(Conversation conversation){
        this.id = conversation.getId();
        this.name = conversation.getName();
        String topicValue = conversation.getTopic().getValue();
        if (topicValue.isEmpty())
            this.topic = null;
        else
            this.topic = topicValue;

        String purposeValue = conversation.getPurpose().getValue();
        if (purposeValue.isEmpty())
            this.purpose = null;
        else
            this.purpose = purposeValue;

        this.creatorId = conversation.getCreator();
        this.isArchive = conversation.isArchived();
        this.isPrivate = conversation.isPrivate();


        Instant instant = Instant.ofEpochSecond(conversation.getCreated());
        DateTimeFormatter formatter = DateTimeFormatter.ISO_INSTANT;

        this.created = formatter.format(instant);
    }


    // Getters
    public JsonArray getMembersId() {
        return membersId;
    }
    public JsonObject toJson(){
        JsonObject json = new JsonObject();
        json.addProperty("Id", this.id);
        json.addProperty("Name", this.name);
        json.addProperty("Topic", this.topic);
        json.addProperty("Purpose", this.purpose);
        json.addProperty("Is Private", this.isPrivate);
        json.addProperty("Is Archived", this.isArchive);
        json.addProperty("Creator Id", this.creatorId);
        json.addProperty("Created", this.created);
        json.addProperty("Num Members", this.numMembers);
        json.add("Members Id", this.membersId);
        return json;
    }
    protected String getId() {
        return id;
    }
    public String getName() {
        return name;
    }

    // Add memberId
    protected void addMemberId(String memberId){
        this.membersId.add(memberId);
        this.numMembers++;
    }

    // API method
    protected boolean removeUser(String userId, MethodsClient client){
        try {
            var result = client.conversationsKick(r -> r
                    .channel(this.id)
                    .user(userId)
            );
            if (result.isOk()){
                Logs.writeLog(REMOVE_USER_MESSAGE + userId + FROM_CHANNEL_MESSAGE + this.name);
                membersId.remove(new Gson().fromJson(userId, JsonElement.class));
                return true;
            }else {
                Logs.writeLog(REMOVE_USER_MESSAGE + userId + FROM_CHANNEL_MESSAGE + this.name + FAILED_MESSAGE);
                return false;
            }
        } catch (SlackApiException | IOException e) {
            Logs.writeLog(REMOVE_USER_MESSAGE + userId + FROM_CHANNEL_MESSAGE + this.name + FAILED_MESSAGE);
            return false;
        }
    }
    protected boolean addUser(String userId, MethodsClient client){
        try {
            var result = client.conversationsInvite(r -> r
                    .channel(this.id)
                    .users(List.of(userId))
            );
            if (result.isOk()){
                Logs.writeLog(ADD_USER_MESSAGE + userId + TO_CHANNEL_MESSAGE + this.name);
                membersId.add(new Gson().fromJson(userId, JsonElement.class));
                return true;
            }else {
                Logs.writeLog(ADD_USER_MESSAGE + userId + TO_CHANNEL_MESSAGE + this.name + FAILED_MESSAGE);
                return false;
            }
        } catch (SlackApiException | IOException e) {
            Logs.writeLog(ADD_USER_MESSAGE + userId + TO_CHANNEL_MESSAGE + this.name + FAILED_MESSAGE);

            return false;
        }
    }
    protected static List<Channel> listChannels(MethodsClient client) {
        List<Channel> channels = new ArrayList<>();
        String nextCursor = "";
        try {
            do {
                String finalNextCursor = nextCursor;
                var response = client.conversationsList(r -> r
                        .types(List.of(ConversationType.PRIVATE_CHANNEL, ConversationType.PUBLIC_CHANNEL))
                        .cursor(finalNextCursor)
                        .limit(1000)
                );
                for (var channel : response.getChannels()) {
                    channels.add(new Channel(channel));
                }
                nextCursor = response.getResponseMetadata().getNextCursor();
            } while (nextCursor != null && !nextCursor.isEmpty());
            Logs.writeLog("List channels successfully");
            return channels;
        } catch (Exception e) {
            Logs.writeLog("List channels failed");
            return Collections.emptyList();
        }
    }
    protected static Conversation createChannel(String name, boolean isPrivate, MethodsClient client){
        try {
            var result = client.conversationsCreate(r -> r
                    .name(name)
                    .isPrivate(isPrivate)
            );
            if (result.isOk()){
                Logs.writeLog(CREATE_CHANNEL_MESSAGE + name + " success");
                return result.getChannel();
            }else {
                Logs.writeLog(CREATE_CHANNEL_MESSAGE + name + FAILED_MESSAGE);
                return null;
            }
        } catch (SlackApiException | IOException e) {
            Logs.writeLog(CREATE_CHANNEL_MESSAGE + name + FAILED_MESSAGE);
            return null;
        }
    }
}
