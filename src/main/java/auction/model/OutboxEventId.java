package auction.model;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Embeddable
@NoArgsConstructor
@AllArgsConstructor
@Data
public class OutboxEventId implements Serializable {

    @Column(name = "auction_id", nullable = false)
    private Long auctionId;

    @Column(name = "event_type", nullable = false)
    private String topic;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OutboxEventId other)) return false;
        return Objects.equals(auctionId, other.auctionId) &&
               Objects.equals(topic, other.topic);
    }

    @Override
    public int hashCode() {
        return Objects.hash(auctionId, topic);
    }
}
