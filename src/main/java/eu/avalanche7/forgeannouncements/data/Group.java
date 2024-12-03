package eu.avalanche7.forgeannouncements.data;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class Group {
    private final String name;
    private final UUID owner;
    private final Set<UUID> members = new HashSet<>();

    public Group(String name, UUID owner) {
        this.name = name;
        this.owner = owner;
        this.members.add(owner);
    }

    public String getName() {
        return name;
    }

    public UUID getOwner() {
        return owner;
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
}
