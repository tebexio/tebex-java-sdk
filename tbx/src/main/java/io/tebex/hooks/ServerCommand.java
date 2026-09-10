package io.tebex.hooks;

/**
 * How the SDK runs a command on the host to deliver a purchase.
 *
 * <p>This is the hook without which nothing can be delivered, so the queue check
 * refuses to run at all until one is installed rather than queueing work that
 * could only fail (TBX_060).
 *
 * <p><b>Execution must be synchronous</b> (TBX_062). The SDK hands over one
 * deliverable at a time and dispatches the next only once this method returns,
 * so returning before the command has actually been applied gives up the
 * ordering the store queued them in. That ordering is load-bearing: a package
 * purchase and the removal that follows it — which is what every test purchase
 * looks like — are separate commands, and applying the removal first undoes work
 * that has not happened yet. On a host whose command API is asynchronous, wait
 * for it here rather than firing and forgetting.
 */
public interface ServerCommand {

    /**
     * Executes a command as the server console, returning once it has been
     * applied.
     *
     * @param command the command line as the host should parse it: never empty,
     *                trimmed, without a leading slash, and with the player tags
     *                Tebex wrote into it already resolved (TBX_068, TBX_063)
     */
    void Execute(String command);
}
