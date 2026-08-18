package io.tebex;

import io.tebex.exception.TebexException;
import io.tebex.model.PluginEvent;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The SDK's logging harness: everything the engine and the plugin report goes
 * through here.
 *
 * <p>Two things happen to a message. It is written to the host's logger — a
 * {@link Logger} the integration installs with {@link #SetLogger(Logger)} — and,
 * for anything at warning level or worse, it is also turned into a
 * {@link PluginEvent} and offered to the installed {@link LogSink} so it can be
 * reported to Tebex (TBX_011, TBX_012, TBX_013). The sink is what makes the
 * second half happen; with none installed, logging is purely local.
 *
 * <p>Nothing here may throw: an integration logs from its own threads and the
 * engine logs from its worker, and a failure in logging must never become a
 * failure in the host (TBX_001).
 */
public class Log {

    /**
     * The default logger name, used until an integration supplies its own.
     */
    private static final String DEFAULT_LOGGER_NAME = "Tebex";

    // Defaulted rather than left null: every Log method dereferences this, so an
    // integration that logs before calling SetLogger would otherwise take an NPE
    // out of the SDK — exactly what TBX_001 forbids.
    Logger logger = Logger.getLogger(DEFAULT_LOGGER_NAME);

    // volatile: bound by whoever starts the engine, read by every thread that
    // logs. Null until an engine binds one, which is the normal state for a
    // caller using the SDK's HTTP clients without running the engine.
    private volatile LogSink sink;

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
     * Installs the sink that receives plugin events derived from what is logged.
     *
     * <p>Called by {@link TXE#StartPlugin(String)} with the running engine's
     * outbound queue. A later call replaces the previous sink, so in a process
     * running more than one engine the most recently started one collects the
     * events.
     *
     * @param sink the sink to install, or {@code null} to stop deriving events
     */
    public void SetSink(LogSink sink) {
        this.sink = sink;
    }

    /**
     * Removes the given sink if it is still the installed one.
     *
     * <p>Identity-checked rather than unconditional so a stopped engine cannot
     * detach a sink that a different engine installed after it.
     *
     * @param expected the sink the caller believes is installed
     */
    public void ClearSink(LogSink expected) {
        if (sink == expected) {
            sink = null;
        }
    }

    /**
     * Logs an informational message through the configured logger.
     *
     * <p>Deliberately not reported to Tebex: the plugin-logs service exists for
     * problems, and mirroring routine progress messages into it would cost a
     * request per tick without telling anyone anything.
     *
     * @param message the message
     */
    public void Info(String message) {
        logger.info(message);
    }

    /**
     * Logs a warning through the configured logger and records it as a plugin
     * event (TBX_011).
     *
     * @param message the message
     */
    public void Warn(String message) {
        logger.warning(message);
        record(PluginEvent.Level.WARNING, message, null);
    }

    /**
     * Logs an error through the configured logger and records it as a plugin
     * event (TBX_012).
     *
     * <p>Two exceptions to that, both driven by what the throwable turns out to
     * be. A timeout is recorded at warning level rather than error (TBX_013),
     * because failing to reach a host is a transient condition rather than a
     * fault in the integration. And a failed API call produces no event at all
     * (TBX_008): the call that would deliver the report runs over the same
     * credentials and the same network that just failed, so reporting it either
     * fails in turn or fills the store's log with the operator's own
     * misconfiguration.
     *
     * @param message the message
     * @param error   the associated throwable, or {@code null}
     */
    public void Error(String message, Throwable error) {
        logger.log(Level.SEVERE, message, error);
        record(PluginEvent.Level.ERROR, message, error);
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

    /**
     * Offers an event to the installed sink, deciding first whether this is
     * something Tebex should hear about and at what level.
     *
     * @param level   the level the message was logged at
     * @param message the logged message
     * @param error   the throwable that came with it, or {@code null}
     */
    private void record(PluginEvent.Level level, String message, Throwable error) {
        LogSink current = sink;
        if (current == null) {
            return;
        }

        PluginEvent.Level reportedLevel = level;
        if (isTimeout(error)) {
            // Checked before the API-failure suppression below, and so wins over
            // it: a timeout is exactly the condition TBX_013 asks to be reported,
            // and the SDK's own timeouts arrive wrapped in a TebexException.
            reportedLevel = PluginEvent.Level.WARNING;
        } else if (isApiFailure(error)) {
            return;
        }

        try {
            current.record(new PluginEvent(reportedLevel, message).withTrace(error));
        } catch (RuntimeException ignored) {
            // A sink that misbehaves must not turn logging into a failure.
            logger.log(Level.FINE, "A log sink rejected an event", ignored);
        }
    }

    /**
     * Returns whether a throwable, or anything it wraps, is a timeout.
     *
     * @param error the throwable to inspect, may be {@code null}
     * @return {@code true} if the failure was a timeout
     */
    private static boolean isTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException
                    || cause instanceof TimeoutException
                    || cause instanceof InterruptedIOException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break; // a self-referencing cause would loop forever
            }
        }
        return false;
    }

    /**
     * Returns whether a throwable, or anything it wraps, came from a Tebex API
     * call that failed.
     *
     * @param error the throwable to inspect, may be {@code null}
     * @return {@code true} if the failure came from an API call
     */
    private static boolean isApiFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof TebexException
                    || cause instanceof io.tebex.headless.invoker.ApiException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
