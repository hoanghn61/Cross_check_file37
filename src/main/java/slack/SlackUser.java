package slack;

import logs.Logs;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.slack.api.methods.MethodsClient;
import com.slack.api.model.ConversationType;
import com.slack.api.model.User;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SlackUser {
    private final String id;
    private final String name;
    private final String realName;
    private final String email;
    private final String updated;
    private int numChannels;
    private final boolean isActive;
    private final JsonArray channelsId;
    private final JsonArray roles = new JsonArray();


    // Constructor
    protected SlackUser(User user, MethodsClient client){
        this.id = user.getId();
        this.name = user.getProfile().getDisplayName() == null ? user.getName() : user.getProfile().getDisplayName();
        this.realName = user.getRealName() == null ? user.getName() : user.getRealName();
        this.email = user.getProfile().getEmail();
        this.isActive = !user.isDeleted();

        Instant instant = Instant.ofEpochSecond(user.getUpdated());
        DateTimeFormatter formatter = DateTimeFormatter.ISO_INSTANT;

        this.updated = formatter.format(instant);

        if(user.isBot())
            roles.add("Bot");

        if(user.isOwner())
            roles.add("Owner");
        if(user.isPrimaryOwner())
            roles.add("Primary Owner");
        if(user.isRestricted())
            roles.add("Restricted");
        if(user.isUltraRestricted())
            roles.add("Ultra Restricted");
        if(user.isAdmin())
            roles.add("Admin");

        this.channelsId = listChannelsId(this, client);
        this.numChannels = channelsId != null ? channelsId.size() : 0;
    }


    protected void addChannelId(String channelId){
        channelsId.add(channelId);
        numChannels++;
    }
    protected void removeChannelId(String channelId){
        JsonElement element = new Gson().fromJson(channelId, JsonElement.class);
        if (channelsId.contains(element)){
            channelsId.remove(element);
            numChannels--;
        }
    }


    // Getters
    public JsonObject toJson(){
        JsonObject json = new JsonObject();
        json.addProperty("Id", id);
        json.addProperty("Display Name", name);
        json.addProperty("Real Name", realName);
        json.addProperty("Email", email);
        json.addProperty("Updated", updated);
        json.addProperty("Num Channels", numChannels);
        json.addProperty("Is Active", isActive);
        json.addProperty("Updated", updated);
        json.add("Role", roles);
        json.add("Channels Id", channelsId);
        return json;
    }
    public String getId() {
        return id;
    }
    public String getEmail() {
        return email;
    }
    public JsonArray getChannelsId() {
        return channelsId;
    }


    // API Method
    protected static List<SlackUser> listUsers(MethodsClient client){
        List<SlackUser> slackUsers = new ArrayList<>();
        String nextCursor = "";
        try {
            do {
                String finalNextCursor = nextCursor;
                var response = client.usersList(r -> r
                        .cursor(finalNextCursor)
                        .limit(1000)
                );
                // Add all users not is_bot and have id != USLACKBOT
                for (var user : response.getMembers()) {
                    if (!user.isBot() && !user.getId().equals("USLACKBOT"))
                        slackUsers.add(new SlackUser(user, client));
                }
                nextCursor = response.getResponseMetadata().getNextCursor();
            } while (nextCursor != null && !nextCursor.isEmpty());

            Logs.writeLog("List users successfully");
            return slackUsers;
        } catch (Exception e) {
            Logs.writeLog("List users failed");
            return Collections.emptyList();
        }
    }
    private JsonArray listChannelsId(SlackUser user, MethodsClient client) {
        JsonArray channelsIdList = new JsonArray();
        String nextCursor = "";
        try {
            do {
                String finalNextCursor = nextCursor;
                var response = client.usersConversations(r -> r
                        .user(user.id)
                        .types(List.of(ConversationType.PRIVATE_CHANNEL, ConversationType.PUBLIC_CHANNEL))
                        .cursor(finalNextCursor)
                        .limit(1000)
                );
                for (var channel : response.getChannels()) {
                    channelsIdList.add(channel.getId());
                }
                nextCursor = response.getResponseMetadata().getNextCursor();
            } while (nextCursor != null && !nextCursor.isEmpty());
            Logs.writeLog("List channels id of user " + user.name + " successfully");
            return channelsIdList;
        } catch (Exception e) {
            Logs.writeLog("List channels id of user " + user.name + " failed");
            return null;
        }
    }
    protected static boolean leaveChannel(String channelId, MethodsClient client){
        try {
            client.conversationsLeave(r -> r.channel(channelId));
            Logs.writeLog("Leave channel " + channelId + " successfully");
            return true;
        } catch (Exception e) {
            Logs.writeLog("Leave channel " + channelId + " failed");
            return false;
        }
    }
}
