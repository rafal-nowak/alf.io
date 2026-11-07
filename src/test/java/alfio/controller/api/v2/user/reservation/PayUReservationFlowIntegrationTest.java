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
package alfio.controller.api.v2.user.reservation;

import alfio.TestConfiguration;
import alfio.config.DataSourceConfiguration;
import alfio.config.Initializer;
import alfio.controller.api.ControllerConfiguration;
import alfio.controller.api.v2.model.ReservationInfo;
import alfio.controller.api.v2.model.ReservationPaymentResult;
import alfio.controller.form.ContactAndTicketsForm;
import alfio.controller.form.PaymentForm;
import alfio.controller.form.ReservationForm;
import alfio.controller.form.UpdateTicketOwnerForm;
import alfio.controller.payment.PayUCallbackController;
import alfio.controller.payment.api.payu.PayUPaymentWebhookController;
import alfio.manager.payment.payu.PayUSignature;
import alfio.manager.support.response.ValidatedResponse;
import alfio.manager.user.UserManager;
import alfio.model.Event;
import alfio.model.PriceContainer;
import alfio.model.PurchaseContext.PurchaseContextType;
import alfio.model.TicketCategory;
import alfio.model.TicketReservation;
import alfio.model.metadata.AlfioMetadata;
import alfio.model.modification.DateTimeModification;
import alfio.model.modification.TicketCategoryModification;
import alfio.model.modification.TicketReservationModification;
import alfio.model.system.ConfigurationKeys;
import alfio.model.transaction.PaymentProxy;
import alfio.model.transaction.StaticPaymentMethods;
import alfio.model.transaction.Transaction;
import alfio.repository.TransactionRepository;
import alfio.repository.user.OrganizationRepository;
import alfio.test.util.AlfioIntegrationTest;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.context.request.ServletWebRequest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

import static alfio.manager.support.extension.ExtensionEvent.*;
import static alfio.test.util.IntegrationTestUtil.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Reservation flow paid with PayU. The PayU REST API is replaced by WireMock through the hidden property
 * {@code alfio.payu.endpoint-override}; notifications are signed with the test second key, exactly as PayU does.
 */
@AlfioIntegrationTest
@ContextConfiguration(classes = {DataSourceConfiguration.class, TestConfiguration.class, ControllerConfiguration.class})
@ActiveProfiles({Initializer.PROFILE_DEV, Initializer.PROFILE_DISABLE_JOBS, Initializer.PROFILE_INTEGRATION_TEST})
class PayUReservationFlowIntegrationTest extends BaseReservationFlowTest {

    private static final String SECOND_KEY = "test-second-key";
    private static final String ORDERS_PATH = "/api/v2_1/orders";
    private static final String REDIRECT_URI = "https://merch-prod.snd.payu.com/pay/?orderId=";
    private static final String ORDER_ID = "orderId";
    private static final String CANCELLATION_SCENARIO = "order cancellation";
    private static final String ORDER_CANCELLED_STATE = "cancelled";
    private static final WireMockServer PAYU = startPayUServer();

    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private UserManager userManager;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private PayUPaymentWebhookController payUPaymentWebhookController;
    @Autowired
    private PayUCallbackController payUCallbackController;

    private static WireMockServer startPayUServer() {
        var server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }

    @DynamicPropertySource
    static void payUEndpoint(DynamicPropertyRegistry registry) {
        registry.add("alfio.payu.endpoint-override", PAYU::baseUrl);
    }

    @AfterAll
    static void stopPayUServer() {
        PAYU.stop();
    }

    @BeforeEach
    void init() {
        PAYU.resetAll();
        configurationRepository.insert(ConfigurationKeys.PAYU_ENABLED.name(), "true", "");
        configurationRepository.insert(ConfigurationKeys.PAYU_POS_ID.name(), "300746", "");
        configurationRepository.insert(ConfigurationKeys.PAYU_CLIENT_ID.name(), "300746", "");
        configurationRepository.insert(ConfigurationKeys.PAYU_CLIENT_SECRET.name(), "test-client-secret", "");
        configurationRepository.insert(ConfigurationKeys.PAYU_SECOND_KEY.name(), SECOND_KEY, "");
    }

    @Test
    void payWithPayU() throws Exception {
        // the base flow checks that bank transfer is the only payment method offered: PayU is not listed in the
        // accepted payment proxies of this event, as for the Stripe flow test
        super.testBasicFlow(() -> {
            var eventAndUser = createEvent(List.of(PaymentProxy.OFFLINE));
            return new ReservationFlowContext(eventAndUser.getLeft(), owner(eventAndUser.getRight()));
        });
    }

    @Test
    void returnWithErrorCancelsOrderAndBringsReservationBackToPending() {
        var event = createEvent(List.of(PaymentProxy.PAYU)).getLeft();
        var reservationId = reserveAndMoveToOverview(event);
        var orderId = startPayUPayment(reservationId);
        stubOrder(orderId, reservationId, "NEW");
        // the DELETE cancels the order: the re-read that confirms the cancellation shows it CANCELED
        PAYU.stubFor(delete(urlEqualTo(ORDERS_PATH + "/" + orderId))
            .inScenario(CANCELLATION_SCENARIO)
            .willSetStateTo(ORDER_CANCELLED_STATE)
            .willReturn(okJson(successStatus(orderId).toString())));
        PAYU.stubFor(get(urlEqualTo(ORDERS_PATH + "/" + orderId))
            .inScenario(CANCELLATION_SCENARIO)
            .whenScenarioStateIs(ORDER_CANCELLED_STATE)
            .willReturn(okJson(orderLookupBody(orderId, reservationId, "CANCELED"))));

        var redirect = payUCallbackController.payuReturn(PurchaseContextType.event, event.getShortName(), reservationId, "501", null);

        assertEquals("redirect:/event/" + event.getShortName() + "/reservation/" + reservationId + "/overview?paymentCancelled=true", redirect);
        PAYU.verify(1, deleteRequestedFor(urlEqualTo(ORDERS_PATH + "/" + orderId)));
        assertReservationStatus(reservationId, TicketReservation.TicketReservationStatus.PENDING);
        assertTrue(transactionRepository.loadOptionalByStatusAndPaymentProxyForUpdate(reservationId, Transaction.Status.PENDING, PaymentProxy.PAYU).isEmpty());
        PAYU.verify(1, postRequestedFor(urlEqualTo(ORDERS_PATH)));
    }

    @Test
    void returnConfirmsPaymentAndLaterNotificationsAreAcknowledged() {
        var event = createEvent(List.of(PaymentProxy.PAYU)).getLeft();
        var reservationId = reserveAndMoveToOverview(event);
        var orderId = startPayUPayment(reservationId);
        var extOrderId = stubOrder(orderId, reservationId, "COMPLETED");

        // the buyer comes back before the notification: the force-check confirms the reservation
        var redirect = payUCallbackController.payuReturn(PurchaseContextType.event, event.getShortName(), reservationId, null, null);
        assertEquals("redirect:/event/" + event.getShortName() + "/reservation/" + reservationId + "/book", redirect);
        assertReservationStatus(reservationId, TicketReservation.TicketReservationStatus.COMPLETE);
        assertTransactionSuccessful(reservationId);

        // the COMPLETED notification arriving afterwards is acknowledged and changes nothing
        var duplicate = sendNotification(reservationId, orderNotification(orderId, extOrderId, "COMPLETED"));
        assertEquals(HttpStatus.OK, duplicate.getStatusCode());
        assertReservationStatus(reservationId, TicketReservation.TicketReservationStatus.COMPLETE);

        // a refund notification is acknowledged, so that PayU does not retry it
        var refund = new JsonObject();
        refund.addProperty("refundId", "912128");
        refund.addProperty("extRefundId", reservationId + "-refund-1000-1");
        refund.addProperty("amount", "1000");
        refund.addProperty("currencyCode", "CHF");
        refund.addProperty("status", "FINALIZED");
        var refundNotification = new JsonObject();
        refundNotification.addProperty(ORDER_ID, orderId);
        refundNotification.addProperty("extOrderId", extOrderId);
        refundNotification.add("refund", refund);
        var refundResponse = sendNotification(reservationId, refundNotification);
        assertEquals(HttpStatus.OK, refundResponse.getStatusCode());
        assertReservationStatus(reservationId, TicketReservation.TicketReservationStatus.COMPLETE);
        PAYU.verify(1, postRequestedFor(urlEqualTo(ORDERS_PATH)));
    }

    @Test
    void returnForCanceledOrderBringsReservationBackToPendingAndAllowsANewOrder() {
        var event = createEvent(List.of(PaymentProxy.PAYU)).getLeft();
        var reservationId = reserveAndMoveToOverview(event);
        var orderId = startPayUPayment(reservationId);
        stubOrder(orderId, reservationId, "CANCELED");

        // no error parameter, but PayU already cancelled the order: the force-check brings the reservation back
        var redirect = payUCallbackController.payuReturn(PurchaseContextType.event, event.getShortName(), reservationId, null, null);

        assertEquals("redirect:/event/" + event.getShortName() + "/reservation/" + reservationId + "/overview?paymentCancelled=true", redirect);
        assertReservationStatus(reservationId, TicketReservation.TicketReservationStatus.PENDING);
        assertTrue(transactionRepository.loadOptionalByStatusAndPaymentProxyForUpdate(reservationId, Transaction.Status.PENDING, PaymentProxy.PAYU).isEmpty());
        PAYU.verify(0, deleteRequestedFor(urlEqualTo(ORDERS_PATH + "/" + orderId)));

        // PayU extends the validity from the fixed test clock (today at 10:00), while confirmOverview checks it
        // against the wall clock: move it forward so that the second attempt is not rejected as expired
        ticketReservationRepository.updateValidity(reservationId, Date.from(Instant.now().plus(Duration.ofMinutes(25))));

        // the buyer pays again: the cancelled order cannot be paid any more, so a new one is created
        var secondOrderId = startPayUPayment(reservationId);
        assertNotEquals(orderId, secondOrderId);
        PAYU.verify(2, postRequestedFor(urlEqualTo(ORDERS_PATH)));
        var pending = transactionRepository.loadOptionalByStatusAndPaymentProxyForUpdate(reservationId, Transaction.Status.PENDING, PaymentProxy.PAYU);
        assertEquals(secondOrderId, pending.orElseThrow().getPaymentId());
    }

    @Test
    void secondConfirmWhileOrderIsBeingPaidCreatesNoSecondOrder() {
        var event = createEvent(List.of(PaymentProxy.PAYU)).getLeft();
        var reservationId = reserveAndMoveToOverview(event);
        var orderId = startPayUPayment(reservationId);
        stubOrder(orderId, reservationId, "PENDING");

        var response = confirmOverviewWithPayU(reservationId);

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
        PAYU.verify(1, postRequestedFor(urlEqualTo(ORDERS_PATH)));
        PAYU.verify(0, deleteRequestedFor(urlEqualTo(ORDERS_PATH + "/" + orderId)));
        assertReservationStatus(reservationId, TicketReservation.TicketReservationStatus.EXTERNAL_PROCESSING_PAYMENT);
        var pending = transactionRepository.loadOptionalByStatusAndPaymentProxyForUpdate(reservationId, Transaction.Status.PENDING, PaymentProxy.PAYU);
        assertEquals(orderId, pending.orElseThrow().getPaymentId());
    }

    @Test
    void waitingForConfirmationNotificationCapturesThenCompletedNotificationConfirms() {
        var event = createEvent(List.of(PaymentProxy.PAYU)).getLeft();
        var reservationId = reserveAndMoveToOverview(event);
        var orderId = startPayUPayment(reservationId);
        var extOrderId = stubOrder(orderId, reservationId, "WAITING_FOR_CONFIRMATION");
        var statusPath = ORDERS_PATH + "/" + orderId + "/status";
        PAYU.stubFor(put(urlEqualTo(statusPath)).willReturn(okJson(successStatus(null).toString())));

        var waiting = sendNotification(reservationId, orderNotification(orderId, extOrderId, "WAITING_FOR_CONFIRMATION"));

        assertEquals(HttpStatus.OK, waiting.getStatusCode());
        PAYU.verify(1, putRequestedFor(urlEqualTo(statusPath)));
        var capture = JsonParser.parseString(PAYU.findAll(putRequestedFor(urlEqualTo(statusPath))).getFirst().getBodyAsString()).getAsJsonObject();
        assertEquals(orderId, capture.get(ORDER_ID).getAsString());
        assertEquals("COMPLETED", capture.get("orderStatus").getAsString());
        assertReservationStatus(reservationId, TicketReservation.TicketReservationStatus.WAITING_EXTERNAL_CONFIRMATION);

        stubOrder(orderId, reservationId, "COMPLETED");
        var completed = sendNotification(reservationId, orderNotification(orderId, extOrderId, "COMPLETED"));

        assertEquals(HttpStatus.OK, completed.getStatusCode());
        assertEquals("OK", completed.getBody());
        assertReservationStatus(reservationId, TicketReservation.TicketReservationStatus.COMPLETE);
        assertTransactionSuccessful(reservationId);
        PAYU.verify(1, postRequestedFor(urlEqualTo(ORDERS_PATH)));
    }

    @Test
    void completedOrderWithWrongAmountIsRetriedAndConfirmsNothing() {
        var event = createEvent(List.of(PaymentProxy.PAYU)).getLeft();
        var reservationId = reserveAndMoveToOverview(event);
        var orderId = startPayUPayment(reservationId);
        var extOrderId = stubOrder(orderId, reservationId, "COMPLETED", "999");

        var response = sendNotification(reservationId, orderNotification(orderId, extOrderId, "COMPLETED"));

        // 500: PayU retries the notification, an operator has to look at the order
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertReservationStatus(reservationId, TicketReservation.TicketReservationStatus.EXTERNAL_PROCESSING_PAYMENT);
        var pending = transactionRepository.loadOptionalByStatusAndPaymentProxyForUpdate(reservationId, Transaction.Status.PENDING, PaymentProxy.PAYU);
        assertEquals(orderId, pending.orElseThrow().getPaymentId());

        // the return force-check does not confirm it either, and keeps the transaction
        var redirect = payUCallbackController.payuReturn(PurchaseContextType.event, event.getShortName(), reservationId, null, null);
        assertEquals("redirect:/event/" + event.getShortName() + "/reservation/" + reservationId + "/book", redirect);
        assertReservationStatus(reservationId, TicketReservation.TicketReservationStatus.EXTERNAL_PROCESSING_PAYMENT);
        assertTrue(transactionRepository.loadOptionalByStatusAndPaymentProxyForUpdate(reservationId, Transaction.Status.PENDING, PaymentProxy.PAYU).isPresent());
    }

    @Override
    protected void performAndValidatePayment(ReservationFlowContext context,
                                             String reservationId,
                                             int promoCodeId,
                                             Runnable cleanupExtensionLog) {
        var tStatus = reservationApiV2Controller.getTransactionStatus(reservationId, StaticPaymentMethods.PAYU.name());
        assertEquals(HttpStatus.NOT_FOUND, tStatus.getStatusCode());

        var orderId = startPayUPayment(reservationId);
        var resInfoResponse = reservationApiV2Controller.getReservationInfo(reservationId, null);
        assertEquals(TicketReservation.TicketReservationStatus.EXTERNAL_PROCESSING_PAYMENT, requireNonNull(resInfoResponse.getBody()).getStatus());

        var promoCodeUsage = promoCodeRequestManager.retrieveDetailedUsage(promoCodeId, context.event.getId());
        assertTrue(promoCodeUsage.isEmpty());

        var extOrderId = stubOrder(orderId, reservationId, "COMPLETED");
        cleanupExtensionLog.run();
        var response = sendNotification(reservationId, orderNotification(orderId, extOrderId, "COMPLETED"));
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("OK", response.getBody());

        checkStatus(reservationId, HttpStatus.OK, true, TicketReservation.TicketReservationStatus.COMPLETE, context);
        assertTransactionSuccessful(reservationId);

        var reservation = reservationApiV2Controller.getReservationInfo(reservationId, context.getPublicUser()).getBody();
        assertNotNull(reservation);
        checkOrderSummary(reservation, context);

        var extLogs = extensionLogRepository.getPage(null, null, null, 100, 0);
        assertEventLogged(extLogs, RESERVATION_CONFIRMED);
        assertEventLogged(extLogs, CONFIRMATION_MAIL_CUSTOM_TEXT);
        assertEventLogged(extLogs, TICKET_ASSIGNED);
        assertEventLogged(extLogs, TICKET_ASSIGNED_GENERATE_METADATA);
        assertEventLogged(extLogs, TICKET_MAIL_CUSTOM_TEXT);

        // one PayU order per payment attempt
        PAYU.verify(1, postRequestedFor(urlEqualTo(ORDERS_PATH)));
    }

    @Override
    protected void checkOrderSummary(ReservationInfo reservation, ReservationFlowContext context) {
        var orderSummary = reservation.getOrderSummary();
        assertFalse(orderSummary.isNotYetPaid());
        assertEquals("10.00", orderSummary.getTotalPrice());
        assertEquals("0.10", orderSummary.getTotalVAT());
        assertEquals("1.00", orderSummary.getVatPercentage());
    }

    private Pair<Event, String> createEvent(List<PaymentProxy> acceptedPaymentProxies) {
        List<TicketCategoryModification> categories = Arrays.asList(
            new TicketCategoryModification(null, "default", TicketCategory.TicketAccessType.INHERIT, AVAILABLE_SEATS,
                new DateTimeModification(LocalDate.now(clockProvider.getClock()).minusDays(1), LocalTime.now(clockProvider.getClock())),
                new DateTimeModification(LocalDate.now(clockProvider.getClock()).plusDays(1), LocalTime.now(clockProvider.getClock())),
                DESCRIPTION, BigDecimal.TEN, false, "", false, null, null, null, null, null, 0, null, null, AlfioMetadata.empty()),
            new TicketCategoryModification(null, "hidden", TicketCategory.TicketAccessType.INHERIT, 2,
                new DateTimeModification(LocalDate.now(clockProvider.getClock()).minusDays(1), LocalTime.now(clockProvider.getClock())),
                new DateTimeModification(LocalDate.now(clockProvider.getClock()).plusDays(1), LocalTime.now(clockProvider.getClock())),
                DESCRIPTION, BigDecimal.ONE, true, "", true, URL_CODE_HIDDEN, null, null, null, null, 0, null, null, AlfioMetadata.empty())
        );
        Pair<Event, String> eventAndUser = initEvent(categories, organizationRepository, userManager, eventManager, eventRepository,
            null, Event.EventFormat.IN_PERSON, PriceContainer.VatStatus.INCLUDED, acceptedPaymentProxies);
        assertEquals("CHF", eventAndUser.getLeft().getCurrency());
        return eventAndUser;
    }

    /**
     * Creates a reservation for one ticket of the default category and fills in the contact data.
     */
    private String reserveAndMoveToOverview(Event event) {
        ensureMinimalConfiguration(configurationRepository);
        var categories = requireNonNull(eventApiV2Controller.getTicketCategories(event.getShortName(), null).getBody()).ticketCategories();
        var ticketReservation = new TicketReservationModification();
        ticketReservation.setQuantity(1);
        ticketReservation.setTicketCategoryId(categories.getFirst().getId());
        var form = new ReservationForm();
        form.setReservation(Collections.singletonList(ticketReservation));
        var reserveResponse = eventApiV2Controller.reserveTickets(event.getShortName(), "en", form, new BeanPropertyBindingResult(form, "reservation"),
            new ServletWebRequest(new MockHttpServletRequest(), new MockHttpServletResponse()), null);
        assertEquals(HttpStatus.OK, reserveResponse.getStatusCode());
        var reservationId = requireNonNull(reserveResponse.getBody()).getValue();

        var reservation = requireNonNull(reservationApiV2Controller.getReservationInfo(reservationId, null).getBody());
        assertTrue(reservation.getActivePaymentMethods().containsKey(StaticPaymentMethods.PAYU.getPaymentMethodId()));
        var ticketForm = new UpdateTicketOwnerForm();
        ticketForm.setFirstName("ticketfull");
        ticketForm.setLastName("ticketname");
        ticketForm.setEmail("tickettest@test.com");
        var contactForm = new ContactAndTicketsForm();
        contactForm.setEmail("test@test.com");
        contactForm.setBillingAddress("my billing address");
        contactForm.setFirstName("full");
        contactForm.setLastName("name");
        contactForm.setTickets(Collections.singletonMap(reservation.getTicketsByCategory().getFirst().getTickets().getFirst().getUuid(), ticketForm));
        var overviewResponse = reservationApiV2Controller.validateToOverview(reservationId, "en", false, contactForm,
            new BeanPropertyBindingResult(contactForm, "paymentForm"), null);
        assertEquals(HttpStatus.OK, overviewResponse.getStatusCode());
        return reservationId;
    }

    /**
     * Confirms the overview with PayU and checks that the buyer is sent to the PayU hosted payment page.
     *
     * @return the PayU order id
     */
    private String startPayUPayment(String reservationId) {
        var orderId = "PAYU" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
        stubAuthorization();
        var orderCreated = successStatus(orderId);
        orderCreated.addProperty("redirectUri", REDIRECT_URI + orderId);
        PAYU.stubFor(post(urlEqualTo(ORDERS_PATH))
            .willReturn(aResponse()
                .withStatus(302)
                .withHeader("Content-Type", "application/json")
                .withHeader("Location", REDIRECT_URI + orderId)
                .withBody(orderCreated.toString())));
        PAYU.stubFor(get(urlEqualTo(ORDERS_PATH + "/" + orderId + "/refunds"))
            .willReturn(okJson("{\"refunds\":[]}")));

        var response = confirmOverviewWithPayU(reservationId);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        var result = requireNonNull(requireNonNull(response.getBody()).getValue());
        assertTrue(result.isRedirect());
        assertEquals(REDIRECT_URI + orderId, result.getRedirectUrl());
        assertReservationStatus(reservationId, TicketReservation.TicketReservationStatus.EXTERNAL_PROCESSING_PAYMENT);

        var orderRequest = JsonParser.parseString(PAYU.findAll(postRequestedFor(urlEqualTo(ORDERS_PATH))).getLast().getBodyAsString()).getAsJsonObject();
        assertEquals("CHF", orderRequest.get("currencyCode").getAsString());
        assertEquals("1000", orderRequest.get("totalAmount").getAsString());
        assertEquals("192.0.2.10", orderRequest.get("customerIp").getAsString());
        assertEquals(BASE_URL + "/api/payment/webhook/payu/reservation/" + reservationId, orderRequest.get("notifyUrl").getAsString());
        assertTrue(orderRequest.get("extOrderId").getAsString().startsWith(reservationId));
        return orderId;
    }

    private ResponseEntity<ValidatedResponse<ReservationPaymentResult>> confirmOverviewWithPayU(String reservationId) {
        var paymentForm = new PaymentForm();
        paymentForm.setPrivacyPolicyAccepted(true);
        paymentForm.setTermAndConditionsAccepted(true);
        paymentForm.setPaymentProxy(PaymentProxy.PAYU);
        paymentForm.setSelectedPaymentMethod(StaticPaymentMethods.PAYU);
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");
        return reservationApiV2Controller.confirmOverview(reservationId, "en", paymentForm,
            new BeanPropertyBindingResult(paymentForm, "paymentForm"), request, null);
    }

    private static void stubAuthorization() {
        PAYU.stubFor(post(urlEqualTo("/pl/standard/user/oauth/authorize"))
            .willReturn(okJson("{\"access_token\":\"test-access-token\",\"token_type\":\"bearer\",\"expires_in\":43199,\"grant_type\":\"client_credentials\"}")));
    }

    /**
     * Stubs GET of the order, with the amount and extOrderId sent when it was created.
     *
     * @return the extOrderId of the order
     */
    private static String stubOrder(String orderId, String reservationId, String status) {
        var orderRequest = JsonParser.parseString(PAYU.findAll(postRequestedFor(urlEqualTo(ORDERS_PATH))).getLast().getBodyAsString()).getAsJsonObject();
        return stubOrder(orderId, reservationId, status, orderRequest.get("totalAmount").getAsString());
    }

    /**
     * Stubs GET of the order with the given amount, which may differ from the one sent when it was created.
     *
     * @return the extOrderId of the order
     */
    private static String stubOrder(String orderId, String reservationId, String status, String totalAmount) {
        PAYU.stubFor(get(urlEqualTo(ORDERS_PATH + "/" + orderId)).willReturn(okJson(orderLookupBody(orderId, reservationId, status, totalAmount))));
        return lastOrderRequest().get("extOrderId").getAsString();
    }

    private static JsonObject lastOrderRequest() {
        return JsonParser.parseString(PAYU.findAll(postRequestedFor(urlEqualTo(ORDERS_PATH))).getLast().getBodyAsString()).getAsJsonObject();
    }

    private static String orderLookupBody(String orderId, String reservationId, String status) {
        return orderLookupBody(orderId, reservationId, status, lastOrderRequest().get("totalAmount").getAsString());
    }

    /**
     * Body of GET /api/v2_1/orders/{orderId} for the last order created.
     */
    private static String orderLookupBody(String orderId, String reservationId, String status, String totalAmount) {
        var orderRequest = lastOrderRequest();
        var extOrderId = orderRequest.get("extOrderId").getAsString();
        var order = new JsonObject();
        order.addProperty(ORDER_ID, orderId);
        order.addProperty("extOrderId", extOrderId);
        order.addProperty("description", reservationId);
        order.addProperty("currencyCode", orderRequest.get("currencyCode").getAsString());
        order.addProperty("totalAmount", totalAmount);
        order.addProperty("status", status);
        var orders = new JsonArray();
        orders.add(order);
        var body = successStatus(null);
        body.add("orders", orders);
        return body.toString();
    }

    private static JsonObject successStatus(String orderId) {
        var status = new JsonObject();
        status.addProperty("statusCode", "SUCCESS");
        var body = new JsonObject();
        body.add("status", status);
        if (orderId != null) {
            body.addProperty(ORDER_ID, orderId);
        }
        return body;
    }

    private static JsonObject orderNotification(String orderId, String extOrderId, String status) {
        var order = new JsonObject();
        order.addProperty(ORDER_ID, orderId);
        order.addProperty("extOrderId", extOrderId);
        order.addProperty("merchantPosId", "300746");
        order.addProperty("currencyCode", "CHF");
        order.addProperty("totalAmount", "1000");
        order.addProperty("status", status);
        var notification = new JsonObject();
        notification.add("order", order);
        notification.addProperty("localReceiptDateTime", "2026-11-07T12:00:00.000+01:00");
        return notification;
    }

    private ResponseEntity<String> sendNotification(String reservationId, JsonObject notification) {
        var body = notification.toString();
        var signatureHeader = "sender=checkout;signature=" + md5Hex(body + SECOND_KEY) + ";algorithm=MD5;content=DOCUMENT";
        assertTrue(PayUSignature.isValid(signatureHeader, body, SECOND_KEY));
        var request = new MockHttpServletRequest("POST", "/api/payment/webhook/payu/reservation/" + reservationId);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.addHeader("OpenPayu-Signature", signatureHeader);
        return payUPaymentWebhookController.receivePaymentNotification(request, reservationId);
    }

    private static String md5Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void assertReservationStatus(String reservationId, TicketReservation.TicketReservationStatus expected) {
        var status = requireNonNull(reservationApiV2Controller.getReservationStatus(reservationId).getBody()).getStatus();
        assertEquals(expected, status);
    }

    private void assertTransactionSuccessful(String reservationId) {
        var tStatus = reservationApiV2Controller.getTransactionStatus(reservationId, StaticPaymentMethods.PAYU.name());
        assertEquals(HttpStatus.OK, tStatus.getStatusCode());
        assertTrue(requireNonNull(tStatus.getBody()).isSuccess());
        var transaction = transactionRepository.loadByReservationId(reservationId);
        assertEquals(Transaction.Status.COMPLETE, transaction.getStatus());
        assertEquals(PaymentProxy.PAYU, transaction.getPaymentProxy());
    }
}
