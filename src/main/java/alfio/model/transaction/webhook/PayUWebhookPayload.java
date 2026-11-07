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
package alfio.model.transaction.webhook;

import alfio.model.transaction.TransactionWebhookPayload;

/**
 * Notification received from PayU, after its signature has been verified.
 * The status is re-read from the PayU API before being applied, so the payload is used only to identify the order.
 * <p>
 * Every signed notification yields a payload: refunds and notifications that cannot be matched to the reservation
 * are kept (as {@link Kind#REFUND} or {@link Kind#UNRECOGNIZED}) so that they can be acknowledged, otherwise PayU
 * would retry them forever.
 */
public class PayUWebhookPayload implements TransactionWebhookPayload {

    public enum Kind {
        ORDER, REFUND, UNRECOGNIZED
    }

    private final Kind kind;
    private final String reservationId;
    private final String orderId;
    private final String orderStatus;

    private PayUWebhookPayload(Kind kind, String reservationId, String orderId, String orderStatus) {
        this.kind = kind;
        this.reservationId = reservationId;
        this.orderId = orderId;
        this.orderStatus = orderStatus;
    }

    /**
     * An order status notification. {@code orderId} and {@code orderStatus} may be null.
     */
    public static PayUWebhookPayload order(String reservationId, String orderId, String orderStatus) {
        return new PayUWebhookPayload(Kind.ORDER, reservationId, orderId, orderStatus);
    }

    public static PayUWebhookPayload refund(String reservationId, String orderId) {
        return new PayUWebhookPayload(Kind.REFUND, reservationId, orderId, null);
    }

    public static PayUWebhookPayload unrecognized(String reservationId, String orderId) {
        return new PayUWebhookPayload(Kind.UNRECOGNIZED, reservationId, orderId, null);
    }

    @Override
    public String getPayload() {
        return orderId;
    }

    @Override
    public String getType() {
        if (kind == Kind.REFUND) {
            return "payu-refund";
        }
        if (kind == Kind.UNRECOGNIZED || orderStatus == null) {
            return "payu-unrecognized";
        }
        return "payu-" + orderStatus;
    }

    @Override
    public String getReservationId() {
        return reservationId;
    }

    @Override
    public Status getStatus() {
        if (kind == Kind.ORDER && "CANCELED".equals(orderStatus)) {
            return Status.FAILURE;
        }
        return Status.SUCCESS;
    }

    public Kind getKind() {
        return kind;
    }

    /**
     * @return the PayU order id, or null when the notification does not carry one
     */
    public String getOrderId() {
        return orderId;
    }

    public String getOrderStatus() {
        return orderStatus;
    }
}
