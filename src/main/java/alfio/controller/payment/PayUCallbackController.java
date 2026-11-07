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
import alfio.manager.payment.PayUManager;
import alfio.model.PurchaseContext;
import alfio.util.RequestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Handles the browser coming back from the PayU hosted payment page (continueUrl).
 * PayU appends {@code error} (and possibly {@code errorCode}) when the customer abandoned or the payment failed.
 */
@Controller
public class PayUCallbackController {

    private static final Logger log = LoggerFactory.getLogger(PayUCallbackController.class);
    private static final String PURCHASE_CONTEXT_TEMPLATE = "/{purchaseContextType}/{purchaseContextIdentifier}";
    private static final String RESERVATION_TEMPLATE = PURCHASE_CONTEXT_TEMPLATE + "/reservation/{reservationId}";
    private static final String OVERVIEW_TEMPLATE = RESERVATION_TEMPLATE + "/overview";
    private static final String BOOK_TEMPLATE = RESERVATION_TEMPLATE + "/book";

    private final TicketReservationManager ticketReservationManager;
    private final PurchaseContextManager purchaseContextManager;

    public PayUCallbackController(TicketReservationManager ticketReservationManager, PurchaseContextManager purchaseContextManager) {
        this.ticketReservationManager = ticketReservationManager;
        this.purchaseContextManager = purchaseContextManager;
    }

    @GetMapping(PayUManager.RETURN_URL_TEMPLATE)
    public String payuReturn(@PathVariable PurchaseContext.PurchaseContextType purchaseContextType,
                             @PathVariable String purchaseContextIdentifier,
                             @PathVariable String reservationId,
                             @RequestParam(value = "error", required = false) String error,
                             @RequestParam(value = "errorCode", required = false) String errorCode) {
        var maybePurchaseContext = purchaseContextManager.findBy(purchaseContextType, purchaseContextIdentifier);
        if (maybePurchaseContext.isEmpty()) {
            return "redirect:/";
        }
        var purchaseContext = maybePurchaseContext.get();
        String type = purchaseContext.getType().getUrlComponent();
        String identifier = purchaseContext.getPublicIdentifier();
        var optionalReservation = ticketReservationManager.findById(reservationId);
        if (optionalReservation.isEmpty()) {
            return "redirect:" + UriComponentsBuilder.fromPath(PURCHASE_CONTEXT_TEMPLATE)
                .buildAndExpand(type, identifier).toUriString();
        }
        var reservation = optionalReservation.get();
        boolean errorPresent = error != null;
        if (errorPresent) {
            log.info("PayU returned with error {} (errorCode {}) for reservation {}",
                RequestUtils.sanitizeForLog(error), RequestUtils.sanitizeForLog(errorCode), reservationId);
            if (ticketReservationManager.cancelPendingPayment(reservationId, purchaseContext)) {
                return overviewWithCancellation(type, identifier, reservationId);
            }
        }
        var optionalResult = ticketReservationManager.forceTransactionCheck(purchaseContext, reservation);
        boolean failed = optionalResult.isPresent() && optionalResult.get().isFailed();
        boolean noTransactionAfterError = errorPresent && optionalResult.isEmpty();
        if (failed || noTransactionAfterError) {
            return overviewWithCancellation(type, identifier, reservationId);
        }
        return "redirect:" + UriComponentsBuilder.fromPath(BOOK_TEMPLATE)
            .buildAndExpand(type, identifier, reservationId).toUriString();
    }

    private static String overviewWithCancellation(String type, String identifier, String reservationId) {
        return "redirect:" + UriComponentsBuilder.fromPath(OVERVIEW_TEMPLATE)
            .queryParam("paymentCancelled", "true")
            .buildAndExpand(type, identifier, reservationId).toUriString();
    }
}
