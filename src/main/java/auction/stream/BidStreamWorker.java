package auction.stream;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import auction.service.AuctionFinaliseService;

public class BidStreamWorker implements Runnable {

    private static final Logger log        = LoggerFactory.getLogger(BidStreamWorker.class);
    private static final String GROUP_NAME = "bid-processors";

    private final String                        auctionId;
    private final LocalDateTime                 endTime;
    private final BigDecimal                    minBidIncrement;
    private final RedisTemplate<String, String> redisTemplate;
    private final AuctionFinaliseService        finaliseService;
    private final AtomicBoolean                 running = new AtomicBoolean(true);

    // built once — reused across methods
    private final String streamKey;
    private final String consumerName;
    private final String leaderboardKey;

    public BidStreamWorker(String auctionId, LocalDateTime endTime,
                           BigDecimal minBidIncrement,
                           RedisTemplate<String, String> redisTemplate,
                           AuctionFinaliseService finaliseService) {
        this.auctionId       = auctionId;
        this.endTime         = endTime;
        this.minBidIncrement = minBidIncrement;
        this.redisTemplate   = redisTemplate;
        this.finaliseService = finaliseService;
        this.streamKey       = "auction:{" + auctionId + "}:bids";
        this.consumerName    = "consumer-" + auctionId + "-" + ProcessHandle.current().pid();
        this.leaderboardKey  = "auction:{" + auctionId + "}:leaderboard";
    }

    public void stop() {
        running.set(false);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Entry point — called by virtual thread
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void run() {
        log.info("BidStreamWorker started — auctionId={} consumer={}", auctionId, consumerName);

        // 1. Create consumer group if not exists
        createGroupIfAbsent();

        // 2. Reclaim stuck messages from previous crash (one-time on startup)
        reclaimStaleMessages();

        // 3. Start reading loop
        getIncomingBids();

        // 4. Finalise top bidders — runs regardless of how the loop exited
        //    (idle timeout, external stop() call from AuctionStreamManager, or drain worker)
        finaliseTopBidders();

        log.info("BidStreamWorker stopped — auctionId={}", auctionId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Main read loop
    // ─────────────────────────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private void getIncomingBids() {
        while (running.get()) {

            List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                    .read(Consumer.from(GROUP_NAME, consumerName),
                          StreamReadOptions.empty().block(Duration.ofSeconds(2)).count(1),
                          StreamOffset.create(streamKey, ReadOffset.lastConsumed()));

            // exit — stream idle and 5 minutes past endTime
            if ((records == null || records.isEmpty())
                    && LocalDateTime.now().isAfter(endTime.plusMinutes(5))) {
                log.info("Stream idle 5min past endTime — auctionId={} stopping", auctionId);
                redisTemplate.opsForSet().remove("auctions:active", auctionId);
                break;
            }

            if (records == null || records.isEmpty()) continue;

            processIncomingBid(records.get(0));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Finalise — delegates to AuctionFinaliseService (@Transactional Spring bean)
    // ─────────────────────────────────────────────────────────────────────────
    private void finaliseTopBidders() {
        try {
            finaliseService.finalise(Long.parseLong(auctionId));
        } catch (Exception e) {
            log.error("Failed to finalise top bidders — auctionId={}", auctionId, e);
        }
    }


    // ─────────────────────────────────────────────────────────────────────────
    // Process a single bid record
    // ─────────────────────────────────────────────────────────────────────────
    private void processIncomingBid(MapRecord<String, Object, Object> record) {
        String messageId = record.getId().getValue();
        String bidderId  = (String) record.getValue().get("bidderId");
        String amountStr = (String) record.getValue().get("amount");

        // guard — malformed message
        if (bidderId == null || amountStr == null) {
            log.warn("Malformed bid — messageId={} auctionId={}", messageId, auctionId);
            redisTemplate.opsForStream().acknowledge(streamKey, GROUP_NAME, messageId);
            return;
        }

        BigDecimal amount = new BigDecimal(amountStr);

        // fetch top 3 with scores — single Redis round trip
        Set<ZSetOperations.TypedTuple<String>> top3 = redisTemplate.opsForZSet()
                .reverseRangeWithScores(leaderboardKey, 0, 2);

        // determine current top bid and current rank of this bidder (0-based, -1 if absent)
        BigDecimal currentTop = BigDecimal.ZERO;
        int currentRank = -1;
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

        // enforce minimum increment — any bidder who is not currently #1
        // must beat currentTop + minBidIncrement to enter the leaderboard
        if (currentRank != 0 && amount.compareTo(currentTop.add(minBidIncrement)) < 0) {
            log.info("Bid rejected — below minimum increment auctionId={} bidderId={} amount={} required={}",
                    auctionId, bidderId, amount, currentTop.add(minBidIncrement));
            redisTemplate.opsForStream().acknowledge(streamKey, GROUP_NAME, messageId);
            return;
        }

        // determine what rank this bid would land at (0-based)
        int landingRank = 0; // assume #1 by default
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

        // reject — bid does not land in top 3
        if (landingRank > 2) {
            log.info("Bid rejected — does not reach top 3 auctionId={} bidderId={} amount={} landingRank={}",
                    auctionId, bidderId, amount, landingRank + 1);
            redisTemplate.opsForStream().acknowledge(streamKey, GROUP_NAME, messageId);
            return;
        }

        // Rules (bidder can only move UP, not stay or fall):
        //   landing #1 → rejected if already #1
        //   landing #2 → rejected if already #1 or #2
        //   landing #3 → rejected if already anywhere in top 3
        boolean rejected = (landingRank == 0 && currentRank == 0)
                        || (landingRank == 1 && currentRank >= 0 && currentRank <= 1)
                        || (landingRank == 2 && currentRank >= 0);
        if (rejected) {
            log.info("Bid rejected — not an upward move auctionId={} bidderId={} amount={} currentRank={} landingRank={}",
                    auctionId, bidderId, amount, currentRank + 1, landingRank + 1);
            redisTemplate.opsForStream().acknowledge(streamKey, GROUP_NAME, messageId);
            return;
        }

        // accept — update leaderboard
        redisTemplate.opsForZSet().add(leaderboardKey, bidderId, amount.doubleValue());
        log.info("Bid accepted — auctionId={} bidderId={} amount={} landingRank={}",
                auctionId, bidderId, amount, landingRank + 1);

        // write to processed stream — batch writer inserts to DB
        Map<String, String> processedBid = new HashMap<>();
        processedBid.put("auctionId",  auctionId);
        processedBid.put("bidderId",   bidderId);
        processedBid.put("amount",     amount.toString());
        processedBid.put("timestamp",  LocalDateTime.now().toString());
        redisTemplate.opsForStream().add("bids-processed", processedBid);

        // XACK — remove from PEL
        redisTemplate.opsForStream().acknowledge(streamKey, GROUP_NAME, messageId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reclaim messages stuck in PEL from previous crash
    // ─────────────────────────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private void reclaimStaleMessages() {
        try {
            List<MapRecord<String, Object, Object>> claimed = redisTemplate.opsForStream()
                    .claim(streamKey, GROUP_NAME, consumerName,
                           Duration.ofSeconds(30), RecordId.of("0-0"));

            if (claimed != null && !claimed.isEmpty()) {
                log.warn("Reclaimed {} stuck message(s) from PEL — auctionId={}", claimed.size(), auctionId);
                for (MapRecord<String, Object, Object> record : claimed) {
                    processIncomingBid(record);
                }
            }
        } catch (Exception e) {
            log.warn("PEL reclaim failed — auctionId={}", auctionId, e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Create consumer group — idempotent, BUSYGROUP silently ignored
    // ─────────────────────────────────────────────────────────────────────────
    private void createGroupIfAbsent() {
        try {
            redisTemplate.opsForStream()
                    .createGroup(streamKey, ReadOffset.from("0"), GROUP_NAME);
            log.info("Consumer group created — stream={} group={}", streamKey, GROUP_NAME);
        } catch (Exception e) {
            // Lettuce wraps the Redis error in RedisSystemException — check the full
            // cause chain for BUSYGROUP rather than just the top-level message.
            if (isBusyGroup(e)) {
                log.debug("Consumer group already exists — stream={}", streamKey);
            } else {
                throw new RuntimeException("Failed to create consumer group — stream=" + streamKey, e);
            }
        }
    }

    private boolean isBusyGroup(Throwable e) {
        while (e != null) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                return true;
            }
            e = e.getCause();
        }
        return false;
    }
}
