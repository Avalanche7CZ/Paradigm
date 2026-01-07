package eu.avalanche7.paradigm.data;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class Group {
    private final String name;
    private UUID owner;
    private final Set<UUID> members = new HashSet<>();
    private boolean isPublic;

    public Group(String name, UUID owner) {
        this.name = name;
        this.owner = owner;
        this.members.add(owner);
        this.isPublic = false;
    }

    public String getName() {
        return name;
    }

    public UUID getOwner() {
        return owner;
    }

    public void setOwner(UUID owner) {
        this.owner = owner;
    }

    public Set<UUID> getMembers() {
        return members;
    }

    public void addMember(UUID member) {
        members.add(member);
    }

    public void removeMember(UUID member) {
        members.remove(member);
    }

    public boolean isPublic() {
        return isPublic;
    }

    public void setPublic(boolean isPublic) {
        this.isPublic = isPublic;
    }
}
