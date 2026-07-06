package io.virbius.policy.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Publish audit events asynchronously via a pluggable {@link AuditEventSink}. */
public class AuditEventPublisher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AuditEventPublisher.class);

    private final AuditEventSink sink;
    private final boolean async;
    private final int batchSize;
    private final long flushIntervalMs;
    private final BlockingQueue<Map<String, String>> queue;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread worker;

    public AuditEventPublisher(AuditEventSink sink) {
        this(sink, false, 2000, 64, 100L);
    }

    public AuditEventPublisher(
            AuditEventSink sink,
            boolean async,
            int queueMax,
            int batchSize,
            long flushIntervalMs) {
        this.sink = sink;
        this.batchSize = batchSize > 0 ? batchSize : 64;
        this.flushIntervalMs = flushIntervalMs > 0 ? flushIntervalMs : 100L;
        this.async = async;
        if (async) {
            int cap = queueMax > 0 ? queueMax : 2000;
            this.queue = new ArrayBlockingQueue<>(cap);
            this.worker = new Thread(this::workerLoop, "virbius-audit-publish");
            this.worker.setDaemon(true);
            this.worker.start();
        } else {
            this.queue = null;
            this.worker = null;
        }
    }

    public void publish(Map<String, String> fields) {
        if (fields == null || fields.isEmpty()) {
            return;
        }
        if (async) {
            if (!queue.offer(fields)) {
                log.warn("audit publish queue full, dropping event");
            }
            return;
        }
        List<Map<String, String>> batch = new ArrayList<>(1);
        batch.add(fields);
        sink.publish(batch);
    }

    public boolean asyncEnabled() {
        return async;
    }

    public int queueDepth() {
        return queue != null ? queue.size() : 0;
    }

    @Override
    public void close() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        flushQueue();
        if (sink != null) {
            sink.close();
        }
    }

    private void workerLoop() {
        List<Map<String, String>> batch = new ArrayList<>(batchSize);
        while (running.get()) {
            try {
                Map<String, String> first =
                        queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    queue.drainTo(batch, batchSize - 1);
                }
                if (!batch.isEmpty()) {
                    sink.publish(batch);
                    batch.clear();
                }
            } catch (InterruptedException e) {
                if (!running.get()) {
                    break;
                }
            } catch (Exception e) {
                log.warn("audit async publish failed: {}", e.getMessage());
                batch.clear();
            }
        }
        flushQueue();
    }

    private void flushQueue() {
        if (queue == null || queue.isEmpty()) {
            return;
        }
        List<Map<String, String>> batch = new ArrayList<>(batchSize);
        queue.drainTo(batch, batchSize);
        while (!batch.isEmpty()) {
            try {
                sink.publish(batch);
            } catch (Exception e) {
                log.warn("audit flush on shutdown failed: {}", e.getMessage());
            }
            batch.clear();
            queue.drainTo(batch, batchSize);
        }
    }
}
