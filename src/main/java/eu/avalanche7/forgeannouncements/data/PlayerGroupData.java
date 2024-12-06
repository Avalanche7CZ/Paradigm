package eu.avalanche7.forgeannouncements.data;

import java.util.HashSet;
import java.util.Set;

public class PlayerGroupData {
    private String currentGroup;
    private Set<String> invitations = new HashSet<>();
    private boolean groupChatToggled;

    public String getCurrentGroup() {
        return currentGroup;
    }

    public void setCurrentGroup(String currentGroup) {
        this.currentGroup = currentGroup;
    }

    public Set<String> getInvitations() {
        return invitations;
    }

    public void addInvitation(String groupName) {
        invitations.add(groupName);
    }

    public boolean isGroupChatToggled() {
        return groupChatToggled;
    }

    public void setGroupChatToggled(boolean groupChatToggled) {
        this.groupChatToggled = groupChatToggled;
    }
}
