package auction.schedule;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import auction.model.Auction;
import auction.repository.AuctionRepository;
import auction.service.PaymentDefaultProcessor;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentDeadlineScheduler {

    private static final Logger log = LoggerFactory.getLogger(PaymentDeadlineScheduler.class);

    private final ExecutorService auctionPaymentExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final AuctionRepository        auctionRepository;
    private final PaymentDefaultProcessor  paymentDefaultProcessor;

    @Scheduled(fixedDelay = 1 * 60 * 1000) // every 5 minutes
    public void checkAuctionPaymentDoneStatus() {
        log.debug("PaymentDeadlineScheduler triggered");

        List<Auction> deadlinedAuctions = auctionRepository
                .findOverdueAuctionsWithPendingBidder(LocalDateTime.now());

        if (deadlinedAuctions.isEmpty()) {
            log.debug("No overdue payment pending auctions found");
            return;
        }

        log.info("Found {} overdue auction(s) to process", deadlinedAuctions.size());
        Collections.shuffle(deadlinedAuctions);

        List<CompletableFuture<Void>> futures = deadlinedAuctions.stream()
                .map(auction -> CompletableFuture.runAsync(() -> {
                    try {
                        paymentDefaultProcessor.process(auction);
                    } catch (Exception ex) {
                        log.error("Failed to process deadlined auction — auctionId={}", auction.getId(), ex);
                    }
                }, auctionPaymentExecutor))
                .toList();

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(15, TimeUnit.MINUTES);
            log.info("Completed processing {} overdue auction(s)", deadlinedAuctions.size());
        } catch (TimeoutException ex) {
            log.warn("Timeout waiting for overdue auction batch — auctionCount={}", deadlinedAuctions.size());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException ex) {
            log.error("Unexpected error in overdue auction batch", ex);
        }
    }
}
