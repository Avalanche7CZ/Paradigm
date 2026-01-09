package eu.avalanche7.paradigm.data;

import java.util.HashSet;
import java.util.Set;

public class Group {
    private final String name;
    private String owner;
    private final Set<String> members = new HashSet<>();
    private boolean isPublic;

    public Group(String name, String owner) {
        this.name = name;
        this.owner = owner;
        this.members.add(owner);
        this.isPublic = false;
    }

    public String getName() {
        return name;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public Set<String> getMembers() {
        return members;
    }

    public void addMember(String member) {
        members.add(member);
    }

    public void removeMember(String member) {
        members.remove(member);
    }

    public boolean isPublic() {
        return isPublic;
    }

    public void setPublic(boolean isPublic) {
        this.isPublic = isPublic;
    }
}
