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
    private final AuctionRegistrationsRepository auctionRegistrationsRepository;
    private final AuctionTopBiddersRepository auctionTopBiddersRepository;
    private final AuctionRepository auctionRepository;
    private static final Logger log = LoggerFactory.getLogger(AuctionEndScheduler.class);

    private final KafkaTemplate<String,Object> kafkaTemplate;
    @Transactional
    public void processSingleAuctionEnded(Auction auction){


        List<AuctionTopBidders> bidders =auctionTopBiddersRepository.findAllByAuctionId(auction.getId());

        if(bidders.isEmpty())
        {
            return ;
        }
        AuctionEndedRequestDTO auctionEndedRequestDTO = AuctionEndedRequestDTO.builder().auctionId(auction.getId()).excludedBidderIds(bidders.stream().map(bid ->{return bid.getBidderId();}).toList()).build();
        Integer rowsChanged =auctionRegistrationsRepository.markEligibleLosersAsPending(auction.getId(),BigDecimal.valueOf(300),bidders.stream().map(bid ->{return bid.getBidderId();}).toList());
        auction.setRefundStatus(RefundStatus.PENDING);
        auctionRepository.save(auction);
        kafkaTemplate.send("auction-ended",String.valueOf(auction.getId()),auctionEndedRequestDTO)
        .thenAccept(result -> log.info("Completed processing batch of {} expired auction(s)", auction.getId()));

    }
    
}
