package com.urbantransport.rental.service.impl;

import com.urbantransport.rental.client.CustomerClient;
import com.urbantransport.rental.domain.RentalChannel;
import com.urbantransport.rental.domain.RentalEntity;
import com.urbantransport.rental.domain.RentalSettingsEntity;
import com.urbantransport.rental.domain.RentalStatus;
import com.urbantransport.rental.dto.AssignRentalRequest;
import com.urbantransport.rental.dto.CounterRentalRequest;
import com.urbantransport.rental.dto.CreateRentalRequest;
import com.urbantransport.rental.dto.PageResponse;
import com.urbantransport.rental.dto.PayRentalRequest;
import com.urbantransport.rental.dto.QuoteRentalRequest;
import com.urbantransport.rental.dto.RejectRentalRequest;
import com.urbantransport.rental.dto.RentalEstimateResponse;
import com.urbantransport.rental.dto.RentalResponse;
import com.urbantransport.rental.dto.RescheduleRentalRequest;
import com.urbantransport.rental.event.EventTypes;
import com.urbantransport.rental.event.PaymentRequestedPayload;
import com.urbantransport.rental.event.RentalAssignedPayload;
import com.urbantransport.rental.event.RentalEventPayload;
import com.urbantransport.rental.exception.ApiException;
import com.urbantransport.rental.exception.ErrorCodes;
import com.urbantransport.rental.repository.RentalRepository;
import com.urbantransport.rental.repository.RentalSettingsRepository;
import com.urbantransport.rental.repository.RentalSpecifications;
import com.urbantransport.rental.service.OutboxService;
import com.urbantransport.rental.service.RentalService;
import com.urbantransport.rental.util.NitUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RentalServiceImpl implements RentalService {

    private static final int ONE_WAY_LEGS = 1;
    private static final int ROUND_TRIP_LEGS = 2;
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final int MAX_PAGE_SIZE = 100;

    private final RentalRepository rentalRepository;
    private final RentalSettingsRepository rentalSettingsRepository;
    private final OutboxService outboxService;
    private final CustomerClient customerClient;
    private final TransactionTemplate transactionTemplate;

    @Value("${app.zone:America/Guatemala}")
    private ZoneId zone;

    @Override
    @Transactional(readOnly = true)
    public RentalEstimateResponse estimate(CreateRentalRequest request) {
        validateDates(request.departureDate(), request.returnDate());
        RentalSettingsEntity settings = getSettingsOrThrow(request.branchId());
        int legs = legsOf(request.returnDate());
        return new RentalEstimateResponse(
                request.branchId(),
                request.distanceKm(),
                legs,
                request.passengers(),
                settings.getPricePerKm(),
                settings.getPassengerSurcharge(),
                calculatePrice(settings, request.distanceKm(), legs, request.passengers()));
    }

    @Override
    @Transactional
    public RentalResponse requestOnline(CreateRentalRequest request, UUID customerUserId, String customerNit) {
        validateDates(request.departureDate(), request.returnDate());
        RentalSettingsEntity settings = getSettingsOrThrow(request.branchId());
        BigDecimal estimatedPrice = calculatePrice(settings, request.distanceKm(), legsOf(request.returnDate()),
                request.passengers());

        RentalEntity rental = rentalRepository.save(RentalEntity.builder()
                .channel(RentalChannel.ONLINE)
                .customerNit(customerNit)
                .customerUserId(customerUserId)
                .branchId(request.branchId())
                .originText(request.originText().trim())
                .destinationText(request.destinationText().trim())
                .departureDate(request.departureDate())
                .returnDate(request.returnDate())
                .passengers(request.passengers())
                .distanceKm(request.distanceKm())
                .estimatedPrice(estimatedPrice)
                .status(RentalStatus.REQUESTED)
                .build());

        outboxService.record(EventTypes.AGGREGATE_RENTAL, rental.getId(), EventTypes.RENTAL_REQUESTED,
                RentalEventPayload.fromEntity(rental));

        return RentalResponse.fromEntity(rental);
    }

    @Override
    public RentalResponse registerCounter(CounterRentalRequest request, UUID branchId, String accessToken) {
        String requestedNit = NitUtils.normalize(request.customerNit());
        if (!NitUtils.isValid(requestedNit)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_NIT, "El NIT no es válido");
        }
        validateDates(request.departureDate(), request.returnDate());
        LocalDate paymentDate = resolvePaymentDate(request.paymentDate());
        RentalSettingsEntity settings = getSettingsOrThrow(branchId);
        BigDecimal estimatedPrice = calculatePrice(settings, request.distanceKm(), legsOf(request.returnDate()),
                request.passengers());

        String customerNit = NitUtils.GENERIC_NIT.equals(requestedNit)
                ? requestedNit
                : customerClient.getByNit(requestedNit, accessToken).nit();

        RentalEntity rental = transactionTemplate.execute(status -> {
            RentalEntity saved = rentalRepository.save(RentalEntity.builder()
                    .channel(RentalChannel.COUNTER)
                    .customerNit(customerNit)
                    .branchId(branchId)
                    .originText(request.originText().trim())
                    .destinationText(request.destinationText().trim())
                    .departureDate(request.departureDate())
                    .returnDate(request.returnDate())
                    .passengers(request.passengers())
                    .distanceKm(request.distanceKm())
                    .estimatedPrice(estimatedPrice)
                    .confirmedPrice(request.price().setScale(2, RoundingMode.HALF_UP))
                    .paymentDate(paymentDate)
                    .status(RentalStatus.PAID)
                    .build());

            outboxService.record(EventTypes.AGGREGATE_RENTAL, saved.getId(), EventTypes.RENTAL_PAID,
                    RentalEventPayload.fromEntity(saved));
            return saved;
        });

        return RentalResponse.fromEntity(rental);
    }

    @Override
    @Transactional
    public RentalResponse quote(UUID id, QuoteRentalRequest request, UUID branchId) {
        RentalEntity rental = getLockedOwnedOrThrow(id, branchId);
        if (rental.getStatus() != RentalStatus.REQUESTED) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.RENTAL_NOT_REQUESTED,
                    "Solo se pueden cotizar alquileres en estado REQUESTED; este está en " + rental.getStatus());
        }

        BigDecimal price = request.price() == null ? rental.getEstimatedPrice() : request.price();
        rental.setConfirmedPrice(price.setScale(2, RoundingMode.HALF_UP));
        rental.setStatus(RentalStatus.QUOTED);

        outboxService.record(EventTypes.AGGREGATE_RENTAL, rental.getId(), EventTypes.RENTAL_QUOTED,
                RentalEventPayload.fromEntity(rental));

        return RentalResponse.fromEntity(rental);
    }

    @Override
    @Transactional
    public RentalResponse reject(UUID id, RejectRentalRequest request, UUID branchId) {
        RentalEntity rental = getLockedOwnedOrThrow(id, branchId);
        if (rental.getStatus() != RentalStatus.REQUESTED) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.RENTAL_NOT_REQUESTED,
                    "Solo se pueden rechazar alquileres en estado REQUESTED; este está en " + rental.getStatus());
        }

        rental.setRejectionReason(request.reason().trim());
        rental.setStatus(RentalStatus.REJECTED);

        outboxService.record(EventTypes.AGGREGATE_RENTAL, rental.getId(), EventTypes.RENTAL_REJECTED,
                RentalEventPayload.fromEntity(rental));

        return RentalResponse.fromEntity(rental);
    }

    @Override
    @Transactional
    public RentalResponse pay(UUID id, PayRentalRequest request, UUID customerUserId, UUID customerId) {
        LocalDate paymentDate = resolvePaymentDate(request.paymentDate());
        RentalEntity rental = getLockedOfCustomerOrThrow(id, customerUserId);
        if (rental.getStatus() != RentalStatus.QUOTED) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.RENTAL_NOT_QUOTED,
                    "Solo se pueden pagar alquileres en estado QUOTED; este está en " + rental.getStatus());
        }
        if (rental.getPaymentDate() != null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.PAYMENT_IN_PROGRESS,
                    "El pago del alquiler ya está en proceso; espera el resultado");
        }

        rental.setPaymentDate(paymentDate);
        rental.setPaymentFailureReason(null);
        rental.setCustomerId(customerId);
        rental.setPaymentRequestedAt(Instant.now());

        outboxService.record(EventTypes.AGGREGATE_RENTAL, rental.getId(), EventTypes.PAYMENT_REQUESTED,
                PaymentRequestedPayload.from(rental));

        return RentalResponse.fromEntity(rental);
    }

    @Override
    @Transactional
    public RentalResponse assign(UUID id, AssignRentalRequest request, UUID branchId, UUID assignedBy) {
        RentalEntity rental = getLockedOwnedOrThrow(id, branchId);
        if (rental.getStatus() != RentalStatus.PAID) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.RENTAL_NOT_PAID,
                    "Solo se pueden asignar alquileres en estado PAID; este está en " + rental.getStatus());
        }
        validateAssignmentSchedule(rental, request);

        rental.setBusId(request.busId());
        rental.setDriverId(request.driverId());
        rental.setAssignmentRejectionReason(null);
        rental.setStatus(RentalStatus.ASSIGNED);

        outboxService.record(EventTypes.AGGREGATE_RENTAL, rental.getId(), EventTypes.RENTAL_ASSIGNED,
                RentalAssignedPayload.from(rental, request, assignedBy));

        return RentalResponse.fromEntity(rental);
    }

    private void validateAssignmentSchedule(RentalEntity rental, AssignRentalRequest request) {
        if (request.departureAt().isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.DEPARTURE_IN_PAST,
                    "La hora de salida no puede ser anterior a la hora actual");
        }
        validateLeg(request.departureAt(), request.estimatedArrival(), rental.getDepartureDate(), "ida");

        boolean hasReturnSchedule = request.returnDepartureAt() != null || request.returnEstimatedArrival() != null;
        if (rental.getReturnDate() == null) {
            if (hasReturnSchedule) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.RETURN_SCHEDULE_NOT_ALLOWED,
                        "El alquiler es solo de ida; no envíes horario de regreso");
            }
            return;
        }
        if (request.returnDepartureAt() == null || request.returnEstimatedArrival() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.RETURN_SCHEDULE_REQUIRED,
                    "El alquiler es de ida y vuelta; indica la salida y la llegada estimada del regreso");
        }
        if (request.returnDepartureAt().isBefore(request.estimatedArrival())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.RETURN_BEFORE_ARRIVAL,
                    "El regreso no puede salir antes de la llegada estimada de la ida");
        }
        validateLeg(request.returnDepartureAt(), request.returnEstimatedArrival(), rental.getReturnDate(), "regreso");
    }

    private void validateLeg(Instant departure, Instant arrival, LocalDate expectedDate, String legName) {
        if (!LocalDate.ofInstant(departure, zone).equals(expectedDate)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.SCHEDULE_DATE_MISMATCH,
                    "La salida del tramo de " + legName + " debe ser el " + DISPLAY_DATE.format(expectedDate)
                            + ", la fecha solicitada");
        }
        if (!arrival.isAfter(departure)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.ARRIVAL_BEFORE_DEPARTURE,
                    "La llegada estimada del tramo de " + legName + " debe ser posterior a su salida");
        }
    }

    @Override
    @Transactional
    public RentalResponse cancelAsCustomer(UUID id, UUID customerUserId) {
        return cancel(getLockedOfCustomerOrThrow(id, customerUserId));
    }

    @Override
    @Transactional
    public RentalResponse cancelAsAdmin(UUID id, UUID branchId) {
        return cancel(getLockedOwnedOrThrow(id, branchId));
    }

    @Override
    @Transactional
    public RentalResponse reschedule(UUID id, RescheduleRentalRequest request, UUID branchId) {
        RentalEntity rental = getLockedOwnedOrThrow(id, branchId);
        if (rental.getStatus() != RentalStatus.PAID) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.RENTAL_NOT_PAID,
                    "Solo se pueden reprogramar alquileres en estado PAID; este está en " + rental.getStatus());
        }
        if (rental.getReturnDate() != null && request.returnDate() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.RETURN_DATE_REQUIRED,
                    "El alquiler es de ida y vuelta; indica la nueva fecha de retorno");
        }
        if (rental.getReturnDate() == null && request.returnDate() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.RETURN_DATE_NOT_ALLOWED,
                    "El alquiler es solo de ida; no envíes fecha de retorno");
        }
        validateDates(request.departureDate(), request.returnDate());

        rental.setDepartureDate(request.departureDate());
        rental.setReturnDate(request.returnDate());
        return RentalResponse.fromEntity(rental);
    }

    @Override
    @Transactional(readOnly = true)
    public RentalResponse findForCustomer(UUID id, UUID customerUserId, String customerNit) {
        RentalEntity rental = getOrThrow(id);
        if (!customerUserId.equals(rental.getCustomerUserId()) && !customerNit.equals(rental.getCustomerNit())) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.RENTAL_NOT_OWNED,
                    "El alquiler no te pertenece");
        }
        return RentalResponse.fromEntity(rental);
    }

    @Override
    @Transactional(readOnly = true)
    public RentalResponse findForStaff(UUID id, UUID scopeBranchId) {
        RentalEntity rental = getOrThrow(id);
        if (scopeBranchId != null && !rental.getBranchId().equals(scopeBranchId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.RENTAL_NOT_IN_BRANCH,
                    "El alquiler no pertenece a tu sucursal");
        }
        return RentalResponse.fromEntity(rental);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<RentalResponse> searchForCustomer(UUID customerUserId, String customerNit,
                                                          RentalStatus status, LocalDate from, LocalDate to,
                                                          int page, int size) {
        return findPage(status, null, customerUserId, customerNit, from, to, page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<RentalResponse> searchForBranch(UUID branchId, RentalStatus status, LocalDate from,
                                                        LocalDate to, int page, int size) {
        return findPage(status, branchId, null, null, from, to, page, size);
    }

    private PageResponse<RentalResponse> findPage(RentalStatus status, UUID branchId, UUID customerUserId,
                                                  String customerNit, LocalDate from, LocalDate to,
                                                  int page, int size) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_DATE_RANGE,
                    "La fecha final no puede ser anterior a la inicial");
        }
        PageRequest pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.of(rentalRepository
                .findAll(RentalSpecifications.filter(status, branchId, customerUserId, customerNit, from, to),
                        pageable)
                .map(RentalResponse::fromEntity));
    }

    private RentalResponse cancel(RentalEntity rental) {
        if (rental.getStatus() != RentalStatus.REQUESTED && rental.getStatus() != RentalStatus.QUOTED) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.RENTAL_NOT_CANCELLABLE,
                    "Solo se pueden cancelar alquileres sin pagar (REQUESTED o QUOTED); este está en "
                            + rental.getStatus());
        }
        if (rental.getPaymentDate() != null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.PAYMENT_IN_PROGRESS,
                    "El pago del alquiler está en proceso; espera el resultado antes de cancelar");
        }

        rental.setStatus(RentalStatus.CANCELLED);
        return RentalResponse.fromEntity(rental);
    }

    private RentalEntity getOrThrow(UUID id) {
        return rentalRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RENTAL_NOT_FOUND,
                        "Alquiler no encontrado"));
    }

    private RentalEntity getLockedOrThrow(UUID id) {
        return rentalRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RENTAL_NOT_FOUND,
                        "Alquiler no encontrado"));
    }

    private RentalEntity getLockedOwnedOrThrow(UUID id, UUID branchId) {
        RentalEntity rental = getLockedOrThrow(id);
        if (!rental.getBranchId().equals(branchId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.RENTAL_NOT_IN_BRANCH,
                    "El alquiler no pertenece a tu sucursal");
        }
        return rental;
    }

    private RentalEntity getLockedOfCustomerOrThrow(UUID id, UUID customerUserId) {
        RentalEntity rental = getLockedOrThrow(id);
        if (!customerUserId.equals(rental.getCustomerUserId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.RENTAL_NOT_OWNED,
                    "El alquiler no te pertenece");
        }
        return rental;
    }

    private LocalDate resolvePaymentDate(LocalDate requested) {
        LocalDate today = LocalDate.now(zone);
        if (requested == null) {
            return today;
        }
        if (requested.isAfter(today)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PAYMENT_DATE_IN_FUTURE,
                    "La fecha de pago no puede ser futura");
        }
        return requested;
    }

    private void validateDates(LocalDate departureDate, LocalDate returnDate) {
        if (departureDate.isBefore(LocalDate.now(zone))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.DEPARTURE_IN_PAST,
                    "La fecha de salida no puede ser anterior a hoy");
        }
        if (returnDate != null && returnDate.isBefore(departureDate)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.RETURN_BEFORE_DEPARTURE,
                    "La fecha de retorno no puede ser anterior a la fecha de salida");
        }
    }

    private RentalSettingsEntity getSettingsOrThrow(UUID branchId) {
        return rentalSettingsRepository.findById(branchId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                        ErrorCodes.RENTAL_SETTINGS_NOT_FOUND,
                        "La sucursal no tiene configuradas las tarifas de alquiler"));
    }

    private int legsOf(LocalDate returnDate) {
        return returnDate == null ? ONE_WAY_LEGS : ROUND_TRIP_LEGS;
    }

    private BigDecimal calculatePrice(RentalSettingsEntity settings, BigDecimal distanceKm, int legs,
                                      Integer passengers) {
        BigDecimal distanceCost = distanceKm.multiply(settings.getPricePerKm()).multiply(BigDecimal.valueOf(legs));
        BigDecimal passengerCost = settings.getPassengerSurcharge().multiply(BigDecimal.valueOf(passengers));
        return distanceCost.add(passengerCost).setScale(2, RoundingMode.HALF_UP);
    }
}
