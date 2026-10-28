package com.urbantransport.rental.service;

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

import java.time.LocalDate;
import java.util.UUID;

public interface RentalService {

    RentalEstimateResponse estimate(CreateRentalRequest request);

    RentalResponse requestOnline(CreateRentalRequest request, UUID customerUserId, String customerNit);

    RentalResponse registerCounter(CounterRentalRequest request, UUID branchId, String accessToken);

    RentalResponse quote(UUID id, QuoteRentalRequest request, UUID branchId);

    RentalResponse reject(UUID id, RejectRentalRequest request, UUID branchId);

    RentalResponse pay(UUID id, PayRentalRequest request, UUID customerUserId, UUID customerId);

    RentalResponse assign(UUID id, AssignRentalRequest request, UUID branchId, UUID assignedBy);

    RentalResponse cancelAsCustomer(UUID id, UUID customerUserId);

    RentalResponse cancelAsAdmin(UUID id, UUID branchId);

    RentalResponse reschedule(UUID id, RescheduleRentalRequest request, UUID branchId);

    RentalResponse findForCustomer(UUID id, UUID customerUserId, String customerNit);

    RentalResponse findForStaff(UUID id, UUID scopeBranchId);

    PageResponse<RentalResponse> searchForCustomer(UUID customerUserId, String customerNit, RentalStatus status,
                                                   LocalDate from, LocalDate to, int page, int size);

    PageResponse<RentalResponse> searchForBranch(UUID branchId, RentalStatus status, LocalDate from, LocalDate to,
                                                 int page, int size);
}
