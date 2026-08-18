package io.tebex;

import java.util.logging.Level;
import java.util.logging.Logger;

public class Log {

    /**
     * The default logger name, used until an integration supplies its own.
     */
    private static final String DEFAULT_LOGGER_NAME = "Tebex";

    // Defaulted rather than left null: every Log method dereferences this, so an
    // integration that logs before calling SetLogger would otherwise take an NPE
    // out of the SDK — exactly what TBX_001 forbids.
    Logger logger = Logger.getLogger(DEFAULT_LOGGER_NAME);

    /**
     * Sets the logger the engine reports through. Integrations should call this
     * before StartPlugin to route output to their own logger.
     *
     * @param logger the logger to use
     */
    public void SetLogger(Logger logger) {
        if (logger != null) {
            this.logger = logger;
        }
    }

    /**
     * Logs an informational message through the configured logger.
     *
     * @param message the message
     */
    public void Info(String message) {
        logger.info(message);
    }

    /**
     * Logs a warning through the configured logger.
     *
     * @param message the message
     */
    public void Warn(String message) {
        logger.warning(message);
    }

    /**
     * Logs an error through the configured logger.
     *
     * @param message the message
     * @param error   the associated throwable, or {@code null}
     */
    public void Error(String message, Throwable error) {
        logger.log(Level.SEVERE, message, error);
    }

    /**
     * Logs a diagnostic message, but only while debug mode is enabled.
     *
     * <p>Gated on {@link TXE#DEBUG_MODE} so the cost of building debug strings is
     * the only thing paid when debugging is off, and so {@code /tebex debug} has
     * a visible effect.
     *
     * <p>Emitted at {@code INFO} rather than {@code FINEST} deliberately:
     * {@code java.util.logging} discards records below the logger's level and the
     * default is {@code INFO}, so a {@code FINEST} record would be dropped by
     * almost every host configuration and turning debug mode on would appear to
     * do nothing. The {@code [DEBUG]} prefix keeps it distinguishable.
     *
     * @param message the message
     */
    public void Debug(String message) {
        if (!TXE.DEBUG_MODE) {
            return;
        }
        logger.log(Level.INFO, "[DEBUG] " + message);
    }
}
