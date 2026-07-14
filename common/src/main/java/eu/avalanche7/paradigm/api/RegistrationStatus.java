package eu.avalanche7.paradigm.api;

/** Outcome of an owned permission-node or placeholder registration. */
public enum RegistrationStatus {
    REGISTERED,
    ALREADY_REGISTERED,
    CONFLICT,
    INVALID,
    API_UNAVAILABLE
}
