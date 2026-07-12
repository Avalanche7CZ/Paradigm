package eu.avalanche7.paradigm.modules.permissions;

import java.util.Map;

public class PermissionMutationRequest {
    public String action;
    public String group;
    public String parent;
    public String permission;
    public String assignmentId;
    public String user;
    public String scope;
    public Map<String, String> contexts;
    public Map<String, String> metadata;
    public String duration;
    public Long expiresAtMs;
    public Boolean permanent;
    public Boolean denied;
    public Boolean confirmed;
}
