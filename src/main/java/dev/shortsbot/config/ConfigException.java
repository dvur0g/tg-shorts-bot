package dev.shortsbot.config;

/** Thrown when the environment does not describe a valid configuration. */
public class ConfigException extends RuntimeException {

    public ConfigException(String message) {
        super(message);
    }
}
