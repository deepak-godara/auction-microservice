package auction.streams;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import auction.model.Auction;
import auction.model.types.Status;
import auction.repository.AuctionRegistrationsRepository;
import auction.repository.AuctionRepository;
import auction.service.AuctionFinaliseService;
import auction.stream.WorkerRegistry;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class BidStreamCoordinater {

    private static final Logger log = LoggerFactory.getLogger(BidStreamCoordinater.class);

    private final RedisTemplate<String, String>      redisTemplate;
    private final RedissonClient                     redissonClient;
    private final AuctionRepository                  auctionRepository;
    private final AuctionRegistrationsRepository     auctionRegistrationsRepository;
    private final WorkerRegistry                     workerRegistry;
    private final AuctionFinaliseService             auctionFinaliseService;

    @Value("${bid.stream.pool-size:20}")
    private int poolSize;

    @Value("${bid.stream.batch-size:10}")
    private int batchSize;

    @Value("${bid.stream.stream-key:bids:tobeProcessed}")
    private String streamKey;

    @Value("${bid.stream.consumer-group:bid-processors}")
    private String consumerGroup;

    @Value("${bid.stream.consumer-name:bid-reader-1}")
    private String consumerName;

    // fields — not final, initialised in start()
    private ExecutorService pool;
    private AtomicBoolean   running;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        running = new AtomicBoolean(true);

        pool = new ThreadPoolExecutor(
                poolSize, poolSize,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(200),
                new ThreadPoolExecutor.AbortPolicy()
        );

        Thread.startVirtualThread(
                new BidStreamReader(streamKey, consumerGroup, consumerName,
                                    pool, redissonClient, redisTemplate, running));

        log.info("BidStreamCoordinater started — poolSize={} streamKey={}", poolSize, streamKey);
    }

    @PreDestroy
    public void stop() {
        log.info("BidStreamCoordinater stopping...");
        if (running != null) running.set(false);
        if (pool != null) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.warn("BidStreamCoordinater — pool did not terminate in 30s, forcing shutdown");
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
            }
        }
        log.info("BidStreamCoordinater stopped");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Writes auction metadata + registered users to Redis every 5 minutes.
    // This is the only data BidService and BidProcessor need from the DB —
    // keeping it in Redis avoids a DB hit on every bid.
    //
    // Also picks up newly CREATED auctions starting within the next 15 minutes
    // and transitions them to START_INITIATED so BidService allows bids.
    //
    // Also finalises auctions that ended more than 5 minutes ago.
    // ─────────────────────────────────────────────────────────────────────────
    @Scheduled(fixedDelay = 300_000) // every 5 minutes
    public void syncAuctionDataToRedis() {
        try {
            doSyncAuctionDataToRedis();
        } catch (Exception e) {
            log.error("syncAuctionDataToRedis tick failed — will retry next interval", e);
        }
    }

    private void doSyncAuctionDataToRedis() {
        LocalDateTime now    = LocalDateTime.now();
        LocalDateTime window = now.plus(Duration.ofMinutes(15));

        // ── 1. Write metadata + users for upcoming/active auctions ───────────
        List<Auction> upcoming = auctionRepository
                .findByStatusAndStartTimeBetweenOrderByStartTimeAsc(Status.CREATED, now, window);

        for (Auction auction : upcoming) {
            try {
                if (LocalDateTime.now().isAfter(auction.getEndTime())) {
                    log.info("syncAuctionData — auction already ended, skipping auctionId={}", auction.getId());
                    continue;
                }

                // Write registered bidders to Redis set
                List<String> users = auctionRegistrationsRepository
                        .findBidderIdsByAuction_IdAndFeePaidTrue(auction.getId());
                if (users != null && !users.isEmpty()) {
                    redisTemplate.opsForSet().add(
                            "auction:{" + auction.getId() + "}:users",
                            users.toArray(new String[0]));
                }

                // Write auction metadata hash
                workerRegistry.storeMetadata(auction);

                // Flip to START_INITIATED so BidService time-window check passes
                auction.setStatus(Status.START_INITIATED);
                auctionRepository.save(auction);

                log.info("syncAuctionData — metadata written auctionId={} status=START_INITIATED",
                        auction.getId());
            } catch (Exception e) {
                log.error("syncAuctionData — failed for auctionId={} — will retry next tick",
                        auction.getId(), e);
            }
        }

        // ── 2. Also refresh metadata for already STARTED/START_INITIATED auctions ──
        // Covers newly registered bidders who paid fee after the initial write.
        List<Auction> active = auctionRepository
                .findByStatusIn(List.of(Status.START_INITIATED, Status.STARTED));

        for (Auction auction : active) {
            try {
                List<String> users = auctionRegistrationsRepository
                        .findBidderIdsByAuction_IdAndFeePaidTrue(auction.getId());
                if (users != null && !users.isEmpty()) {
                    redisTemplate.opsForSet().add(
                            "auction:{" + auction.getId() + "}:users",
                            users.toArray(new String[0]));
                }
            } catch (Exception e) {
                log.error("syncAuctionData — failed refreshing users for auctionId={}", auction.getId(), e);
            }
        }

        // ── 3. Finalise ended auctions ────────────────────────────────────────
        auctionRepository
                .findByStatusInAndEndTimeLessThan(
                        List.of(Status.STARTED, Status.START_INITIATED),
                        LocalDateTime.now())
                .forEach(auction -> {
                    try {
                        auctionFinaliseService.finalise(auction.getId());
                        log.info("syncAuctionData — finalised auctionId={}", auction.getId());
                    } catch (Exception e) {
                        log.error("syncAuctionData — failed to finalise auctionId={}", auction.getId(), e);
                    }
                });
    }
}