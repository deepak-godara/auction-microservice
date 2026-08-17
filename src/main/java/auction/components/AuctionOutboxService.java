package auction.components;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import auction.model.OutboxEvents;
import auction.model.types.OutboxEventStatus;
import auction.repository.OutboxEventsRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuctionOutboxService {

    private static final int BATCH_SIZE = 50;

    private final OutboxEventsRepository outboxEventsRepository;

    // TX1 — claim pending rows and mark PROCESSING
    // FOR UPDATE SKIP LOCKED ensures two pods never claim the same row
    @Transactional
    public List<OutboxEvents> claimPendingEventsBatch() {
        List<OutboxEvents> events = outboxEventsRepository
                .findPendingEventsForUpdateSkipLocked(BATCH_SIZE);

        for (OutboxEvents event : events) {
            event.setStatus(OutboxEventStatus.PROCESSING);
            event.setLastRetryAt(LocalDateTime.now());
        }

        return outboxEventsRepository.saveAll(events);
    }

    // TX2 — persist final status after Kafka ACK or failure
    @Transactional
    public void finalizeEventsBatch(List<OutboxEvents> events) {
        outboxEventsRepository.saveAll(events);
    }

    // Recovery — reset rows stuck in PROCESSING (pod crashed before TX2)
    @Transactional
    public int resetStuckProcessingEvents(LocalDateTime cutoff) {
        return outboxEventsRepository.resetStuckProcessingEvents(cutoff);
    }
}
