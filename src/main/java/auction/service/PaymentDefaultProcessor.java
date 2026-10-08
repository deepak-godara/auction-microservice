package auction.service;

import java.time.LocalDateTime;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import auction.client.PaymentServiceClient;
import auction.client.dto.PaymentStatusResponseDTO;
import auction.model.Auction;
import auction.model.AuctionTopBidders;
import auction.model.types.BidAmountPaymentStatus;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaymentDefaultProcessor {

    private static final Logger log = LoggerFactory.getLogger(PaymentDefaultProcessor.class);

    private final PaymentServiceClient paymentServiceClient;
    private final AuctionDefaultService auctionDefaultService;
    private final RedissonClient        redissonClient;

    @Value("${payment.service.internal-secret}")
    private String internalSecret;

    public void process(Auction auction) {
        RLock lock = redissonClient.getLock("auction-deadline-check:" + auction.getId());

        if (!lock.tryLock()) {
            log.debug("Auction already being processed by another pod — skipping. auctionId={}", auction.getId());
            return;
        }

        try {
            processInternal(auction);
        } finally {
            lock.unlock();
        }
    }

    private void processInternal(Auction auction) {
        AuctionTopBidders currentWinner = auction.getTopBidders().stream()
                .filter(b -> b.getStatus() == BidAmountPaymentStatus.PENDING)
                .findFirst()
                .orElse(null);

        if (currentWinner == null) {
            boolean paid = auction.getTopBidders().stream()
                    .anyMatch(b -> b.getStatus() == BidAmountPaymentStatus.COMPLETED);

            if (paid) {
                log.info("Payment landed but auction table not updated yet — Kafka lagging. auctionId={}",
                        auction.getId());
            } else {
                log.warn("No PENDING or COMPLETED bidder found — cancelling. auctionId={}", auction.getId());
                auctionDefaultService.cancelAuction(auction.getId());
            }
            return;
        }

        log.info("Checking payment status — auctionId={} bidderId={} rank={}",
                auction.getId(), currentWinner.getBidderId(), currentWinner.getRank());

        PaymentStatusResponseDTO response = paymentServiceClient.getPaymentStatus(
                auction.getId(),
                currentWinner.getBidderId(),
                "AUCTION_PAYMENT",
                internalSecret);

        log.info("Payment status response — auctionId={} status={} expiresAt={}",
                auction.getId(), response.getStatus(), response.getOrderExpiresAt());

        switch (response.getStatus()) {

            case "SUCCESS" -> log.info("Payment SUCCESS — Kafka event pending. auctionId={}", auction.getId());

            case "CREATED" -> {
                if (response.getOrderExpiresAt() != null
                        && response.getOrderExpiresAt().isAfter(LocalDateTime.now())) {
                    log.info("Winner mid-payment — order still valid. auctionId={} expiresAt={}",
                            auction.getId(), response.getOrderExpiresAt());
                } else {
                    log.info("Order expired — treating as default. auctionId={}", auction.getId());
                    auctionDefaultService.handleDefault(auction, currentWinner);
                }
            }

            case "NOT_FOUND", "EXPIRED" -> {
                log.info("No payment attempted or order expired — genuine default. auctionId={}", auction.getId());
                auctionDefaultService.handleDefault(auction, currentWinner);
            }

            default -> log.warn("Unknown payment status={} — skipping. auctionId={}",
                    response.getStatus(), auction.getId());
        }
    }
}
