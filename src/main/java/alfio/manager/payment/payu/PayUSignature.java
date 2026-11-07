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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Verifies the {@code OpenPayu-Signature} header sent by PayU with every notification.
 * <p>
 * Header format: {@code sender=checkout;signature=<hex>;algorithm=MD5;content=DOCUMENT}.
 * The expected signature is {@code hash(body + secondKey)}, where the hash function is the one declared in
 * {@code algorithm} (MD5 or SHA-256).
 */
public final class PayUSignature {

    private PayUSignature() {
    }

    public static boolean isValid(String header, String body, String secondKey) {
        if (header == null || body == null || secondKey == null || secondKey.isBlank()) {
            return false;
        }
        String signature = null;
        String algorithm = null;
        for (String part : header.split(";")) {
            String[] keyValue = part.strip().split("=", 2);
            if (keyValue.length != 2) {
                continue;
            }
            if ("signature".equals(keyValue[0]) && signature == null) {
                signature = keyValue[1].strip();
            } else if ("algorithm".equals(keyValue[0]) && algorithm == null) {
                algorithm = keyValue[1].strip();
            }
        }
        String javaAlgorithm = toJavaAlgorithm(algorithm);
        if (signature == null || javaAlgorithm == null) {
            return false;
        }
        byte[] received;
        try {
            received = HexFormat.of().parseHex(signature.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(digest(javaAlgorithm, body + secondKey), received);
    }

    static String toJavaAlgorithm(String payuAlgorithm) {
        if (payuAlgorithm == null) {
            return null;
        }
        return switch (payuAlgorithm.toUpperCase(Locale.ROOT)) {
            case "MD5" -> "MD5";
            case "SHA-256", "SHA256" -> "SHA-256";
            default -> null;
        };
    }

    static byte[] digest(String javaAlgorithm, String value) {
        try {
            return MessageDigest.getInstance(javaAlgorithm).digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
