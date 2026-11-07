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
package alfio.manager.payment;

import alfio.manager.support.PaymentWebhookResult;
import alfio.manager.system.ConfigurationManager;
import alfio.model.Event;
import alfio.model.system.ConfigurationKeys;
import alfio.manager.system.ConfigurationLevel;
import alfio.model.transaction.PaymentContext;
import alfio.model.transaction.PaymentProxy;
import alfio.model.transaction.StaticPaymentMethods;
import alfio.model.transaction.Transaction;
import alfio.repository.TicketReservationRepository;
import alfio.repository.TransactionRepository;
import alfio.test.util.TestUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static alfio.manager.testSupport.MaybeConfigurationBuilder.existing;
import static alfio.model.system.ConfigurationKeys.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Reproduces lifecycle defects of the PayU draft (TDD red gate).
 */
class PayUManagerLifecycleTest {

    private static final String SECOND_KEY = "b6ca15b0d1020e8094d9b5f8d163db54";
    private static final String RESERVATION_ID = "res-1";
    private static final String OLD_ORDER_ID = "OLDORDER";

    private HttpClient httpClient;
    private TransactionRepository transactionRepository;
    private TicketReservationRepository ticketReservationRepository;
    private ConfigurationManager configurationManager;
    private Event event;
    private PayUManager manager;

    @BeforeEach
    void init() {
        httpClient = mock(HttpClient.class);
        transactionRepository = mock(TransactionRepository.class);
        ticketReservationRepository = mock(TicketReservationRepository.class);
        configurationManager = mock(ConfigurationManager.class);
        event = mock(Event.class);
        when(event.getConfigurationLevel()).thenReturn(ConfigurationLevel.organization(1));
        when(event.getCurrency()).thenReturn("PLN");
        lenient().when(event.getType()).thenReturn(alfio.model.PurchaseContext.PurchaseContextType.event);
        lenient().when(event.getPublicIdentifier()).thenReturn("reunion");
        lenient().when(event.getZoneId()).thenReturn(java.time.ZoneId.of("Europe/Warsaw"));
        lenient().when(event.getDisplayName()).thenReturn("Reunion");
        Map<ConfigurationKeys, ConfigurationManager.MaybeConfiguration> configuration = new EnumMap<>(ConfigurationKeys.class);
        configuration.put(PAYU_ENABLED, existing(PAYU_ENABLED, "true"));
        configuration.put(PAYU_LIVE_MODE, existing(PAYU_LIVE_MODE, "false"));
        configuration.put(PAYU_POS_ID, existing(PAYU_POS_ID, "300746"));
        configuration.put(PAYU_CLIENT_ID, existing(PAYU_CLIENT_ID, "300746"));
        configuration.put(PAYU_CLIENT_SECRET, existing(PAYU_CLIENT_SECRET, "secret"));
        configuration.put(PAYU_SECOND_KEY, existing(PAYU_SECOND_KEY, SECOND_KEY));
        configuration.put(BASE_URL, existing(BASE_URL, "https://tickets.example.org"));
        configuration.put(RESERVATION_TIMEOUT, existing(RESERVATION_TIMEOUT, "25"));
        when(configurationManager.getFor(anySet(), any())).thenAnswer(inv -> {
            Set<ConfigurationKeys> keys = inv.getArgument(0);
            Map<ConfigurationKeys, ConfigurationManager.MaybeConfiguration> result = new EnumMap<>(ConfigurationKeys.class);
            keys.forEach(k -> result.put(k, configuration.getOrDefault(k, new ConfigurationManager.MaybeConfiguration(k))));
            return result;
        });
        manager = new PayUManager(configurationManager, httpClient, ticketReservationRepository, transactionRepository, TestUtil.clockProvider());
        manager.setCancelConfirmationTiming(java.time.Duration.ZERO, java.time.Duration.ofSeconds(3));
    }

    @SuppressWarnings("unchecked")
    private void stubPayU(String orderStatus) throws Exception {
        when(httpClient.send(any(HttpRequest.class), any())).thenAnswer(inv -> {
            HttpRequest request = inv.getArgument(0);
            HttpResponse<String> response = mock(HttpResponse.class);
            var path = request.uri().getPath();
            if (path.endsWith("/oauth/authorize")) {
                when(response.statusCode()).thenReturn(200);
                when(response.body()).thenReturn("{\"access_token\":\"tok\",\"token_type\":\"bearer\",\"expires_in\":43199}");
            } else if (path.equals("/api/v2_1/orders/" + OLD_ORDER_ID) && "GET".equals(request.method())) {
                when(response.statusCode()).thenReturn(200);
                when(response.body()).thenReturn("{\"orders\":[{\"orderId\":\"" + OLD_ORDER_ID + "\",\"extOrderId\":\"" + RESERVATION_ID
                    + "-1\",\"totalAmount\":\"1000\",\"currencyCode\":\"PLN\",\"status\":\"" + orderStatus + "\"}],\"status\":{\"statusCode\":\"SUCCESS\"}}");
            } else {
                when(response.statusCode()).thenReturn(302);
                when(response.body()).thenReturn("{\"status\":{\"statusCode\":\"SUCCESS\"},\"redirectUri\":\"https://merch-prod.snd.payu.com/pay/?orderId=NEWORDER\",\"orderId\":\"NEWORDER\",\"extOrderId\":\"x\"}");
            }
            return response;
        });
    }

    private Transaction pendingTransaction() {
        var transaction = mock(Transaction.class);
        when(transaction.getId()).thenReturn(5);
        when(transaction.getPaymentId()).thenReturn(OLD_ORDER_ID);
        when(transaction.getReservationId()).thenReturn(RESERVATION_ID);
        when(transaction.getPriceInCents()).thenReturn(1000);
        when(transaction.getCurrency()).thenReturn("PLN");
        when(transaction.getPaymentProxy()).thenReturn(PaymentProxy.PAYU);
        when(transaction.getMetadata()).thenReturn(Map.of("extOrderId", RESERVATION_ID + "-1"));
        return transaction;
    }

    @Test
    void fixtureCreatesOrderWhenNoTransactionExists() throws Exception {
        stubPayU("NEW");
        when(transactionRepository.loadOptionalByStatusAndPaymentProxyForUpdate(RESERVATION_ID, Transaction.Status.PENDING, PaymentProxy.PAYU))
            .thenReturn(Optional.empty());
        var result = manager.doPayment(spec());
        assertTrue(result.isRedirect(), "sanity check: fixture must allow order creation");
        assertTrue(newOrderCreated());
    }

    private boolean newOrderCreated() throws Exception {
        return mockingDetails(httpClient).getInvocations().stream()
            .map(i -> (HttpRequest) i.getArgument(0))
            .anyMatch(r -> "POST".equals(r.method()) && r.uri().getPath().equals("/api/v2_1/orders"));
    }

    private PaymentSpecification spec() {
        return new PaymentSpecification(RESERVATION_ID, null, StaticPaymentMethods.PAYU, 1000, event, "buyer@example.org",
            new alfio.model.CustomerName("Jan Kowalski", "Jan", "Kowalski", true));
    }

    @Test
    void doPaymentMustNotCreateSecondOrderWhileFirstAwaitsConfirmation() throws Exception {
        stubPayU("WAITING_FOR_CONFIRMATION");
        var transaction = pendingTransaction();
        when(transactionRepository.loadOptionalByStatusAndPaymentProxyForUpdate(RESERVATION_ID, Transaction.Status.PENDING, PaymentProxy.PAYU))
            .thenReturn(Optional.of(transaction));
        var result = manager.doPayment(spec());
        assertFalse(newOrderCreated(), "a second PayU order was created: buyer may be charged twice");
        assertFalse(result.isRedirect());
    }

    @Test
    void doPaymentMustNotCreateSecondOrderWhileFirstIsBeingPaid() throws Exception {
        stubPayU("PENDING");
        var transaction = pendingTransaction();
        when(transactionRepository.loadOptionalByStatusAndPaymentProxyForUpdate(RESERVATION_ID, Transaction.Status.PENDING, PaymentProxy.PAYU))
            .thenReturn(Optional.of(transaction));
        manager.doPayment(spec());
        assertFalse(newOrderCreated(), "a second PayU order was created while the first one is being paid");
    }

    @Test
    void doPaymentCancelsStillNewOrderBeforeCreatingAnotherOne() throws Exception {
        stubPayU("NEW");
        var transaction = pendingTransaction();
        when(transactionRepository.loadOptionalByStatusAndPaymentProxyForUpdate(RESERVATION_ID, Transaction.Status.PENDING, PaymentProxy.PAYU))
            .thenReturn(Optional.of(transaction));
        manager.doPayment(spec());
        var cancelled = mockingDetails(httpClient).getInvocations().stream()
            .map(i -> (HttpRequest) i.getArgument(0))
            .anyMatch(r -> "DELETE".equals(r.method()) && r.uri().getPath().equals("/api/v2_1/orders/" + OLD_ORDER_ID));
        assertTrue(cancelled, "the old, still payable PayU order was not cancelled");
    }

    @Test
    void newOrderIsNotReportedAsPaymentInProgress() throws Exception {
        stubPayU("NEW");
        var result = manager.forceTransactionCheck(null, pendingTransaction(), new PaymentContext(event));
        assertNotEquals(PaymentWebhookResult.Type.TRANSACTION_INITIATED, result.getType(),
            "an unpaid NEW order must not move the reservation to WAITING_EXTERNAL_CONFIRMATION");
    }

    @Test
    void signedRefundNotificationIsAcknowledged() {
        var body = "{\"orderId\":\"" + OLD_ORDER_ID + "\",\"extOrderId\":\"" + RESERVATION_ID + "-1\",\"refund\":{\"refundId\":\"R1\",\"amount\":\"1000\",\"currencyCode\":\"PLN\",\"status\":\"FINALIZED\"}}";
        var signature = "sender=checkout;signature=" + md5Hex(body + SECOND_KEY) + ";algorithm=MD5;content=DOCUMENT";
        var payload = manager.parseTransactionPayload(body, signature, Map.of("reservationId", RESERVATION_ID), new PaymentContext(event));
        assertTrue(payload.isPresent(), "a valid refund notification is rejected: PayU would retry it forever");
    }

    @Test
    void signedNotificationWithoutExtOrderIdDoesNotThrow() {
        var body = "{\"order\":{\"orderId\":\"" + OLD_ORDER_ID + "\",\"status\":\"COMPLETED\"}}";
        var signature = "sender=checkout;signature=" + md5Hex(body + SECOND_KEY) + ";algorithm=MD5;content=DOCUMENT";
        assertDoesNotThrow(() -> manager.parseTransactionPayload(body, signature, Map.of("reservationId", RESERVATION_ID), new PaymentContext(event)));
    }

    private static String md5Hex(String value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("MD5").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
