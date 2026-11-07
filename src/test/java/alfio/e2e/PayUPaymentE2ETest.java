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
package alfio.e2e;

import alfio.model.transaction.PaymentProxy;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

/**
 * Pays on the PayU sandbox (secure.snd.payu.com). The server under test must have PayU configured
 * (PAYU_ENABLED, PAYU_LIVE_MODE=false and the credentials of a sandbox POS) and a BASE_URL.
 * <p>
 * PayU notifications cannot reach a local server: the payment is confirmed when the attendee comes back
 * from PayU, and by the "force check" of the payment status.
 */
class PayUPaymentE2ETest extends BaseE2ETest {

    private static final String CUSTOMER_LAST_NAME = "McPayU";

    @Override
    protected EnumSet<PaymentProxy> requiredPaymentProxies() {
        return EnumSet.of(PaymentProxy.PAYU);
    }

    /**
     * PayU accepts only some currencies, CHF is not one of them
     */
    @Override
    protected String currency() {
        return "PLN";
    }

    @Test
    void payWithPayU() {
        reservationFlow.buyTicketWithPayU(CUSTOMER_LAST_NAME);
    }

    @Test
    void abandonPaymentOnPayU() {
        reservationFlow.abandonPayUPayment(CUSTOMER_LAST_NAME);
        var message = reservationFlow.waitForErrorToast();
        Assertions.assertTrue(message.contains("The payment was not completed"), "unexpected message: " + message);
        Assertions.assertTrue(reservationFlow.paymentMethodCanBeChosen("PAYU"));
    }
}
