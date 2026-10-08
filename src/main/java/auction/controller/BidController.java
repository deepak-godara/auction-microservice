package auction.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RestController;

import auction.dto.PlaceBidRequest;
import auction.service.BidService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;


@RestController
@RequiredArgsConstructor
@RequestMapping("bid")
public class BidController {
    
    private final BidService bidService;

    @PostMapping("/{auctionId}")
    public ResponseEntity<String> writeBidToStream(
            Authentication authentication,
            @PathVariable Long auctionId,
            @Valid @RequestBody PlaceBidRequest bid) {
        String bidderId = (String) authentication.getPrincipal();
        String recordId = bidService.writeBidToStream(auctionId, bidderId, bid);
        return ResponseEntity.accepted().body(recordId);
    }
    
}
