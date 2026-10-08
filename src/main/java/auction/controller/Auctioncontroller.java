package auction.controller;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import auction.dto.BidResponseDTO;
import auction.dto.BidderResponseDTO;
import auction.dto.CreateAuctionRequestDTO;
import auction.dto.CreateAuctionResponseDTO;
import auction.dto.GetAuctionByIdResponseDTO;
import auction.dto.GetAuctionsResponseDTO;
import auction.dto.GetRegistratonResponseDTO;
import auction.dto.LeaderboardEntryDTO;
import auction.dto.RegistrationStatusResponseDTO;
import auction.dto.UpdateAuctionRequestDTO;
import auction.dto.WinnerStatusResponseDTO;
import auction.model.types.Status;
import auction.service.AuctionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("auctions")
public class AuctionController {

    private final AuctionService auctionService;

    @PostMapping
    @PreAuthorize("hasRole('SELLER')")
    public ResponseEntity<CreateAuctionResponseDTO> createAuction(
            Authentication authentication,
            @Valid @RequestBody CreateAuctionRequestDTO createAuctionRequest) {
        String userId = (String) authentication.getPrincipal();
        return ResponseEntity.status(201).body(auctionService.createAuction(userId, createAuctionRequest));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SELLER')")
    public ResponseEntity<CreateAuctionResponseDTO> updateAuction(
            Authentication authentication,
            @PathVariable Long id,
            @Valid @RequestBody UpdateAuctionRequestDTO request) {
        String userId = (String) authentication.getPrincipal();
        return ResponseEntity.ok(auctionService.updateAuction(userId, id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SELLER')")
    public ResponseEntity<Void> deleteAuction(
            Authentication authentication,
            @PathVariable Long id) {
        String userId = (String) authentication.getPrincipal();
        auctionService.deleteAuction(userId, id);
        return ResponseEntity.noContent().build();
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
    public ResponseEntity<Page<BidderResponseDTO>> getBidders(
            Authentication authentication,
            @PathVariable Long id,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "10") int size) {
        String userId = (String) authentication.getPrincipal();
        PageRequest pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(auctionService.getBidders(userId, id, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<GetAuctionByIdResponseDTO> getAuctionById(@PathVariable Long id) {
        return ResponseEntity.ok(auctionService.getAuctionById(id));
    }

    @GetMapping("/my/listings")
    @PreAuthorize("hasRole('SELLER')")
    public ResponseEntity<Page<GetAuctionsResponseDTO>> getMyListings(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        String userId = (String) authentication.getPrincipal();
        PageRequest pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(auctionService.getMyListings(userId, pageable));
    }

    @GetMapping("/my/registrations")
    public ResponseEntity<Page<GetRegistratonResponseDTO>> getRegistrations(
            Authentication authentication,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "10") int size) {
        String userId = (String) authentication.getPrincipal();
        PageRequest pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(auctionService.getRegistrations(userId, pageable));
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

    @GetMapping("/{auctionId}/leaderboard")
    @PreAuthorize("hasRole('BIDDER')")
    public ResponseEntity<List<LeaderboardEntryDTO>> getLeaderboard(
            Authentication authentication,
            @PathVariable Long auctionId) {
        String bidderId = (String) authentication.getPrincipal();
        return ResponseEntity.ok(auctionService.getLeaderboard(bidderId, auctionId));
    }

    @GetMapping("/{auctionId}/my-bids")
    @PreAuthorize("hasRole('BIDDER')")
    public ResponseEntity<Page<BidResponseDTO>> getMyBids(
            Authentication authentication,
            @PathVariable Long auctionId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "10") int size) {
        String bidderId = (String) authentication.getPrincipal();
        PageRequest pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(auctionService.getMyBids(bidderId, auctionId, pageable));
    }

    // ─── Admin endpoints ──────────────────────────────────────────────────────

    @GetMapping("/admin/all")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<GetAuctionByIdResponseDTO>> adminGetAllAuctions(
            @RequestParam(required = false) Status status,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "10") int size) {
        PageRequest pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(auctionService.adminGetAllAuctions(status, pageable));
    }

    @DeleteMapping("/admin/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> adminDeleteAuction(@PathVariable Long id) {
        auctionService.adminDeleteAuction(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/admin/{id}/bidders")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<BidderResponseDTO>> adminGetBidders(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "10") int size) {
        PageRequest pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(auctionService.adminGetBidders(id, pageable));
    }
}
