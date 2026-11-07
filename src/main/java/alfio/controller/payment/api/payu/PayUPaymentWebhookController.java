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
import alfio.manager.support.PaymentWebhookResult;
import alfio.model.transaction.PaymentContext;
import alfio.model.transaction.PaymentProxy;
import alfio.model.transaction.Transaction;
import alfio.repository.TransactionRepository;
import alfio.util.RequestUtils;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.regex.Pattern;

import static alfio.manager.payment.PayUManager.ADDITIONAL_INFO_RESERVATION_ID;
import static alfio.manager.payment.PayUManager.PAID_ORDER_STATUSES;
import static alfio.manager.payment.PayUManager.WEBHOOK_URL_TEMPLATE;

@RestController
@AllArgsConstructor
public class PayUPaymentWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PayUPaymentWebhookController.class);
    static final String SIGNATURE_HEADER = "OpenPayu-Signature";
    static final String FALLBACK_SIGNATURE_HEADER = "X-OpenPayU-Signature";
    private static final String NOT_RELEVANT = "NOT_RELEVANT";
    // reservation ids are random UUIDs
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final TicketReservationManager ticketReservationManager;
    private final PurchaseContextManager purchaseContextManager;
    private final TransactionRepository transactionRepository;

    @PostMapping(WEBHOOK_URL_TEMPLATE)
    public ResponseEntity<String> receivePaymentNotification(HttpServletRequest request,
                                                             @PathVariable String reservationId) {
        var body = RequestUtils.readRequest(request).filter(StringUtils::isNotBlank);
        if (body.isEmpty()) {
            return ResponseEntity.badRequest().body("NOK");
        }
        var content = body.get();
        var purchaseContext = purchaseContextManager.findByReservationId(reservationId);
        if (purchaseContext.isEmpty()) {
            logUnknownReservation(reservationId, content);
            return ResponseEntity.ok(NOT_RELEVANT);
        }
        var result = ticketReservationManager.processTransactionWebhook(content, readSignature(request), PaymentProxy.PAYU,
            Map.of(ADDITIONAL_INFO_RESERVATION_ID, reservationId), new PaymentContext(purchaseContext.get(), reservationId));
        if (result.isSuccessful()) {
            return ResponseEntity.ok("OK");
        } else if (result.isError()) {
            // PayU retries the notification; the reason stays in the logs
            log.warn("PayU notification for reservation {} could not be processed: {}", RequestUtils.sanitizeForLog(reservationId), result.getReason());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("NOK");
        }
        if (result.getType() == PaymentWebhookResult.Type.NOT_RELEVANT) {
            alertOnDiscardedPaidOrder(reservationId, content);
        }
        return ResponseEntity.ok(result.getReason());
    }

    /**
     * The core discards a notification (NOT_RELEVANT) when the reservation has no valid transaction (an order
     * cancelled and invalidated, then paid anyway), when the transaction is not PENDING or the reservation cannot
     * be paid any more, and PayUManager when the notification refers to another order. Every NOT_RELEVANT result
     * comes after the signature check, so the body can be trusted here. A paid order discarded this way means money
     * may have been taken without a reservation, unless it is the order already recorded as paid (a duplicate or
     * late notification): that is checked on the transaction itself rather than on the result reason.
     */
    private void alertOnDiscardedPaidOrder(String reservationId, String content) {
        var order = childObject(parseObject(content), "order");
        var status = stringOrNull(order, "status");
        if (status == null || !PAID_ORDER_STATUSES.contains(status)) {
            return;
        }
        var orderId = stringOrNull(order, "orderId");
        if (isOrderRecordedAsPaid(reservationId, orderId)) {
            return;
        }
        log.error("PayU notified the {} order {} for reservation {}, but the notification was discarded: money may be captured without a reservation; check the PayU panel and refund if needed",
            RequestUtils.sanitizeForLog(status), RequestUtils.sanitizeForLog(orderId), RequestUtils.sanitizeForLog(reservationId));
    }

    /**
     * Whether the order is the one already recorded as paid for the reservation: its valid transaction is COMPLETE
     * and refers to that order. Reads the same valid transaction as the rest of the payment code.
     */
    private boolean isOrderRecordedAsPaid(String reservationId, String orderId) {
        if (orderId == null) {
            return false;
        }
        return transactionRepository.loadOptionalByReservationId(reservationId)
            .filter(transaction -> transaction.getStatus() == Transaction.Status.COMPLETE)
            .filter(transaction -> orderId.equals(transaction.getPaymentId()))
            .isPresent();
    }

    private static String readSignature(HttpServletRequest request) {
        var signature = StringUtils.trimToNull(request.getHeader(SIGNATURE_HEADER));
        if (signature == null) {
            signature = StringUtils.trimToNull(request.getHeader(FALLBACK_SIGNATURE_HEADER));
        }
        return signature;
    }

    /**
     * The body is not verified here (the signature is checked only for a known reservation): its values are only
     * logged, sanitized. A paid order for an unknown reservation means money may have been taken without a
     * reservation, so it is logged as an error, but only when the reservation id has the shape of a real one: the
     * body is unsigned, and a random path would otherwise raise a false alarm.
     */
    private static void logUnknownReservation(String reservationId, String content) {
        var root = parseObject(content);
        var order = childObject(root, "order");
        var orderId = stringOrNull(order, "orderId");
        if (orderId == null) {
            orderId = stringOrNull(root, "orderId");
        }
        var status = stringOrNull(order, "status");
        var safeOrderId = RequestUtils.sanitizeForLog(orderId);
        var safeStatus = RequestUtils.sanitizeForLog(status);
        var safeReservationId = RequestUtils.sanitizeForLog(reservationId);
        boolean paid = status != null && PAID_ORDER_STATUSES.contains(status);
        if (paid && UUID_PATTERN.matcher(reservationId).matches()) {
            log.error("Received PayU notification of a {} order {} for unknown reservation {}: money may be captured without a reservation; check the PayU panel and refund if needed",
                safeStatus, safeOrderId, safeReservationId);
        } else {
            log.warn("Received PayU notification for unknown reservation {} (orderId: {}, status: {})", safeReservationId, safeOrderId, safeStatus);
        }
    }

    private static JsonObject parseObject(String content) {
        try {
            var element = JsonParser.parseString(content);
            if (element.isJsonObject()) {
                return element.getAsJsonObject();
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static JsonObject childObject(JsonObject parent, String property) {
        if (parent == null || !parent.has(property) || !parent.get(property).isJsonObject()) {
            return null;
        }
        return parent.getAsJsonObject(property);
    }

    private static String stringOrNull(JsonObject object, String property) {
        if (object == null || !object.has(property) || !object.get(property).isJsonPrimitive()) {
            return null;
        }
        return object.get(property).getAsString();
    }
}
