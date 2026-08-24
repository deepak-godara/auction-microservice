package auction.schedule;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// import com.fasterxml.jackson.core.type.TypeReference;
// import com.fasterxml.jackson.databind.ObjectMapper;

import auction.components.AuctionOutboxService;
import auction.dto.ActivateAuction;
import auction.dto.AuctionEndedRequestDTO;
import auction.model.OutboxEvents;
import auction.model.types.OutboxEventStatus;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class AuctionOutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(AuctionOutboxPoller.class);
    private static final int STUCK_THRESHOLD_MINUTES = 10;

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final AuctionOutboxService auctionOutboxService;
    private final ObjectMapper objectMapper;

    // ✅ Define allowed types
    private static final Set<String> ALLOWED_PAYLOAD_TYPES = Set.of(
        ActivateAuction.class.getName(),
        AuctionEndedRequestDTO.class.getName()       // add future types here
    );

// ✅ Guard in the poller before Class.forName()


    @Scheduled(fixedDelay = 60000) // every 60 seconds
    public void pollAndPublish() {

        // Step 0 — recover rows stuck in PROCESSING (JVM crash after Kafka ACK, before TX2)
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(STUCK_THRESHOLD_MINUTES);
        int recovered = auctionOutboxService.resetStuckProcessingEvents(cutoff);
        if (recovered > 0) {
            log.warn("Recovered {} stuck PROCESSING outbox event(s) back to PENDING", recovered);
        }

        // Step 1 — TX1: claim pending rows, mark PROCESSING, release connection
        List<OutboxEvents> events = auctionOutboxService.claimPendingEventsBatch();

        if (events.isEmpty()) {
            return;
        }

        log.info("Publishing {} outbox event(s) to Kafka topic 'auction-ended'", events.size());

        // Step 2 — set fallback status before firing async sends.
        // If allOf() times out or the JVM is interrupted, these values are what TX2 persists.
        // thenAccept overrides to DELIVERED on success; exceptionally overrides to FAILED if retryCount >= 5.
        for (OutboxEvents event : events) {
            event.setRetryCount(event.getRetryCount() + 1);
            event.setStatus(event.getRetryCount() >= 5
                    ? OutboxEventStatus.FAILED
                    : OutboxEventStatus.PENDING);
        }

        // Step 2b — async Kafka publish — NO DB connection held during network I/O
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (OutboxEvents event : events) {
            try {

                if (!ALLOWED_PAYLOAD_TYPES.contains(event.getPayloadType())) {
    log.error(
        "Rejected unknown payloadType — auctionId={} topic={} payloadType='{}' allowedTypes={}",
        event.getId().getAuctionId(),
        event.getId().getTopic(),
        event.getPayloadType(),
        ALLOWED_PAYLOAD_TYPES
    );
    event.setStatus(OutboxEventStatus.FAILED);
    continue;
}

// ✅ Past the guard — safe to resolve
log.debug("Resolving payloadType='{}' for auctionId={} topic={}",
        event.getPayloadType(),
        event.getId().getAuctionId(),
        event.getId().getTopic());
                 Object payload = objectMapper.readValue(event.getPayload(), Object.class);

                CompletableFuture<Void> future = kafkaTemplate
                        .send(event.getId().getTopic(), String.valueOf(event.getId().getAuctionId()), payload)
                        .thenAccept(result -> {
                            // ACK received — override fallback with final success status
                            event.setStatus(OutboxEventStatus.DELIVERED);
                            log.info("Published auction-ended for auctionId={} partition={} offset={}",
                                    event.getId().getAuctionId(),
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        })
                        .exceptionally(ex -> {
                            // Kafka send failed — fallback already has the correct status/retryCount
                            log.error("Failed to publish auction-ended for auctionId={}", event.getId().getAuctionId(), ex);
                            return null;
                        });

                futures.add(future);

            } catch (Exception ex) {
                // payload deserialization failure — should never happen for List<String>
                log.error("Failed to deserialize payload for auctionId={}", event.getId().getAuctionId(), ex);
                event.setStatus(OutboxEventStatus.FAILED);
            }
        }

        // Wait for all Kafka ACKs (max 30 seconds)
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(30, TimeUnit.SECONDS);
        } catch (Exception ex) {
            // allOf timed out or interrupted — in-flight events already carry PENDING/FAILED fallback.
            // TX2 below will persist that fallback; the next poll cycle will retry PENDING rows.
            log.warn("Kafka publish timed out or interrupted — saving fallback statuses: {}", ex.getMessage());
        }

        // Step 3 — TX2: persist final statuses (DELIVERED / PENDING / FAILED)
        auctionOutboxService.finalizeEventsBatch(events);
    }
}
