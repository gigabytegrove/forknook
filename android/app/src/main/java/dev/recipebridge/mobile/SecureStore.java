package dev.recipebridge.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypts bearer credentials and KO-Pellet session cookies under Android Keystore. */
final class SecureStore {
    private static final String KEY_ALIAS = "recipe_bridge_secret_v1";
    private final SharedPreferences preferences;

    SecureStore(Context context) {
        preferences = context.getSharedPreferences("recipe_bridge_secure", Context.MODE_PRIVATE);
    }

    private SecretKey key() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        SecretKey existing = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        if (existing != null) return existing;
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return gen.generateKey();
    }

    void put(String name, String value) throws Exception {
        if (value == null || value.isEmpty()) {
            preferences.edit().remove(name).apply();
            return;
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(), new SecureRandom());
        byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        byte[] combined = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
        preferences.edit().putString(name, Base64.encodeToString(combined, Base64.NO_WRAP)).apply();
    }

    String get(String name) {
        String encoded = preferences.getString(name, "");
        if (encoded.isEmpty()) return "";
        try {
            byte[] combined = Base64.decode(encoded, Base64.NO_WRAP);
            if (combined.length < 29) throw new IllegalArgumentException("Invalid encrypted secret");
            byte[] iv = new byte[12];
            System.arraycopy(combined, 0, iv, 0, 12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(combined, iv.length, combined.length - iv.length), StandardCharsets.UTF_8);
        } catch (Exception error) {
            // Old secret after device restore/keystore reset: require login again.
            preferences.edit().remove(name).apply();
            return "";
        }
    }
}
