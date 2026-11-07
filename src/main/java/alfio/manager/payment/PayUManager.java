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
import alfio.model.transaction.capabilities.ServerInitiatedTransaction;
import alfio.model.transaction.capabilities.WebhookHandler;
import alfio.model.transaction.token.PayUToken;
import alfio.model.transaction.webhook.PayUWebhookPayload;
import alfio.repository.TicketReservationRepository;
import alfio.repository.TransactionRepository;
import alfio.util.ClockProvider;
import alfio.util.HttpUtils;
import alfio.util.MonetaryUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

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
public class PayUManager implements PaymentProvider, WebhookHandler, PaymentInfo, RefundRequest, ServerInitiatedTransaction {

    public static final String WEBHOOK_URL_TEMPLATE = "/api/payment/webhook/payu/reservation/{reservationId}";
    public static final String RETURN_URL_TEMPLATE = "/{purchaseContextType}/{purchaseContextIdentifier}/reservation/{reservationId}/payment/payu/return";
    public static final String ADDITIONAL_INFO_RESERVATION_ID = "reservationId";
    static final String SANDBOX_ENDPOINT = "https://secure.snd.payu.com";
    static final String LIVE_ENDPOINT = "https://secure.payu.com";
    private static final String EXT_ORDER_ID = "extOrderId";
    private static final String DEFAULT_CUSTOMER_IP = "127.0.0.1";
    // PayU answers HTTP 404 with this status code for an unknown order (confirmed on the sandbox)
    private static final String ORDER_NOT_FOUND_STATUS = "DATA_NOT_FOUND";
    private static final int MIN_VALIDITY_SECONDS = 300;
    private static final int DEFAULT_RESERVATION_TIMEOUT_MINUTES = 25;
    private static final long RESERVATION_VALIDITY_SLACK_SECONDS = 300;
    // every PayU call runs under the reservation lock: keep the worst case short (spec L7)
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    // PayU applies an accepted DELETE asynchronously: the order can still be NEW or PENDING right after it
    private static final int CANCEL_CONFIRMATION_READS = 5;
    private static final Duration CANCEL_CONFIRMATION_PAUSE = Duration.ofMillis(500);
    // the re-reads run under the reservation lock: the waiting for the cancellation is bounded as a whole
    private static final Duration CANCEL_CONFIRMATION_BUDGET = Duration.ofSeconds(3);
    // order statuses in which the buyer's money has been taken (or authorized)
    private static final Set<String> PAID_ORDER_STATUSES = Set.of("COMPLETED", "WAITING_FOR_CONFIRMATION");
    // used when the OAuth response has no expires_in
    private static final long DEFAULT_TOKEN_LIFETIME_SECONDS = 300;
    // a cached token is renewed this long before it expires
    private static final long TOKEN_RENEWAL_MARGIN_SECONDS = 60;
    static final Set<String> SUPPORTED_CURRENCIES = Set.of("PLN", "EUR", "USD", "CZK", "GBP", "HUF", "RON", "BGN", "DKK", "NOK", "SEK", "CHF");
    // languages accepted by PayU for buyer.language (hosted payment page)
    static final Set<String> BUYER_LANGUAGES = Set.of("pl", "en", "cs", "sk", "de", "es", "fr", "it", "hu", "ro", "bg", "uk",
        "lt", "lv", "et", "hr", "sl", "el", "pt", "ru", "tr");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger LOGGER = LoggerFactory.getLogger(PayUManager.class);
    private static final Set<ConfigurationKeys> REQUIRED_KEYS = EnumSet.of(PAYU_POS_ID, PAYU_CLIENT_ID, PAYU_CLIENT_SECRET, PAYU_SECOND_KEY, BASE_URL);
    private static final Set<ConfigurationKeys> ALL_KEYS = EnumSet.of(PAYU_ENABLED, PAYU_LIVE_MODE, PAYU_POS_ID, PAYU_CLIENT_ID, PAYU_CLIENT_SECRET, PAYU_SECOND_KEY, BASE_URL, RESERVATION_TIMEOUT);

    private final ConfigurationManager configurationManager;
    private final HttpClient httpClient;
    private final TicketReservationRepository ticketReservationRepository;
    private final TransactionRepository transactionRepository;
    private final ClockProvider clockProvider;
    // hidden test property, never used in live mode; null when not set
    private final String endpointOverride;
    private final AtomicBoolean liveOverrideReported = new AtomicBoolean(false);
    private Duration cancelConfirmationPause = CANCEL_CONFIRMATION_PAUSE;
    private Duration cancelConfirmationBudget = CANCEL_CONFIRMATION_BUDGET;
    // PayU tokens last about 12 hours; the entry itself is checked against its expiration (see accessToken)
    private final Cache<String, AccessToken> accessTokens = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofHours(12))
        .maximumSize(1000)
        .build();

    public PayUManager(ConfigurationManager configurationManager,
                       HttpClient httpClient,
                       TicketReservationRepository ticketReservationRepository,
                       TransactionRepository transactionRepository,
                       ClockProvider clockProvider) {
        this(configurationManager, httpClient, ticketReservationRepository, transactionRepository, clockProvider, null);
    }

    /**
     * @param endpointOverride hidden property for tests: when set, every sandbox PayU call goes to this URL. It is
     *                         ignored in live mode, so that live credentials are never sent to another host.
     */
    @Autowired
    public PayUManager(ConfigurationManager configurationManager,
                       HttpClient httpClient,
                       TicketReservationRepository ticketReservationRepository,
                       TransactionRepository transactionRepository,
                       ClockProvider clockProvider,
                       @Value("${alfio.payu.endpoint-override:}") String endpointOverride) {
        this.configurationManager = configurationManager;
        this.httpClient = httpClient;
        this.ticketReservationRepository = ticketReservationRepository;
        this.transactionRepository = transactionRepository;
        this.clockProvider = clockProvider;
        if (StringUtils.isNotBlank(endpointOverride)) {
            this.endpointOverride = Strings.CS.removeEnd(endpointOverride.trim(), "/");
            LOGGER.warn("PayU endpoint overridden: every sandbox PayU call goes to {} (ignored in live mode)", this.endpointOverride);
        } else {
            this.endpointOverride = null;
        }
    }

    /**
     * For tests: the pause between the re-reads that confirm a cancellation, and the time budget of those re-reads.
     */
    void setCancelConfirmationTiming(Duration pause, Duration budget) {
        this.cancelConfirmationPause = pause;
        this.cancelConfirmationBudget = budget;
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
        var purchaseContext = paymentContext.getPurchaseContext();
        if (purchaseContext != null && !SUPPORTED_CURRENCIES.contains(purchaseContext.getCurrency())) {
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
            var reconfirmation = reconfirmExistingOrder(existingTransaction.get(), configuration);
            if (reconfirmation.isPresent()) {
                return reconfirmation.get();
            }
        }

        return createOrder(spec, configuration);
    }

    /**
     * Applies the re-confirm state matrix to the order of an existing PENDING transaction.
     * <p>
     * Returns the result to give back to the caller, or empty when the previous order can no longer be paid
     * (confirmed CANCELED, not found, or cancelled by us) and a new order must be created.
     * <p>
     * {@code failed} is never returned here: core would then delete the PENDING transaction, and with it the
     * record of an order the buyer may still pay.
     */
    private Optional<PaymentResult> reconfirmExistingOrder(Transaction transaction,
                                                           Map<ConfigurationKeys, ConfigurationManager.MaybeConfiguration> configuration) {
        var orderId = transaction.getPaymentId();
        var lookup = retrieveOrder(configuration, orderId);
        if (lookup.outcome() == OrderLookupOutcome.NOT_FOUND) {
            LOGGER.info("PayU order {} not found, creating a new order for reservation {}", orderId, transaction.getReservationId());
            return Optional.empty();
        }
        if (lookup.outcome() == OrderLookupOutcome.ERROR) {
            LOGGER.warn("Cannot read PayU order {} of reservation {}, keeping the pending transaction", orderId, transaction.getReservationId());
            return Optional.of(PaymentResult.pending(orderId));
        }
        var status = orderStatus(lookup.order());
        switch (status) {
            case "COMPLETED": {
                var completion = completeTransaction(transaction, lookup.order());
                if (completion.matched()) {
                    return Optional.of(PaymentResult.successful(completion.token().getToken()));
                }
                return Optional.of(PaymentResult.pending(orderId));
            }
            case "CANCELED":
                return Optional.empty();
            case "NEW": {
                if (cancelOrder(configuration, orderId) == CancelOutcome.CANCELLED) {
                    return Optional.empty();
                }
                LOGGER.warn("PayU order {} was NEW and is not confirmed cancelled, not creating a new one (the next check settles it)", orderId);
                return Optional.of(PaymentResult.pending(orderId));
            }
            case "WAITING_FOR_CONFIRMATION":
                // POS without automatic collection: we confirm the payment right away
                confirmOrder(configuration, orderId);
                return Optional.of(PaymentResult.pending(orderId));
            default:
                // PENDING (being paid) or a status we do not know: the order may still be paid
                return Optional.of(PaymentResult.pending(orderId));
        }
    }

    /**
     * Marks the transaction COMPLETE when the PayU order matches it (amount, currency and extOrderId).
     * On a mismatch nothing is changed and the mismatch is logged.
     */
    private CompletionResult completeTransaction(Transaction transaction, JsonObject order) {
        var orderId = transaction.getPaymentId();
        var expectedAmount = Integer.toString(transaction.getPriceInCents());
        var actualAmount = stringOrNull(order, "totalAmount");
        var actualCurrency = stringOrNull(order, "currencyCode");
        var expectedExtOrderId = metadataValue(transaction, EXT_ORDER_ID);
        var actualExtOrderId = stringOrNull(order, EXT_ORDER_ID);
        // an order without extOrderId cannot be proven to belong to a transaction that has one
        boolean extOrderIdMatches = expectedExtOrderId == null || expectedExtOrderId.equals(actualExtOrderId);
        boolean matches = expectedAmount.equals(actualAmount)
            && transaction.getCurrency() != null
            && transaction.getCurrency().equals(actualCurrency)
            && extOrderIdMatches;
        if (!matches) {
            LOGGER.error("PayU order {} of reservation {} does not match its transaction: expected amount {} {} extOrderId {}, got amount {} {} extOrderId {}",
                orderId, transaction.getReservationId(), expectedAmount, transaction.getCurrency(), expectedExtOrderId,
                actualAmount, actualCurrency, actualExtOrderId);
            return new CompletionResult(false, null);
        }
        transactionRepository.update(transaction.getId(), orderId, orderId,
            ZonedDateTime.now(clockProvider.getClock()), transaction.getPlatformFee(), transaction.getGatewayFee(),
            Transaction.Status.COMPLETE, transaction.getMetadata());
        return new CompletionResult(true, new PayUToken(orderId));
    }

    private static String metadataValue(Transaction transaction, String key) {
        var metadata = transaction.getMetadata();
        if (metadata == null) {
            return null;
        }
        return metadata.get(key);
    }

    private PaymentResult createOrder(PaymentSpecification spec,
                                      Map<ConfigurationKeys, ConfigurationManager.MaybeConfiguration> configuration) {
        var purchaseContext = spec.getPurchaseContext();
        var reservationId = spec.getReservationId();
        try {
            var reservation = ticketReservationRepository.findReservationById(reservationId);
            var description = configurationManager.getShortReservationID(purchaseContext, reservation) + " - " + purchaseContext.getDisplayName();
            var extOrderId = generateExtOrderId(reservationId);
            var now = Instant.now(clockProvider.getClock());
            var validityTime = computeValidityTime(reservation, configuration, now);
            var orderBody = buildOrderRequest(spec, configuration, description, extOrderId, validityTime);
            // PayU answers with HTTP 302 and a JSON body: the HttpClient must not follow redirects (default: NEVER)
            var response = sendAuthorized(configuration, "/api/v2_1/orders", builder -> builder
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(orderBody.toString())));
            var responseBody = parseObject(response.body());
            if (!isSuccess(responseBody)) {
                LOGGER.warn("PayU order creation failed with status {}, body {}", response.statusCode(), response.body());
                return PaymentResult.failed("error.STEP2_PAYU_unexpected");
            }
            var orderId = stringOrNull(responseBody, "orderId");
            var redirectUri = stringOrNull(responseBody, "redirectUri");
            if (orderId == null || redirectUri == null) {
                // PayU may have created an order that alf.io cannot track: it expires unpaid, as the buyer never gets its link
                LOGGER.error("PayU accepted the order {} of reservation {} but its response has no orderId or redirectUri (orderId {}): status {}, body {}",
                    extOrderId, reservationId, orderId, response.statusCode(), response.body());
                return PaymentResult.failed("error.STEP2_PAYU_unexpected");
            }
            ticketReservationRepository.updateReservationStatus(reservationId, EXTERNAL_PROCESSING_PAYMENT.toString());
            // keep the reservation alive while the PayU order can still be paid
            ticketReservationRepository.updateValidity(reservationId, Date.from(now.plusSeconds(validityTime + RESERVATION_VALIDITY_SLACK_SECONDS)));
            invalidateExistingTransactions(reservationId, transactionRepository);
            transactionRepository.insert(orderId, orderId, reservationId,
                ZonedDateTime.now(clockProvider.withZone(purchaseContext.getZoneId())),
                spec.getPriceWithVAT(), purchaseContext.getCurrency(), "PayU payment",
                PaymentProxy.PAYU.name(), 0L, 0L, Transaction.Status.PENDING, Map.of(EXT_ORDER_ID, extOrderId));
            return PaymentResult.redirect(redirectUri);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.error("PayU order creation interrupted", e);
            return PaymentResult.failed("error.STEP2_PAYU_unexpected");
        } catch (Exception e) {
            LOGGER.error("Unexpected error while creating PayU order", e);
            return PaymentResult.failed("error.STEP2_PAYU_unexpected");
        }
    }

    /**
     * The extOrderId must be unique per POS: the random suffix prevents collisions when two orders are created
     * for the same reservation within the same millisecond (or under a fixed clock).
     */
    private static String generateExtOrderId(String reservationId) {
        var suffix = new byte[4];
        RANDOM.nextBytes(suffix);
        return reservationId + "-" + HexFormat.of().formatHex(suffix);
    }

    /**
     * PayU order validity (seconds): the time left on the reservation, but at least {@value #MIN_VALIDITY_SECONDS}.
     * When the reservation validity is not available, the configured reservation timeout is used.
     */
    private static long computeValidityTime(TicketReservation reservation,
                                            Map<ConfigurationKeys, ConfigurationManager.MaybeConfiguration> configuration,
                                            Instant now) {
        long seconds;
        if (reservation != null && reservation.getValidity() != null) {
            seconds = reservation.getValidity().toInstant().getEpochSecond() - now.getEpochSecond();
        } else {
            seconds = 60L * reservationTimeoutMinutes(configuration);
        }
        return Math.max(seconds, MIN_VALIDITY_SECONDS);
    }

    private static int reservationTimeoutMinutes(Map<ConfigurationKeys, ConfigurationManager.MaybeConfiguration> configuration) {
        var timeout = configuration.get(RESERVATION_TIMEOUT);
        if (timeout == null) {
            return DEFAULT_RESERVATION_TIMEOUT_MINUTES;
        }
        return timeout.getValueAsIntOrDefault(DEFAULT_RESERVATION_TIMEOUT_MINUTES);
    }

    JsonObject buildOrderRequest(PaymentSpecification spec,
                                 Map<ConfigurationKeys, ConfigurationManager.MaybeConfiguration> configuration,
                                 String description,
                                 String extOrderId,
                                 long validityTime) {
        var baseUrl = StringUtils.removeEnd(configuration.get(BASE_URL).getRequiredValue(), "/");
        var purchaseContext = spec.getPurchaseContext();
        var amount = Integer.toString(spec.getPriceWithVAT());

        var body = new JsonObject();
        body.addProperty("notifyUrl", baseUrl + UriComponentsBuilder.fromPath(WEBHOOK_URL_TEMPLATE).buildAndExpand(spec.getReservationId()).toUriString());
        body.addProperty("continueUrl", baseUrl + UriComponentsBuilder.fromPath(RETURN_URL_TEMPLATE)
            .buildAndExpand(purchaseContext.getType().getUrlComponent(), purchaseContext.getPublicIdentifier(), spec.getReservationId()).toUriString());
        body.addProperty("customerIp", resolveCustomerIp(spec));
        body.addProperty("merchantPosId", configuration.get(PAYU_POS_ID).getRequiredValue());
        body.addProperty("description", StringUtils.abbreviate(description, 4000));
        body.addProperty("currencyCode", purchaseContext.getCurrency());
        body.addProperty("totalAmount", amount);
        body.addProperty(EXT_ORDER_ID, extOrderId);
        body.addProperty("validityTime", Long.toString(validityTime));

        var buyer = new JsonObject();
        buyer.addProperty("email", spec.getEmail());
        var customerName = spec.getCustomerName();
        if (customerName != null && StringUtils.isNotBlank(customerName.getFirstName())) {
            buyer.addProperty("firstName", customerName.getFirstName());
        }
        if (customerName != null && StringUtils.isNotBlank(customerName.getLastName())) {
            buyer.addProperty("lastName", customerName.getLastName());
        }
        if (spec.getLocale() != null && BUYER_LANGUAGES.contains(spec.getLocale().getLanguage())) {
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

    /**
     * PayU requires the buyer's IP address. The controller puts it in the payment specification; when it is missing
     * (for example, a payment not started from the public frontend), the loopback address is sent.
     * <p>
     * PayU accepts an IPv6 address (confirmed on the sandbox), but refuses with INVALID_CUSTOMER_IP one in brackets,
     * as Jetty returns it, or with a zone index: both are removed.
     */
    private static String resolveCustomerIp(PaymentSpecification spec) {
        String customerIp = spec.getCustomerIp();
        if (customerIp == null || customerIp.isBlank()) {
            return DEFAULT_CUSTOMER_IP;
        }
        String address = customerIp.trim();
        if (address.startsWith("[") && address.endsWith("]")) {
            address = address.substring(1, address.length() - 1);
        }
        int zoneIndex = address.indexOf('%');
        if (zoneIndex > 0) {
            address = address.substring(0, zoneIndex);
        }
        return address;
    }

    @Override
    public Optional<TransactionWebhookPayload> parseTransactionPayload(String body,
                                                                       String signature,
                                                                       Map<String, String> additionalInfo,
                                                                       PaymentContext paymentContext) {
        var configuration = configurationManager.getFor(ALL_KEYS, paymentContext.getConfigurationLevel());
        if (!PayUSignature.isValid(signature, body, configuration.get(PAYU_SECOND_KEY).getValueOrNull())) {
            LOGGER.warn("Discarding PayU notification with invalid signature");
            return Optional.empty();
        }
        var json = parseObject(body);
        if (json == null) {
            LOGGER.warn("Discarding signed PayU notification with unparseable body");
            return Optional.empty();
        }
        var reservationId = additionalInfo.get(ADDITIONAL_INFO_RESERVATION_ID);
        return Optional.of(toWebhookPayload(json, reservationId));
    }

    /**
     * Classifies a signed notification (spec §1, B5). It always gives a payload, so that refunds and notifications
     * that cannot be matched are acknowledged instead of being retried by PayU.
     */
    private static PayUWebhookPayload toWebhookPayload(JsonObject json, String reservationId) {
        if (json.has("order") && json.get("order").isJsonObject()) {
            var order = json.getAsJsonObject("order");
            var orderId = stringOrNull(order, "orderId");
            var extOrderId = stringOrNull(order, EXT_ORDER_ID);
            if (extOrderId != null && (reservationId == null || !extOrderId.startsWith(reservationId))) {
                LOGGER.warn("PayU notification for order {} ({}) does not match reservation {}", orderId, extOrderId, reservationId);
                return PayUWebhookPayload.unrecognized(reservationId, orderId);
            }
            return PayUWebhookPayload.order(reservationId, orderId, stringOrNull(order, "status"));
        }
        var topLevelOrderId = stringOrNull(json, "orderId");
        if (json.has("refund") && json.get("refund").isJsonObject()) {
            return PayUWebhookPayload.refund(reservationId, topLevelOrderId);
        }
        LOGGER.warn("Unrecognized PayU notification for reservation {}", reservationId);
        return PayUWebhookPayload.unrecognized(reservationId, topLevelOrderId);
    }

    @Override
    public PaymentWebhookResult processWebhook(TransactionWebhookPayload payload, Transaction transaction, PaymentContext paymentContext) {
        if (!(payload instanceof PayUWebhookPayload payUPayload) || payUPayload.getKind() != PayUWebhookPayload.Kind.ORDER) {
            return PaymentWebhookResult.notRelevant("not a PayU order notification");
        }
        var notifiedOrderId = payUPayload.getOrderId();
        boolean sameOrder = notifiedOrderId != null && notifiedOrderId.equals(transaction.getPaymentId());
        if (sameOrder && transaction.getStatus() == Transaction.Status.PENDING) {
            return checkOrderStatus(transaction, loadConfiguration(paymentContext.getPurchaseContext()),
                PaymentWebhookResult.notRelevant("order not paid yet"));
        }
        var notifiedStatus = payUPayload.getOrderStatus();
        boolean paid = notifiedStatus != null && PAID_ORDER_STATUSES.contains(notifiedStatus);
        boolean alreadyCompleted = sameOrder && transaction.getStatus() == Transaction.Status.COMPLETE;
        if (paid && !alreadyCompleted) {
            LOGGER.error("PayU notified the {} order {} for reservation {}, whose transaction ({}) refers to order {}: money may be captured without a reservation; check the PayU panel and refund if needed",
                notifiedStatus, notifiedOrderId, transaction.getReservationId(), transaction.getStatus(), transaction.getPaymentId());
        }
        if (!sameOrder) {
            return PaymentWebhookResult.notRelevant("notification refers to a different PayU order");
        }
        return PaymentWebhookResult.notRelevant("transaction is not pending");
    }

    /**
     * Also used by the stuck-payment check. A NEW order maps to {@code error}, which changes nothing: NOT_RELEVANT
     * would mark the reservation STUCK, and TRANSACTION_INITIATED would report a payment in progress.
     * The {@code reservation} argument is not used (it may be null).
     */
    @Override
    public PaymentWebhookResult forceTransactionCheck(TicketReservation reservation, Transaction transaction, PaymentContext paymentContext) {
        return checkOrderStatus(transaction, loadConfiguration(paymentContext.getPurchaseContext()),
            PaymentWebhookResult.error("PayU order not paid yet"));
    }

    /**
     * Applies the PayU order status to the transaction (spec §3).
     *
     * @param newOrderResult the result for an order still NEW (not paid yet), which differs between the webhook and the force-check
     */
    PaymentWebhookResult checkOrderStatus(Transaction transaction,
                                          Map<ConfigurationKeys, ConfigurationManager.MaybeConfiguration> configuration,
                                          PaymentWebhookResult newOrderResult) {
        var orderId = transaction.getPaymentId();
        var lookup = retrieveOrder(configuration, orderId);
        if (lookup.outcome() == OrderLookupOutcome.NOT_FOUND) {
            return PaymentWebhookResult.error("PayU order " + orderId + " not found");
        }
        if (lookup.outcome() == OrderLookupOutcome.ERROR) {
            return PaymentWebhookResult.error("cannot retrieve PayU order " + orderId);
        }
        var status = orderStatus(lookup.order());
        switch (status) {
            case "COMPLETED": {
                var completion = completeTransaction(transaction, lookup.order());
                if (completion.matched()) {
                    return PaymentWebhookResult.successful(completion.token());
                }
                return PaymentWebhookResult.error("PayU order " + orderId + " does not match its transaction");
            }
            case "CANCELED":
                LOGGER.debug("PayU order {} canceled, invalidating transaction {}", orderId, transaction.getId());
                transactionRepository.invalidateById(transaction.getId());
                ticketReservationRepository.updateValidity(transaction.getReservationId(),
                    Date.from(Instant.now(clockProvider.getClock()).plusSeconds(60L * reservationTimeoutMinutes(configuration))));
                return PaymentWebhookResult.cancelled();
            case "WAITING_FOR_CONFIRMATION":
                // POS without automatic collection: we confirm the payment right away
                confirmOrder(configuration, orderId);
                return PaymentWebhookResult.pending();
            case "NEW":
                return newOrderResult;
            default:
                // PENDING (being paid) or a status we do not know
                return PaymentWebhookResult.pending();
        }
    }

    /**
     * PayU orders are always created through {@link #doPayment}: the attendee is redirected to the hosted payment page.
     * The token asks core to reload the reservation, which is then reconciled through {@link #forceTransactionCheck}.
     */
    @Override
    public TransactionInitializationToken initTransaction(PaymentSpecification paymentSpecification, Map<String, List<String>> params) {
        return errorToken("PayU does not support server-initiated transactions", true);
    }

    @Override
    public TransactionInitializationToken errorToken(String errorMessage, boolean reservationStatusChanged) {
        return new PayUErrorToken(errorMessage, reservationStatusChanged);
    }

    /**
     * Cancels the PayU order of a transaction that is being discarded.
     *
     * @return true only when the order can no longer be paid: a paid, authorized or unknown-state order is never
     * reported as discarded
     */
    @Override
    public boolean discardTransaction(Transaction transaction, PurchaseContext purchaseContext) {
        var configuration = loadConfiguration(purchaseContext);
        var orderId = transaction.getPaymentId();
        var lookup = retrieveOrder(configuration, orderId);
        if (lookup.outcome() == OrderLookupOutcome.NOT_FOUND) {
            return true;
        }
        if (lookup.outcome() == OrderLookupOutcome.ERROR) {
            LOGGER.warn("Cannot read PayU order {}, not discarding transaction {}", orderId, transaction.getId());
            return false;
        }
        var status = orderStatus(lookup.order());
        switch (status) {
            case "CANCELED":
                return true;
            case "NEW":
            case "PENDING":
                // DELETE cancels a NEW or PENDING order; cancelOrder confirms it by re-reading the order
                return cancelOrder(configuration, orderId) == CancelOutcome.CANCELLED;
            default:
                LOGGER.warn("PayU order {} is {}, not discarding transaction {}", orderId, status, transaction.getId());
                return false;
        }
    }

    @Override
    public Optional<PaymentInformation> getInfo(Transaction transaction, PurchaseContext purchaseContext) {
        var configuration = loadConfiguration(purchaseContext);
        var lookup = retrieveOrder(configuration, transaction.getPaymentId());
        if (lookup.outcome() != OrderLookupOutcome.FOUND) {
            return Optional.empty();
        }
        var order = lookup.order();
        var totalAmount = stringOrNull(order, "totalAmount");
        var currency = stringOrNull(order, "currencyCode");
        if (totalAmount == null || currency == null) {
            LOGGER.warn("PayU order {} has no amount or currency", transaction.getPaymentId());
            return Optional.empty();
        }
        String paidAmount;
        try {
            paidAmount = MonetaryUtil.formatCents(Long.parseLong(totalAmount), currency);
        } catch (NumberFormatException e) {
            LOGGER.warn("PayU order {} has an unexpected amount {}", transaction.getPaymentId(), totalAmount);
            return Optional.empty();
        }
        return Optional.of(new PaymentInformation(paidAmount, refundedAmount(configuration, transaction.getPaymentId(), currency), null, null));
    }

    /**
     * @return the sum of the FINALIZED refunds of an order, or null when there is none or the refunds cannot be read
     */
    private String refundedAmount(Map<ConfigurationKeys, ConfigurationManager.MaybeConfiguration> configuration, String orderId, String currency) {
        var refunds = retrieveRefunds(configuration, orderId);
        if (refunds.isEmpty()) {
            return null;
        }
        long refundedCents = 0;
        boolean anyFinalized = false;
        for (var element : refunds.get()) {
            if (!element.isJsonObject()) {
                continue;
            }
            var refund = element.getAsJsonObject();
            var amount = stringOrNull(refund, "amount");
            if (!"FINALIZED".equals(stringOrNull(refund, "status")) || amount == null) {
                continue;
            }
            try {
                refundedCents += Long.parseLong(amount);
                anyFinalized = true;
            } catch (NumberFormatException e) {
                LOGGER.warn("PayU refund of order {} has an unexpected amount {}", orderId, amount);
            }
        }
        if (!anyFinalized) {
            return null;
        }
        return MonetaryUtil.formatCents(refundedCents, currency);
    }

    /**
     * Refunds a PayU order, in full ({@code amount} null) or in part. The {@code extRefundId} is deterministic:
     * {@code <reservationId>-refund-<amountCents>-<n>}, where n = 1 + the refunds already listed for the order.
     * <p>
     * It protects against:
     * <ul>
     *     <li>a POST that times out: the refunds are re-read within the same call and the refund counts as done
     *     only if one with this id is listed;</li>
     *     <li>concurrent duplicates: two requests computed from the same listing carry the same id, and PayU accepts
     *     an {@code extRefundId} only once per order.</li>
     * </ul>
     * It does not protect against an operator retry after a refund that actually landed (for example, after the
     * timeout re-read could not confirm it): the listing then has one more refund, n is different, and a second
     * refund is sent. Check the PayU panel before retrying a refund that was reported as failed.
     */
    @Override
    public boolean refund(Transaction transaction, PurchaseContext purchaseContext, Integer amount) {
        var configuration = loadConfiguration(purchaseContext);
        var orderId = transaction.getPaymentId();
        int amountCents;
        if (amount != null) {
            amountCents = amount;
        } else {
            amountCents = transaction.getPriceInCents();
        }
        var existingRefunds = retrieveRefunds(configuration, orderId);
        if (existingRefunds.isEmpty()) {
            LOGGER.warn("Cannot read the refunds of PayU order {}, not refunding", orderId);
            return false;
        }
        var extRefundId = transaction.getReservationId() + "-refund-" + amountCents + "-" + (existingRefunds.get().size() + 1);
        var refund = new JsonObject();
        refund.addProperty("description", "Refund " + transaction.getReservationId());
        refund.addProperty("extRefundId", extRefundId);
        if (amount != null) {
            refund.addProperty("amount", Integer.toString(amount));
        }
        var body = new JsonObject();
        body.add("refund", refund);
        try {
            var response = sendAuthorized(configuration, "/api/v2_1/orders/" + orderId + "/refunds", builder -> builder
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())));
            if (!isSuccess(parseObject(response.body()))) {
                LOGGER.warn("PayU refund {} failed with status {}, body {}", extRefundId, response.statusCode(), response.body());
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("PayU refund {} interrupted", extRefundId, e);
            return false;
        } catch (IOException e) {
            LOGGER.warn("PayU refund {} got no answer, re-reading the refunds of order {}", extRefundId, orderId, e);
            return refundExists(configuration, orderId, extRefundId);
        } catch (Exception e) {
            LOGGER.error("Unexpected error while refunding PayU order {}", orderId, e);
            return false;
        }
    }

    private boolean refundExists(Map<ConfigurationKeys, ConfigurationManager.MaybeConfiguration> configuration, String orderId, String extRefundId) {
        var refunds = retrieveRefunds(configuration, orderId);
        if (refunds.isEmpty()) {
            LOGGER.warn("Cannot re-read the refunds of PayU order {}, refund {} not confirmed", orderId, extRefundId);
            return false;
        }
        for (var element : refunds.get()) {
            if (element.isJsonObject() && extRefundId.equals(stringOrNull(element.getAsJsonObject(), "extRefundId"))) {
                return true;
            }
        }
        LOGGER.warn("PayU refund {} of order {} not found", extRefundId, orderId);
        return false;
    }

    /**
     * Reads the refunds of a PayU order (GET {@code /api/v2_1/orders/{id}/refunds}). This call has no side effects.
     *
     * @return the {@code refunds} array, or empty when it cannot be read
     */
    private Optional<JsonArray> retrieveRefunds(Map<ConfigurationKeys, ConfigurationManager.MaybeConfiguration> configuration, String orderId) {
        try {
            var response = sendAuthorized(configuration, "/api/v2_1/orders/" + orderId + "/refunds", HttpRequest.Builder::GET);
            var body = parseObject(response.body());
            if (!HttpUtils.callSuccessful(response) || body == null || !body.has("refunds") || !body.get("refunds").isJsonArray()) {
                LOGGER.warn("Cannot retrieve the refunds of PayU order {}: status {}, body {}", orderId, response.statusCode(), response.body());
                return Optional.empty();
            }
            return Optional.of(body.getAsJsonArray("refunds"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted while retrieving the refunds of PayU order {}", orderId);
            return Optional.empty();
        } catch (Exception e) {
            LOGGER.warn("Unexpected error while retrieving the refunds of PayU order {}", orderId, e);
            return Optional.empty();
        }
    }

    @Override
    public boolean requiresSignedBody() {
        return true;
    }

    /**
     * Reads a PayU order. This call has no side effects.
     * <ul>
     *     <li>FOUND: HTTP 2xx, body status SUCCESS and a non-empty {@code orders} array</li>
     *     <li>NOT_FOUND: HTTP 404, or body status {@value #ORDER_NOT_FOUND_STATUS}</li>
     *     <li>ERROR: anything else (timeout, I/O, interruption, 5xx, unexpected body)</li>
     * </ul>
     */
    private OrderLookup retrieveOrder(Map<ConfigurationKeys, ConfigurationManager.MaybeConfiguration> configuration, String orderId) {
        try {
            var response = sendAuthorized(configuration, "/api/v2_1/orders/" + orderId, HttpRequest.Builder::GET);
            var body = parseObject(response.body());
            if (response.statusCode() == 404 || ORDER_NOT_FOUND_STATUS.equals(statusCode(body))) {
                return new OrderLookup(OrderLookupOutcome.NOT_FOUND, null);
            }
            if (!HttpUtils.callSuccessful(response) || !isSuccess(body) || !body.has("orders") || !body.get("orders").isJsonArray()) {
                LOGGER.warn("Cannot retrieve PayU order {}: status {}, body {}", orderId, response.statusCode(), response.body());
                return new OrderLookup(OrderLookupOutcome.ERROR, null);
            }
            var orders = body.getAsJsonArray("orders");
            if (orders.isEmpty() || !orders.get(0).isJsonObject()) {
                LOGGER.warn("PayU returned no order for {}", orderId);
                return new OrderLookup(OrderLookupOutcome.ERROR, null);
            }
            return new OrderLookup(OrderLookupOutcome.FOUND, orders.get(0).getAsJsonObject());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted while retrieving PayU order {}", orderId);
            return new OrderLookup(OrderLookupOutcome.ERROR, null);
        } catch (Exception e) {
            LOGGER.error("Unexpected error while retrieving PayU order {}", orderId, e);
            return new OrderLookup(OrderLookupOutcome.ERROR, null);
        }
    }

    /**
     * Cancels a PayU order (DELETE), then always re-reads it to confirm the cancellation.
     * <p>
     * The DELETE response alone proves nothing: PayU answers HTTP 200 with body status SUCCESS even for an order
     * that is already COMPLETED, and the order stays COMPLETED (confirmed on the sandbox). The order counts as
     * cancelled only when the re-read shows it CANCELED or not found, whatever the DELETE answered (including a
     * timeout or an I/O error).
     * <p>
     * On the sandbox, a DELETE of a PENDING order answers SUCCESS and the order becomes CANCELED, and no money is
     * taken afterwards. PayU applies an accepted DELETE asynchronously, so a NEW or PENDING order is re-read up to
     * {@value #CANCEL_CONFIRMATION_READS} times, with a short pause, within an overall time budget (the re-reads run
     * under the reservation lock); when the budget is used up the order is reported as not cancelled.
     */
    private CancelOutcome cancelOrder(Map<ConfigurationKeys, ConfigurationManager.MaybeConfiguration> configuration, String orderId) {
        boolean deleteAccepted = false;
        try {
            var response = sendAuthorized(configuration, "/api/v2_1/orders/" + orderId, HttpRequest.Builder::DELETE);
            if (isSuccess(parseObject(response.body()))) {
                deleteAccepted = true;
            } else {
                LOGGER.warn("PayU did not accept the cancellation of order {}: status {}, body {}", orderId, response.statusCode(), response.body());
            }
        } catch (InterruptedException e) {
            // the re-read could not run on an interrupted thread: the order may still be payable
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted while cancelling PayU order {}", orderId);
            return CancelOutcome.NOT_CANCELLED;
        } catch (Exception e) {
            LOGGER.warn("Unexpected error while cancelling PayU order {}", orderId, e);
        }
        long pollingStart = System.nanoTime();
        var lookup = retrieveOrder(configuration, orderId);
        int reads = 1;
        while (deleteAccepted && reads < CANCEL_CONFIRMATION_READS && isCancellationInProgress(lookup)) {
            var elapsed = Duration.ofNanos(System.nanoTime() - pollingStart);
            if (elapsed.plus(cancelConfirmationPause).compareTo(cancelConfirmationBudget) >= 0) {
                LOGGER.warn("PayU order {} still not cancelled after {} re-read(s) in {} ms, giving up", orderId, reads, elapsed.toMillis());
                break;
            }
            try {
                Thread.sleep(cancelConfirmationPause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.warn("Interrupted while waiting for the cancellation of PayU order {}", orderId);
                return CancelOutcome.NOT_CANCELLED;
            }
            lookup = retrieveOrder(configuration, orderId);
            reads++;
        }
        if (lookup.outcome() == OrderLookupOutcome.NOT_FOUND) {
            return CancelOutcome.CANCELLED;
        }
        if (lookup.outcome() == OrderLookupOutcome.FOUND) {
            var status = orderStatus(lookup.order());
            if ("CANCELED".equals(status)) {
                LOGGER.debug("PayU order {} confirmed cancelled after {} re-read(s)", orderId, reads);
                return CancelOutcome.CANCELLED;
            }
            if ("COMPLETED".equals(status)) {
                LOGGER.error("PayU order {} is COMPLETED after the cancellation request: it was paid and is not cancelled", orderId);
                return CancelOutcome.NOT_CANCELLED;
            }
            LOGGER.warn("PayU order {} is {} after the cancellation request, not cancelled", orderId, status);
            return CancelOutcome.NOT_CANCELLED;
        }
        LOGGER.warn("Cannot re-read PayU order {} after the cancellation request, assuming it is not cancelled", orderId);
        return CancelOutcome.NOT_CANCELLED;
    }

    /**
     * An accepted cancellation is still being applied while the order is NEW or PENDING.
     */
    private boolean isCancellationInProgress(OrderLookup lookup) {
        if (lookup.outcome() != OrderLookupOutcome.FOUND) {
            return false;
        }
        var status = orderStatus(lookup.order());
        return "NEW".equals(status) || "PENDING".equals(status);
    }

    private void confirmOrder(Map<ConfigurationKeys, ConfigurationManager.MaybeConfiguration> configuration, String orderId) {
        var body = new JsonObject();
        body.addProperty("orderId", orderId);
        body.addProperty("orderStatus", "COMPLETED");
        try {
            var response = sendAuthorized(configuration, "/api/v2_1/orders/" + orderId + "/status", builder -> builder
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body.toString())));
            if (!isSuccess(parseObject(response.body()))) {
                LOGGER.warn("Cannot confirm PayU order {}: status {}, body {}", orderId, response.statusCode(), response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOGGER.error("Unexpected error while confirming PayU order {}", orderId, e);
        }
    }

    /**
     * Sends an authenticated PayU API call. On HTTP 401 the cached token is dropped and the call is retried once
     * with a fresh token; a second 401 is returned to the caller, which handles it as an error.
     *
     * @param method sets the HTTP method, body and extra headers on the request builder
     */
    private HttpResponse<String> sendAuthorized(Map<ConfigurationKeys, ConfigurationManager.MaybeConfiguration> configuration,
                                                String path,
                                                UnaryOperator<HttpRequest.Builder> method) throws IOException, InterruptedException {
        var response = httpClient.send(authorizedRequest(configuration, path, method), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 401) {
            return response;
        }
        LOGGER.info("PayU rejected the access token for {}, retrying with a new one", path);
        accessTokens.invalidate(tokenCacheKey(configuration));
        return httpClient.send(authorizedRequest(configuration, path, method), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpRequest authorizedRequest(Map<ConfigurationKeys, ConfigurationManager.MaybeConfiguration> configuration,
                                          String path,
                                          UnaryOperator<HttpRequest.Builder> method) throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder()
            .uri(URI.create(endpoint(configuration) + path))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer " + accessToken(configuration))
            .header("Accept", "application/json");
        return method.apply(builder).build();
    }

    private String accessToken(Map<ConfigurationKeys, ConfigurationManager.MaybeConfiguration> configuration) throws IOException, InterruptedException {
        var cacheKey = tokenCacheKey(configuration);
        var now = Instant.now(clockProvider.getClock());
        var cached = accessTokens.getIfPresent(cacheKey);
        if (cached != null && cached.expiresAt().minusSeconds(TOKEN_RENEWAL_MARGIN_SECONDS).isAfter(now)) {
            return cached.value();
        }
        var clientId = configuration.get(PAYU_CLIENT_ID).getRequiredValue();
        var clientSecret = configuration.get(PAYU_CLIENT_SECRET).getRequiredValue();
        var form = "grant_type=client_credentials"
            + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
            + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);
        var request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint(configuration) + "/pl/standard/user/oauth/authorize"))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        var body = parseObject(response.body());
        var value = stringOrNull(body, "access_token");
        if (!HttpUtils.callSuccessful(response) || value == null) {
            throw new IllegalStateException("PayU authorization failed (HTTP " + response.statusCode() + ")");
        }
        var token = new AccessToken(value, now.plusSeconds(tokenLifetimeSeconds(body)));
        accessTokens.put(cacheKey, token);
        return token.value();
    }

    private static long tokenLifetimeSeconds(JsonObject body) {
        var expiresIn = stringOrNull(body, "expires_in");
        if (expiresIn == null) {
            return DEFAULT_TOKEN_LIFETIME_SECONDS;
        }
        try {
            return Long.parseLong(expiresIn);
        } catch (NumberFormatException e) {
            LOGGER.warn("Unexpected PayU token lifetime {}, using {} seconds", expiresIn, DEFAULT_TOKEN_LIFETIME_SECONDS);
            return DEFAULT_TOKEN_LIFETIME_SECONDS;
        }
    }

    /**
     * The token depends on the endpoint and on the credentials. The secret is hashed, so that it is not kept in clear
     * as a cache key, and a changed secret gets a new token.
     */
    private String tokenCacheKey(Map<ConfigurationKeys, ConfigurationManager.MaybeConfiguration> configuration) {
        return endpoint(configuration)
            + "|" + configuration.get(PAYU_CLIENT_ID).getRequiredValue()
            + "|" + sha256Hex(configuration.get(PAYU_CLIENT_SECRET).getRequiredValue());
    }

    private static String sha256Hex(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * The endpoint override applies to the sandbox only: in live mode the live endpoint is always used, so that live
     * credentials never reach another host. The ignored override is logged as an error once.
     */
    private String endpoint(Map<ConfigurationKeys, ConfigurationManager.MaybeConfiguration> configuration) {
        if (configuration.get(PAYU_LIVE_MODE).getValueAsBooleanOrDefault()) {
            if (endpointOverride != null && liveOverrideReported.compareAndSet(false, true)) {
                LOGGER.error("PayU endpoint override {} is set but PayU is in live mode: the override is ignored and {} is used. Remove alfio.payu.endpoint-override",
                    endpointOverride, LIVE_ENDPOINT);
            }
            return LIVE_ENDPOINT;
        }
        if (endpointOverride != null) {
            return endpointOverride;
        }
        return SANDBOX_ENDPOINT;
    }

    private static boolean isSuccess(JsonObject body) {
        return "SUCCESS".equals(statusCode(body));
    }

    /**
     * @return {@code status.statusCode} of a PayU response body, or null when it is missing
     */
    private static String statusCode(JsonObject body) {
        if (body == null || !body.has("status") || !body.get("status").isJsonObject()) {
            return null;
        }
        return stringOrNull(body.getAsJsonObject("status"), "statusCode");
    }

    private static String orderStatus(JsonObject order) {
        var status = stringOrNull(order, "status");
        if (status == null) {
            return "";
        }
        return status;
    }

    private static String stringOrNull(JsonObject object, String property) {
        if (object == null || !object.has(property)) {
            return null;
        }
        var element = object.get(property);
        if (!element.isJsonPrimitive()) {
            return null;
        }
        return element.getAsString();
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

    private enum OrderLookupOutcome {
        FOUND, NOT_FOUND, ERROR
    }

    /**
     * Result of {@link #retrieveOrder}: {@code order} is set only when the outcome is FOUND.
     */
    private record OrderLookup(OrderLookupOutcome outcome, JsonObject order) {
    }

    private enum CancelOutcome {
        CANCELLED, NOT_CANCELLED
    }

    private record CompletionResult(boolean matched, PayUToken token) {
    }

    /**
     * Returned by {@link #errorToken}. PayU has no client-side initialization, so there is no client secret.
     */
    private static final class PayUErrorToken implements TransactionInitializationToken {

        private final String errorMessage;
        private final boolean reservationStatusChanged;

        private PayUErrorToken(String errorMessage, boolean reservationStatusChanged) {
            this.errorMessage = errorMessage;
            this.reservationStatusChanged = reservationStatusChanged;
        }

        @Override
        public String getClientSecret() {
            return null;
        }

        @Override
        public String getErrorMessage() {
            return errorMessage;
        }

        @Override
        public boolean isReservationStatusChanged() {
            return reservationStatusChanged;
        }

        @Override
        public String getToken() {
            return null;
        }

        @Override
        public PaymentMethod getPaymentMethod() {
            return StaticPaymentMethods.PAYU;
        }

        @Override
        public PaymentProxy getPaymentProvider() {
            return PaymentProxy.PAYU;
        }
    }
}
