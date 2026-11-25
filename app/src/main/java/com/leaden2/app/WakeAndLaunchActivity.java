package com.leaden2.app;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * Transición visual mínima para solicitudes de llamada que llegan cuando ORVEX
 * está en segundo plano. Despierta la pantalla y respeta el bloqueo seguro:
 * WhatsApp solo se abre cuando Android indica que el dispositivo está desbloqueado.
 */
public class WakeAndLaunchActivity extends Activity {
    private static final String TAG = "ORVEXWakeLaunch";
    public static final String EXTRA_CONTACT_NAME = "orvex_contact_name";
    public static final String EXTRA_PHONE = "orvex_contact_phone";
    public static final String EXTRA_REQUEST_ID = "orvex_call_request_id";
    public static final String EXTRA_USER_INITIATED = "orvex_call_user_initiated";
    public static final String EXTRA_AUTO_MESSAGE = "orvex_auto_message_resume";
    public static final String EXTRA_MESSAGE_ARMED_AT = "orvex_message_armed_at";
    private static final String PREFS = "leaden2_local_data";
    private static final long MESSAGE_VALIDITY_MS = 90000L;
    private static final long REQUEST_VALIDITY_MS = 60000L;
    private boolean launchHandled = false;
    private boolean unlockRequestSent = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WhatsAppCallDiagnostics.recordDeviceState(this, "ACTIVITY: WakeAndLaunchActivity.onCreate ejecutado.");
        requestVisibleOverLockScreen();
        continueAfterUnlock();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        WhatsAppCallDiagnostics.recordDeviceState(this, "ACTIVITY: onNewIntent recibido.");
        launchHandled = false;
        continueAfterUnlock();
    }

    @Override
    protected void onResume() {
        super.onResume();
        WhatsAppCallDiagnostics.recordDeviceState(this, "ACTIVITY: onResume ejecutado.");
        // Se vuelve a comprobar al regresar del diálogo de desbloqueo del sistema.
        continueAfterUnlock();
    }

    @Override
    protected void onStart() {
        super.onStart();
        WhatsAppCallDiagnostics.recordDeviceState(this, "ACTIVITY: WakeAndLaunchActivity.onStart ejecutado.");
    }

    @Override
    protected void onPause() {
        WhatsAppCallDiagnostics.recordDeviceState(this, "ACTIVITY: WakeAndLaunchActivity.onPause ejecutado.");
        super.onPause();
    }

    @Override
    protected void onStop() {
        WhatsAppCallDiagnostics.recordDeviceState(this, "ACTIVITY: WakeAndLaunchActivity.onStop ejecutado.");
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        WhatsAppCallDiagnostics.recordDeviceState(this, "ACTIVITY: WakeAndLaunchActivity.onDestroy ejecutado.");
        super.onDestroy();
    }

    private void requestVisibleOverLockScreen() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
    }

    private void continueAfterUnlock() {
        if (launchHandled) return;
        if (!isPendingRequestValid()) {
            finish();
            return;
        }

        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (keyguard == null || !keyguard.isKeyguardLocked()) {
            WhatsAppCallDiagnostics.record(this, "BLOQUEO: Android informa que el dispositivo no está bloqueado; se continuará con la solicitud pendiente.");
            continueRequestedFlow();
            return;
        }

        WhatsAppCallDiagnostics.record(this, "BLOQUEO: Android informa que el dispositivo sigue bloqueado.");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (unlockRequestSent) return;
            unlockRequestSent = true;
            WhatsAppCallDiagnostics.record(this, "BLOQUEO: solicitando a Android requestDismissKeyguard; requiere autorización normal del usuario.");
            keyguard.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override
                public void onDismissSucceeded() {
                    unlockRequestSent = false;
                    WhatsAppCallDiagnostics.recordDeviceState(WakeAndLaunchActivity.this, "BLOQUEO: callback onDismissSucceeded; Android confirmó el desbloqueo.");
                    runOnUiThread(() -> continueRequestedFlow());
                }

                @Override
                public void onDismissCancelled() {
                    unlockRequestSent = false;
                    WhatsAppCallDiagnostics.recordDeviceState(WakeAndLaunchActivity.this, "BLOQUEO: callback onDismissCancelled; WhatsApp no se abrió.");
                    Log.i(TAG, "El usuario canceló el desbloqueo; WhatsApp no se abrirá todavía.");
                }

                @Override
                public void onDismissError() {
                    WhatsAppCallDiagnostics.recordDeviceState(WakeAndLaunchActivity.this, "BLOQUEO ERROR: callback onDismissError; Android no pudo completar la solicitud de desbloqueo.");
                    Log.w(TAG, "Android no pudo mostrar el flujo de desbloqueo; se esperará el desbloqueo manual.");
                }
            });
        } else {
            Toast.makeText(this, "Desbloquea el teléfono para continuar con WhatsApp.", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Una orden automática solo puede abrir WhatsApp si sigue armada y conserva
     * su identificador. La ruta automática usa 60 segundos; la ruta iniciada
     * expresamente desde la notificación usa su vencimiento independiente.
     * Las solicitudes de apertura de chat sin automatización no llevan identificador.
     */
    private boolean isPendingRequestValid() {
        if (getIntent().getBooleanExtra(EXTRA_AUTO_MESSAGE, false)) {
            android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            long expectedArmedAt = getIntent().getLongExtra(EXTRA_MESSAGE_ARMED_AT, 0L);
            long activeArmedAt = prefs.getLong("wa_auto_message_armed_at", 0L);
            long now = System.currentTimeMillis();
            boolean valid = prefs.getBoolean("wa_auto_message_armed", false)
                    && expectedArmedAt > 0L
                    && expectedArmedAt == activeArmedAt
                    && now >= activeArmedAt
                    && now - activeArmedAt <= MESSAGE_VALIDITY_MS;
            if (!valid) {
                WhatsAppMessageDiagnostics.record(this,
                        "BLOQUEO: solicitud de desbloqueo vencida o sustituida; no se reanudará el mensaje.");
                WhatsAppLockedMessageNotification.cancel(this);
            }
            return valid;
        }

        String requestId = getIntent().getStringExtra(EXTRA_REQUEST_ID);
        if (requestId == null || requestId.trim().isEmpty()) {
            return true;
        }

        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String activeRequestId = prefs.getString("wa_auto_call_request_id", "");
        long armedAt = prefs.getLong("wa_auto_call_armed_at", 0L);
        long launchExpiresAt = prefs.getLong("wa_auto_call_launch_expires_at", 0L);
        long notificationExpiresAt = prefs.getLong("wa_auto_call_notification_expires_at", 0L);
        long now = System.currentTimeMillis();
        boolean userInitiated = getIntent().getBooleanExtra(EXTRA_USER_INITIATED, false);
        boolean withinExpiry = userInitiated
                ? notificationExpiresAt >= armedAt && now <= notificationExpiresAt
                : launchExpiresAt >= armedAt && now <= launchExpiresAt
                    && now - armedAt <= REQUEST_VALIDITY_MS;
        boolean valid = prefs.getBoolean("wa_auto_call_armed", false)
                && requestId.equals(activeRequestId)
                && armedAt > 0L
                && now >= armedAt
                && withinExpiry;

        if (!valid) {
            WhatsAppCallDiagnostics.recordDeviceState(this,
                    "SOLICITUD_RECHAZADA: orden inexistente, sustituida o vencida; no se abrirá WhatsApp.");
            // Solo limpiar si esta actividad corresponde a la orden que aún figura activa.
            if (requestId.equals(activeRequestId)) {
                prefs.edit()
                        .putBoolean("wa_auto_call_armed", false)
                        .remove("wa_auto_call_name")
                        .remove("wa_auto_call_phone")
                        .remove("wa_auto_call_request_id")
                        .remove("wa_auto_call_armed_at")
                        .remove("wa_auto_call_launch_expires_at")
                        .remove("wa_auto_call_notification_expires_at")
                        .putBoolean("wa_call_menu_opened", false)
                        .apply();
            }
            return false;
        }
        return true;
    }

    private void continueRequestedFlow() {
        if (getIntent().getBooleanExtra(EXTRA_AUTO_MESSAGE, false)) {
            resumeAutoMessageAfterUnlock();
        } else {
            launchWhatsApp();
        }
    }

    private void resumeAutoMessageAfterUnlock() {
        if (launchHandled || !isPendingRequestValid()) {
            finish();
            return;
        }
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (keyguard != null && keyguard.isKeyguardLocked()) {
            WhatsAppMessageDiagnostics.record(this,
                    "BLOQUEO: el teléfono continúa bloqueado; se mantiene pendiente la reanudación del mensaje.");
            return;
        }

        launchHandled = true;
        Intent resume = new Intent(WhatsAppCallAccessibilityService.ACTION_START_AUTO_MESSAGE);
        resume.setPackage(getPackageName());
        sendBroadcast(resume);
        WhatsAppLockedMessageNotification.cancel(this);
        WhatsAppMessageDiagnostics.record(this,
                "BLOQUEO: usuario desbloqueó el teléfono; se reanudó la automatización del mensaje en WhatsApp.");
        finish();
    }

    private void launchWhatsApp() {
        if (launchHandled) return;
        if (!isPendingRequestValid()) {
            finish();
            return;
        }

        if (getIntent().getBooleanExtra(EXTRA_USER_INITIATED, false)) {
            WhatsAppLockedCallNotification.cancel(this);
            WhatsAppCallDiagnostics.record(this,
                    "NOTIFICACION: el usuario pulsó Continuar; se validó la orden para abrir WhatsApp.");
        }

        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (keyguard != null && keyguard.isKeyguardLocked()) {
            WhatsAppCallDiagnostics.record(this, "APERTURA BLOQUEADA: launchWhatsApp se detuvo porque Android mantiene el bloqueo activo.");
            Log.i(TAG, "El teléfono sigue bloqueado; se esperará el desbloqueo manual.");
            return;
        }

        String phone = getIntent().getStringExtra(EXTRA_PHONE);
        String contactName = getIntent().getStringExtra(EXTRA_CONTACT_NAME);
        if (phone == null || phone.trim().isEmpty()) {
            WhatsAppCallDiagnostics.record(this, "APERTURA ERROR: falta el número validado; no se abrirá WhatsApp.");
            Log.e(TAG, "Falta el número validado; no se abrirá WhatsApp.");
            finish();
            return;
        }

        String digits = phone.replaceAll("\\D", "");
        Uri[] uris = new Uri[] {
                Uri.parse("whatsapp://send?phone=" + digits),
                Uri.parse("https://wa.me/" + digits)
        };
        String[] packages = {"com.whatsapp", "com.whatsapp.w4b"};

        for (String packageName : packages) {
            for (int uriIndex = 0; uriIndex < uris.length; uriIndex++) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, uris[uriIndex]);
                    intent.setPackage(packageName);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    WhatsAppCallDiagnostics.record(this, "APERTURA: intentando abrir paquete=" + packageName + " mediante URI " + (uriIndex == 0 ? "whatsapp://send" : "https://wa.me") + ".");
                    startActivity(intent);
                    launchHandled = true;
                    WhatsAppCallDiagnostics.recordDeviceState(this, "APERTURA: startActivity no lanzó excepción para paquete=" + packageName + "; WhatsApp aún debe confirmarse mediante accesibilidad.");
                    Log.i(TAG, "Chat de " + (contactName == null ? "contacto" : contactName)
                            + " solicitado mediante " + packageName + ".");
                    finish();
                    return;
                } catch (ActivityNotFoundException e) {
                    WhatsAppCallDiagnostics.record(this, "APERTURA: ActivityNotFoundException para paquete=" + packageName + " y URI " + (uriIndex == 0 ? "whatsapp://send" : "https://wa.me") + ".");
                    Log.i(TAG, "No se encontró manejador para " + packageName + " y URI " + uris[uriIndex]);
                } catch (SecurityException e) {
                    WhatsAppCallDiagnostics.record(this, "APERTURA BLOQUEADA: SecurityException para paquete=" + packageName + ": " + String.valueOf(e.getMessage()));
                    Log.w(TAG, "Android bloqueó la apertura de WhatsApp para " + packageName, e);
                }
            }
        }

        WhatsAppCallDiagnostics.record(this, "APERTURA ERROR: no se encontró un manejador de WhatsApp compatible después de probar los paquetes y URI disponibles.");
        Toast.makeText(this, "No pude abrir WhatsApp. Comprueba que esté instalado.", Toast.LENGTH_LONG).show();
        finish();
    }
}
