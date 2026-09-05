package auction.streams;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

import lombok.AllArgsConstructor;


@AllArgsConstructor
public class BidProcessor implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(BidProcessor.class);

    private static final RedisScript<Long> bidProcessdScript = BidStreamScripts.ACK_AND_DECR;

    // ── stream constants ──────────────────────────────────────────────────────
    private static final String PROCESSED_STREAM = "bids-processed";
    private static final String GROUP_NAME        = "bid-processors";

    // ── per-message state (set via @AllArgsConstructor) ───────────────────────
    private final MapRecord<String, Object, Object> record;
    private final RedisTemplate<String, String>     redisTemplate;
    private final RedissonClient                    redissonClient;
    private final String                            streamKey;       
    private final BigDecimal                        minBidIncrement; 

    // ─────────────────────────────────────────────────────────────────────────
    // Entry point — called by virtual thread from pool
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void run() {
        processBid();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Main bid processing logic
    // ─────────────────────────────────────────────────────────────────────────
    public void processBid() {
        String messageId = record.getId().getValue();
        Map<Object, Object> body = record.getValue();

        // ── 1. Parse message fields ───────────────────────────────────────────
        String auctionIdStr = (String) body.get("auctionId");
        String bidderId     = (String) body.get("bidderId");
        String amountStr    = (String) body.get("amount");

        if (auctionIdStr == null || bidderId == null || amountStr == null) {
            log.warn("BidProcessor — malformed message messageId={} — ACKing and skipping", messageId);
            // Cannot use ack() here — auctionIdStr may be null so the pel counter key
            // would be wrong. ACK directly without touching any counter.
            try {
                redisTemplate.opsForStream().acknowledge(streamKey, GROUP_NAME, messageId);
            } catch (Exception e) {
                log.error("BidProcessor — failed to ACK malformed messageId={}", messageId, e);
            }
            return;
        }

        String     leaderboardKey = "auction:{" + auctionIdStr + "}:leaderboard";
        BigDecimal amount         = new BigDecimal(amountStr);

        
        String lockName = "auction:{" + auctionIdStr + "}:lock";
        RLock  lock     = redissonClient.getLock(lockName);

        boolean acquired;
        try {
            acquired = lock.tryLock(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("BidProcessor — interrupted waiting for lock messageId={} auctionId={}",
                    messageId, auctionIdStr);
            return; // no ACK — stays in PEL
        }

        if (!acquired) {
            log.error("BidProcessor — ALERT: could not acquire lock in 5s — " +
                      "extreme contention or Redis issue auctionId={} bidderId={}",
                      auctionIdStr, bidderId);
            return; // no ACK — stays in PEL
        }

        long lockAcquiredAt = System.currentTimeMillis();

        try {
            // ── 3. Read leaderboard top-3 (inside lock) ───────────────────────
            Set<ZSetOperations.TypedTuple<String>> topBidders =
        redisTemplate.opsForZSet()
                .reverseRangeWithScores(leaderboardKey, 0, 0);

if (topBidders != null && !topBidders.isEmpty()) {

    ZSetOperations.TypedTuple<String> topBidder =
            topBidders.iterator().next();

    String currentTopBidder = topBidder.getValue();

    BigDecimal currentTopBid =
            BigDecimal.valueOf(topBidder.getScore());

    // Same bidder is already #1 — no change to leaderboard, just ACK via finally
    if (bidderId.equals(currentTopBidder)) {
        log.info("BidProcessor — bidder is already highest bidder " +
                 "auctionId={} bidderId={} amount={}",
                 auctionIdStr, bidderId, amount);
        return;
    }

    // Bid must be at least current top + minimum increment
    BigDecimal requiredBid = currentTopBid.add(minBidIncrement);

    if (amount.compareTo(requiredBid) < 0) {
        log.info("BidProcessor — rejected (below minimum increment) " +
                 "auctionId={} bidderId={} amount={} required={}",
                 auctionIdStr, bidderId, amount, requiredBid);
        return;
    }
}


            // ── 9. Accept — update leaderboard ────────────────────────────────
            redisTemplate.opsForZSet().add(leaderboardKey, bidderId, amount.doubleValue());
            log.info("BidProcessor — accepted auctionId={} bidderId={} amount={} ",
                    auctionIdStr, bidderId, amount);

            // ── 10. Write to bids-processed stream — DB pipeline picks it up ──
            Map<String, String> processed = new HashMap<>();
            processed.put("auctionId", auctionIdStr);
            processed.put("bidderId",  bidderId);
            processed.put("amount",    amount.toPlainString());
            processed.put("timestamp", LocalDateTime.now().toString());
            redisTemplate.opsForStream().add(PROCESSED_STREAM, processed);

        } finally {
            // ── 11. Alert if lock was held too long ────────────────────────────
            long elapsed = System.currentTimeMillis() - lockAcquiredAt;
            if (elapsed > 1_000) {
                log.error("BidProcessor — ALERT: lock held for {}ms (normal <10ms) " +
                          "auctionId={} — investigate Redis latency", elapsed, auctionIdStr);
            }

            // ── 12. Release lock ───────────────────────────────────────────────
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }

            // ── 13. ACK after lock released — removes message from PEL ─────────
            // ACK is last: if it fails, message is redelivered (ZADD is idempotent
            // for same score, so reprocessing the same bid is safe).
            ack(messageId,auctionIdStr);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Acknowledge — removes message from PEL
    // ─────────────────────────────────────────────────────────────────────────
    private void ack(String messageId, String auctionId) {
        try {
            redisTemplate.execute(
                    bidProcessdScript,
                    List.of(streamKey, "auction:{" + auctionId + "}:pel"),
                    GROUP_NAME, messageId);
        } catch (Exception e) {
            log.error("BidProcessor — failed to ACK messageId={} — will be redelivered", messageId, e);
        }
    }
}
