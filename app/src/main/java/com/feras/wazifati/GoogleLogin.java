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

/** الدخول بحساب Google عبر Credential Manager (مع إرسال تقرير تلقائي عند الفشل). */
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
        CredentialManager cm;
        try {
            cm = CredentialManager.create(act);
            startGet(act, cm, req, cb);
        } catch (Throwable e) {   // مكتبة ناقصة أو خدمات Google غير متوفرة: رسالة بدل الانهيار + تقرير للمطوّر
            CrashHandler.reportAuthFail(act, "Init:" + e.getClass().getSimpleName(), String.valueOf(e.getMessage()));
            cb.err("تعذّر تشغيل تسجيل الدخول بحساب Google على هذا الهاتف. حدّث التطبيق و«خدمات Google Play»، أو تابع كزائر الآن.");
        }
    }

    private static void startGet(Activity act, CredentialManager cm, GetCredentialRequest req, Cb cb) {
        cm.getCredentialAsync(act, req, new CancellationSignal(), Executors.newSingleThreadExecutor(),
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse r) {
                        try {
                            Credential c = r.getCredential();
                            if (c instanceof CustomCredential && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(c.getType())) {
                                GoogleIdTokenCredential g = GoogleIdTokenCredential.createFrom(c.getData());
                                cb.ok(g.getIdToken());
                            } else {
                                CrashHandler.reportAuthFail(act, "UnsupportedCredential", String.valueOf(c == null ? "null" : c.getType()));
                                cb.err("نوع حساب غير مدعوم");
                            }
                        } catch (Throwable e) {
                            CrashHandler.reportAuthFail(act, e.getClass().getSimpleName(), String.valueOf(e.getMessage()));
                            cb.err(friendlyError(e.getMessage()));
                        }
                    }

                    @Override
                    public void onError(GetCredentialException e) {
                        if (e instanceof GetCredentialCancellationException) { cb.err(""); return; }
                        CrashHandler.reportAuthFail(act, e.getClass().getSimpleName(), String.valueOf(e.getMessage()));
                        cb.err(friendlyError(e.getMessage()));
                    }
                });
    }

    private static String friendlyError(String raw) {
        String base = (raw == null || raw.isEmpty()) ? "خطأ غير معروف في خدمات Google" : raw;
        if (CrashHandler.isRooted()) {
            return base + "\n\n⚠️ يبدو أن جهازك عليه روت (Root)، وقد تقيّد خدمات Google تسجيل الدخول عليه. جرّب تحديث «خدمات Google Play» أو تصفّح كضيف.";
        }
        return base;
    }
}
