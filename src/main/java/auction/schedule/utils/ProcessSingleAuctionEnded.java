package auction.schedule.utils;

import java.math.BigDecimal;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.json.JsonParseException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// import com.fasterxml.jackson.core.JsonProcessingException;
// import com.fasterxml.jackson.databind.ObjectMapper;

import auction.model.Auction;
import auction.model.AuctionTopBidders;
import auction.model.OutboxEvents;
import auction.model.types.Status;
import auction.repository.AuctionRegistrationsRepository;
import auction.repository.AuctionRepository;
import auction.repository.AuctionTopBiddersRepository;
import auction.repository.OutboxEventsRepository;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class ProcessSingleAuctionEnded {

    private static final Logger log = LoggerFactory.getLogger(ProcessSingleAuctionEnded.class);

    private final AuctionRepository              auctionRepository;
    private final AuctionRegistrationsRepository auctionRegistrationsRepository;
    private final AuctionTopBiddersRepository    auctionTopBiddersRepository;
    private final OutboxEventsRepository         outboxEventsRepository;
    private final ObjectMapper                   objectMapper;

    @Transactional
    public void processSingleAuctionEnded(Auction auction) {
        Long auctionId = auction.getId();

        List<AuctionTopBidders> bidders = auctionTopBiddersRepository.findAllByAuctionId(auctionId);

        if (bidders.isEmpty()) {
            log.warn("No top bidders for auctionId={} — skipping", auctionId);
            return;
        }

        List<String> excludedBidderIds = bidders.stream()
                .map(AuctionTopBidders::getBidderId)
                .toList();

        // Mark losers PENDING + save outbox row atomically in this TX
        BigDecimal refundAmount = auction.getRegistrationFee().subtract(BigDecimal.valueOf(300));
        int rowsChanged = auctionRegistrationsRepository.markEligibleLosersAsPending(
                auctionId, refundAmount, excludedBidderIds);

        try {
            String payload = objectMapper.writeValueAsString(excludedBidderIds);
            OutboxEvents outboxEvent = OutboxEvents.builder()
                    .auctionId(auctionId)
                    // .topic("auction-ended")
                    .payload(payload)
                    .build();
            outboxEventsRepository.save(outboxEvent);
        } catch (JsonParseException ex) {
            // List<String> serialization cannot fail in practice
            throw new RuntimeException("Failed to serialize excludedBidderIds for auctionId=" + auctionId, ex);
        }

        // Update auction status last — if anything above failed the TX rolls back
        // and the auction stays CLOSED so the next poll cycle retries cleanly
        Status newStatus = rowsChanged > 0 ? Status.REFUND_INITIATED : Status.PAYMENT_PENDING;
        auctionRepository.updateAuctionStatus(auctionId, newStatus);

        log.info("auctionId={} → {} ({} registration(s) marked for refund)", auctionId, newStatus, rowsChanged);
    }
}
