package com.feras.wazifati;

import android.app.Activity;
import android.os.CancellationSignal;

import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;

import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

import java.util.concurrent.Executors;

/** الدخول بحساب Google عبر Credential Manager (بدون كلمة سر). */
public final class GoogleLogin {
    private GoogleLogin() { }

    public interface Cb {
        void ok(String idToken);
        void err(String msg);
    }

    public static boolean configured() {
        return BuildConfig.GOOGLE_CLIENT_ID != null && BuildConfig.GOOGLE_CLIENT_ID.endsWith(".apps.googleusercontent.com");
    }

    public static void signIn(Activity act, Cb cb) {
        if (!configured()) { cb.err("تسجيل الدخول غير مهيأ في هذه النسخة"); return; }
        GetSignInWithGoogleOption opt = new GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_CLIENT_ID).build();
        GetCredentialRequest req = new GetCredentialRequest.Builder().addCredentialOption(opt).build();
        CredentialManager cm = CredentialManager.create(act);
        cm.getCredentialAsync(act, req, new CancellationSignal(), Executors.newSingleThreadExecutor(),
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse r) {
                        try {
                            Credential c = r.getCredential();
                            if (c instanceof CustomCredential && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(c.getType())) {
                                GoogleIdTokenCredential g = GoogleIdTokenCredential.createFrom(c.getData());
                                cb.ok(g.getIdToken());
                            } else cb.err("نوع حساب غير مدعوم");
                        } catch (Throwable e) {
                            cb.err(String.valueOf(e.getMessage()));
                        }
                    }

                    @Override
                    public void onError(GetCredentialException e) {
                        if (e instanceof GetCredentialCancellationException) cb.err("");
                        else cb.err(String.valueOf(e.getMessage()));
                    }
                });
    }
}
