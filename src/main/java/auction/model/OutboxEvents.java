package auction.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import auction.model.types.OutboxEventStatus;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "auction_outbox_events")
public class OutboxEvents {

    @EmbeddedId
    private OutboxEventId id;                // composite PK: (auctionId, eventType)

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;
    
     @Column(name = "payload_type", nullable = false)
    private String payloadType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private OutboxEventStatus status = OutboxEventStatus.PENDING;

    @Column(nullable = false)
    @Builder.Default
    private int retryCount = 0;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime lastRetryAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
