/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.controller.payment;

import alfio.manager.PurchaseContextManager;
import alfio.manager.TicketReservationManager;
import alfio.manager.support.PaymentResult;
import alfio.model.PurchaseContext;
import alfio.model.TicketReservation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PayUCallbackControllerTest {

    private static final String IDENTIFIER = "my-event";
    private static final String RESERVATION_ID = "reservation-id";
    private static final String OVERVIEW_CANCELLED = "redirect:/event/my-event/reservation/reservation-id/overview?paymentCancelled=true";
    private static final String BOOK = "redirect:/event/my-event/reservation/reservation-id/book";

    private TicketReservationManager ticketReservationManager;
    private PurchaseContextManager purchaseContextManager;
    private PurchaseContext purchaseContext;
    private TicketReservation reservation;
    private PayUCallbackController controller;

    @BeforeEach
    void setUp() {
        ticketReservationManager = mock(TicketReservationManager.class);
        purchaseContextManager = mock(PurchaseContextManager.class);
        purchaseContext = mock(PurchaseContext.class);
        reservation = mock(TicketReservation.class);
        when(purchaseContext.getType()).thenReturn(PurchaseContext.PurchaseContextType.event);
        when(purchaseContext.getPublicIdentifier()).thenReturn(IDENTIFIER);
        doReturn(Optional.of(purchaseContext)).when(purchaseContextManager).findBy(PurchaseContext.PurchaseContextType.event, IDENTIFIER);
        when(ticketReservationManager.findById(RESERVATION_ID)).thenReturn(Optional.of(reservation));
        controller = new PayUCallbackController(ticketReservationManager, purchaseContextManager);
    }

    @Test
    void errorAndPendingPaymentCancelledRedirectsToOverview() {
        when(ticketReservationManager.cancelPendingPayment(RESERVATION_ID, purchaseContext)).thenReturn(true);
        assertEquals(OVERVIEW_CANCELLED, payuReturn("501", "501"));
        verify(ticketReservationManager, never()).forceTransactionCheck(any(), any());
    }

    @Test
    void errorWithoutPendingTransactionRedirectsToOverview() {
        when(ticketReservationManager.cancelPendingPayment(RESERVATION_ID, purchaseContext)).thenReturn(false);
        when(ticketReservationManager.forceTransactionCheck(purchaseContext, reservation)).thenReturn(Optional.empty());
        assertEquals(OVERVIEW_CANCELLED, payuReturn("501", null));
    }

    @Test
    void errorWithOrderThatCannotBeCancelledFollowsTheForceCheck() {
        // e.g. PayU appended error but the order is already PENDING (BLIK in flight): the payment is not abandoned
        when(ticketReservationManager.cancelPendingPayment(RESERVATION_ID, purchaseContext)).thenReturn(false);
        when(ticketReservationManager.forceTransactionCheck(purchaseContext, reservation)).thenReturn(Optional.of(PaymentResult.pending("ORDER-1")));
        assertEquals(BOOK, payuReturn("501", null));

        when(ticketReservationManager.forceTransactionCheck(purchaseContext, reservation)).thenReturn(Optional.of(PaymentResult.successful("ORDER-1")));
        assertEquals(BOOK, payuReturn("501", null));

        when(ticketReservationManager.forceTransactionCheck(purchaseContext, reservation)).thenReturn(Optional.of(PaymentResult.failed("CANCELED")));
        assertEquals(OVERVIEW_CANCELLED, payuReturn("501", null));
    }

    @Test
    void successfulPaymentRedirectsToBook() {
        when(ticketReservationManager.forceTransactionCheck(purchaseContext, reservation)).thenReturn(Optional.of(PaymentResult.successful("ORDER-1")));
        assertEquals(BOOK, payuReturn(null, null));
        verify(ticketReservationManager, never()).cancelPendingPayment(any(), any());
    }

    @Test
    void failedPaymentRedirectsToOverview() {
        when(ticketReservationManager.forceTransactionCheck(purchaseContext, reservation)).thenReturn(Optional.of(PaymentResult.failed("CANCELED")));
        assertEquals(OVERVIEW_CANCELLED, payuReturn(null, null));
    }

    @Test
    void pendingPaymentRedirectsToBook() {
        when(ticketReservationManager.forceTransactionCheck(purchaseContext, reservation)).thenReturn(Optional.of(PaymentResult.pending("ORDER-1")));
        assertEquals(BOOK, payuReturn(null, null));
    }

    @Test
    void unknownPurchaseContextRedirectsToRoot() {
        when(purchaseContextManager.findBy(PurchaseContext.PurchaseContextType.event, "unknown")).thenReturn(Optional.empty());
        var result = controller.payuReturn(PurchaseContext.PurchaseContextType.event, "unknown", RESERVATION_ID, null, null);
        assertEquals("redirect:/", result);
        verify(ticketReservationManager, never()).findById(any());
    }

    private String payuReturn(String error, String errorCode) {
        return controller.payuReturn(PurchaseContext.PurchaseContextType.event, IDENTIFIER, RESERVATION_ID, error, errorCode);
    }
}
