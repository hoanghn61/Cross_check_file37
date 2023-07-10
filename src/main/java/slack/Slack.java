package slack;

import logs.Logs;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.slack.api.methods.MethodsClient;
import com.slack.api.model.Conversation;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;

public class Slack {
    private static final String SLACK_USER = "SlackUser ";
    private static final String ADD_TO_CHANNEL = " added to channel ";
    private static final String FAILED_MESSAGE = " failed";
    private static final String REMOVE_FROM_CHANNEL = " removed from channel ";
    private MethodsClient client;
    private List<SlackUser> users;
    private List<Channel> channels;
    private final boolean active;
    public Slack() {
        long start = System.currentTimeMillis();
        try {
            FileReader fileReader = new FileReader("src/main/resources/data/config.json");
            JsonObject jsonObject = new Gson().fromJson(new JsonReader(fileReader), JsonObject.class);
            String token = jsonObject.get("slack").getAsString();
            this.client = com.slack.api.Slack.getInstance().methods(token);
        } catch (FileNotFoundException e) {
            active = false;
            return;
        }
        active = syncLocal();
        Logs.writeLog("Slack started in " + (System.currentTimeMillis() - start) + " ms");
    }

    // Sync local data with Slack
    public boolean syncLocal() {
        try (ExecutorService executorService = Executors.newFixedThreadPool(2)) {

            Callable<List<SlackUser>> listUsersTask = () -> SlackUser.listUsers(client);
            Future<List<SlackUser>> usersFuture = executorService.submit(listUsersTask);

            Callable<List<Channel>> listChannelsTask = () -> Channel.listChannels(client);
            Future<List<Channel>> channelsFuture = executorService.submit(listChannelsTask);

            users = usersFuture.get();
            channels = channelsFuture.get();

            if (users == null || channels == null)
                return false;

            for (SlackUser user : users) {
                executorService.submit(() -> addMemberToChannels(user, channels));
            }

            executorService.shutdown();
            return !Objects.equals(users, Collections.emptyList()) || !Objects.equals(channels, Collections.emptyList());
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void addMemberToChannels(SlackUser user, List<Channel> channels) {
        for (var channelId : user.getChannelsId()) {
            for (Channel channel : channels) {
                if (channel.getId().equals(channelId.getAsString())) {
                    channel.addMemberId(user.getId());
                    break;
                }
            }
        }
    }
    public boolean isActive() {
        return active;
    }

    // Getters
    public List<SlackUser> getUsers() {
        return users;
    }
    public List<Channel> getChannels() {
        return channels;
    }


    // Get Channel, SlackUser by name, email
    public SlackUser getUser(String email) {
        for (SlackUser user : users) {
            if (user.getEmail().equals(email))
                return user;
        }
        return null;
    }
    public Channel getChannel(String name) {
        for (Channel channel : channels) {
            if (channel.getName().equals(name))
                return channel;
        }
        return null;
    }
    public Channel getChannelById(String id) {
        for (Channel channel : channels) {
            if (channel.getId().equals(id))
                return channel;
        }
        return null;
    }

    public SlackUser getUserById(String id) {
        for (SlackUser user : users) {
            if (user.getId().equals(id))
                return user;
        }
        return null;
    }


    // Channel, SlackUser management
    public boolean addUserToChannel(Channel channel, SlackUser user) {
        int userIndex = users.indexOf(user);
        if (userIndex == -1) {
            Logs.writeLog(SLACK_USER + user.getEmail() + ADD_TO_CHANNEL + channel.getName() + FAILED_MESSAGE);
            return false;
        }

        int channelIndex = channels.indexOf(channel);
        if (channelIndex == -1) {
            Logs.writeLog(SLACK_USER + user.getEmail() + ADD_TO_CHANNEL + channel.getName() + FAILED_MESSAGE);
            return false;
        }


        if (channels.get(channelIndex).addUser(user.getId(), client)){
            users.get(userIndex).addChannelId(channel.getId());
            Logs.writeLog(SLACK_USER + user.getEmail() + ADD_TO_CHANNEL + channel.getName());
            return true;
        }
        Logs.writeLog(SLACK_USER + user.getEmail() + ADD_TO_CHANNEL + channel.getName() + FAILED_MESSAGE);
        return false;
    }
    public boolean removeUserFromChannel(Channel channel, SlackUser user) {
        int userIndex = users.indexOf(user);
        if (userIndex == -1) {
            Logs.writeLog(SLACK_USER + user.getEmail() + REMOVE_FROM_CHANNEL + channel.getName() + FAILED_MESSAGE);
            return false;
        }

        int channelIndex = channels.indexOf(channel);
        if (channelIndex == -1) {
            Logs.writeLog(SLACK_USER + user.getEmail() + REMOVE_FROM_CHANNEL + channel.getName() + FAILED_MESSAGE);
            return false;
        }

        if (channels.get(channelIndex).removeUser(user.getId(), client)){
            users.get(userIndex).removeChannelId(channel.getId());
            Logs.writeLog(SLACK_USER + user.getEmail() + REMOVE_FROM_CHANNEL + channel.getName());
            return true;
        }
        if (SlackUser.leaveChannel(channel.getId(), client)) {
            users.get(userIndex).removeChannelId(channel.getId());
            Logs.writeLog(SLACK_USER + user.getEmail() + REMOVE_FROM_CHANNEL + channel.getName());
            return true;
        }
        Logs.writeLog(SLACK_USER + user.getEmail() + REMOVE_FROM_CHANNEL + channel.getName() + FAILED_MESSAGE);
        return false;
    }
    public boolean createChannel(String name, boolean isPrivate) {
        Conversation channel = Channel.createChannel(name , isPrivate, client);
        if (channel != null) {
            channels.add(new Channel(channel));
            Logs.writeLog("Channel " + name + " created");
            return true;
        }
        Logs.writeLog("Channel " + name + " created failed");
        return false;
    }
}
