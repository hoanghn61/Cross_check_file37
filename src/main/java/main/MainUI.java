package main;

import airtable.AirTable;
import com.google.gson.*;
import logs.Logs;
import slack.Channel;
import slack.Slack;
import slack.SlackUser;
import synctask.DataSyncTask;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class MainUI {
    private final AirTable airTable;
    private final Slack slack;
    private static final String LOCAL_SYNC_NOT_SUCCESS = "localSyncNotSuccess";
    private static final String ENTER_CHANNEL_NAME = "enterChannelName";
    private static final String INVALID_TIME = "invalidTime";
    private JPanel mainPanel;
    private JButton createChannelsButton;
    private JButton addUserToChannelButton;
    private JButton removeUserFromChannelButton;
    private JButton airTableToXlsxButton;
    private JButton listAllUsersButton;
    private JButton listAllChannelsButton;
    private JButton syncButton;
    private JButton setSyncTimeButton;
    private JTextField status;
    private JList<String> list;
    private JButton changeLanguageButton;
    private final DataSyncTask dataSyncTask;

    private JsonObject language;
    Thread syncThread;

    public MainUI(AirTable airTable, Slack slack) {
        this.airTable = airTable;
        this.slack = slack;
        dataSyncTask = new DataSyncTask();
        dataSyncTask.setTimeSync(0, 0, 0);


        syncThread = new Thread(() -> dataSyncTask.setTimeSync(0, 0, 0));
        syncThread.start();
        list.setCellRenderer(new ColorfulCellRenderer());

        createChannelsButton.addActionListener(e -> createChannel());
        addUserToChannelButton.addActionListener(e -> addUserToChannel());
        removeUserFromChannelButton.addActionListener(e -> removeUserFromChannel());
        airTableToXlsxButton.addActionListener(e -> toXlsx());
        listAllUsersButton.addActionListener(e -> listAllUsers());
        listAllChannelsButton.addActionListener(e -> listAllChannels());
        syncButton.addActionListener(e -> syncData());
        setSyncTimeButton.addActionListener(e -> setSyncTime());
        changeLanguageButton.addActionListener(e -> changeLanguage());

        File file = new File("src/main/resources/data/en.json");
        try {
            if (file.exists()) {
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                FileReader fileReader = new FileReader(file);
                language = gson.fromJson(fileReader, JsonObject.class);
            }
        } catch (FileNotFoundException e) {
            showErrorDialog("Error: Could not find language file");
        }

        createChannelsButton.setText(language.get("createChannelButton").getAsString());
        addUserToChannelButton.setText(language.get("addUserButton").getAsString());
        removeUserFromChannelButton.setText(language.get("removeUserButton").getAsString());
        syncButton.setText(language.get("syncButton").getAsString());
        setSyncTimeButton.setText(language.get("setSyncButton").getAsString());
        airTableToXlsxButton.setText(language.get("toXlsxButton").getAsString());
        changeLanguageButton.setText(language.get("changeLanguage").getAsString());
        listAllChannelsButton.setText(language.get("listChannelButton").getAsString());
        listAllUsersButton.setText(language.get("listUserButton").getAsString());

    }

    private void listAllChannels(){
        if (!slack.syncLocal()) {
            status.setText(language.get(LOCAL_SYNC_NOT_SUCCESS).getAsString());
            return;
        }
        List<Channel> channels = slack.getChannels();

        DefaultListModel<String> model = new DefaultListModel<>();
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        for (var channel : channels){
            JsonObject json = channel.toJson();
            JsonArray membersId = json.getAsJsonArray("Members Id");
            JsonArray membersEmail = new JsonArray();
            for (var id : membersId){
                String memberEmail = slack.getUserById(id.getAsString()).getEmail();
                membersEmail.add(memberEmail);
            }
            json.add("Members Email", membersEmail);
            json.remove("Members Id");
            String jsonString = gson.toJson(json);
            String html = "<html>" + jsonString.replace("\n", "<br>").replace(" ", "&nbsp;") + "</html>";
            model.addElement(html);
        }
        list.setModel(model);
        status.setText(language.get("listChannelStatus").getAsString());
    }

    private void listAllUsers(){
        if (!slack.syncLocal()) {
            status.setText(language.get(LOCAL_SYNC_NOT_SUCCESS).getAsString());
            return;
        }
        List<SlackUser> users = slack.getUsers();

        DefaultListModel<String> model = new DefaultListModel<>();
        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        for (var user : users){
            JsonObject json = user.toJson();
            JsonArray channels = json.getAsJsonArray("Channels Id");

            JsonArray channelsName = new JsonArray();
            for (var channel : channels){
                String channelName = slack.getChannelById(channel.getAsString()).getName();
                channelsName.add(channelName);
            }
            json.add("Channels Name", channelsName);
            json.remove("Channels Id");

            String jsonString = gson.toJson(json);
            String html = "<html>" + jsonString.replace("\n", "<br>").replace(" ", "&nbsp;") + "</html>";
            model.addElement(html);
        }
        list.setModel(model);
        status.setText(language.get("listUserStatus").getAsString());
    }

    private void createChannel(){
        String channelName = JOptionPane.showInputDialog(language.get(ENTER_CHANNEL_NAME).getAsString());

        if (channelName == null || channelName.isEmpty())
            return;
        boolean isPrivate = JOptionPane.showConfirmDialog(null, language.get("isPrivate?").getAsString(), language.get("private").getAsString(), JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;

        if (slack.createChannel(channelName, isPrivate))
            status.setText(language.get("channelCreated").getAsString());
        else
            status.setText(language.get("ChannelNotCreated").getAsString());
    }

    private void addUserToChannel(){
        String channelName = JOptionPane.showInputDialog(language.get(ENTER_CHANNEL_NAME).getAsString());
        if (channelName == null || channelName.isEmpty())
            return;
        if (!slack.syncLocal()) {
            status.setText(language.get(LOCAL_SYNC_NOT_SUCCESS).getAsString());
            return;
        }
        Channel channel = slack.getChannel(channelName);
        if (channel == null){
            status.setText(language.get("channelNotFound").getAsString());
            return;
        }

        String userEmail = JOptionPane.showInputDialog(language.get("enterUserEmail").getAsString());
        SlackUser user = slack.getUser(userEmail);
        if (user == null){
            status.setText(language.get("userNotFound").getAsString());
            return;
        }

        for (JsonElement r : channel.getMembersId()) {
            if (r.getAsString().equals(user.getId())) {
                status.setText(language.get("userAlreadyInChannel").getAsString());
                return;
            }
        }

        if (JOptionPane.showConfirmDialog(null, language.get("sureContinue").getAsString(), language.get("continue").getAsString(), JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION){
            status.setText(language.get("userNotAddedToChannel").getAsString());
            return;
        }
        if (slack.addUserToChannel(channel, user))
            status.setText(language.get("userAddedToChannel").getAsString());
        else
            status.setText(language.get("userNotAddedToChannel").getAsString());
    }

    private void removeUserFromChannel(){
        String channelName = JOptionPane.showInputDialog(language.get(ENTER_CHANNEL_NAME).getAsString());
        if (channelName == null || channelName.isEmpty())
            return;
        if (!slack.syncLocal()) {
            status.setText(language.get(LOCAL_SYNC_NOT_SUCCESS).getAsString());
            return;
        }
        Channel channel = slack.getChannel(channelName);
        if (channel == null){
            status.setText(language.get("channelNotFound").getAsString());
            return;
        }

        String userEmail = JOptionPane.showInputDialog(language.get("enterUserEmail").getAsString());
        SlackUser user = slack.getUser(userEmail);
        if (user == null){
            status.setText(language.get("userNotFound").getAsString());
            return;
        }

        for (JsonElement r : channel.getMembersId()) {
            if (r.getAsString().equals(user.getId())) {
                if (JOptionPane.showConfirmDialog(null, language.get("sureContinue").getAsString(), language.get("continue").getAsString(), JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION){
                    status.setText(language.get("userNotRemovedFromChannel").getAsString());
                    return;
                }
                if (slack.removeUserFromChannel(channel, user))
                    status.setText(language.get("userRemovedFromChannel").getAsString());
                else
                    status.setText(language.get("userNotRemovedFromChannel").getAsString());
                return;
            }
        }
        status.setText(language.get("userNotInChannel").getAsString());
    }

    private void syncData(){
        long start = System.currentTimeMillis();
        if (!slack.syncLocal()) {
            status.setText(language.get(LOCAL_SYNC_NOT_SUCCESS).getAsString());
            return;
        }

        if (airTable.pushData(slack.getChannels(), slack.getUsers(), true))
            status.setText(language.get("dataSynced").getAsString());
        else{
            status.setText(language.get("dataNotSynced").getAsString());
            airTable.reSync();
        }

        long end = System.currentTimeMillis();

        Logs.writeLog("Synced data in " + (end - start) + "ms");
    }

    private void setSyncTime(){
        String time = JOptionPane.showInputDialog(language.get("enterTime").getAsString());
        if (time == null)
            return;
        String[] timeSplit = time.split(":");
        if (timeSplit.length != 3){
            status.setText(language.get(INVALID_TIME).getAsString());
            return;
        }

        int hour;
        int minute;
        int second;

        try {
            hour = Integer.parseInt(timeSplit[0]);
            minute = Integer.parseInt(timeSplit[1]);
            second = Integer.parseInt(timeSplit[2]);
        } catch (NumberFormatException e){
            status.setText(language.get(INVALID_TIME).getAsString());
            return;
        }

        if (hour < 0 || hour > 23 || minute < 0 || minute > 59 || second < 0 || second > 59){
            status.setText(language.get(INVALID_TIME).getAsString());
            return;
        }
        dataSyncTask.setTimeSync(hour, minute, second);
        status.setText(language.get("timeSyncSet").getAsString());
    }

    private void toXlsx(){
        String path = JOptionPane.showInputDialog(language.get("enterPath").getAsString());
        if (path == null || path.isEmpty())
            return;
        // check valid directory path
        File dir = new File(path);
        if (!dir.isDirectory()){
            status.setText(language.get("invalidPath").getAsString());
            return;
        }

        airTable.exportToXlsx(path);
        status.setText(language.get("ExportToXlsxSuccess").getAsString());
    }

    private void changeLanguage(){

        File file;
        if(language.get("currentLanguage").getAsString().equals("en")){
            file = new File("src/main/resources/data/vi.json");
        } else {
            file = new File("src/main/resources/data/en.json");
        }

        try {
            if (file.exists()) {
                Gson gson = new Gson();
                FileReader fileReader = new FileReader(file);
                language = gson.fromJson(fileReader, JsonObject.class);

                status.setText(language.get("languageChanged").getAsString());

                createChannelsButton.setText(language.get("createChannelButton").getAsString());
                addUserToChannelButton.setText(language.get("addUserButton").getAsString());
                removeUserFromChannelButton.setText(language.get("removeUserButton").getAsString());
                syncButton.setText(language.get("syncButton").getAsString());
                setSyncTimeButton.setText(language.get("setSyncButton").getAsString());
                airTableToXlsxButton.setText(language.get("toXlsxButton").getAsString());
                changeLanguageButton.setText(language.get("changeLanguage").getAsString());

                listAllChannelsButton.setText(language.get("listChannelButton").getAsString());

                listAllUsersButton.setText(language.get("listUserButton").getAsString());
            }
            else {
                status.setText(language.get("languageNotChanged").getAsString());
            }
        } catch (FileNotFoundException e) {
            showErrorDialog("Error: Could not find language file");
        }
    }


    public static void main(String[] args) {
        JFrame loadingDialog = createLoadingDialog();

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            boolean isDone = true;
            private Slack slack;
            private AirTable airTable;

            @Override
            protected Void doInBackground() {
                CompletableFuture<Slack> slackFuture = CompletableFuture.supplyAsync(Slack::new);
                CompletableFuture<AirTable> airTableFuture = CompletableFuture.supplyAsync(AirTable::new);


                slack = slackFuture.join();


                airTable = airTableFuture.join();


                if (!airTable.isActive() || !slack.isActive()) {
                    if (airTable.isActive())
                        showErrorDialog("Error: Could not connect to Slack (please check your internet connection or your Slack API token)");
                    else if (slack.isActive())
                        showErrorDialog("Error: Could not connect to AirTable (please check your internet connection or your AirTable API token and base ID)");
                    else
                        showErrorDialog("Error: Could not connect to Slack and AirTable (please check your internet connection or your Slack and AirTable API token)");
                    isDone = false;
                    return null;
                }

                return null;
            }

            @Override
            protected void done() {
                loadingDialog.dispose();
                if (!isDone)
                    return;
                showMainUI(airTable, slack);
            }
        };

        worker.execute();
        loadingDialog.setVisible(true);
    }

    private static JFrame createLoadingDialog() {
        JFrame frame = new JFrame("Loading");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setSize(400, 100);
        frame.setLocationRelativeTo(null);
        JLabel loadingLabel = new JLabel("Loading...");
        loadingLabel.setHorizontalAlignment(SwingConstants.CENTER);
        frame.add(loadingLabel);
        return frame;
    }


    private static void showErrorDialog(String message) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Error");
            frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            frame.setSize(400, 200);
            frame.setLocationRelativeTo(null);

            // Create a JTextArea to display the error message with scrollbars
            JTextArea errorTextArea = new JTextArea(message);
            errorTextArea.setLineWrap(true);
            errorTextArea.setWrapStyleWord(true);
            errorTextArea.setEditable(false);

            JScrollPane scrollPane = new JScrollPane(errorTextArea); // Wrap the JTextArea in a JScrollPane

            // Add the scroll pane to the center of the frame's content pane
            frame.getContentPane().add(scrollPane, BorderLayout.CENTER);

            frame.setVisible(true);
        });
    }


    private static void showMainUI(AirTable airTable, Slack slack) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("MainUI");
            MainUI mainUI = new MainUI(airTable, slack);
            frame.setContentPane(mainUI.mainPanel);
            frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}
