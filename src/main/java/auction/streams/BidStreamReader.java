package auction.streams;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.core.RedisTemplate;

public class BidStreamReader implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(BidStreamReader.class);

    // Run PEL drain every 20 reader loop iterations.
    // Idle threshold (30s) must exceed the maximum expected lock-hold time (20s test sleep)
    // so we never reclaim a message that is still actively being processed.
    private static final int PEL_DRAIN_EVERY_N_ITERATIONS = 20;
    private static final Duration PEL_IDLE_THRESHOLD = Duration.ofSeconds(10);

    private final String                        streamKey;
    private final String                        consumerGroup;
    private final String                        consumerName;
    private final ExecutorService               pool;
    private final RedissonClient                redissonClient;
    private final RedisTemplate<String, String> redisTemplate;
    private final AtomicBoolean                 running;


    private int loopCounter = 0;

    public BidStreamReader(String streamKey, String consumerGroup, String consumerName,
                           ExecutorService pool, RedissonClient redissonClient,
                           RedisTemplate<String, String> redisTemplate, AtomicBoolean running) {
        this.streamKey     = streamKey;
        this.consumerGroup = consumerGroup;
        this.consumerName  = consumerName;
        this.pool          = pool;
        this.redissonClient = redissonClient;
        this.redisTemplate  = redisTemplate;
        this.running        = running;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Entry point
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void run() {
        createGroupIfAbsent();
        while (running.get()) {
            // Only drain PEL every N iterations — avoid hammering Redis on every tight loop,
            // and ensure idle threshold (30s) has time to pass before we reclaim.
            if (++loopCounter % (PEL_DRAIN_EVERY_N_ITERATIONS*5) == 0) {
                pendingBidClaimer();
            }
            bidsToProcess(); // one pass — fetch and submit fresh messages
        }
        log.info("BidStreamReader — loop exited");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fresh messages — XREADGROUP ">"
    // ─────────────────────────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    public void bidsToProcess() {
        List<MapRecord<String, Object, Object>> bidsToExecute =
                redisTemplate.opsForStream().read(
                        Consumer.from(this.consumerGroup, this.consumerName),
                        StreamReadOptions.empty().block(Duration.ofMillis(100)).count(10),
                        StreamOffset.create(this.streamKey, ReadOffset.lastConsumed()));

        if (bidsToExecute == null || bidsToExecute.isEmpty()) return;

        for (MapRecord<String, Object, Object> bid : bidsToExecute) {
            try {
                String auctionId       = (String) bid.getValue().get("auctionId");
                String minBidIncrStr   = (String) redisTemplate.opsForHash()
                        .get("auction:{" + auctionId + "}:metadata", "minBidIncrement");

                if (minBidIncrStr == null) {
                    log.warn("BidStreamReader — no metadata for auctionId={} — ACKing and skipping", auctionId);
                    ackAndDecr(auctionId, bid.getId().getValue());
                    continue;
                }

                pool.execute(new BidProcessor(
                        bid, redisTemplate, redissonClient,
                        streamKey, new BigDecimal(minBidIncrStr)));

            } catch (RejectedExecutionException ex) {
                // pool saturated — do NOT ACK — message stays in PEL — retried next claim cycle
                log.warn("BidStreamReader — pool saturated, bid stays in PEL messageId={}",
                        bid.getId().getValue());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PEL drain — XCLAIM messages idle > 5s (genuinely stuck, not in-flight)
    // ─────────────────────────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
   public void pendingBidClaimer() {
    log.info("BidStreamReader — checking PEL for messages idle >= 30 seconds");

    final Consumer consumer =
            Consumer.from(this.consumerGroup, this.consumerName);

    final Duration minIdleTime = Duration.ofSeconds(5); // 30s — must exceed max processing time

    PendingMessages pendingMessages;

    try {
        /*
         * Find pending messages:
         * - for this stream
         * - belonging to this consumer group + consumer
         * - with IDs from 0-0 onwards
         * - idle for at least 30 seconds
         *
         * count = 100 means process at most 100 per claimer cycle.
         */
        pendingMessages = redisTemplate.opsForStream().pending(
                this.streamKey,
                consumer,
                Range.closed("0-0", "+"),
                100,
                minIdleTime
        );

    } catch (Exception e) {
        log.warn(
                "BidStreamReader — PEL lookup failed " +
                "(stream/group may not exist yet): {}",
                e.getMessage()
        );
        return;
    }

    if (pendingMessages == null || pendingMessages.isEmpty()) {
        log.info(
                "BidStreamReader — PEL check: no messages idle >= {} seconds",
                minIdleTime.toSeconds()
        );
        return;
    }

    /*
     * Extract the IDs of the stale pending messages.
     */
    List<RecordId> messageIds = pendingMessages.stream()
            .map(PendingMessage::getId)
            .toList();

    log.info(
            "BidStreamReader — found {} pending message(s) idle >= {} seconds",
            messageIds.size(),
            minIdleTime.toSeconds()
    );


    List<MapRecord<String, Object, Object>> pelBidsToExecute;

    try {
        pelBidsToExecute = redisTemplate.opsForStream().claim(
                this.streamKey,
                this.consumerGroup,
                this.consumerName,
                minIdleTime,
                messageIds.toArray(new RecordId[0])
        );

    } catch (Exception e) {
        log.warn(
                "BidStreamReader — PEL claim failed: {}",
                e.getMessage()
        );
        return;
    }

    if (pelBidsToExecute == null || pelBidsToExecute.isEmpty()) {
        log.info("BidStreamReader — no messages were reclaimed");
        return;
    }

    log.info(
            "BidStreamReader — reclaimed {} PEL message(s)",
            pelBidsToExecute.size()
    );

    for (MapRecord<String, Object, Object> bid : pelBidsToExecute) {
        try {
            String auctionId = (String) bid.getValue().get("auctionId");

            String minBidIncrStr = (String) redisTemplate.opsForHash()
                    .get(
                            "auction:{" + auctionId + "}:metadata",
                            "minBidIncrement"
                    );

            if (minBidIncrStr == null) {
                log.warn("BidStreamReader — no metadata for auctionId={} (PEL) — ACKing messageId={}",
                        auctionId, bid.getId().getValue());
                ackAndDecr(auctionId, bid.getId().getValue());
                continue;
            }

            pool.execute(new BidProcessor(
                    bid,
                    redisTemplate,
                    redissonClient,
                    streamKey,
                    new BigDecimal(minBidIncrStr)
            ));

        } catch (RejectedExecutionException ex) {

            /*
             * Don't ACK.
             *
             * The message remains pending.
             * It can be picked up by the next claimer cycle
             * once it has been idle for >= 30 seconds again.
             */
            log.warn(
                    "BidStreamReader — pool saturated, " +
                    "messageId={} remains pending",
                    bid.getId().getValue()
            );
            try {
                log.info("Allowing the thread to get cooled down");
                Thread.sleep(Duration.ofSeconds(10));
            } catch (InterruptedException ex1) {
                Thread.currentThread().interrupt();
                log.warn("BidStreamReader — PEL pool backoff interrupted");
            }

        } catch (Exception ex) {

            /*
             * Don't ACK on processing errors.
             * Message remains in PEL for retry.
             */
            log.error(
                    "BidStreamReader — error processing PEL messageId={}",
                    bid.getId().getValue(),
                    ex
            );
        }
    }
   }
    // ─────────────────────────────────────────────────────────────────────────
    // ACK + DECR pel counter atomically via Lua
    // ─────────────────────────────────────────────────────────────────────────
    private void ackAndDecr(String auctionId, String messageId) {
        try {
            redisTemplate.execute(
                    BidStreamScripts.ACK_AND_DECR,
                    List.of(streamKey, "auction:{" + auctionId + "}:pel"),
                    consumerGroup, messageId);
        } catch (Exception e) {
            log.error("BidStreamReader — failed to ACK+DECR auctionId={} messageId={}", auctionId, messageId, e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Create consumer group — idempotent, BUSYGROUP silently ignored
    // ─────────────────────────────────────────────────────────────────────────
    private void createGroupIfAbsent() {
        try {
            redisTemplate.opsForStream()
                    .createGroup(streamKey, ReadOffset.from("0"), consumerGroup);
            log.info("BidStreamReader — consumer group created stream={} group={}",
                    streamKey, consumerGroup);
        } catch (Exception e) {
            if (isBusyGroup(e)) {
                log.debug("BidStreamReader — consumer group already exists stream={}", streamKey);
            } else {
                throw new RuntimeException("BidStreamReader — failed to create consumer group", e);
            }
        }
    }

    private boolean isBusyGroup(Throwable e) {
        while (e != null) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) return true;
            e = e.getCause();
        }
        return false;
    }
}
