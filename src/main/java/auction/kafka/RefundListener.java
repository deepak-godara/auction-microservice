package auction.kafka;

import auction.repository.AuctionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import auction.dto.TransactionDoneDTO;
import auction.model.types.RefundStatus;
import auction.model.types.Status;
import auction.repository.AuctionRegistrationsRepository;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RefundListener {
    
    private final AuctionRepository auctionRepository;

    private static final Logger log = LoggerFactory.getLogger(RefundListener.class);

    private final AuctionRegistrationsRepository auctionRegistrationsRepository;

    @RetryableTopic(
        attempts = "3",
        backOff  = @BackOff(delay = 1000, multiplier = 2.0),
        topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
        dltTopicSuffix = ".DLT"
    )
    @KafkaListener(
        topics = "refund-completed",
        groupId = "auction-service-group",
        concurrency = "6",
        properties = {
            "max.poll.records=50",
            "max.poll.interval.ms=300000"
        }
    )
    public void registerRefundPayment(TransactionDoneDTO event,
        Acknowledgment ack,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset
    ){
        log.info("Consuming event from partition={} offset={} auctionId={} bidderId={} for refund-completed",
                partition, offset, event.getAuctionId(), event.getUserId());

        Integer userUpdated = auctionRegistrationsRepository.markRefundDoneforUser(event.getAuctionId(),event.getUserId(),event.getTransactionId(),RefundStatus.COMPLETED);

        if(userUpdated == 0){

            ack.acknowledge();
            return;
        }
        if (auctionRegistrationsRepository.countByAuction_IdAndFeePaidTrueAndRefundStatus(event.getAuctionId(), RefundStatus.PENDING) == 0) {
            // All refunds done — transition auction to PAYMENT_PENDING
            // updateAuctionStatus returning 0 means another pod already did it — not an error, still ack
            auctionRepository.updateAuctionStatus(event.getAuctionId(), Status.PAYMENT_PENDING);
        }

        ack.acknowledge();
        

    }
}
