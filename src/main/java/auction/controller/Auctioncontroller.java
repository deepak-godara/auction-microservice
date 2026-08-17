package auction.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import auction.dto.BidderResponseDTO;
import auction.dto.CreateAuctionRequestDTO;
import auction.dto.CreateAuctionResponseDTO;
import auction.dto.GetAuctionByIdResponseDTO;
import auction.dto.GetAuctionsResponseDTO;
import auction.dto.GetRegistratonResponseDTO;
import auction.dto.RegistrationStatusResponseDTO;
import auction.dto.WinnerStatusResponseDTO;
import auction.service.AuctionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("auctions")
public class Auctioncontroller {

    private final AuctionService auctionService;

    @PostMapping
    @PreAuthorize("hasRole('SELLER')")
    public ResponseEntity<CreateAuctionResponseDTO> createAuction(
            Authentication authentication,
            @Valid @RequestBody CreateAuctionRequestDTO createAuctionRequest) {
        String userId = (String) authentication.getPrincipal();
        return ResponseEntity.status(201).body(auctionService.createAuction(userId, createAuctionRequest));
    }

    @PostMapping("/{auctionId}/register")
    @PreAuthorize("hasRole('BIDDER')")
    public ResponseEntity<Void> registerForAuction(
            @PathVariable Long auctionId,
            Authentication authentication) {
        String bidderId = (String) authentication.getPrincipal();
        auctionService.registerForAuction(auctionId, bidderId);
        return ResponseEntity.status(201).build();
    }

    @GetMapping
    public ResponseEntity<Page<GetAuctionsResponseDTO>> getAuctions(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "10") int size) {
        PageRequest pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(auctionService.getAuctions(pageable));
    }

    @GetMapping("/{id}/bidders")
    @PreAuthorize("hasRole('SELLER')")
    public ResponseEntity<Page<BidderResponseDTO>> getBidders(Authentication authentication,@PathVariable Long id, @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "10") int size) {

        String userId =(String) authentication.getPrincipal();
        PageRequest pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(auctionService.getBidders(userId,id,pageable));
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<GetAuctionByIdResponseDTO> getAuctionById(@PathVariable Long id) {
        return ResponseEntity.ok(auctionService.getAuctionById(id));
    }

    @GetMapping("/my/listings")
    @PreAuthorize("hasRole('SELLER')")
    public ResponseEntity<Page<GetAuctionsResponseDTO>> getMyListings(
            Authentication authentication,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "10") int size) {
        String userId = (String) authentication.getPrincipal();
        PageRequest pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(auctionService.getMyListings(userId, pageable));
    }

    @GetMapping("/my/registrations")
    public ResponseEntity<Page<GetRegistratonResponseDTO>> getRegistrations(Authentication authentication,@RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "10") int size){
        String userId =(String) authentication.getPrincipal();
        PageRequest pageable = PageRequest.of(page, size);

        return ResponseEntity.ok(auctionService.getRegistrations(userId,pageable));
    }

    @GetMapping("/{auctionId}/registrations/status")
    public ResponseEntity<RegistrationStatusResponseDTO> getRegistrationStatus(
            Authentication authentication,
            @PathVariable Long auctionId) {
        String bidderId = (String) authentication.getPrincipal();
        return ResponseEntity.ok(auctionService.getRegistrationStatus(auctionId, bidderId));
    }

    @GetMapping("/{auctionId}/winner/status")
    public ResponseEntity<WinnerStatusResponseDTO> getWinnerStatus(
            Authentication authentication,
            @PathVariable Long auctionId) {
        String userId = (String) authentication.getPrincipal();
        return ResponseEntity.ok(auctionService.getWinnerStatus(auctionId, userId));
    }
}
