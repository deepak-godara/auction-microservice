package auction.exception;

public class AuctionDoesNotExistsExeption extends RuntimeException {
    public AuctionDoesNotExistsExeption(String message) {
        super(message);
    }
}
