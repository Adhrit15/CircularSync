package com.circularsync.daemon;

/**
 * Callback for pipeline outcomes. Invoked on watcher or worker threads, so
 * implementations must be thread-safe and should return quickly.
 */
@FunctionalInterface
public interface ProcessingListener {

    void onProcessed(ProcessingEvent event);
}
