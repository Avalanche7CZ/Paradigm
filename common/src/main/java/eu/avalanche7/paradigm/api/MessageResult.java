package eu.avalanche7.paradigm.api;

/** Result of a server-side message delivery request. */
public enum MessageResult {
    SENT,
    API_UNAVAILABLE,
    PLAYER_OFFLINE,
    INVALID_TEMPLATE,
    DELIVERY_FAILED
}
