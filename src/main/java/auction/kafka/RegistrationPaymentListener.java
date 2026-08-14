package auction.kafka;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.kafka.annotation.BackOff;

import auction.dto.FeePaidRequestDTO;
import auction.repository.AuctionRegistrationsRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor

public class RegistrationPaymentListener {
    private final Logger log = LoggerFactory.getLogger(RegistrationPaymentListener.class);
    private final AuctionRegistrationsRepository auctionRegistrationsRepository;
    @RetryableTopic(
        attempts = "3",
        backOff  = @BackOff(delay = 1000, multiplier = 2.0), // Retries at 1s, 2s, 4s
        topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
        dltTopicSuffix = ".DLT"
    )
 @KafkaListener(
        topics = "registration-fee-paid",
        groupId = "auction-service-group",
        concurrency = "6", // 6 parallel threads matching 6 topic partitions
        properties = {
            "max.poll.records=50",         // Process max 50 records per poll
            "max.poll.interval.ms=300000"  // Max 5 mins execution limit per batch
        }
    )
    public void getRegsiterationpaymentUpdated(FeePaidRequestDTO event,
            Acknowledgment ack,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        log.info("Consuming event from Partition {}, Offset {} for auctionId={}, bidderId={}",
                partition, offset, event.getAuctionId(), event.getBidderId());
        int rowsUpdated = auctionRegistrationsRepository.updateFeePaid(event.getAuctionId(), event.getBidderId(), event.getPaymentId());
        
        if (rowsUpdated > 0) {
            log.info("Successfully updated feePaid=true for auctionId={}, bidderId={}", event.getAuctionId(), event.getBidderId());
        } else {
            log.info("No registration updated (already paid or registration not found) for auctionId={}, bidderId={}", event.getAuctionId(), event.getBidderId());
        }

        // Commit offset to Kafka AFTER database update completes
        ack.acknowledge();
    }
}
