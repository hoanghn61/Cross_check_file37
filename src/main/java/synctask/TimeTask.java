package synctask;

import airtable.AirTable;
import logs.Logs;
import slack.Slack;

import slack.Channel;
import slack.SlackUser;

import java.util.List;

class TimeTask extends java.util.TimerTask {

    private final AirTable airtable;
    private final Slack slack;

    public TimeTask() {
        this.airtable = new AirTable();
        this.slack = new Slack();
    }

    @Override
    public void run() {
        slack.syncLocal();
        List<SlackUser> userList = slack.getUsers();
        List<Channel> channelList = slack.getChannels();
        if (!airtable.pushData(channelList, userList, false)) {
            Logs.writeLog("Failed to push data to Airtable when scheduled");
            airtable.reSync();
        }
        Logs.writeLog("Scheduled sync completed");
    }
}
