package auction.exception;

import java.math.BigDecimal;

public class BidTooLowException extends RuntimeException {
    public BidTooLowException(Long auctionId, BigDecimal basePrice) {
        super("Bid must be greater than the base price of " + basePrice + " for auction " + auctionId);
    }
}
