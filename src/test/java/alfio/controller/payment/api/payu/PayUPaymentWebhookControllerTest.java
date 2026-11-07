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
import alfio.model.transaction.Transaction;
import alfio.repository.TransactionRepository;
import alfio.test.util.LogCapture;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PayUPaymentWebhookControllerTest {

    private static final String RESERVATION_ID = "5f2d3c1a-8b7e-4f6a-9c0d-1e2f3a4b5c6d";
    private static final String BODY = "{\"order\":{\"orderId\":\"ORDER-1\",\"status\":\"COMPLETED\"}}";
    private static final String SIGNATURE = "sender=checkout;signature=abc;algorithm=MD5;content=DOCUMENT";

    private TicketReservationManager ticketReservationManager;
    private PurchaseContextManager purchaseContextManager;
    private PurchaseContext purchaseContext;
    private TransactionRepository transactionRepository;
    private PayUPaymentWebhookController controller;

    @BeforeEach
    void setUp() {
        ticketReservationManager = mock(TicketReservationManager.class);
        purchaseContextManager = mock(PurchaseContextManager.class);
        purchaseContext = mock(PurchaseContext.class);
        transactionRepository = mock(TransactionRepository.class);
        when(purchaseContextManager.findByReservationId(RESERVATION_ID)).thenReturn(Optional.of(purchaseContext));
        controller = new PayUPaymentWebhookController(ticketReservationManager, purchaseContextManager, transactionRepository);
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
    void errorAnswersInternalServerErrorWithGenericBody() {
        mockResult(PaymentWebhookResult.error("cannot retrieve PayU order ORDER-1"));
        var response = controller.receivePaymentNotification(request(BODY, "OpenPayu-Signature", SIGNATURE), RESERVATION_ID);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        // the reason is logged, never sent back to the caller
        assertEquals("NOK", response.getBody());
    }

    @Test
    void notRelevantAnswersOkWithReason() {
        mockResult(PaymentWebhookResult.notRelevant("order not paid yet"));
        var response = controller.receivePaymentNotification(request(BODY, "OpenPayu-Signature", SIGNATURE), RESERVATION_ID);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("order not paid yet", response.getBody());
    }

    @ParameterizedTest
    @CsvSource({
        "COMPLETED, transaction not found",
        "WAITING_FOR_CONFIRMATION, reservation status is not compatible",
        "COMPLETED, notification refers to a different PayU order"
    })
    void discardedPaidNotificationIsLoggedAsError(String status, String reason) {
        mockResult(PaymentWebhookResult.notRelevant(reason));
        when(transactionRepository.loadOptionalByReservationId(RESERVATION_ID)).thenReturn(Optional.empty());
        var body = "{\"order\":{\"orderId\":\"ORDER-1\",\"status\":\"" + status + "\"}}";
        try (var logs = LogCapture.of(PayUPaymentWebhookController.class)) {
            var response = controller.receivePaymentNotification(request(body, "OpenPayu-Signature", SIGNATURE), RESERVATION_ID);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(reason, response.getBody());
            var errors = logs.messages(Level.ERROR);
            assertEquals(1, errors.size(), errors.toString());
            assertTrue(errors.get(0).contains(RESERVATION_ID) && errors.get(0).contains("ORDER-1") && errors.get(0).contains(status), errors.get(0));
            assertTrue(errors.get(0).contains("refund"), errors.get(0));
        }
    }

    @ParameterizedTest
    @CsvSource({
        "ORDER-1, COMPLETE, false",
        "ORDER-1, PENDING, true",
        "ORDER-1, CANCELLED, true",
        "ORDER-2, COMPLETE, true"
    })
    void paidNotificationIsAnErrorUnlessItsOrderIsRecordedAsPaid(String transactionOrderId, Transaction.Status transactionStatus, boolean errorExpected) {
        mockResult(PaymentWebhookResult.notRelevant("reservation status is not compatible"));
        var transaction = mock(Transaction.class);
        when(transaction.getPaymentId()).thenReturn(transactionOrderId);
        when(transaction.getStatus()).thenReturn(transactionStatus);
        when(transactionRepository.loadOptionalByReservationId(RESERVATION_ID)).thenReturn(Optional.of(transaction));
        try (var logs = LogCapture.of(PayUPaymentWebhookController.class)) {
            var response = controller.receivePaymentNotification(request(BODY, "OpenPayu-Signature", SIGNATURE), RESERVATION_ID);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(errorExpected, logs.messages(Level.ERROR).size() == 1, logs.messages(Level.ERROR).toString());
        }
    }

    @Test
    void discardedUnpaidNotificationIsNotAnError() {
        mockResult(PaymentWebhookResult.notRelevant("transaction not found"));
        var body = "{\"order\":{\"orderId\":\"ORDER-1\",\"status\":\"CANCELED\"}}";
        try (var logs = LogCapture.of(PayUPaymentWebhookController.class)) {
            controller.receivePaymentNotification(request(body, "OpenPayu-Signature", SIGNATURE), RESERVATION_ID);

            assertTrue(logs.messages(Level.ERROR).isEmpty(), logs.messages(Level.ERROR).toString());
        }
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void processedNotificationDoesNotLookUpTheTransaction() {
        mockResult(PaymentWebhookResult.successful(null));
        controller.receivePaymentNotification(request(BODY, "OpenPayu-Signature", SIGNATURE), RESERVATION_ID);
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void paidNotificationForNonUuidReservationIsOnlyAWarning() {
        var reservationId = "not-a-reservation";
        when(purchaseContextManager.findByReservationId(reservationId)).thenReturn(Optional.empty());
        try (var logs = LogCapture.of(PayUPaymentWebhookController.class)) {
            var response = controller.receivePaymentNotification(request(BODY, "OpenPayu-Signature", SIGNATURE), reservationId);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertTrue(logs.messages(Level.ERROR).isEmpty(), logs.messages(Level.ERROR).toString());
            assertEquals(1, logs.messages(Level.WARN).size(), logs.messages(Level.WARN).toString());
        }
    }

    @Test
    void unknownReservationIdIsSanitizedBeforeLogging() {
        var reservationId = "forged\r\nINFO forged log line";
        when(purchaseContextManager.findByReservationId(reservationId)).thenReturn(Optional.empty());
        try (var logs = LogCapture.of(PayUPaymentWebhookController.class)) {
            controller.receivePaymentNotification(request(BODY, "OpenPayu-Signature", SIGNATURE), reservationId);

            var warnings = logs.messages(Level.WARN);
            assertEquals(1, warnings.size(), warnings.toString());
            assertFalse(warnings.get(0).contains("\r") || warnings.get(0).contains("\n"), warnings.get(0));
        }
    }

    @Test
    void unknownReservationAnswersOkWithoutProcessing() {
        when(purchaseContextManager.findByReservationId(RESERVATION_ID)).thenReturn(Optional.empty());
        var response = controller.receivePaymentNotification(request(BODY, "OpenPayu-Signature", SIGNATURE), RESERVATION_ID);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("NOT_RELEVANT", response.getBody());
        verify(ticketReservationManager, never()).processTransactionWebhook(anyString(), any(), any(), anyMap(), any());
    }

    @ParameterizedTest
    @CsvSource({
        "COMPLETED, true",
        "WAITING_FOR_CONFIRMATION, true",
        "NEW, false",
        "CANCELED, false"
    })
    void paidNotificationForUnknownReservationIsLoggedAsError(String status, boolean errorExpected) {
        when(purchaseContextManager.findByReservationId(RESERVATION_ID)).thenReturn(Optional.empty());
        var body = "{\"order\":{\"orderId\":\"ORDER-1\",\"status\":\"" + status + "\"}}";
        try (var logs = LogCapture.of(PayUPaymentWebhookController.class)) {
            var response = controller.receivePaymentNotification(request(body, "OpenPayu-Signature", SIGNATURE), RESERVATION_ID);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(errorExpected, logs.messages(Level.ERROR).size() == 1, logs.messages(Level.ERROR).toString());
            assertEquals(errorExpected, logs.messages(Level.WARN).isEmpty(), logs.messages(Level.WARN).toString());
        }
    }

    @Test
    void unsignedOrderIdIsSanitizedBeforeLogging() {
        when(purchaseContextManager.findByReservationId(RESERVATION_ID)).thenReturn(Optional.empty());
        var forgedOrderId = "ORDER-1\\r\\nINFO forged log line " + "x".repeat(200);
        var body = "{\"order\":{\"orderId\":\"" + forgedOrderId + "\",\"status\":\"NEW\"}}";
        try (var logs = LogCapture.of(PayUPaymentWebhookController.class)) {
            controller.receivePaymentNotification(request(body, "OpenPayu-Signature", SIGNATURE), RESERVATION_ID);

            var warnings = logs.messages(Level.WARN);
            assertEquals(1, warnings.size(), warnings.toString());
            assertFalse(warnings.get(0).contains("\r") || warnings.get(0).contains("\n"), warnings.get(0));
            assertFalse(warnings.get(0).contains("x".repeat(100)), warnings.get(0));
            assertTrue(warnings.get(0).contains("ORDER-1"), warnings.get(0));
        }
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
