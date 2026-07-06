package io.virbius.policy.audit;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.StreamEntryID;

public class RedisStreamAuditSink implements AuditEventSink {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamAuditSink.class);

    private final Optional<JedisPool> redisPool;
    private final String streamKey;

    public RedisStreamAuditSink(Optional<JedisPool> redisPool, String streamKey) {
        this.redisPool = redisPool;
        this.streamKey = streamKey != null && !streamKey.isBlank()
                ? streamKey
                : "virbius:audit:events";
    }

    @Override
    public void publish(List<Map<String, String>> events) {
        if (redisPool.isEmpty() || events.isEmpty()) {
            return;
        }
        try (Jedis jedis = redisPool.get().getResource()) {
            Pipeline pipeline = jedis.pipelined();
            for (Map<String, String> fields : events) {
                pipeline.xadd(streamKey, StreamEntryID.NEW_ENTRY, fields);
            }
            pipeline.sync();
        } catch (Exception e) {
            log.warn("audit redis batch publish failed: {}", e.getMessage());
        }
    }

    public String streamKey() {
        return streamKey;
    }
}
