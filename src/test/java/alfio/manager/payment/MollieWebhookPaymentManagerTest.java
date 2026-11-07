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

import alfio.model.transaction.StaticPaymentMethods;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MollieWebhookPaymentManagerTest {

    @Test
    void unsupportedMethodsAreIgnored() {
        var body = JsonParser.parseString("""
            {"count": 3, "_embedded": {"methods": [
                {"id": "creditcard"}, {"id": "blik"}, {"id": "przelewy24"}
            ]}}
            """).getAsJsonObject();

        var methods = MollieWebhookPaymentManager.parseAvailablePaymentMethods(body);

        assertEquals(Set.of(StaticPaymentMethods.CREDIT_CARD, StaticPaymentMethods.PRZELEWY_24), methods);
    }

    @Test
    void onlyUnsupportedMethodsResultInEmptySet() {
        var body = JsonParser.parseString("""
            {"count": 1, "_embedded": {"methods": [{"id": "blik"}]}}
            """).getAsJsonObject();

        assertTrue(MollieWebhookPaymentManager.parseAvailablePaymentMethods(body).isEmpty());
    }

    @Test
    void noMethodsResultInEmptySet() {
        var body = JsonParser.parseString("""
            {"count": 0, "_embedded": {"methods": []}}
            """).getAsJsonObject();

        assertTrue(MollieWebhookPaymentManager.parseAvailablePaymentMethods(body).isEmpty());
    }
}
