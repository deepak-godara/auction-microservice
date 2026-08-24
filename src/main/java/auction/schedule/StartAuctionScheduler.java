package auction.schedule;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import auction.model.Auction;
import auction.model.types.Status;
import auction.repository.AuctionRepository;
import auction.schedule.utils.IntitatedAuction;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class StartAuctionScheduler {

    private static final Logger log = LoggerFactory.getLogger(StartAuctionScheduler.class);

    private final AuctionRepository auctionRepository;
    private final IntitatedAuction  intitatedAuction;

    @Scheduled(fixedDelay = 1000 * 60 * 1)
    public void AddAuctionsToBeActivated() {
        LocalDateTime now    = LocalDateTime.now();
        LocalDateTime window = now.plus(Duration.ofMinutes(15));

        log.info("StartAuctionScheduler — tick. window=[{} → {}]", now, window);

        List<Auction> auctionList = auctionRepository
                .findByStatusAndStartTimeBetweenOrderByStartTimeAsc(Status.CREATED, now, window);

        log.info("Auctions eligible for activation — count={}", auctionList.size());

        for (Auction auction : auctionList) {
            log.info("Initiating auction — id={} title='{}' startTime={}",
                    auction.getId(), auction.getTitle(), auction.getStartTime());
            try {
                intitatedAuction.initateAuction(auction);
                log.info("Auction initiated successfully — id={}", auction.getId());
            } catch (Exception ex) {
                log.error("Failed to initiate auction — id={}", auction.getId(), ex);
            }
        }
    }
}
