package auction.schedule.utils;

import java.math.BigDecimal;
import java.util.List;

import org.hibernate.annotations.Comment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import auction.dto.AuctionEndedRequestDTO;
import auction.model.Auction;
import auction.model.AuctionTopBidders;
import auction.model.types.RefundStatus;
import auction.repository.AuctionRegistrationsRepository;
import auction.repository.AuctionRepository;
import auction.repository.AuctionTopBiddersRepository;
import auction.schedule.AuctionEndScheduler;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ProcessSingleAuctionEnded {

    private static final Logger log = LoggerFactory.getLogger(ProcessSingleAuctionEnded.class);

    private final AuctionRegistrationsRepository auctionRegistrationsRepository;
    private final AuctionTopBiddersRepository auctionTopBiddersRepository;
    private final AuctionRepository auctionRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Transactional
    public void processSingleAuctionEnded(Auction auction) {
        Long auctionId = auction.getId();
        log.info("Starting end-of-auction processing for auctionId={}, title='{}'", auctionId, auction.getTitle());

        List<AuctionTopBidders> bidders = auctionTopBiddersRepository.findAllByAuctionId(auctionId);
        log.debug("Fetched {} top bidder(s) for auctionId={}", bidders.size(), auctionId);

        if (bidders.isEmpty()) {
            log.warn("No top bidders found for auctionId={}. Skipping refund initiation and event publishing.", auctionId);
            return;
        }

        List<String> excludedBidderIds = bidders.stream()
                .map(AuctionTopBidders::getBidderId)
                .toList();
        log.info("Top bidders excluded from refund for auctionId={}: {}", auctionId, excludedBidderIds);

        AuctionEndedRequestDTO auctionEndedRequestDTO = AuctionEndedRequestDTO.builder()
                .auctionId(auctionId)
                .excludedBidderIds(excludedBidderIds)
                .build();

        Integer rowsChanged = auctionRegistrationsRepository.markEligibleLosersAsPending(
                auctionId,
                BigDecimal.valueOf(300),
                excludedBidderIds
        );
        log.info("Updated {} registration(s) to refundStatus=PENDING for auctionId={}", rowsChanged, auctionId);

        auction.setRefundStatus(RefundStatus.PENDING);
        auctionRepository.save(auction);
        log.debug("Updated auctionId={} refundStatus to PENDING in database", auctionId);

        log.info("Publishing 'auction-ended' event to Kafka for auctionId={}", auctionId);
        kafkaTemplate.send("auction-ended", String.valueOf(auctionId), auctionEndedRequestDTO)
                .thenAccept(result -> {
                    log.info("Successfully published 'auction-ended' event for auctionId={} to partition={}, offset={}",
                            auctionId,
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                })
                .exceptionally(ex -> {
                    log.error("Failed to publish 'auction-ended' event to Kafka for auctionId={}", auctionId, ex);
                    return null;
                });
    }
}
