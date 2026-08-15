package auction.schedule;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import auction.model.Auction;
import auction.repository.AuctionRegistrationsRepository;
import auction.repository.AuctionRepository;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AuctionEndScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuctionEndScheduler.class);

    private final AuctionRepository auctionRepository;
    private final auction.schedule.utils.ProcessSingleAuctionEnded processSingleAuctionEnded;

    @Scheduled(fixedDelay = 60000) // Runs every 60 seconds
    public void initiateRefundForAuctions() {
        log.debug("AuctionEndScheduler triggered — checking for expired auctions");

        List<Auction> endedAuctions = auctionRepository.findEndedAuctionsForUpdateSkipLocked();

        if (endedAuctions.isEmpty()) {

            log.info("Found {} expired auction(s) to process 11111", endedAuctions.size());
            return;
        }

        log.info("Found {} expired auction(s) to process", endedAuctions.size());

        List<CompletableFuture<Void>> futures = endedAuctions.stream()
            .map(auction -> CompletableFuture.runAsync(() -> {
                try {
                    log.info("Processing auction end for auctionId={}, title='{}'", auction.getId(), auction.getTitle());
                    processSingleAuctionEnded.processSingleAuctionEnded(auction);
                    log.info("Successfully processed auction end for auctionId={}", auction.getId());
                } catch (Exception ex) {
                    log.error("Failed to process auction end for auctionId={}", auction.getId(), ex);
                }
            }))
            .toList();

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(5, TimeUnit.SECONDS);
            log.info("Completed processing batch of {} expired auction(s)", endedAuctions.size());
        } catch (Exception e) {
            log.error("Error/timeout processing auction end batch", e);
        }
    }
}
