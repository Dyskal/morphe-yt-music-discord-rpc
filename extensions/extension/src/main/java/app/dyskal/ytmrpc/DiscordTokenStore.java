package app.dyskal.ytmrpc;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.json.JSONObject;

import java.security.KeyStore;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** A separate private store: neither tokens nor PKCE verifiers participate in settings exports. */
final class DiscordTokenStore {
    private static final String ALIAS = "ytm_discord_oauth";
    private final SharedPreferences preferences;
    DiscordTokenStore(Context context) {
        preferences = context.getSharedPreferences("ytm_discord_oauth", Context.MODE_PRIVATE);
    }

    synchronized JSONObject read() throws Exception {
        String saved = preferences.getString("credentials", "");
        if (saved.isEmpty()) return new JSONObject();
        String[] parts = saved.split(":", 2);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
        return new JSONObject(new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), java.nio.charset.StandardCharsets.UTF_8));
    }

    synchronized void write(JSONObject credentials) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(credentials.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String encoded = Base64.getEncoder().encodeToString(cipher.getIV()) + ":" + Base64.getEncoder().encodeToString(encrypted);
        if (!preferences.edit().putString("credentials", encoded).commit()) throw new java.io.IOException("Could not save Discord credentials");
    }

    synchronized void clear() { preferences.edit().clear().commit(); }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }
}
