package org.jahia.modules.formidable.salesforce.client;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Loads the RSA private key of a Connected App from its PEM text.
 *
 * <p>Accepts the full PEM (with {@code -----BEGIN PRIVATE KEY-----} headers), the PEM with
 * line breaks written as the two characters {@code \n} (the only way to keep it on one line in
 * a .cfg file), or the bare base64 body. Only PKCS#8 is supported; a PKCS#1 key
 * ({@code BEGIN RSA PRIVATE KEY}) is rejected with a conversion hint rather than an opaque
 * {@code InvalidKeySpecException}.
 */
public final class PrivateKeyLoader {

    private PrivateKeyLoader() {
    }

    public static PrivateKey load(String pem) throws GeneralSecurityException {
        if (pem == null || pem.isBlank()) {
            throw new GeneralSecurityException("privateKey is empty");
        }
        String text = pem.replace("\\n", "\n").replace("\\r", "");
        if (text.contains("BEGIN RSA PRIVATE KEY")) {
            throw new GeneralSecurityException("privateKey is PKCS#1 (BEGIN RSA PRIVATE KEY); convert it with "
                    + "'openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem'");
        }
        if (text.contains("ENCRYPTED PRIVATE KEY")) {
            throw new GeneralSecurityException("privateKey is encrypted; store an unencrypted PKCS#8 key");
        }
        String b64 = text
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der;
        try {
            der = Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("privateKey is not valid base64/PEM", e);
        }
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }
}
