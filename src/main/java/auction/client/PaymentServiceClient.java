package auction.client;

import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import auction.client.dto.PaymentStatusResponseDTO;

@HttpExchange
public interface PaymentServiceClient {

    @GetExchange("/payment/internal/status")
    PaymentStatusResponseDTO getPaymentStatus(
            @RequestParam Long auctionId,
            @RequestParam String bidderId,
            @RequestParam String type,
            @RequestHeader("X-Internal-Secret") String internalSecret);
}
