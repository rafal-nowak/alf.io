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

import alfio.manager.payment.payu.PayUSignature;
import alfio.manager.support.PaymentResult;
import alfio.manager.support.PaymentWebhookResult;
import alfio.manager.system.ConfigurationManager;
import alfio.model.PaymentInformation;
import alfio.model.PurchaseContext;
import alfio.model.TicketReservation;
import alfio.model.system.ConfigurationKeys;
import alfio.model.transaction.*;
import alfio.model.transaction.capabilities.PaymentInfo;
import alfio.model.transaction.capabilities.RefundRequest;
import alfio.model.transaction.capabilities.WebhookHandler;
import alfio.model.transaction.token.PayUToken;
import alfio.model.transaction.webhook.PayUWebhookPayload;
import alfio.repository.TicketReservationRepository;
import alfio.repository.TransactionRepository;
import alfio.util.ClockProvider;
import alfio.util.HttpUtils;
import alfio.util.MonetaryUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static alfio.manager.payment.PaymentManagerUtils.invalidateExistingTransactions;
import static alfio.model.TicketReservation.TicketReservationStatus.EXTERNAL_PROCESSING_PAYMENT;
import static alfio.model.system.ConfigurationKeys.*;

/**
 * PayU (REST API v2.1) payment provider.
 * <p>
 * The attendee is redirected to the PayU hosted payment page, where they can pay with BLIK, pay-by-link
 * (Polish online bank transfers), card, Apple Pay / Google Pay and other methods enabled on the POS.
 * PayU confirms the payment through a signed notification; the order status is then re-read from the PayU API
 * before being applied. The same status check runs when the attendee comes back to alf.io, so the reservation
 * is confirmed even if the notification has not arrived yet.
 *
 * @see <a href="https://developers.payu.com/europe/api/">PayU API</a>
 */
@Component
@Transactional
public class PayUManager implements PaymentProvider, WebhookHandler, PaymentInfo, RefundRequest {

    public static final String WEBHOOK_URL_TEMPLATE = "/api/payment/webhook/payu/reservation/{reservationId}";
    static final String RETURN_URL_TEMPLATE = "/{purchaseContextType}/{purchaseContextIdentifier}/reservation/{reservationId}/book";
    static final String SANDBOX_ENDPOINT = "https://secure.snd.payu.com";
    static final String LIVE_ENDPOINT = "https://secure.payu.com";
    private static final String EXT_ORDER_ID = "extOrderId";
    private static final Logger LOGGER = LoggerFactory.getLogger(PayUManager.class);
    private static final Set<ConfigurationKeys> REQUIRED_KEYS = EnumSet.of(PAYU_POS_ID, PAYU_CLIENT_ID, PAYU_CLIENT_SECRET, PAYU_SECOND_KEY, BASE_URL);
    private static final Set<ConfigurationKeys> ALL_KEYS = EnumSet.of(PAYU_ENABLED, PAYU_LIVE_MODE, PAYU_POS_ID, PAYU_CLIENT_ID, PAYU_CLIENT_SECRET, PAYU_SECOND_KEY, BASE_URL, RESERVATION_TIMEOUT);

    private final ConfigurationManager configurationManager;
    private final HttpClient httpClient;
    private final TicketReservationRepository ticketReservationRepository;
    private final TransactionRepository transactionRepository;
    private final ClockProvider clockProvider;
    private final Map<String, AccessToken> accessTokens = new ConcurrentHashMap<>();

    public PayUManager(ConfigurationManager configurationManager,
                       HttpClient httpClient,
                       TicketReservationRepository ticketReservationRepository,
                       TransactionRepository transactionRepository,
                       ClockProvider clockProvider) {
        this.configurationManager = configurationManager;
        this.httpClient = httpClient;
        this.ticketReservationRepository = ticketReservationRepository;
        this.transactionRepository = transactionRepository;
        this.clockProvider = clockProvider;
    }

    @Override
    public Set<? extends PaymentMethod> getSupportedPaymentMethods(PaymentContext paymentContext, TransactionRequest transactionRequest) {
        return EnumSet.of(StaticPaymentMethods.PAYU);
    }

    @Override
    public PaymentProxy getPaymentProxy() {
        return PaymentProxy.PAYU;
    }

    @Override
    public boolean accept(PaymentMethod paymentMethod, PaymentContext context, TransactionRequest transactionRequest) {
        return paymentMethod == StaticPaymentMethods.PAYU && isActive(context);
    }

    @Override
    public boolean accept(Transaction transaction) {
        return transaction.getPaymentProxy() == PaymentProxy.PAYU;
    }

    @Override
    public PaymentMethod getPaymentMethodForTransaction(Transaction transaction) {
        return StaticPaymentMethods.PAYU;
    }

    @Override
    public boolean isActive(PaymentContext paymentContext) {
        var configuration = configurationManager.getFor(ALL_KEYS, paymentContext.getConfigurationLevel());
        if (!configuration.get(PAYU_ENABLED).getValueAsBooleanOrDefault()) {
            return false;
        }
        return REQUIRED_KEYS.stream().allMatch(key -> configuration.get(key).isPresent());
    }

    @Override
    public PaymentResult doPayment(PaymentSpecification spec) {
        var purchaseContext = spec.getPurchaseContext();
        var configuration = loadConfiguration(purchaseContext);
        var reservationId = spec.getReservationId();

        var existingTransaction = transactionRepository.loadOptionalByStatusAndPaymentProxyForUpdate(reservationId, Transaction.Status.PENDING, PaymentProxy.PAYU);
        if (existingTransaction.isPresent()) {
            var result = checkOrderStatus(existingTransaction.get(), configuration);
            if (result.getType() == PaymentWebhookResult.Type.SUCCESSFUL) {
                return PaymentResult.successful(result.getPaymentToken().getToken());
            }
        }

        try {
            var reservation = ticketReservationRepository.findReservationById(reservationId);
            var description = configurationManager.getShortReservationID(purchaseContext, reservation) + " - " + purchaseContext.getDisplayName();
            var extOrderId = reservationId + "-" + Instant.now(clockProvider.getClock()).toEpochMilli();
            var orderBody = buildOrderRequest(spec, configuration, description, extOrderId);
            var request = authorizedRequest(configuration, "/api/v2_1/orders")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(orderBody.toString()))
                .build();
            // PayU answers with HTTP 302 and a JSON body: the HttpClient must not follow redirects (default: NEVER)
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            var responseBody = parseObject(response.body());
            if (!isSuccess(responseBody) || responseBody == null || !responseBody.has("redirectUri")) {
                LOGGER.warn("PayU order creation failed with status {}, body {}", response.statusCode(), response.body());
                return PaymentResult.failed("error.STEP2_PAYU_unexpected");
            }
            var orderId = responseBody.get("orderId").getAsString();
            ticketReservationRepository.updateReservationStatus(reservationId, EXTERNAL_PROCESSING_PAYMENT.toString());
            invalidateExistingTransactions(reservationId, transactionRepository);
            transactionRepository.insert(orderId, orderId, reservationId,
                ZonedDateTime.now(clockProvider.withZone(purchaseContext.getZoneId())),
                spec.getPriceWithVAT(), purchaseContext.getCurrency(), "PayU payment",
                PaymentProxy.PAYU.name(), 0L, 0L, Transaction.Status.PENDING, Map.of(EXT_ORDER_ID, extOrderId));
            return PaymentResult.redirect(responseBody.get("redirectUri").getAsString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.error("PayU order creation interrupted", e);
            return PaymentResult.failed("error.STEP2_PAYU_unexpected");
        } catch (Exception e) {
            LOGGER.error("Unexpected error while creating PayU order", e);
            return PaymentResult.failed("error.STEP2_PAYU_unexpected");
        }
    }

    JsonObject buildOrderRequest(PaymentSpecification spec,
                                 Map<ConfigurationKeys, ConfigurationManager.MaybeConfiguration> configuration,
                                 String description,
                                 String extOrderId) {
        var baseUrl = StringUtils.removeEnd(configuration.get(BASE_URL).getRequiredValue(), "/");
        var purchaseContext = spec.getPurchaseContext();
        var amount = Integer.toString(spec.getPriceWithVAT());

        var body = new JsonObject();
        body.addProperty("notifyUrl", baseUrl + UriComponentsBuilder.fromPath(WEBHOOK_URL_TEMPLATE).buildAndExpand(spec.getReservationId()).toUriString());
        body.addProperty("continueUrl", baseUrl + UriComponentsBuilder.fromPath(RETURN_URL_TEMPLATE)
            .buildAndExpand(purchaseContext.getType().getUrlComponent(), purchaseContext.getPublicIdentifier(), spec.getReservationId()).toUriString());
        // the attendee's IP address is not available here; PayU accepts any valid address
        body.addProperty("customerIp", "127.0.0.1");
        body.addProperty("merchantPosId", configuration.get(PAYU_POS_ID).getRequiredValue());
        body.addProperty("description", StringUtils.abbreviate(description, 4000));
        body.addProperty("currencyCode", purchaseContext.getCurrency());
        body.addProperty("totalAmount", amount);
        body.addProperty(EXT_ORDER_ID, extOrderId);

        var buyer = new JsonObject();
        buyer.addProperty("email", spec.getEmail());
        if (spec.getCustomerName() != null) {
            buyer.addProperty("firstName", spec.getCustomerName().getFirstName());
            buyer.addProperty("lastName", spec.getCustomerName().getLastName());
        }
        if (spec.getLocale() != null) {
            buyer.addProperty("language", spec.getLocale().getLanguage());
        }
        body.add("buyer", buyer);

        var product = new JsonObject();
        product.addProperty("name", StringUtils.abbreviate(description, 255));
        product.addProperty("unitPrice", amount);
        product.addProperty("quantity", "1");
        var products = new JsonArray();
        products.add(product);
        body.add("products", products);
        return body;
    }

    @Override
    public Optional<TransactionWebhookPayload> parseTransactionPayload(String body,
                                                                       String signature,
                                                                       Map<String, String> additionalInfo,
                                                                       PaymentContext paymentContext) {
        var configuration = configurationManager.getFor(EnumSet.of(PAYU_SECOND_KEY), paymentContext.getConfigurationLevel());
        if (!PayUSignature.isValid(signature, body, configuration.get(PAYU_SECOND_KEY).getValueOrNull())) {
            LOGGER.warn("Discarding PayU notification with invalid signature");
            return Optional.empty();
        }
        var json = parseObject(body);
        if (json == null || !json.has("order")) {
            return Optional.empty();
        }
        var order = json.getAsJsonObject("order");
        var reservationId = additionalInfo.get("reservationId");
        var extOrderId = order.get(EXT_ORDER_ID).getAsString();
        if (reservationId == null || !extOrderId.startsWith(reservationId)) {
            LOGGER.warn("PayU notification for order {} does not match reservation {}", extOrderId, reservationId);
            return Optional.empty();
        }
        return Optional.of(new PayUWebhookPayload(reservationId, order.get("orderId").getAsString(), order.get("status").getAsString()));
    }

    @Override
    public PaymentWebhookResult processWebhook(TransactionWebhookPayload payload, Transaction transaction, PaymentContext paymentContext) {
        var notifiedOrderId = ((PayUWebhookPayload) payload).getOrderId();
        if (!notifiedOrderId.equals(transaction.getPaymentId())) {
            return PaymentWebhookResult.notRelevant("notification refers to a different PayU order");
        }
        return checkOrderStatus(transaction, loadConfiguration(paymentContext.getPurchaseContext()));
    }

    @Override
    public PaymentWebhookResult forceTransactionCheck(TicketReservation reservation, Transaction transaction, PaymentContext paymentContext) {
        return checkOrderStatus(transaction, loadConfiguration(paymentContext.getPurchaseContext()));
    }

    PaymentWebhookResult checkOrderStatus(Transaction transaction, Map<ConfigurationKeys, ConfigurationManager.MaybeConfiguration> configuration) {
        var orderId = transaction.getPaymentId();
        var maybeOrder = retrieveOrder(configuration, orderId);
        if (maybeOrder.isEmpty()) {
            return PaymentWebhookResult.error("cannot retrieve PayU order " + orderId);
        }
        var status = maybeOrder.get().get("status").getAsString();
        switch (status) {
            case "COMPLETED":
                transactionRepository.update(transaction.getId(), orderId, orderId,
                    ZonedDateTime.now(clockProvider.getClock()), transaction.getPlatformFee(), transaction.getGatewayFee(),
                    Transaction.Status.COMPLETE, transaction.getMetadata());
                return PaymentWebhookResult.successful(new PayUToken(orderId));
            case "CANCELED":
                LOGGER.debug("PayU order {} canceled, invalidating transaction {}", orderId, transaction.getId());
                transactionRepository.invalidateById(transaction.getId());
                ticketReservationRepository.updateValidity(transaction.getReservationId(),
                    DateUtils.addMinutes(new Date(), configuration.get(RESERVATION_TIMEOUT).getValueAsIntOrDefault(25)));
                return PaymentWebhookResult.cancelled();
            case "WAITING_FOR_CONFIRMATION":
                // POS without automatic collection: we confirm the payment right away
                confirmOrder(configuration, orderId);
                return PaymentWebhookResult.pending();
            case "NEW":
                return PaymentWebhookResult.processStarted(new PayUToken(orderId));
            default:
                return PaymentWebhookResult.pending();
        }
    }

    @Override
    public Optional<PaymentInformation> getInfo(Transaction transaction, PurchaseContext purchaseContext) {
        return retrieveOrder(loadConfiguration(purchaseContext), transaction.getPaymentId())
            .map(order -> new PaymentInformation(
                MonetaryUtil.formatCents(Integer.parseInt(order.get("totalAmount").getAsString()), order.get("currencyCode").getAsString()),
                null, null, null));
    }

    @Override
    public boolean refund(Transaction transaction, PurchaseContext purchaseContext, Integer amount) {
        var configuration = loadConfiguration(purchaseContext);
        var refund = new JsonObject();
        refund.addProperty("description", "Refund " + transaction.getReservationId());
        if (amount != null) {
            refund.addProperty("amount", Integer.toString(amount));
        }
        var body = new JsonObject();
        body.add("refund", refund);
        try {
            var request = authorizedRequest(configuration, "/api/v2_1/orders/" + transaction.getPaymentId() + "/refunds")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (!isSuccess(parseObject(response.body()))) {
                LOGGER.warn("PayU refund failed with status {}, body {}", response.statusCode(), response.body());
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("PayU refund interrupted", e);
            return false;
        } catch (Exception e) {
            LOGGER.error("Unexpected error while refunding PayU order {}", transaction.getPaymentId(), e);
            return false;
        }
    }

    @Override
    public boolean requiresSignedBody() {
        return true;
    }

    private Optional<JsonObject> retrieveOrder(Map<ConfigurationKeys, ConfigurationManager.MaybeConfiguration> configuration, String orderId) {
        try {
            var request = authorizedRequest(configuration, "/api/v2_1/orders/" + orderId).GET().build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            var body = parseObject(response.body());
            if (!HttpUtils.callSuccessful(response) || !isSuccess(body) || !body.has("orders")) {
                LOGGER.warn("Cannot retrieve PayU order {}: status {}, body {}", orderId, response.statusCode(), response.body());
                return Optional.empty();
            }
            var orders = body.getAsJsonArray("orders");
            if (orders.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(orders.get(0).getAsJsonObject());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            LOGGER.error("Unexpected error while retrieving PayU order {}", orderId, e);
            return Optional.empty();
        }
    }

    private void confirmOrder(Map<ConfigurationKeys, ConfigurationManager.MaybeConfiguration> configuration, String orderId) {
        var body = new JsonObject();
        body.addProperty("orderId", orderId);
        body.addProperty("orderStatus", "COMPLETED");
        try {
            var request = authorizedRequest(configuration, "/api/v2_1/orders/" + orderId + "/status")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (!isSuccess(parseObject(response.body()))) {
                LOGGER.warn("Cannot confirm PayU order {}: status {}, body {}", orderId, response.statusCode(), response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOGGER.error("Unexpected error while confirming PayU order {}", orderId, e);
        }
    }

    private HttpRequest.Builder authorizedRequest(Map<ConfigurationKeys, ConfigurationManager.MaybeConfiguration> configuration, String path) throws Exception {
        return HttpRequest.newBuilder()
            .uri(URI.create(endpoint(configuration) + path))
            .header("Authorization", "Bearer " + accessToken(configuration))
            .header("Accept", "application/json");
    }

    private String accessToken(Map<ConfigurationKeys, ConfigurationManager.MaybeConfiguration> configuration) throws Exception {
        var endpoint = endpoint(configuration);
        var clientId = configuration.get(PAYU_CLIENT_ID).getRequiredValue();
        var clientSecret = configuration.get(PAYU_CLIENT_SECRET).getRequiredValue();
        var cacheKey = endpoint + "|" + clientId;
        var cached = accessTokens.get(cacheKey);
        var now = Instant.now(clockProvider.getClock());
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.value();
        }
        var form = "grant_type=client_credentials"
            + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
            + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);
        var request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint + "/pl/standard/user/oauth/authorize"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        var body = parseObject(response.body());
        if (!HttpUtils.callSuccessful(response) || body == null || !body.has("access_token")) {
            throw new IllegalStateException("PayU authorization failed (HTTP " + response.statusCode() + ")");
        }
        // renew one minute before the actual expiration
        var expiresAt = now.plusSeconds(Math.max(0, body.get("expires_in").getAsLong() - 60));
        var token = new AccessToken(body.get("access_token").getAsString(), expiresAt);
        accessTokens.put(cacheKey, token);
        return token.value();
    }

    private static String endpoint(Map<ConfigurationKeys, ConfigurationManager.MaybeConfiguration> configuration) {
        if (configuration.get(PAYU_LIVE_MODE).getValueAsBooleanOrDefault()) {
            return LIVE_ENDPOINT;
        }
        return SANDBOX_ENDPOINT;
    }

    private static boolean isSuccess(JsonObject body) {
        return body != null
            && body.has("status")
            && "SUCCESS".equals(body.getAsJsonObject("status").get("statusCode").getAsString());
    }

    private static JsonObject parseObject(String body) {
        if (StringUtils.isBlank(body)) {
            return null;
        }
        try {
            var element = JsonParser.parseString(body);
            if (element.isJsonObject()) {
                return element.getAsJsonObject();
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Map<ConfigurationKeys, ConfigurationManager.MaybeConfiguration> loadConfiguration(PurchaseContext purchaseContext) {
        return configurationManager.getFor(ALL_KEYS, purchaseContext.getConfigurationLevel());
    }

    private record AccessToken(String value, Instant expiresAt) {
    }
}
