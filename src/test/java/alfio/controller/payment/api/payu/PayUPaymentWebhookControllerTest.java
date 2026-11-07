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
package alfio.controller.payment.api.payu;

import alfio.manager.PurchaseContextManager;
import alfio.manager.TicketReservationManager;
import alfio.manager.payment.PayUManager;
import alfio.manager.support.PaymentWebhookResult;
import alfio.model.PurchaseContext;
import alfio.model.transaction.PaymentContext;
import alfio.model.transaction.PaymentProxy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PayUPaymentWebhookControllerTest {

    private static final String RESERVATION_ID = "reservation-id";
    private static final String BODY = "{\"order\":{\"orderId\":\"ORDER-1\",\"status\":\"COMPLETED\"}}";
    private static final String SIGNATURE = "sender=checkout;signature=abc;algorithm=MD5;content=DOCUMENT";

    private TicketReservationManager ticketReservationManager;
    private PurchaseContextManager purchaseContextManager;
    private PurchaseContext purchaseContext;
    private PayUPaymentWebhookController controller;

    @BeforeEach
    void setUp() {
        ticketReservationManager = mock(TicketReservationManager.class);
        purchaseContextManager = mock(PurchaseContextManager.class);
        purchaseContext = mock(PurchaseContext.class);
        when(purchaseContextManager.findByReservationId(RESERVATION_ID)).thenReturn(Optional.of(purchaseContext));
        controller = new PayUPaymentWebhookController(ticketReservationManager, purchaseContextManager);
    }

    @Test
    void successfulNotificationAnswersOk() {
        mockResult(PaymentWebhookResult.successful(null));
        var response = controller.receivePaymentNotification(request(BODY, "OpenPayu-Signature", SIGNATURE), RESERVATION_ID);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("OK", response.getBody());
        verify(ticketReservationManager).processTransactionWebhook(eq(BODY), eq(SIGNATURE), eq(PaymentProxy.PAYU),
            eq(Map.of(PayUManager.ADDITIONAL_INFO_RESERVATION_ID, RESERVATION_ID)), any(PaymentContext.class));
    }

    @Test
    void errorAnswersInternalServerErrorWithReason() {
        mockResult(PaymentWebhookResult.error("invalid signature"));
        var response = controller.receivePaymentNotification(request(BODY, "OpenPayu-Signature", SIGNATURE), RESERVATION_ID);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("invalid signature", response.getBody());
    }

    @Test
    void notRelevantAnswersOkWithReason() {
        mockResult(PaymentWebhookResult.notRelevant("order not paid yet"));
        var response = controller.receivePaymentNotification(request(BODY, "OpenPayu-Signature", SIGNATURE), RESERVATION_ID);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("order not paid yet", response.getBody());
    }

    @Test
    void unknownReservationAnswersOkWithoutProcessing() {
        when(purchaseContextManager.findByReservationId(RESERVATION_ID)).thenReturn(Optional.empty());
        var response = controller.receivePaymentNotification(request(BODY, "OpenPayu-Signature", SIGNATURE), RESERVATION_ID);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("NOT_RELEVANT", response.getBody());
        verify(ticketReservationManager, never()).processTransactionWebhook(anyString(), any(), any(), anyMap(), any());
    }

    @Test
    void emptyBodyAnswersBadRequest() {
        var response = controller.receivePaymentNotification(request("", "OpenPayu-Signature", SIGNATURE), RESERVATION_ID);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("NOK", response.getBody());
        verify(ticketReservationManager, never()).processTransactionWebhook(anyString(), any(), any(), anyMap(), any());
    }

    @Test
    void fallbackSignatureHeaderIsPassedThrough() {
        mockResult(PaymentWebhookResult.successful(null));
        var response = controller.receivePaymentNotification(request(BODY, "X-OpenPayU-Signature", SIGNATURE), RESERVATION_ID);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(ticketReservationManager).processTransactionWebhook(eq(BODY), eq(SIGNATURE), eq(PaymentProxy.PAYU), anyMap(), any(PaymentContext.class));
    }

    private void mockResult(PaymentWebhookResult result) {
        when(ticketReservationManager.processTransactionWebhook(anyString(), any(), eq(PaymentProxy.PAYU), anyMap(), any(PaymentContext.class)))
            .thenReturn(result);
    }

    private static MockHttpServletRequest request(String body, String signatureHeader, String signature) {
        var request = new MockHttpServletRequest("POST", "/api/payment/webhook/payu/reservation/" + RESERVATION_ID);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.addHeader(signatureHeader, signature);
        return request;
    }
}
