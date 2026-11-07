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
package alfio.manager.payment.payu;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class PayUSignatureTest {

    private static final String BODY = "{\"order\":{\"orderId\":\"ORDER1\",\"status\":\"COMPLETED\"}}";
    private static final String SECOND_KEY = "b6ca15b0d1020e8094d9b5f8d163db54";
    // computed independently: printf '%s%s' "$BODY" "$SECOND_KEY" | openssl dgst -md5
    private static final String MD5_SIGNATURE = "4971ad33743370b6c085e5de1d84b672";
    // computed independently: printf '%s%s' "$BODY" "$SECOND_KEY" | openssl dgst -sha256
    private static final String SHA256_SIGNATURE = "f59d6bcdaaf6d2534d5c390faa79058a985d88f90911509f3d26810e1a211472";

    @Test
    void md5AndSha256VectorsAreAccepted() {
        assertTrue(PayUSignature.isValid(header(MD5_SIGNATURE, "MD5"), BODY, SECOND_KEY));
        assertTrue(PayUSignature.isValid(header(SHA256_SIGNATURE, "SHA-256"), BODY, SECOND_KEY));
        assertTrue(PayUSignature.isValid(header(SHA256_SIGNATURE, "SHA256"), BODY, SECOND_KEY));
        // the digest must match the declared algorithm
        assertFalse(PayUSignature.isValid(header(SHA256_SIGNATURE, "MD5"), BODY, SECOND_KEY));
        // a tampered body is rejected
        assertFalse(PayUSignature.isValid(header(MD5_SIGNATURE, "MD5"), BODY + " ", SECOND_KEY));
    }

    @Test
    void upperCaseHexDigestIsAccepted() {
        assertTrue(PayUSignature.isValid(header(MD5_SIGNATURE.toUpperCase(Locale.ROOT), "md5"), BODY, SECOND_KEY));
        assertTrue(PayUSignature.isValid(header(SHA256_SIGNATURE.toUpperCase(Locale.ROOT), "sha-256"), BODY, SECOND_KEY));
    }

    @Test
    void wrongOrBlankSecondKeyIsRejected() {
        assertFalse(PayUSignature.isValid(header(MD5_SIGNATURE, "MD5"), BODY, "another-key"));
        assertFalse(PayUSignature.isValid(header(MD5_SIGNATURE, "MD5"), BODY, " "));
        assertFalse(PayUSignature.isValid(header(MD5_SIGNATURE, "MD5"), BODY, null));
    }

    @Test
    void malformedOrMissingHeaderIsRejected() {
        assertFalse(PayUSignature.isValid(null, BODY, SECOND_KEY));
        assertFalse(PayUSignature.isValid("", BODY, SECOND_KEY));
        assertFalse(PayUSignature.isValid("garbage", BODY, SECOND_KEY));
        // missing algorithm
        assertFalse(PayUSignature.isValid("sender=checkout;signature=" + MD5_SIGNATURE + ";content=DOCUMENT", BODY, SECOND_KEY));
        // unknown algorithm
        assertFalse(PayUSignature.isValid(header(MD5_SIGNATURE, "SHA-1"), BODY, SECOND_KEY));
        // missing signature
        assertFalse(PayUSignature.isValid("sender=checkout;algorithm=MD5;content=DOCUMENT", BODY, SECOND_KEY));
        // signature that is not hex
        assertFalse(PayUSignature.isValid(header("not-a-hex-digest", "MD5"), BODY, SECOND_KEY));
        assertFalse(PayUSignature.isValid(header(MD5_SIGNATURE, "MD5"), null, SECOND_KEY));
    }

    @Test
    void firstSignatureAndAlgorithmWin() {
        var validFirst = "sender=checkout;signature=" + MD5_SIGNATURE + ";algorithm=MD5;signature=00;algorithm=SHA-1";
        assertTrue(PayUSignature.isValid(validFirst, BODY, SECOND_KEY));
        var invalidFirst = "sender=checkout;signature=00;algorithm=MD5;signature=" + MD5_SIGNATURE;
        assertFalse(PayUSignature.isValid(invalidFirst, BODY, SECOND_KEY));
    }

    private static String header(String signature, String algorithm) {
        return "sender=checkout;signature=" + signature + ";algorithm=" + algorithm + ";content=DOCUMENT";
    }
}
