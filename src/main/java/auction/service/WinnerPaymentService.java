package auction.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import auction.dto.RefundRequestDTO;
import auction.dto.PaymentEventDTO;
import auction.model.Auction;
import auction.model.AuctionTopBidders;
import auction.model.types.BidAmountPaymentStatus;
import auction.model.types.Status;
import auction.repository.AuctionRegistrationsRepository;
import auction.repository.AuctionRepository;
import auction.repository.AuctionTopBiddersRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class WinnerPaymentService {

    private static final Logger log = LoggerFactory.getLogger(WinnerPaymentService.class);

    private final AuctionTopBiddersRepository    auctionTopBiddersRepository;
    private final AuctionRepository              auctionRepository;
    private final AuctionRegistrationsRepository auctionRegistrationsRepository;
    private final OutboxEventSaver               outboxEventSaver;

    @Transactional
    public void processWinnerPayment(PaymentEventDTO event) {

        AuctionTopBidders bidder = auctionTopBiddersRepository
                .findByAuction_IdAndBidderId(event.getAuctionId(), event.getUserId())
                .orElse(null);

        if (bidder == null) {
            log.error("Bidder not found in auction_top_bidders — auctionId={} bidderId={}",
                    event.getAuctionId(), event.getUserId());
            throw new IllegalStateException("Bidder not found — cannot process winner payment. auctionId="
                    + event.getAuctionId() + " bidderId=" + event.getUserId());
        }

        Auction auction = auctionRepository.findById(event.getAuctionId()).orElse(null);
        if (auction == null) {
            log.error("Auction not found — auctionId={}", event.getAuctionId());
            throw new IllegalStateException("Auction not found — cannot process winner payment. auctionId="
                    + event.getAuctionId());
        }

        // Idempotency guard — skip if already processed
        if (auction.getStatus() != Status.PAYMENT_PENDING) {
            log.info("Idempotency guard — auction not PAYMENT_PENDING, skipping. auctionId={}",
                    event.getAuctionId());
            return;
        }

        // Mark winner
        bidder.setPaymentId(event.getTransactionId());
        bidder.setStatus(BidAmountPaymentStatus.COMPLETED);
        auction.setWinnerId(event.getUserId());
        auction.setAuctionedPrice(bidder.getBid());
        auction.setAuctionPaymentDeadline(null);
        auctionTopBiddersRepository.save(bidder);

        // Find remaining top bidders who need refund (NOT_REQUIRED status)
        List<AuctionTopBidders> topBidders = auctionTopBiddersRepository
                .findAllByAuction_IdAndStatus(event.getAuctionId(), BidAmountPaymentStatus.NOT_REQUIRED);

        if (topBidders.isEmpty()) {
            log.info("No top bidders to refund — moving to PAYOUT_PENDING. auctionId={}", event.getAuctionId());
            auction.setStatus(Status.PAYOUT_PENDING);
            saveSellerPayoutOutbox(auction);
        } else {
            // Mark their registrations for refund
            List<String> topBidderIds = topBidders.stream()
                    .map(AuctionTopBidders::getBidderId)
                    .collect(Collectors.toList());

            BigDecimal refundAmount = auction.getRegistrationFee().subtract(BigDecimal.valueOf(300));
            auctionRegistrationsRepository.markEligibleWinnersAsPending(
                    event.getAuctionId(), refundAmount, topBidderIds);

            // Build excluded list: defaulted bidders + winner (no refund for them)
            List<String> excludedBidders = auctionTopBiddersRepository
                    .findAllByAuction_IdAndStatus(event.getAuctionId(), BidAmountPaymentStatus.DEFAULTED)
                    .stream()
                    .map(AuctionTopBidders::getBidderId)
                    .collect(Collectors.toList());
            excludedBidders.add(event.getUserId());

            outboxEventSaver.save(event.getAuctionId(), "winner-refund-initiated",
                    RefundRequestDTO.builder()
                            .auctionId(auction.getId())
                            .refundAmount(refundAmount)
                            .excludedBidderIds(excludedBidders)
                            .build());

            auction.setStatus(Status.WINNERS_REFUND_INITIATED);
        }

        auctionRepository.save(auction);
        log.info("Winner payment processed — auctionId={} winnerId={} status={}",
                event.getAuctionId(), event.getUserId(), auction.getStatus());
    }
    private void saveSellerPayoutOutbox(Auction auction) {
        outboxEventSaver.save(auction.getId(), "seller-payout-pending",
                PaymentEventDTO.builder()
                        .auctionId(auction.getId())
                        .userId(auction.getSellerId())
                        .build());
        log.info("Outbox 'seller-payout-pending' saved — auctionId={} sellerId={}",
                auction.getId(), auction.getSellerId());
    }
}
