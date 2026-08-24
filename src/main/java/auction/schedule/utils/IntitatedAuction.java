package auction.schedule.utils;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import auction.dto.ActivateAuction;
import auction.model.Auction;
import auction.model.OutboxEventId;
import auction.model.OutboxEvents;
import auction.model.types.Status;
import auction.repository.AuctionRegistrationsRepository;
import auction.repository.AuctionRepository;
import auction.repository.OutboxEventsRepository;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@RequiredArgsConstructor
@Component
public class IntitatedAuction {

    private static final Logger log = LoggerFactory.getLogger(IntitatedAuction.class);

    private final AuctionRepository              auctionRepository;
    private final OutboxEventsRepository         outboxEventsRepository;
    private final AuctionRegistrationsRepository auctionRegistrationsRepository;
    private final ObjectMapper                   objectMapper;

    @Transactional
    public void initateAuction(Auction auction) {
        log.info("initateAuction — auctionId={} title='{}'", auction.getId(), auction.getTitle());
        try {
            List<String> eligibleBidders = auctionRegistrationsRepository
                    .findAllByAuction_IdAndFeePaidTrue(auction.getId())
                    .stream()
                    .map(item -> item.getBidderId())
                    .toList();

            log.info("Eligible bidders — auctionId={} count={}", auction.getId(), eligibleBidders.size());

            ActivateAuction activateAuction = ActivateAuction.builder()
                    .Id(auction.getId())
                    .startTime(auction.getStartTime())
                    .endTime(auction.getEndTime())
                    .basePrice(auction.getReservePrice())
                    .minBidIncrement(auction.getMinBidIncrement())
                    .registeredUsers(eligibleBidders)
                    .build();

            OutboxEvents outboxEvents = OutboxEvents.builder()
                    .id(new OutboxEventId(auction.getId(), "activate-auction"))
                    .payload(objectMapper.writeValueAsString(activateAuction))
                    .payloadType(ActivateAuction.class.getName()) 
                    .build();

            outboxEventsRepository.save(outboxEvents);
            log.info("Outbox event saved — auctionId={} topic=activate-auction", auction.getId());

            auction.setStatus(Status.START_INITIATED);
            auctionRepository.save(auction);
            log.info("Auction status → START_INITIATED — auctionId={}", auction.getId());

        } catch (Exception ex) {
            log.error("Failed to initiate auction — auctionId={}", auction.getId(), ex);
            throw new RuntimeException("Failed to initiate auction", ex);
        }
    }
}
