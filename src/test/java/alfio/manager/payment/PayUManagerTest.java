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

import alfio.manager.support.PaymentResult;
import alfio.manager.system.ConfigurationLevel;
import alfio.manager.system.ConfigurationManager;
import alfio.model.CustomerName;
import alfio.model.Event;
import alfio.model.PurchaseContext;
import alfio.model.TicketReservation;
import alfio.model.system.ConfigurationKeys;
import alfio.manager.support.PaymentWebhookResult;
import alfio.model.transaction.PaymentContext;
import alfio.model.transaction.PaymentProxy;
import alfio.model.transaction.StaticPaymentMethods;
import alfio.model.transaction.Transaction;
import alfio.model.transaction.TransactionWebhookPayload;
import alfio.model.transaction.webhook.PayUWebhookPayload;
import alfio.repository.TicketReservationRepository;
import alfio.repository.TransactionRepository;
import alfio.test.util.TestUtil;
import alfio.util.ClockProvider;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static alfio.manager.testSupport.MaybeConfigurationBuilder.existing;
import static alfio.model.TicketReservation.TicketReservationStatus.EXTERNAL_PROCESSING_PAYMENT;
import static alfio.model.system.ConfigurationKeys.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PayUManagerTest {

    private static final String RESERVATION_ID = "res-1";
    private static final String BASE = "https://tickets.example.org";
    private static final String OLD_ORDER_ID = "OLDORDER";
    private static final String EXISTING_EXT_ORDER_ID = RESERVATION_ID + "-1";

    private HttpClient httpClient;
    private TransactionRepository transactionRepository;
    private TicketReservationRepository ticketReservationRepository;
    private Event event;
    private Map<ConfigurationKeys, ConfigurationManager.MaybeConfiguration> configuration;
    private PayUManager manager;
    private final List<String> orderBodies = new ArrayList<>();
    // answers POST /api/v2_1/orders (order creation)
    private ExistingOrderApi orderCreationApi = request -> new Reply(302,
        "{\"status\":{\"statusCode\":\"SUCCESS\"},\"redirectUri\":\"https://payu.example/pay\",\"orderId\":\"ORDER1\"}");
    // answers every PayU call except OAuth and order creation
    private ExistingOrderApi existingOrderApi = request -> new Reply(500, "");
    // answers the OAuth calls; the argument is the 1-based number of the OAuth call
    private OAuthApi oauthApi = count -> "{\"access_token\":\"tok\",\"expires_in\":43199}";
    private int oauthCalls = 0;
    private ConfigurationManager configurationManager;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void init() throws Exception {
        httpClient = mock(HttpClient.class);
        transactionRepository = mock(TransactionRepository.class);
        ticketReservationRepository = mock(TicketReservationRepository.class);
        configurationManager = mock(ConfigurationManager.class);
        event = mock(Event.class);
        when(event.getConfigurationLevel()).thenReturn(ConfigurationLevel.organization(1));
        when(event.getCurrency()).thenReturn("PLN");
        when(event.getType()).thenReturn(PurchaseContext.PurchaseContextType.event);
        when(event.getPublicIdentifier()).thenReturn("reunion");
        when(event.getZoneId()).thenReturn(ZoneId.of("Europe/Warsaw"));
        when(event.getDisplayName()).thenReturn("Reunion");
        configuration = new EnumMap<>(ConfigurationKeys.class);
        configuration.put(PAYU_ENABLED, existing(PAYU_ENABLED, "true"));
        configuration.put(PAYU_LIVE_MODE, existing(PAYU_LIVE_MODE, "false"));
        configuration.put(PAYU_POS_ID, existing(PAYU_POS_ID, "300746"));
        configuration.put(PAYU_CLIENT_ID, existing(PAYU_CLIENT_ID, "300746"));
        configuration.put(PAYU_CLIENT_SECRET, existing(PAYU_CLIENT_SECRET, "secret"));
        configuration.put(PAYU_SECOND_KEY, existing(PAYU_SECOND_KEY, "key"));
        configuration.put(BASE_URL, existing(BASE_URL, BASE + "/"));
        // only the Set overload is stubbed: a single-key getFor(key, level) call would return null
        when(configurationManager.getFor(anySet(), any())).thenAnswer(inv -> {
            Set<ConfigurationKeys> keys = inv.getArgument(0);
            Map<ConfigurationKeys, ConfigurationManager.MaybeConfiguration> result = new EnumMap<>(ConfigurationKeys.class);
            keys.forEach(k -> result.put(k, configuration.getOrDefault(k, new ConfigurationManager.MaybeConfiguration(k))));
            return result;
        });
        when(transactionRepository.loadOptionalByStatusAndPaymentProxyForUpdate(RESERVATION_ID, Transaction.Status.PENDING, PaymentProxy.PAYU))
            .thenReturn(Optional.empty());
        when(httpClient.send(any(HttpRequest.class), any())).thenAnswer(inv -> {
            HttpRequest request = inv.getArgument(0);
            HttpResponse<String> response = mock(HttpResponse.class);
            if (request.uri().getPath().endsWith("/oauth/authorize")) {
                when(response.statusCode()).thenReturn(200);
                oauthCalls++;
                var oauthBody = oauthApi.answer(oauthCalls);
                when(response.body()).thenReturn(oauthBody);
            } else if ("POST".equals(request.method()) && request.uri().getPath().equals("/api/v2_1/orders")) {
                orderBodies.add(readBody(request));
                var reply = orderCreationApi.answer(request);
                when(response.statusCode()).thenReturn(reply.status());
                when(response.body()).thenReturn(reply.body());
            } else {
                var reply = existingOrderApi.answer(request);
                when(response.statusCode()).thenReturn(reply.status());
                when(response.body()).thenReturn(reply.body());
            }
            return response;
        });
        manager = new PayUManager(configurationManager, httpClient, ticketReservationRepository, transactionRepository, TestUtil.clockProvider());
    }

    @Test
    void notifyAndContinueUrlsUseTheTemplates() {
        var result = manager.doPayment(spec(new CustomerName("Jan Kowalski", "Jan", "Kowalski", true), Locale.ENGLISH));
        assertTrue(result.isRedirect());
        var body = lastOrderBody();
        assertEquals(BASE + "/api/payment/webhook/payu/reservation/" + RESERVATION_ID, body.get("notifyUrl").getAsString());
        assertEquals(BASE + "/event/reunion/reservation/" + RESERVATION_ID + "/payment/payu/return", body.get("continueUrl").getAsString());
        assertEquals("/{purchaseContextType}/{purchaseContextIdentifier}/reservation/{reservationId}/payment/payu/return", PayUManager.RETURN_URL_TEMPLATE);
    }

    @Test
    void customerIpFallsBackToLoopback() {
        manager.doPayment(spec(new CustomerName("Jan Kowalski", "Jan", "Kowalski", true), null));
        assertEquals("127.0.0.1", lastOrderBody().get("customerIp").getAsString());
    }

    @Test
    void orderRequestCarriesTheSpecificationCustomerIp() {
        var specification = spec(new CustomerName("Jan Kowalski", "Jan", "Kowalski", true), Locale.ENGLISH)
            .withCustomerIp("203.0.113.7");
        manager.doPayment(specification);
        assertEquals("203.0.113.7", lastOrderBody().get("customerIp").getAsString());
    }

    @Test
    void validityTimeIsRemainingReservationTimeWithFloor() {
        var now = Instant.now(TestUtil.clockProvider().getClock());
        // 20 minutes left
        stubReservationValidity(Date.from(now.plusSeconds(1200)));
        manager.doPayment(spec(null, null));
        assertEquals(1200, lastOrderBody().get("validityTime").getAsLong());

        // only 1 minute left: PayU gets at least 5 minutes
        stubReservationValidity(Date.from(now.plusSeconds(60)));
        manager.doPayment(spec(null, null));
        assertEquals(300, lastOrderBody().get("validityTime").getAsLong());
    }

    @Test
    void validityTimeFallsBackToReservationTimeoutWhenReservationIsMissing() {
        when(ticketReservationRepository.findReservationById(RESERVATION_ID)).thenReturn(null);
        manager.doPayment(spec(null, null));
        assertEquals(25 * 60, lastOrderBody().get("validityTime").getAsLong());

        configuration.put(RESERVATION_TIMEOUT, existing(RESERVATION_TIMEOUT, "40"));
        manager.doPayment(spec(null, null));
        assertEquals(40 * 60, lastOrderBody().get("validityTime").getAsLong());
    }

    @Test
    void blankBuyerNamesAreOmitted() {
        manager.doPayment(spec(new CustomerName("Kowalski", " ", "", false, false), null));
        var buyer = lastOrderBody().getAsJsonObject("buyer");
        assertEquals("buyer@example.org", buyer.get("email").getAsString());
        assertFalse(buyer.has("firstName"));
        assertFalse(buyer.has("lastName"));

        manager.doPayment(spec(new CustomerName("Jan Kowalski", "Jan", "Kowalski", true), null));
        buyer = lastOrderBody().getAsJsonObject("buyer");
        assertEquals("Jan", buyer.get("firstName").getAsString());
        assertEquals("Kowalski", buyer.get("lastName").getAsString());
    }

    @Test
    void buyerLanguageIsOmittedWhenNotSupportedByPayU() {
        manager.doPayment(spec(null, Locale.forLanguageTag("ja")));
        assertFalse(lastOrderBody().getAsJsonObject("buyer").has("language"));

        manager.doPayment(spec(null, Locale.forLanguageTag("pl")));
        assertEquals("pl", lastOrderBody().getAsJsonObject("buyer").get("language").getAsString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void orderCreationUsesUniqueExtOrderIdAndExtendsReservationValidity() {
        var now = Instant.now(TestUtil.clockProvider().getClock());
        stubReservationValidity(Date.from(now.plusSeconds(1200)));

        var result = manager.doPayment(spec(null, null));
        assertTrue(result.isRedirect());
        assertEquals("https://payu.example/pay", result.getRedirectUrl());
        var firstExtOrderId = lastOrderBody().get("extOrderId").getAsString();
        manager.doPayment(spec(null, null));
        var secondExtOrderId = lastOrderBody().get("extOrderId").getAsString();

        assertTrue(firstExtOrderId.matches(RESERVATION_ID + "-[0-9a-f]{8}"), firstExtOrderId);
        assertTrue(secondExtOrderId.matches(RESERVATION_ID + "-[0-9a-f]{8}"), secondExtOrderId);
        assertNotEquals(firstExtOrderId, secondExtOrderId, "extOrderId must not collide under a fixed clock");

        var order = inOrder(ticketReservationRepository, transactionRepository);
        order.verify(ticketReservationRepository).updateReservationStatus(RESERVATION_ID, EXTERNAL_PROCESSING_PAYMENT.toString());
        order.verify(ticketReservationRepository).updateValidity(RESERVATION_ID, Date.from(now.plusSeconds(1200 + 300)));
        order.verify(transactionRepository).invalidateForReservation(eq(RESERVATION_ID), any());
        ArgumentCaptor<Map<String, String>> metadata = ArgumentCaptor.forClass(Map.class);
        order.verify(transactionRepository).insert(eq("ORDER1"), eq("ORDER1"), eq(RESERVATION_ID), any(), eq(1000), eq("PLN"),
            anyString(), eq(PaymentProxy.PAYU.name()), eq(0L), eq(0L), eq(Transaction.Status.PENDING), metadata.capture());
        assertEquals(firstExtOrderId, metadata.getValue().get("extOrderId"));
    }

    // --- re-confirm with an existing PENDING transaction (spec §2) ---

    @Test
    void reconfirmCompletedOrderMarksTransactionComplete() throws Exception {
        givenExistingTransaction();
        existingOrderApi = request -> orderLookup("COMPLETED", "1000", "PLN", EXISTING_EXT_ORDER_ID);

        var result = manager.doPayment(spec(null, null));

        assertEquals(PaymentResult.successful(OLD_ORDER_ID), result);
        verify(transactionRepository).update(eq(5), eq(OLD_ORDER_ID), eq(OLD_ORDER_ID), any(), eq(0L), eq(0L),
            eq(Transaction.Status.COMPLETE), any());
        assertFalse(orderCreationAttempted());
    }

    @Test
    void reconfirmCompletedOrderWithMismatchStaysPending() throws Exception {
        givenExistingTransaction();
        var mismatches = List.of(
            new String[] {"999", "PLN", EXISTING_EXT_ORDER_ID},
            new String[] {"1000", "EUR", EXISTING_EXT_ORDER_ID},
            new String[] {"1000", "PLN", RESERVATION_ID + "-other"});
        for (var mismatch : mismatches) {
            existingOrderApi = request -> orderLookup("COMPLETED", mismatch[0], mismatch[1], mismatch[2]);
            var result = manager.doPayment(spec(null, null));
            assertEquals(PaymentResult.pending(OLD_ORDER_ID), result, String.join("/", mismatch));
        }
        verify(transactionRepository, never()).update(anyInt(), any(), any(), any(), anyLong(), anyLong(), any(), any());
        assertFalse(orderCreationAttempted());
    }

    @Test
    void reconfirmCanceledOrderCreatesNewOrder() throws Exception {
        givenExistingTransaction();
        existingOrderApi = request -> orderLookup("CANCELED", "1000", "PLN", EXISTING_EXT_ORDER_ID);

        var result = manager.doPayment(spec(null, null));

        assertTrue(result.isRedirect());
        assertTrue(orderCreationAttempted());
        verify(transactionRepository).invalidateForReservation(eq(RESERVATION_ID), any());
    }

    @Test
    void reconfirmNotFoundOrderCreatesNewOrder() throws Exception {
        givenExistingTransaction();
        existingOrderApi = request -> new Reply(404, "{\"status\":{\"statusCode\":\"DATA_NOT_FOUND\"}}");

        var result = manager.doPayment(spec(null, null));

        assertTrue(result.isRedirect());
        assertTrue(orderCreationAttempted());
    }

    @Test
    void reconfirmReadErrorKeepsTransactionAndStaysPending() throws Exception {
        givenExistingTransaction();
        existingOrderApi = request -> new Reply(503, "Service Unavailable");
        assertEquals(PaymentResult.pending(OLD_ORDER_ID), manager.doPayment(spec(null, null)));

        existingOrderApi = request -> {
            throw new HttpTimeoutException("request timed out");
        };
        assertEquals(PaymentResult.pending(OLD_ORDER_ID), manager.doPayment(spec(null, null)));

        assertFalse(orderCreationAttempted());
        verify(transactionRepository, never()).invalidateForReservation(any(), any());
        verify(transactionRepository, never()).invalidateById(anyInt());
    }

    @Test
    void reconfirmNewOrderThatCannotBeCancelledStaysPending() throws Exception {
        givenExistingTransaction();
        existingOrderApi = request -> {
            if ("DELETE".equals(request.method())) {
                return new Reply(400, "{\"status\":{\"statusCode\":\"ERROR_ORDER_NOT_CANCELLABLE\"}}");
            }
            // the re-read after the failed cancel still shows NEW
            return orderLookup("NEW", "1000", "PLN", EXISTING_EXT_ORDER_ID);
        };

        var result = manager.doPayment(spec(null, null));

        assertEquals(PaymentResult.pending(OLD_ORDER_ID), result);
        assertFalse(orderCreationAttempted());
        assertTrue(requestSent("DELETE", "/api/v2_1/orders/" + OLD_ORDER_ID));
    }

    @Test
    void reconfirmUnknownOrderStatusStaysPending() throws Exception {
        givenExistingTransaction();
        existingOrderApi = request -> orderLookup("SOMETHING_NEW", "1000", "PLN", EXISTING_EXT_ORDER_ID);

        assertEquals(PaymentResult.pending(OLD_ORDER_ID), manager.doPayment(spec(null, null)));
        assertFalse(orderCreationAttempted());
        verify(transactionRepository, never()).update(anyInt(), any(), any(), any(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void reconfirmWaitingOrderWhoseCaptureFailsStaysPending() throws Exception {
        givenExistingTransaction();
        var captureReplies = new ArrayList<ExistingOrderApi>();
        captureReplies.add(request -> new Reply(500, "{\"status\":{\"statusCode\":\"ERROR_INTERNAL\"}}"));
        captureReplies.add(request -> {
            throw new HttpTimeoutException("request timed out");
        });
        for (var captureReply : captureReplies) {
            existingOrderApi = request -> {
                if ("PUT".equals(request.method())) {
                    return captureReply.answer(request);
                }
                return orderLookup("WAITING_FOR_CONFIRMATION", "1000", "PLN", EXISTING_EXT_ORDER_ID);
            };
            // the authorized money must not be orphaned by a second order, whatever the capture outcome
            assertEquals(PaymentResult.pending(OLD_ORDER_ID), manager.doPayment(spec(null, null)));
        }
        assertEquals(2, requestCount("PUT", "/api/v2_1/orders/" + OLD_ORDER_ID + "/status"));
        assertFalse(orderCreationAttempted());
        verify(transactionRepository, never()).invalidateForReservation(any(), any());
        verify(transactionRepository, never()).update(anyInt(), any(), any(), any(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void reconfirmNewOrderRetriesDeleteOnceAfterUnauthorized() throws Exception {
        givenExistingTransaction();
        oauthApi = count -> "{\"access_token\":\"tok" + count + "\",\"expires_in\":43199}";
        var deletes = new int[] {0};
        existingOrderApi = request -> {
            if ("DELETE".equals(request.method())) {
                deletes[0]++;
                if (deletes[0] == 1) {
                    return new Reply(401, "{\"error\":\"invalid_token\"}");
                }
                return new Reply(200, "{\"status\":{\"statusCode\":\"SUCCESS\"}}");
            }
            return orderLookup("NEW", "1000", "PLN", EXISTING_EXT_ORDER_ID);
        };

        var result = manager.doPayment(spec(null, null));

        assertTrue(result.isRedirect());
        assertEquals(2, requestCount("DELETE", "/api/v2_1/orders/" + OLD_ORDER_ID));
        assertEquals(2, requestCount("POST", "/pl/standard/user/oauth/authorize"));
        assertEquals(1, requestCount("POST", "/api/v2_1/orders"));
    }

    @Test
    void reconfirmNewOrderWhoseDeleteTimesOutDependsOnTheReRead() throws Exception {
        givenExistingTransaction();
        var reads = new int[] {0};
        var reReadAfterTimeout = new Reply[] { orderLookup("CANCELED", "1000", "PLN", EXISTING_EXT_ORDER_ID) };
        existingOrderApi = request -> {
            if ("DELETE".equals(request.method())) {
                throw new HttpTimeoutException("request timed out");
            }
            reads[0]++;
            if (reads[0] == 1) {
                return orderLookup("NEW", "1000", "PLN", EXISTING_EXT_ORDER_ID);
            }
            return reReadAfterTimeout[0];
        };

        // the re-read shows the order CANCELED: the DELETE went through, a new order can be created
        assertTrue(manager.doPayment(spec(null, null)).isRedirect());
        assertEquals(1, requestCount("POST", "/api/v2_1/orders"));

        // the re-read fails: the old order may still be payable, no new order
        clearInvocations(httpClient);
        reads[0] = 0;
        reReadAfterTimeout[0] = new Reply(503, "Service Unavailable");
        assertEquals(PaymentResult.pending(OLD_ORDER_ID), manager.doPayment(spec(null, null)));
        assertTrue(requestSent("DELETE", "/api/v2_1/orders/" + OLD_ORDER_ID));
        assertFalse(orderCreationAttempted());
    }

    @Test
    void orderCreationFailureIsFailedAndRecordsNothing() throws Exception {
        var creationReplies = new ArrayList<ExistingOrderApi>();
        // POST timed out: PayU may have created an order, but the buyer never gets its redirectUri (spec L9)
        creationReplies.add(request -> {
            throw new HttpTimeoutException("request timed out");
        });
        creationReplies.add(request -> new Reply(400, "{\"status\":{\"statusCode\":\"ERROR_VALUE_INVALID\"}}"));
        for (var creationReply : creationReplies) {
            orderCreationApi = creationReply;

            var result = manager.doPayment(spec(null, null));

            assertTrue(result.isFailed());
            assertEquals(Optional.of("error.STEP2_PAYU_unexpected"), result.getErrorCode());
        }
        assertEquals(2, requestCount("POST", "/api/v2_1/orders"));
        verify(ticketReservationRepository, never()).updateReservationStatus(any(), any());
        verify(ticketReservationRepository, never()).updateValidity(any(), any());
        verify(transactionRepository, never()).invalidateForReservation(any(), any());
        verify(transactionRepository, never()).insert(any(), any(), any(), any(), anyInt(), any(), any(), any(), anyLong(), anyLong(), any(), any());
    }

    // --- status mapping: webhook and force-check (spec §3) ---

    @Test
    void completedOrderWithMatchCompletesTransaction() throws Exception {
        var transaction = givenExistingTransaction();
        existingOrderApi = request -> orderLookup("COMPLETED", "1000", "PLN", EXISTING_EXT_ORDER_ID);

        var result = manager.processWebhook(webhookPayload("COMPLETED"), transaction, new PaymentContext(event));

        assertTrue(result.isSuccessful());
        assertEquals(OLD_ORDER_ID, result.getPaymentToken().getToken());
        verify(transactionRepository).update(eq(5), eq(OLD_ORDER_ID), eq(OLD_ORDER_ID), any(), eq(0L), eq(0L),
            eq(Transaction.Status.COMPLETE), any());
    }

    @Test
    void completedOrderWithMismatchIsAnError() throws Exception {
        var transaction = givenExistingTransaction();
        existingOrderApi = request -> orderLookup("COMPLETED", "999", "PLN", EXISTING_EXT_ORDER_ID);

        var webhook = manager.processWebhook(webhookPayload("COMPLETED"), transaction, new PaymentContext(event));
        var forced = manager.forceTransactionCheck(null, transaction, new PaymentContext(event));

        assertEquals(PaymentWebhookResult.Type.ERROR, webhook.getType());
        assertEquals(PaymentWebhookResult.Type.ERROR, forced.getType());
        verify(transactionRepository, never()).update(anyInt(), any(), any(), any(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void canceledOrderInvalidatesTransactionAndExtendsReservation() throws Exception {
        var transaction = givenExistingTransaction();
        existingOrderApi = request -> orderLookup("CANCELED", "1000", "PLN", EXISTING_EXT_ORDER_ID);
        var now = Instant.now(TestUtil.clockProvider().getClock());

        var result = manager.processWebhook(webhookPayload("CANCELED"), transaction, new PaymentContext(event));

        assertEquals(PaymentWebhookResult.Type.CANCELLED, result.getType());
        verify(transactionRepository).invalidateById(5);
        verify(ticketReservationRepository).updateValidity(RESERVATION_ID, Date.from(now.plusSeconds(25 * 60)));
    }

    @Test
    void waitingForConfirmationIsCapturedAndPending() throws Exception {
        var transaction = givenExistingTransaction();
        existingOrderApi = request -> {
            if ("PUT".equals(request.method())) {
                return new Reply(200, "{\"status\":{\"statusCode\":\"SUCCESS\"}}");
            }
            return orderLookup("WAITING_FOR_CONFIRMATION", "1000", "PLN", EXISTING_EXT_ORDER_ID);
        };

        var result = manager.forceTransactionCheck(null, transaction, new PaymentContext(event));

        assertEquals(PaymentWebhookResult.pending().getType(), result.getType());
        assertNull(result.getPaymentToken());
        assertTrue(requestSent("PUT", "/api/v2_1/orders/" + OLD_ORDER_ID + "/status"));
        verify(transactionRepository, never()).update(anyInt(), any(), any(), any(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void newOrderIsNotRelevantForWebhookAndAnErrorForForceCheck() throws Exception {
        var transaction = givenExistingTransaction();
        existingOrderApi = request -> orderLookup("NEW", "1000", "PLN", EXISTING_EXT_ORDER_ID);

        var webhook = manager.processWebhook(webhookPayload("NEW"), transaction, new PaymentContext(event));
        var forced = manager.forceTransactionCheck(null, transaction, new PaymentContext(event));

        assertEquals(PaymentWebhookResult.Type.NOT_RELEVANT, webhook.getType());
        assertEquals(PaymentWebhookResult.Type.ERROR, forced.getType());
        assertEquals("PayU order not paid yet", forced.getReason());
        assertFalse(requestSent("DELETE", "/api/v2_1/orders/" + OLD_ORDER_ID));
    }

    @Test
    void unknownOrderStatusIsPending() throws Exception {
        var transaction = givenExistingTransaction();
        existingOrderApi = request -> orderLookup("SOMETHING_NEW", "1000", "PLN", EXISTING_EXT_ORDER_ID);

        var webhook = manager.processWebhook(webhookPayload("SOMETHING_NEW"), transaction, new PaymentContext(event));
        var forced = manager.forceTransactionCheck(null, transaction, new PaymentContext(event));

        assertEquals(PaymentWebhookResult.pending().getType(), webhook.getType());
        assertEquals(PaymentWebhookResult.pending().getType(), forced.getType());
        verify(transactionRepository, never()).update(anyInt(), any(), any(), any(), anyLong(), anyLong(), any(), any());
        verify(transactionRepository, never()).invalidateById(anyInt());
    }

    @Test
    void unreadableOrderIsAnErrorAndChangesNothing() throws Exception {
        var transaction = givenExistingTransaction();
        var replies = List.of(
            new Reply(404, "{\"status\":{\"statusCode\":\"DATA_NOT_FOUND\"}}"),
            new Reply(200, "{\"orders\":[],\"status\":{\"statusCode\":\"SUCCESS\"}}"),
            new Reply(200, "not json"));
        for (var reply : replies) {
            existingOrderApi = request -> reply;

            var webhook = manager.processWebhook(webhookPayload("COMPLETED"), transaction, new PaymentContext(event));
            var forced = manager.forceTransactionCheck(null, transaction, new PaymentContext(event));

            // ERROR: the webhook answers 500 (PayU retries) and the force-check is a no-op, never a cancellation
            assertEquals(PaymentWebhookResult.Type.ERROR, webhook.getType(), reply.body());
            assertEquals(PaymentWebhookResult.Type.ERROR, forced.getType(), reply.body());
        }
        verify(transactionRepository, never()).invalidateById(anyInt());
        verify(transactionRepository, never()).update(anyInt(), any(), any(), any(), anyLong(), anyLong(), any(), any());
        verify(ticketReservationRepository, never()).updateValidity(any(), any());
        assertFalse(requestSent("DELETE", "/api/v2_1/orders/" + OLD_ORDER_ID));
        assertFalse(requestSent("PUT", "/api/v2_1/orders/" + OLD_ORDER_ID + "/status"));
    }

    // --- server-initiated transaction contract (spec §1) ---

    @ParameterizedTest
    @CsvSource({
        "NEW, true, true",
        "PENDING, true, true",
        "COMPLETED, false, false",
        "WAITING_FOR_CONFIRMATION, false, false",
        "READ_ERROR, false, false",
        "CANCELED, true, false",
        "NOT_FOUND, true, false"
    })
    void discardTransactionFollowsOrderState(String state, boolean expectedResult, boolean deleteExpected) throws Exception {
        var transaction = givenExistingTransaction();
        existingOrderApi = request -> {
            if ("DELETE".equals(request.method())) {
                return new Reply(200, "{\"status\":{\"statusCode\":\"SUCCESS\"}}");
            }
            if ("READ_ERROR".equals(state)) {
                return new Reply(503, "Service Unavailable");
            }
            if ("NOT_FOUND".equals(state)) {
                return new Reply(404, "{\"status\":{\"statusCode\":\"DATA_NOT_FOUND\"}}");
            }
            return orderLookup(state, "1000", "PLN", EXISTING_EXT_ORDER_ID);
        };

        assertEquals(expectedResult, manager.discardTransaction(transaction, event), state);
        assertEquals(deleteExpected, requestSent("DELETE", "/api/v2_1/orders/" + OLD_ORDER_ID), state);
    }

    @Test
    void discardTransactionIsFalseWhenDeleteIsRefused() throws Exception {
        var transaction = givenExistingTransaction();
        existingOrderApi = request -> {
            if ("DELETE".equals(request.method())) {
                return new Reply(400, "{\"status\":{\"statusCode\":\"ERROR_ORDER_NOT_CANCELLABLE\"}}");
            }
            return orderLookup("PENDING", "1000", "PLN", EXISTING_EXT_ORDER_ID);
        };

        assertFalse(manager.discardTransaction(transaction, event));
        assertTrue(requestSent("DELETE", "/api/v2_1/orders/" + OLD_ORDER_ID));
    }

    @Test
    void initTransactionIsNotSupported() {
        var token = manager.initTransaction(spec(null, null), Map.of());

        assertEquals("PayU does not support server-initiated transactions", token.getErrorMessage());
        assertTrue(token.isReservationStatusChanged());
        assertEquals(PaymentProxy.PAYU, token.getPaymentProvider());
        verifyNoInteractions(httpClient);
    }

    // --- notification parsing (spec §1 parseTransactionPayload, B5) ---

    @Test
    void notificationWithInvalidSignatureIsDiscarded() {
        var body = orderNotification(OLD_ORDER_ID, EXISTING_EXT_ORDER_ID, "COMPLETED");

        var payload = manager.parseTransactionPayload(body, signatureHeader(body + "wrong"),
            Map.of("reservationId", RESERVATION_ID), new PaymentContext(event));

        assertTrue(payload.isEmpty());
    }

    @Test
    void notificationWithForeignExtOrderIdIsUnrecognizedAndNotRelevant() {
        var transaction = givenExistingTransaction();
        var body = orderNotification(OLD_ORDER_ID, "other-reservation-1", "COMPLETED");

        var payload = parse(body);

        assertTrue(payload.isPresent());
        assertEquals("payu-unrecognized", payload.get().getType());
        assertEquals(RESERVATION_ID, payload.get().getReservationId());
        var result = manager.processWebhook(payload.get(), transaction, new PaymentContext(event));
        assertEquals(PaymentWebhookResult.Type.NOT_RELEVANT, result.getType());
        verifyNoInteractions(httpClient);
    }

    @Test
    void refundNotificationIsNotRelevant() {
        var transaction = givenExistingTransaction();
        var body = "{\"orderId\":\"" + OLD_ORDER_ID + "\",\"extOrderId\":\"" + EXISTING_EXT_ORDER_ID
            + "\",\"refund\":{\"refundId\":\"R1\",\"amount\":\"1000\",\"currencyCode\":\"PLN\",\"status\":\"FINALIZED\"}}";

        var payload = parse(body);

        assertTrue(payload.isPresent());
        assertEquals("payu-refund", payload.get().getType());
        assertEquals(OLD_ORDER_ID, ((PayUWebhookPayload) payload.get()).getOrderId());
        var result = manager.processWebhook(payload.get(), transaction, new PaymentContext(event));
        assertEquals(PaymentWebhookResult.Type.NOT_RELEVANT, result.getType());
        verifyNoInteractions(httpClient);
    }

    @Test
    void notificationForDifferentOrderIsNotRelevant() {
        var transaction = givenExistingTransaction();
        var body = orderNotification("ANOTHERORDER", EXISTING_EXT_ORDER_ID, "COMPLETED");

        var payload = parse(body);

        assertTrue(payload.isPresent());
        assertEquals("payu-COMPLETED", payload.get().getType());
        var result = manager.processWebhook(payload.get(), transaction, new PaymentContext(event));
        assertEquals(PaymentWebhookResult.Type.NOT_RELEVANT, result.getType());
        verifyNoInteractions(httpClient);
    }

    @Test
    void notificationWithoutStatusIsNullSafe() {
        var body = "{\"order\":{\"orderId\":\"" + OLD_ORDER_ID + "\",\"extOrderId\":\"" + EXISTING_EXT_ORDER_ID + "\"}}";

        var payload = assertDoesNotThrow(() -> parse(body));

        assertTrue(payload.isPresent());
        assertNotEquals("payu-null", payload.get().getType());
        assertDoesNotThrow(() -> payload.get().getStatus());
        assertEquals(OLD_ORDER_ID, ((PayUWebhookPayload) payload.get()).getOrderId());
    }

    // --- hardening: token cache, timeouts, endpoint override, currency gating ---

    @Test
    void accessTokenIsCachedAcrossCalls() {
        manager.doPayment(spec(null, null));
        manager.doPayment(spec(null, null));

        assertEquals(1, requestCount("POST", "/pl/standard/user/oauth/authorize"));
        assertEquals(2, requestCount("POST", "/api/v2_1/orders"));
    }

    @Test
    void differentClientSecretRequestsNewToken() {
        manager.doPayment(spec(null, null));
        configuration.put(PAYU_CLIENT_SECRET, existing(PAYU_CLIENT_SECRET, "another-secret"));
        manager.doPayment(spec(null, null));

        assertEquals(2, requestCount("POST", "/pl/standard/user/oauth/authorize"));
    }

    @Test
    void unauthorizedResponseRefreshesTokenAndRetriesOnce() throws Exception {
        var transaction = givenExistingTransaction();
        oauthApi = count -> "{\"access_token\":\"tok" + count + "\",\"expires_in\":43199}";
        var reads = new int[] {0};
        existingOrderApi = request -> {
            reads[0]++;
            if (reads[0] == 1) {
                return new Reply(401, "{\"error\":\"invalid_token\"}");
            }
            return orderLookup("PENDING", "1000", "PLN", EXISTING_EXT_ORDER_ID);
        };

        var result = manager.forceTransactionCheck(null, transaction, new PaymentContext(event));

        assertEquals(PaymentWebhookResult.pending().getType(), result.getType());
        assertEquals(2, requestCount("POST", "/pl/standard/user/oauth/authorize"));
        var reads2 = sentRequests().stream()
            .filter(r -> "GET".equals(r.method()) && r.uri().getPath().equals("/api/v2_1/orders/" + OLD_ORDER_ID))
            .toList();
        assertEquals(2, reads2.size());
        assertEquals("Bearer tok1", reads2.get(0).headers().firstValue("Authorization").orElseThrow());
        assertEquals("Bearer tok2", reads2.get(1).headers().firstValue("Authorization").orElseThrow());

        // a second 401 is not retried again: the read is an ERROR
        existingOrderApi = request -> new Reply(401, "{\"error\":\"invalid_token\"}");
        var failed = manager.forceTransactionCheck(null, transaction, new PaymentContext(event));
        assertEquals(PaymentWebhookResult.Type.ERROR, failed.getType());
        assertEquals(3, requestCount("POST", "/pl/standard/user/oauth/authorize"));
    }

    @Test
    void missingExpiresInDefaultsToFiveMinutes() {
        var start = Instant.now(TestUtil.clockProvider().getClock());
        var now = new Instant[] {start};
        var clockProvider = mock(ClockProvider.class);
        when(clockProvider.getClock()).thenAnswer(inv -> Clock.fixed(now[0], ZoneId.of("UTC")));
        when(clockProvider.withZone(any())).thenAnswer(inv -> Clock.fixed(now[0], inv.getArgument(0)));
        manager = new PayUManager(configurationManager, httpClient, ticketReservationRepository, transactionRepository, clockProvider);
        oauthApi = count -> "{\"access_token\":\"tok" + count + "\"}";

        assertTrue(manager.doPayment(spec(null, null)).isRedirect());
        // 300 s lifetime, renewed 60 s before it expires
        now[0] = start.plusSeconds(230);
        assertTrue(manager.doPayment(spec(null, null)).isRedirect());
        assertEquals(1, requestCount("POST", "/pl/standard/user/oauth/authorize"));

        now[0] = start.plusSeconds(250);
        assertTrue(manager.doPayment(spec(null, null)).isRedirect());
        assertEquals(2, requestCount("POST", "/pl/standard/user/oauth/authorize"));
    }

    @Test
    void everyRequestHasTenSecondTimeout() throws Exception {
        var transaction = givenExistingTransaction();
        existingOrderApi = request -> {
            if (!"GET".equals(request.method())) {
                return new Reply(200, "{\"status\":{\"statusCode\":\"SUCCESS\"}}");
            }
            return orderLookup("NEW", "1000", "PLN", EXISTING_EXT_ORDER_ID);
        };
        // OAuth, GET, DELETE, POST order
        assertTrue(manager.doPayment(spec(null, null)).isRedirect());
        // PUT capture, then a refund POST
        existingOrderApi = request -> {
            if (!"GET".equals(request.method())) {
                return new Reply(200, "{\"status\":{\"statusCode\":\"SUCCESS\"}}");
            }
            return orderLookup("WAITING_FOR_CONFIRMATION", "1000", "PLN", EXISTING_EXT_ORDER_ID);
        };
        manager.forceTransactionCheck(null, transaction, new PaymentContext(event));
        manager.refund(transaction, event, null);

        var requests = sentRequests();
        var methods = requests.stream().map(HttpRequest::method).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("GET", "POST", "PUT", "DELETE"), methods);
        assertTrue(requests.stream().anyMatch(r -> r.uri().getPath().endsWith("/oauth/authorize")));
        for (var request : requests) {
            assertEquals(Optional.of(Duration.ofSeconds(10)), request.timeout(), request.method() + " " + request.uri());
        }
    }

    @Test
    void endpointOverrideReplacesPayUEndpoints() throws Exception {
        var transaction = givenExistingTransaction();
        existingOrderApi = request -> orderLookup("CANCELED", "1000", "PLN", EXISTING_EXT_ORDER_ID);
        for (var liveMode : List.of("false", "true")) {
            configuration.put(PAYU_LIVE_MODE, existing(PAYU_LIVE_MODE, liveMode));
            manager = new PayUManager(configurationManager, httpClient, ticketReservationRepository, transactionRepository,
                TestUtil.clockProvider(), "http://localhost:1234/");
            assertTrue(manager.doPayment(spec(null, null)).isRedirect());
            manager.discardTransaction(transaction, event);
        }

        var requests = sentRequests();
        assertFalse(requests.isEmpty());
        for (var request : requests) {
            assertTrue(request.uri().toString().startsWith("http://localhost:1234/"), request.uri().toString());
            assertFalse(request.uri().toString().startsWith("http://localhost:1234//"), request.uri().toString());
        }
    }

    @Test
    void isActiveGatesOnSupportedCurrencies() {
        assertTrue(manager.isActive(new PaymentContext(event)));

        when(event.getCurrency()).thenReturn("JPY");
        assertFalse(manager.isActive(new PaymentContext(event)));

        // no purchase context (system-level listing): no gating
        assertTrue(manager.isActive(new PaymentContext()));
    }

    @Test
    void refundUsesDeterministicExtRefundIdAndAmountOnlyWhenPartial() throws Exception {
        var transaction = givenExistingTransaction();
        List<JsonObject> refundBodies = new ArrayList<>();
        var listing = new String[] { refundListing(refundEntry("a", "100", "FINALIZED"), refundEntry("b", "200", "PENDING")) };
        existingOrderApi = request -> {
            if (!request.uri().getPath().equals(REFUNDS_PATH)) {
                return new Reply(500, "");
            }
            if ("POST".equals(request.method())) {
                refundBodies.add(JsonParser.parseString(readBody(request)).getAsJsonObject().getAsJsonObject("refund"));
                return new Reply(200, "{\"orderId\":\"" + OLD_ORDER_ID + "\",\"refund\":{},\"status\":{\"statusCode\":\"SUCCESS\"}}");
            }
            return new Reply(200, listing[0]);
        };

        // partial refund, 2 refunds already listed
        assertTrue(manager.refund(transaction, event, 400));
        assertEquals(RESERVATION_ID + "-refund-400-3", refundBodies.get(0).get("extRefundId").getAsString());
        assertEquals("400", refundBodies.get(0).get("amount").getAsString());

        // full refund, no refund yet: the amount is the transaction price and is not sent
        listing[0] = refundListing();
        assertTrue(manager.refund(transaction, event, null));
        assertEquals(RESERVATION_ID + "-refund-1000-1", refundBodies.get(1).get("extRefundId").getAsString());
        assertFalse(refundBodies.get(1).has("amount"));
    }

    @Test
    void refundTimeoutIsResolvedByReReadingRefunds() throws Exception {
        var transaction = givenExistingTransaction();
        var expectedId = RESERVATION_ID + "-refund-400-1";
        var reReadListing = new String[] { refundListing(refundEntry(expectedId, "400", "PENDING")) };
        int[] gets = { 0 };
        existingOrderApi = request -> {
            if (!request.uri().getPath().equals(REFUNDS_PATH)) {
                return new Reply(500, "");
            }
            if ("POST".equals(request.method())) {
                throw new HttpTimeoutException("request timed out");
            }
            gets[0]++;
            if (gets[0] == 1) {
                return new Reply(200, refundListing());
            }
            return new Reply(200, reReadListing[0]);
        };

        // the refund reached PayU although the answer timed out: success, and the POST is not repeated
        assertTrue(manager.refund(transaction, event, 400));
        assertEquals(1, requestCount("POST", REFUNDS_PATH));

        // the re-read does not list the refund: failure
        gets[0] = 0;
        reReadListing[0] = refundListing();
        assertFalse(manager.refund(transaction, event, 400));

        // the initial GET fails: false, and nothing is posted
        clearInvocations(httpClient);
        existingOrderApi = request -> new Reply(503, "Service Unavailable");
        assertFalse(manager.refund(transaction, event, 400));
        assertEquals(0, requestCount("POST", REFUNDS_PATH));
    }

    @Test
    void getInfoSumsFinalizedRefunds() throws Exception {
        var transaction = givenExistingTransaction();
        var listing = new String[] { refundListing(refundEntry("a", "300", "FINALIZED"), refundEntry("b", "200", "FINALIZED"),
            refundEntry("c", "100", "PENDING"), refundEntry("d", "50", "CANCELED")) };
        existingOrderApi = request -> {
            if (request.uri().getPath().equals(REFUNDS_PATH)) {
                return new Reply(200, listing[0]);
            }
            return orderLookup("COMPLETED", "1000", "PLN", EXISTING_EXT_ORDER_ID);
        };

        var info = manager.getInfo(transaction, event).orElseThrow();
        assertEquals("10.00", info.getPaidAmount());
        assertEquals("5.00", info.getRefundedAmount());
        assertNull(info.getFee());
        assertNull(info.getPlatformFee());

        listing[0] = refundListing(refundEntry("c", "100", "PENDING"));
        var withoutFinalized = manager.getInfo(transaction, event).orElseThrow();
        assertEquals("10.00", withoutFinalized.getPaidAmount());
        assertNull(withoutFinalized.getRefundedAmount());
    }

    private static final String REFUNDS_PATH = "/api/v2_1/orders/" + OLD_ORDER_ID + "/refunds";

    private static String refundListing(String... entries) {
        return "{\"refunds\":[" + String.join(",", entries) + "]}";
    }

    private static String refundEntry(String extRefundId, String amount, String status) {
        return "{\"refundId\":\"R-" + extRefundId + "\",\"extRefundId\":\"" + extRefundId + "\",\"amount\":\"" + amount
            + "\",\"currencyCode\":\"PLN\",\"status\":\"" + status + "\"}";
    }

    private List<HttpRequest> sentRequests() {
        return mockingDetails(httpClient).getInvocations().stream()
            .filter(i -> i.getMethod().getName().equals("send"))
            .map(i -> (HttpRequest) i.getArgument(0))
            .toList();
    }

    private long requestCount(String method, String path) {
        return sentRequests().stream()
            .filter(r -> method.equals(r.method()) && r.uri().getPath().equals(path))
            .count();
    }

    @FunctionalInterface
    private interface OAuthApi {
        String answer(int count);
    }

    private Optional<TransactionWebhookPayload> parse(String body) {
        return manager.parseTransactionPayload(body, signatureHeader(body), Map.of("reservationId", RESERVATION_ID), new PaymentContext(event));
    }

    private static String orderNotification(String orderId, String extOrderId, String status) {
        return "{\"order\":{\"orderId\":\"" + orderId + "\",\"extOrderId\":\"" + extOrderId
            + "\",\"totalAmount\":\"1000\",\"currencyCode\":\"PLN\",\"status\":\"" + status + "\"}}";
    }

    // the second key configured in init() is "key"
    private static String signatureHeader(String body) {
        return "sender=checkout;signature=" + md5Hex(body + "key") + ";algorithm=MD5;content=DOCUMENT";
    }

    private static String md5Hex(String value) {
        try {
            var digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PayUWebhookPayload webhookPayload(String status) {
        return PayUWebhookPayload.order(RESERVATION_ID, OLD_ORDER_ID, status);
    }

    private Transaction givenExistingTransaction() {
        var transaction = mock(Transaction.class);
        when(transaction.getId()).thenReturn(5);
        when(transaction.getPaymentId()).thenReturn(OLD_ORDER_ID);
        when(transaction.getReservationId()).thenReturn(RESERVATION_ID);
        when(transaction.getPriceInCents()).thenReturn(1000);
        when(transaction.getCurrency()).thenReturn("PLN");
        when(transaction.getPaymentProxy()).thenReturn(PaymentProxy.PAYU);
        when(transaction.getMetadata()).thenReturn(Map.of("extOrderId", EXISTING_EXT_ORDER_ID));
        when(transactionRepository.loadOptionalByStatusAndPaymentProxyForUpdate(RESERVATION_ID, Transaction.Status.PENDING, PaymentProxy.PAYU))
            .thenReturn(Optional.of(transaction));
        return transaction;
    }

    private static Reply orderLookup(String status, String totalAmount, String currency, String extOrderId) {
        return new Reply(200, "{\"orders\":[{\"orderId\":\"" + OLD_ORDER_ID + "\",\"extOrderId\":\"" + extOrderId
            + "\",\"totalAmount\":\"" + totalAmount + "\",\"currencyCode\":\"" + currency + "\",\"status\":\"" + status
            + "\"}],\"status\":{\"statusCode\":\"SUCCESS\"}}");
    }

    private boolean orderCreationAttempted() {
        return requestSent("POST", "/api/v2_1/orders");
    }

    private boolean requestSent(String method, String path) {
        return mockingDetails(httpClient).getInvocations().stream()
            .filter(i -> i.getMethod().getName().equals("send"))
            .map(i -> (HttpRequest) i.getArgument(0))
            .anyMatch(r -> method.equals(r.method()) && r.uri().getPath().equals(path));
    }

    @FunctionalInterface
    private interface ExistingOrderApi {
        Reply answer(HttpRequest request) throws Exception;
    }

    private record Reply(int status, String body) {
    }

    private void stubReservationValidity(Date validity) {
        var reservation = mock(TicketReservation.class);
        when(reservation.getValidity()).thenReturn(validity);
        when(ticketReservationRepository.findReservationById(RESERVATION_ID)).thenReturn(reservation);
    }

    private PaymentSpecification spec(CustomerName customerName, Locale locale) {
        return new PaymentSpecification(RESERVATION_ID, null, StaticPaymentMethods.PAYU, 1000, event, "buyer@example.org",
            customerName, null, null, locale, false, false, null, null, null, null, true, true);
    }

    private JsonObject lastOrderBody() {
        assertFalse(orderBodies.isEmpty(), "no order was sent to PayU");
        return JsonParser.parseString(orderBodies.get(orderBodies.size() - 1)).getAsJsonObject();
    }

    private static String readBody(HttpRequest request) throws Exception {
        var publisher = request.bodyPublisher().orElseThrow();
        var output = new ByteArrayOutputStream();
        var done = new CompletableFuture<Void>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                var bytes = new byte[item.remaining()];
                item.get(bytes);
                output.writeBytes(bytes);
            }

            @Override
            public void onError(Throwable throwable) {
                done.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                done.complete(null);
            }
        });
        done.get(5, TimeUnit.SECONDS);
        return output.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
