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
import alfio.model.transaction.PaymentContext;
import alfio.model.transaction.PaymentProxy;
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
import java.util.Set;

import static alfio.manager.payment.PayUManager.ADDITIONAL_INFO_RESERVATION_ID;
import static alfio.manager.payment.PayUManager.WEBHOOK_URL_TEMPLATE;

@RestController
@AllArgsConstructor
public class PayUPaymentWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PayUPaymentWebhookController.class);
    static final String SIGNATURE_HEADER = "OpenPayu-Signature";
    static final String FALLBACK_SIGNATURE_HEADER = "X-OpenPayU-Signature";
    private static final String NOT_RELEVANT = "NOT_RELEVANT";
    private static final Set<String> PAID_ORDER_STATUSES = Set.of("COMPLETED", "WAITING_FOR_CONFIRMATION");

    private final TicketReservationManager ticketReservationManager;
    private final PurchaseContextManager purchaseContextManager;

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
            log.warn("PayU notification for reservation {} could not be processed: {}", reservationId, result.getReason());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("NOK");
        }
        return ResponseEntity.ok(result.getReason());
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
     * reservation, so it is logged as an error.
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
        if (status != null && PAID_ORDER_STATUSES.contains(status)) {
            log.error("Received PayU notification of a {} order {} for unknown reservation {}: money may be captured without a reservation; check the PayU panel and refund if needed",
                safeStatus, safeOrderId, reservationId);
        } else {
            log.warn("Received PayU notification for unknown reservation {} (orderId: {}, status: {})", reservationId, safeOrderId, safeStatus);
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
