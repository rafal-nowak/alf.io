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

import alfio.model.CustomerName;
import alfio.model.OrderSummary;
import alfio.model.PriceContainer;
import alfio.model.PurchaseContext;
import alfio.model.transaction.PaymentToken;
import alfio.model.transaction.StaticPaymentMethods;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class PaymentSpecificationTest {

    @Test
    void withCustomerIpCopiesEveryFieldAndLeavesTheOriginalUnchanged() {
        var token = mock(PaymentToken.class);
        var purchaseContext = mock(PurchaseContext.class);
        var customerName = new CustomerName("Jan Kowalski", "Jan", "Kowalski", true);
        var orderSummary = mock(OrderSummary.class);
        var original = new PaymentSpecification("reservation-id", token, StaticPaymentMethods.PAYU, 1234, purchaseContext,
            "buyer@example.org", customerName, "billing address", "customer reference", Locale.ITALIAN, true, true,
            orderSummary, "PL", "1234567890", PriceContainer.VatStatus.INCLUDED, true, false);

        var copy = original.withCustomerIp("203.0.113.7");

        assertNotSame(original, copy);
        assertNull(original.getCustomerIp());
        assertEquals("203.0.113.7", copy.getCustomerIp());
        assertEquals("reservation-id", copy.getReservationId());
        assertEquals(token, copy.getGatewayToken());
        assertEquals(StaticPaymentMethods.PAYU, copy.getSelectedPaymentMethod());
        assertEquals(1234, copy.getPriceWithVAT());
        assertEquals(purchaseContext, copy.getPurchaseContext());
        assertEquals("buyer@example.org", copy.getEmail());
        assertEquals(customerName, copy.getCustomerName());
        assertEquals("billing address", copy.getBillingAddress());
        assertEquals("customer reference", copy.getCustomerReference());
        assertEquals(Locale.ITALIAN, copy.getLocale());
        assertEquals(true, copy.isInvoiceRequested());
        assertEquals(true, copy.isPostponeAssignment());
        assertEquals(orderSummary, copy.getOrderSummary());
        assertEquals("PL", copy.getVatCountryCode());
        assertEquals("1234567890", copy.getVatNr());
        assertEquals(PriceContainer.VatStatus.INCLUDED, copy.getVatStatus());
        assertEquals(true, copy.isTcAccepted());
        assertEquals(false, copy.isPrivacyAccepted());
    }

    @Test
    void existingPackagePrivateConstructorLeavesCustomerIpNull() {
        var specification = new PaymentSpecification("reservation-id", null, StaticPaymentMethods.PAYU, 100,
            mock(PurchaseContext.class), "buyer@example.org", new CustomerName("Jan Kowalski", "Jan", "Kowalski", true));
        assertNull(specification.getCustomerIp());
    }
}
