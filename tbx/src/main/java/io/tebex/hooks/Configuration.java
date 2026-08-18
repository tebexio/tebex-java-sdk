package io.tebex.hooks;

/**
 * How the SDK reads and writes the integration's configuration.
 *
 * <p>The SDK holds no configuration file of its own and does no file IO: where
 * the values live, and in what format, is entirely the host's business. It only
 * asks for keys by name — the secret key it should connect with, whether debug
 * logging is on, what the buy command is called — and expects the host to answer
 * from wherever it keeps them.
 *
 * <p>The key names the SDK uses are the {@code CONFIG_*} constants on
 * {@link io.tebex.Plugin}. An unset key must be answered with {@code null}
 * rather than an empty string, so that "not configured" can be told apart from
 * "configured as blank".
 */
public interface Configuration {

    /**
     * Sets a configuration value in memory. {@link #Save()} persists it.
     *
     * @param key   the key to set
     * @param value the value to store
     * @return the value previously held for the key, or {@code null} if none
     */
    String Set(String key, String value);

    /**
     * Reads a configuration value.
     *
     * @param key the key to read
     * @return the configured value, or {@code null} if the key is unset
     */
    String Get(String key);

    /**
     * Persists the current values to wherever the host keeps them.
     */
    void Save();

    /**
     * Re-reads the values from wherever the host keeps them, discarding anything
     * held in memory (TBX_029).
     */
    void Load();
}
