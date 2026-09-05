package auction.streams;

import org.springframework.data.redis.core.script.RedisScript;

/**
 * Shared Lua scripts used by both BidStreamReader and BidProcessor.
 * Keeping them here avoids duplication and ensures both use the same
 * XACK+DECR atomicity guarantee.
 */
public final class BidStreamScripts {

    private BidStreamScripts() {}

    /**
     * Atomically ACKs a message and decrements the per-auction pending counter.
     * DECR only fires if XACK actually removed the message (returns 1),
     * preventing double-decrement on redelivered messages.
     *
     * KEYS[1] = stream key
     * KEYS[2] = auction pel counter key  e.g. auction:{id}:pel
     * ARGV[1] = consumer group name
     * ARGV[2] = message id
     */
    public static final RedisScript<Long> ACK_AND_DECR = RedisScript.of("""
            local acked = redis.call('XACK', KEYS[1], ARGV[1], ARGV[2])
            if acked == 1 then
                redis.call('DECR', KEYS[2])
            end
            return acked
            """, Long.class);

    
}
