package auction.stream;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.connection.stream.MapRecord;

import lombok.AllArgsConstructor;

/**
 * Plain Runnable — one instance per bid message.
 * Submitted to the virtual thread pool by BidStreamReader.
 *
 * Responsibilities:
 *  1. Acquire per-auction Redisson lock
 *  2. Read leaderboard (inside lock)
 *  3. Validate bid (top-3, upward-move, min-increment)
 *  4. Update leaderboard + write to bids-processed stream (inside lock)
 *  5. XACK the source message
 *  6. Release lock
 *
 * Pre-conditions (enforced by BidService before message enters the stream):
 *  - Bidder is registered
 *  - Auction is live (startTime < now < endTime)
 *  - Bid amount exceeds basePrice + minBidIncrement floor
 *
 * minBidIncrement is passed in from the reader (fetched once per message
 * outside the lock to keep critical section as short as possible).
 */
@AllArgsConstructor
public class BidProcessor implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(BidProcessor.class);

    // ── constants ─────────────────────────────────────────────────────────────
    private static final String PROCESSED_STREAM = "bids-processed";
    private static final String GROUP_NAME        = "bid-processors";

    // ── per-message state ─────────────────────────────────────────────────────
    private final MapRecord<String, Object, Object> record;
    private final RedisTemplate<String, String>     redisTemplate;
    private final RedissonClient                    redissonClient;
    private final String                            streamKey;      // "bids:tobeProcessed"
    private final BigDecimal                        minBidIncrement;

    // ─────────────────────────────────────────────────────────────────────────
    // Entry point — called by virtual thread from pool
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void run() {
        String messageId = record.getId().getValue();
        Map<Object, Object> body = record.getValue();

        // ── parse message fields ───────────────────────────────────────────────
        String auctionIdStr = (String) body.get("auctionId");
        String bidderId     = (String) body.get("bidderId");
        String amountStr    = (String) body.get("amount");

        if (auctionIdStr == null || bidderId == null || amountStr == null) {
            log.warn("BidProcessor — malformed message messageId={} — ACKing and skipping", messageId);
            ack(messageId);
            return;
        }

        String     leaderboardKey = "auction:{" + auctionIdStr + "}:leaderboard";
        BigDecimal amount         = new BigDecimal(amountStr);

        // ── acquire per-auction lock ───────────────────────────────────────────
        // Lock key is scoped to auction so different auctions process in parallel.
        // tryLock(5s): if lock not acquired in 5s → extreme contention or Redis issue.
        // Watchdog enabled (no leaseTime) → lock auto-renews until unlock() is called.
        RLock lock = redissonClient.getLock("auction:{" + auctionIdStr + "}:lock");
        boolean acquired;
        try {
            acquired = lock.tryLock(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("BidProcessor — interrupted waiting for lock messageId={} auctionId={}",
                    messageId, auctionIdStr);
            return; // no ACK — stays in PEL, retried next cycle
        }

        if (!acquired) {
            log.error("BidProcessor — ALERT: could not acquire lock in 5s — " +
                      "extreme contention or Redis issue auctionId={} bidderId={}",
                      auctionIdStr, bidderId);
            return; // no ACK — stays in PEL
        }

        long lockAcquiredAt = System.currentTimeMillis();

        try {
            // ── read top-3 leaderboard — inside lock ─────────────────────────
            Set<ZSetOperations.TypedTuple<String>> top3 = redisTemplate.opsForZSet()
                    .reverseRangeWithScores(leaderboardKey, 0, 2);

            // ── determine current top bid and bidder's existing rank ──────────
            BigDecimal currentTop  = BigDecimal.ZERO;
            int        currentRank = -1;   // 0-based, -1 = not in leaderboard

            if (top3 != null && !top3.isEmpty()) {
                int rank = 0;
                for (ZSetOperations.TypedTuple<String> entry : top3) {
                    if (rank == 0) {
                        currentTop = BigDecimal.valueOf(entry.getScore());
                    }
                    if (bidderId.equals(entry.getValue())) {
                        currentRank = rank;
                    }
                    rank++;
                }
            }

            // ── min-increment check ───────────────────────────────────────────
            // Bidder who is NOT currently #1 must beat currentTop + minBidIncrement.
            if (currentRank != 0 && amount.compareTo(currentTop.add(minBidIncrement)) < 0) {
                log.info("BidProcessor — rejected (below min increment) " +
                         "auctionId={} bidderId={} amount={} required={}",
                         auctionIdStr, bidderId, amount, currentTop.add(minBidIncrement));
                ack(messageId);
                return;
            }

            // ── determine landing rank ────────────────────────────────────────
            // 0-based: 0=first, 1=second, 2=third
            int landingRank = 0;
            if (top3 != null) {
                int rank = 0;
                for (ZSetOperations.TypedTuple<String> entry : top3) {
                    if (amount.compareTo(BigDecimal.valueOf(entry.getScore())) >= 0) {
                        break;
                    }
                    landingRank = rank + 1;
                    rank++;
                }
            }

            // ── reject if bid does not reach top 3 ───────────────────────────
            if (landingRank > 2) {
                log.info("BidProcessor — rejected (outside top 3) " +
                         "auctionId={} bidderId={} amount={} landingRank={}",
                         auctionIdStr, bidderId, amount, landingRank + 1);
                ack(messageId);
                return;
            }

            // ── upward-move rule ──────────────────────────────────────────────
            // A bidder can only move UP the leaderboard, never stay or fall.
            //   landing #1 → rejected if already #1
            //   landing #2 → rejected if already #1 or #2
            //   landing #3 → rejected if already anywhere in top 3
            boolean rejected = (landingRank == 0 && currentRank == 0)
                            || (landingRank == 1 && currentRank >= 0 && currentRank <= 1)
                            || (landingRank == 2 && currentRank >= 0);

            if (rejected) {
                log.info("BidProcessor — rejected (not an upward move) " +
                         "auctionId={} bidderId={} amount={} currentRank={} landingRank={}",
                         auctionIdStr, bidderId, amount, currentRank + 1, landingRank + 1);
                ack(messageId);
                return;
            }

            // ── accept — update leaderboard ───────────────────────────────────
            redisTemplate.opsForZSet().add(leaderboardKey, bidderId, amount.doubleValue());
            log.info("BidProcessor — accepted auctionId={} bidderId={} amount={} rank={}",
                    auctionIdStr, bidderId, amount, landingRank + 1);

            // ── write to bids-processed stream — DB pipeline picks it up ─────
            Map<String, String> processed = new HashMap<>();
            processed.put("auctionId", auctionIdStr);
            processed.put("bidderId",  bidderId);
            processed.put("amount",    amount.toPlainString());
            processed.put("timestamp", LocalDateTime.now().toString());
            redisTemplate.opsForStream().add(PROCESSED_STREAM, processed);

        } finally {
            // ── release lock ──────────────────────────────────────────────────
            long elapsed = System.currentTimeMillis() - lockAcquiredAt;
            if (elapsed > 1_000) {
                log.error("BidProcessor — ALERT: lock held for {}ms (normal <10ms) " +
                          "auctionId={} — investigate Redis latency", elapsed, auctionIdStr);
            }
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }

            // ── ACK after lock released — message removed from PEL ───────────
            ack(messageId);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Acknowledge — removes message from PEL
    // ─────────────────────────────────────────────────────────────────────────
    private void ack(String messageId) {
        try {
            redisTemplate.opsForStream().acknowledge(streamKey, GROUP_NAME, messageId);
        } catch (Exception e) {
            log.error("BidProcessor — failed to ACK messageId={} — will be redelivered", messageId, e);
        }
    }
}
