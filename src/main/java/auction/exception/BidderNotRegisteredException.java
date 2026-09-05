package auction.exception;

public class BidderNotRegisteredException extends RuntimeException {
    public BidderNotRegisteredException(Long auctionId) {
        super("Bidder is not registered for auction " + auctionId);
    }
}
