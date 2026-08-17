package auction.schedule;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import auction.model.Auction;
import auction.repository.AuctionRepository;
import auction.schedule.utils.ProcessSingleAuctionEnded;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class FetchEndedAuctionScheduler {

    private static final Logger log             = LoggerFactory.getLogger(FetchEndedAuctionScheduler.class);
    private static final int    MAX_CONCURRENCY = 5;
    private static final int    TIMEOUT_SECONDS = 30;

    // Virtual threads — each task blocks on DB I/O; virtual threads park instead of
    // tying up a platform thread, so the fork-join pool is never touched.
    private final ExecutorService auctionEndExecutor = Executors.newVirtualThreadPerTaskExecutor();

    // At most MAX_CONCURRENCY auctions processed at the same time across all virtual threads.
    private final Semaphore concurrencyLimit = new Semaphore(MAX_CONCURRENCY);

    private final AuctionRepository        auctionRepository;
    private final ProcessSingleAuctionEnded processSingleAuctionEnded;

    @Scheduled(fixedDelay = 60000) // every 60 seconds
    public void initiateRefundForAuctions() {
        log.debug("FetchEndedAuctionScheduler triggered — checking for expired auctions");

        List<Auction> endedAuctions = auctionRepository.findEndedAuctionsForUpdateSkipLocked();

        if (endedAuctions.isEmpty()) {
            log.debug("No expired auctions found");
            return;
        }

        log.info("Found {} expired auction(s) to process", endedAuctions.size());

        List<CompletableFuture<Void>> futures = endedAuctions.stream()
            .map(auction -> CompletableFuture.runAsync(() -> {
                try {
                    concurrencyLimit.acquire();
                    log.info("Processing auction end — auctionId={} title='{}'",
                            auction.getId(), auction.getTitle());
                    processSingleAuctionEnded.processSingleAuctionEnded(auction);
                    log.info("Successfully processed auction end — auctionId={}", auction.getId());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception ex) {
                    log.error("Failed to process auction end — auctionId={}", auction.getId(), ex);
                } finally {
                    concurrencyLimit.release();
                }
            }, auctionEndExecutor))
            .toList();

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("Completed processing batch of {} expired auction(s)", endedAuctions.size());
        } catch (Exception e) {
            log.warn("Timeout or interruption waiting for auction end batch — auctionCount={}",
                    endedAuctions.size(), e);
        }
    }
}
