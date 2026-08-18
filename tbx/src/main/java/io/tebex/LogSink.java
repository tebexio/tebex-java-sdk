package io.tebex;

import io.tebex.model.PluginEvent;

/**
 * Receives the plugin events {@link Log} derives from what the SDK logs
 * (TBX_011, TBX_012, TBX_013).
 *
 * <p>{@code Log} decides <em>what</em> becomes an event and at which level; the
 * sink decides whether to keep it and where to put it. The engine binds
 * {@link Plugin}'s outbound log queue as the sink while it is running, which is
 * what turns a warning or an error into something the plugin-logs service
 * eventually receives (TBX_037).
 *
 * <p>Implementations are called from whichever thread logged, including the
 * engine's worker, so they must be safe to call concurrently and must not throw.
 */
public interface LogSink {

    /**
     * Accepts an event derived from a logged message.
     *
     * @param event the event to record
     */
    void record(PluginEvent event);
}
