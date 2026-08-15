package auction.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import auction.dto.BidderResponseDTO;
import auction.dto.CreateAuctionRequestDTO;
import auction.dto.CreateAuctionResponseDTO;
import auction.dto.GetAuctionByIdResponseDTO;
import auction.dto.GetAuctionsResponseDTO;
import auction.dto.GetRegistratonResponseDTO;
import auction.dto.RegistrationResponseDTO;
import auction.dto.RegistrationStatusResponseDTO;
import auction.exception.AuctionDoesNotExistsExeption;
import auction.exception.DuplicateAuctionException;
import auction.exception.DuplicateRegistrationForAuctionExecption;
import auction.model.Auction;
import auction.model.AuctionRegistrations;
import auction.model.types.Status;
import auction.repository.AuctionRegistrationsRepository;
import auction.repository.AuctionRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuctionService {
    
    private final AuctionRepository auctionRepository;
    private final AuctionRegistrationsRepository auctionRegistrationsRepository;
    public CreateAuctionResponseDTO createAuction(String userId, CreateAuctionRequestDTO createAuctionRequest){

        Auction auctions = auctionRepository.findBySellerIdAndTitleAndStatusIn(userId,createAuctionRequest.getTitle(), List.of(Status.CREATED,Status.STARTED)).orElse(null);

        if(auctions !=  null){
            throw new DuplicateAuctionException("You already have the similar auction with same title");
        }
        
        Auction auction= new Auction();

        buildAuction(userId, auction, createAuctionRequest);

        auction =auctionRepository.save(auction);



        return  new CreateAuctionResponseDTO(auction.getId(),"The auction created successfully");
    }

    public RegistrationResponseDTO registerForAuction(Long auctionId,String  userId){
        AuctionRegistrations auctionrRegistration = auctionRegistrationsRepository.findByAuction_IdAndBidderId(auctionId,userId).orElse(null);
        
        if( auctionrRegistration !=null)
        {
            throw new DuplicateRegistrationForAuctionExecption("You have already registered for this auction");
        }

        Auction auction =   auctionRepository.findById(auctionId).orElse(null);

        if(auction ==null)
        {
            throw new AuctionDoesNotExistsExeption("The Auction does not exists");
        }
        if( auction.getStatus() != Status.CREATED){
            throw new IllegalArgumentException("Cannot register for a closed auction");
        }
        AuctionRegistrations registration   =   new AuctionRegistrations();
        buildAuctionRegistration(auction, userId, registration);
        registration =auctionRegistrationsRepository.save(registration);
        return new RegistrationResponseDTO(auctionId,userId,"Registered successfully for the auction");
    }

    public Page<GetAuctionsResponseDTO> getAuctions(Pageable pageable){

        return auctionRepository.findByStatus(Status.CREATED,pageable)
        .map(auction -> mapToGetAuctionsResponseDTO(auction)
        );
    }

    public Page<BidderResponseDTO> getBidders(String userId,Long auctionId,Pageable pageable){

        // Auction auction =auctionRepository.findById(auctionId).orElseThrow(()-> new NotFoundException("Auction not found"));
         Auction auction = auctionRepository.findById(auctionId).orElseThrow(() -> new AuctionDoesNotExistsExeption("Auction not found"));

         if(!auction.getSellerId().equals(userId))
         {
            throw new AccessDeniedException("The auction does not belong to you");
         }
         
         return auctionRegistrationsRepository.findByAuction_Id(auctionId,pageable)
                .map(registeration -> mapBidderResponseDTO(registeration) );
        
    }

    public GetAuctionByIdResponseDTO getAuctionById(Long auctionId) {
        Auction auction = auctionRepository.findById(auctionId)
                .orElseThrow(() -> new AuctionDoesNotExistsExeption("Auction not found"));
        return mapToGetAuctionByIdResponseDTO(auction);
    }

    public Page<GetAuctionsResponseDTO> getMyListings(String sellerId, Pageable pageable) {
        return auctionRepository.findBySellerId(sellerId, pageable)
                .map(this::mapToGetAuctionsResponseDTO);
    }

    public Page<GetRegistratonResponseDTO> getRegistrations(String userId, Pageable page){

        return auctionRegistrationsRepository.findByBidderId(userId,page)
            .map(registration -> mapGetRegistratonResponseDTO(registration));
    }

    private GetRegistratonResponseDTO mapGetRegistratonResponseDTO(AuctionRegistrations registeration){
         return new GetRegistratonResponseDTO(registeration.getId(), registeration.getAuction().getId(), registeration.getFeePaid(), registeration.getFeePaymentId(), registeration.getRegisteredAt());
    }
    private GetAuctionByIdResponseDTO mapToGetAuctionByIdResponseDTO(Auction auction) {
        return new GetAuctionByIdResponseDTO(
                auction.getId(), auction.getSellerId(), auction.getTitle(), auction.getDescription(),
                auction.getReservePrice(), auction.getMinBidIncrement(), auction.getRegistrationFee(),
                auction.getStartTime(), auction.getEndTime(), auction.getSnipeWindowMins(),
                auction.getMaxExtensions(), auction.getExtensionCount(), auction.getStatus(),
                auction.getWinnerId(), auction.getAuctionedPrice(), auction.getCreatedAt(), auction.getUpdatedAt());
    }

    private BidderResponseDTO mapBidderResponseDTO(AuctionRegistrations registeration){
        return new BidderResponseDTO(registeration.getId(), registeration.getBidderId(), registeration.getFeePaid(), registeration.getFeePaymentId(), registeration.getRegisteredAt());
    }
    private GetAuctionsResponseDTO mapToGetAuctionsResponseDTO(Auction auction){

        return new GetAuctionsResponseDTO(auction.getId(), auction.getTitle(), auction.getDescription(), auction.getReservePrice(), auction.getRegistrationFee(), auction.getStartTime(), auction.getEndTime());
    }
    public RegistrationStatusResponseDTO getRegistrationStatus(Long auctionId, String bidderId) {
        AuctionRegistrations registration = auctionRegistrationsRepository
                .findByAuction_IdAndBidderId(auctionId, bidderId).orElse(null);

        if (registration == null) {
            return new RegistrationStatusResponseDTO(null, auctionId, bidderId, false, false, null, null);
        }

        return new RegistrationStatusResponseDTO(
                registration.getId(),
                registration.getAuction().getId(),
                registration.getBidderId(),
                true,
                registration.getFeePaid(),
                registration.getFeePaymentId(),
                registration.getRegisteredAt());
    }

    public void buildAuction(String user,Auction auction,CreateAuctionRequestDTO createAuctionRequest){

            auction.setTitle(createAuctionRequest.getTitle());
            auction.setDescription(createAuctionRequest.getDescription());
            auction.setReservePrice(createAuctionRequest.getReservePrice());
            auction.setMinBidIncrement(createAuctionRequest.getMinBidIncrement());
            auction.setRegistrationFee(createAuctionRequest.getRegistrationFee());
            auction.setStartTime(createAuctionRequest.getStartTime());
            auction.setEndTime(createAuctionRequest.getStartTime().plusMinutes(createAuctionRequest.getDurationMins()));
            auction.setSnipeWindowMins(createAuctionRequest.getSnipeWindowMins());
            auction.setMaxExtensions(createAuctionRequest.getMaxExtensions());
            auction.setSellerId(user);
            auction.setStatus(Status.CREATED);
            auction.setCreatedAt(LocalDateTime.now());
            auction.setExtensionCount(0);

    }

    public  void buildAuctionRegistration(Auction auction, String userId, AuctionRegistrations auctionRegistration){
        auctionRegistration.setAuction(auction);
        auctionRegistration.setBidderId(userId);
        auctionRegistration.setFeePaid(false);
        auctionRegistration.setRegisteredAt(LocalDateTime.now());

    }
}   


