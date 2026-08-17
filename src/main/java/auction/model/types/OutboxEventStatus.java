package auction.model.types;

public enum OutboxEventStatus {
    PENDING,
    PROCESSING,
    DELIVERED,
    FAILED
}
