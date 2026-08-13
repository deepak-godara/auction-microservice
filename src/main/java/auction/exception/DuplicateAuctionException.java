package auction.exception;

public class DuplicateAuctionException extends RuntimeException {
    public DuplicateAuctionException(String message) {
        super(message);
    }
}
