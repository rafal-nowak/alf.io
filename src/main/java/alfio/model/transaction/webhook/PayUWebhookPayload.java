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
 */
public class PayUWebhookPayload implements TransactionWebhookPayload {

    private final String reservationId;
    private final String orderId;
    private final String orderStatus;

    public PayUWebhookPayload(String reservationId, String orderId, String orderStatus) {
        this.reservationId = reservationId;
        this.orderId = orderId;
        this.orderStatus = orderStatus;
    }

    @Override
    public String getPayload() {
        return orderId;
    }

    @Override
    public String getType() {
        return "payu-" + orderStatus;
    }

    @Override
    public String getReservationId() {
        return reservationId;
    }

    @Override
    public Status getStatus() {
        if ("CANCELED".equals(orderStatus)) {
            return Status.FAILURE;
        }
        return Status.SUCCESS;
    }

    public String getOrderId() {
        return orderId;
    }
}
