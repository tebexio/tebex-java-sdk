package io.tebex.hooks;

public interface Configuration {
    String Set(String key, String value);
    String Get(String key);
    void Save();
    void Load();
}
