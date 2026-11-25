package com.leaden2.app;

import android.app.Notification;
import android.content.Intent;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Build;
import android.os.Parcelable;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * Detecta mensajes visibles de WhatsApp sin modificar el servicio de accesibilidad
 * que ya controla la automatización de llamadas.
 */
public class WhatsAppNotificationListenerService extends NotificationListenerService {
    private static final String TAG = "ORVEXWhatsAppNotif";
    private static final String PREFS = "leaden2_local_data";
    private static final long DEDUPE_WINDOW_MS = 30_000L;
    private final Map<String, Long> processed = new HashMap<>();

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        WhatsAppDiagnostics.record(this, "NotificationListener conectado a Android.");
    }

    @Override
    public void onListenerDisconnected() {
        WhatsAppDiagnostics.record(this, "ATENCIÓN: Android desconectó NotificationListener; se solicitará reconexión.");
        super.onListenerDisconnected();
        // Desde Android 7 (API 24), solicitar al sistema que vuelva a enlazar
        // este NotificationListener. El permiso del usuario debe seguir activo.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                NotificationListenerService.requestRebind(
                    new ComponentName(this, WhatsAppNotificationListenerService.class)
                );
                WhatsAppDiagnostics.record(this, "Solicitud de reconexión de NotificationListener enviada a Android.");
            } catch (Exception e) {
                WhatsAppDiagnostics.record(this, "ERROR al solicitar reconexión de NotificationListener: "
                    + e.getClass().getSimpleName());
                Log.e(TAG, "No se pudo solicitar la reconexión de NotificationListener.", e);
            }
        } else {
            WhatsAppDiagnostics.record(this, "Reconexión automática no disponible en esta versión de Android (API menor que 24).");
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if (!"com.whatsapp".equals(pkg) && !"com.whatsapp.w4b".equals(pkg)) return;
        if (!isAssistantEnabled()) return;

        Notification notification = sbn.getNotification();
        if (notification == null) return;
        Bundle extras = notification.extras;
        if (extras == null) return;

        // Evita resúmenes de grupos y notificaciones sin contenido útil.
        if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        if ((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0) return;

        CharSequence titleCs = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence textCs = null;
        String extractionSource = "extras";
        long messageTimestamp = notification.when > 0 ? notification.when : sbn.getPostTime();

        // WhatsApp suele entregar MessagingStyle como un arreglo de Bundle.
        // Se leen directamente los extras para evitar depender de métodos
        // MessagingStyle que no están disponibles en todos los SDK de compilación.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Bundle messageBundle = findLatestMessageBundle(extras, Notification.EXTRA_MESSAGES);
            if (messageBundle == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                messageBundle = findLatestMessageBundle(extras, Notification.EXTRA_HISTORIC_MESSAGES);
            }
            if (messageBundle != null) {
                CharSequence messageText = messageBundle.getCharSequence("text");
                if (!TextUtils.isEmpty(messageText)) {
                    textCs = messageText;
                    CharSequence messageSender = messageBundle.getCharSequence("sender");
                    if (!TextUtils.isEmpty(messageSender)) titleCs = messageSender;
                    long timestamp = messageBundle.getLong("time", 0L);
                    if (timestamp > 0) messageTimestamp = timestamp;
                    extractionSource = "EXTRA_MESSAGES";
                }
            }
        }

        if (TextUtils.isEmpty(textCs)) textCs = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (TextUtils.isEmpty(textCs)) textCs = extras.getCharSequence(Notification.EXTRA_TEXT);
        if (TextUtils.isEmpty(textCs)) {
            CharSequence[] textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (textLines != null) {
                for (int i = textLines.length - 1; i >= 0; i--) {
                    if (!TextUtils.isEmpty(textLines[i])) {
                        textCs = textLines[i];
                        extractionSource = "EXTRA_TEXT_LINES";
                        break;
                    }
                }
            }
        }
        if (TextUtils.isEmpty(textCs)) {
            WhatsAppDiagnostics.record(this, "Notificación recibida sin texto recuperable; paquete=" + pkg);
            Log.w(TAG, "Notificación de WhatsApp sin texto recuperable.");
            return;
        }

        String sender = titleCs == null ? "un contacto" : titleCs.toString().trim();
        String text = textCs.toString().trim();
        if (text.isEmpty()) {
            WhatsAppDiagnostics.record(this, "Notificación descartada: texto vacío después de normalizar.");
            return;
        }

        // Evita reproducir el mismo evento varias veces cuando WhatsApp actualiza la notificación.
        // Identifica el evento por su marca temporal: dos mensajes iguales enviados
        // por la misma persona no deben confundirse con una actualización repetida.
        String key = pkg + "|" + sbn.getId() + "|"
            + (sbn.getTag() == null ? "" : sbn.getTag()) + "|" + sender + "|"
            + messageTimestamp + "|" + text;
        long now = System.currentTimeMillis();
        synchronized (processed) {
            Long previous = processed.get(key);
            if (previous != null && now - previous < DEDUPE_WINDOW_MS) {
                WhatsAppDiagnostics.record(this, "Actualización duplicada ignorada; evento=" + messageTimestamp);
                return;
            }
            processed.put(key, now);
            if (processed.size() > 100) processed.clear();
        }

        Log.i(TAG, "Mensaje WhatsApp detectado; caracteres=" + text.length()
            + ", origen=" + extractionSource);
        WhatsAppDiagnostics.record(this, "Mensaje detectado; origen=" + extractionSource
            + "; caracteres=" + text.length() + "; paquete=" + pkg);

        Intent intent = new Intent(this, PlaybackService.class);
        intent.setAction(PlaybackService.ACTION_WHATSAPP_ALERT);
        intent.putExtra(PlaybackService.EXTRA_WHATSAPP_SENDER, sender);
        intent.putExtra(PlaybackService.EXTRA_WHATSAPP_TEXT, text);
        try {
            startService(intent);
            WhatsAppDiagnostics.record(this, "Solicitud enviada al servicio de reproducción.");
        } catch (Exception e) {
            WhatsAppDiagnostics.record(this, "ERROR al enviar el aviso al servicio: "
                + e.getClass().getSimpleName());
            Log.e(TAG, "No se pudo solicitar el aviso de WhatsApp.", e);
        }
    }

    private Bundle findLatestMessageBundle(Bundle extras, String extraKey) {
        Parcelable[] messages;
        try {
            messages = extras.getParcelableArray(extraKey);
        } catch (Exception e) {
            return null;
        }
        if (messages == null) return null;

        for (int i = messages.length - 1; i >= 0; i--) {
            if (messages[i] instanceof Bundle) {
                Bundle message = (Bundle) messages[i];
                if (!TextUtils.isEmpty(message.getCharSequence("text"))) return message;
            }
        }
        return null;
    }

    private boolean isAssistantEnabled() {
        boolean enabled = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean("assistant_enabled", false);
        if (!enabled) WhatsAppDiagnostics.record(this, "Notificación ignorada: switch principal ORVEX OFF.");
        return enabled;
    }

    /** Utilidad para que MainActivity pueda mostrar el estado del permiso. */
    public static boolean isEnabled(android.content.Context context) {
        try {
            String enabled = Settings.Secure.getString(
                context.getContentResolver(),
                "enabled_notification_listeners"
            );
            String component = context.getPackageName() + "/" + WhatsAppNotificationListenerService.class.getName();
            return enabled != null && enabled.toLowerCase().contains(component.toLowerCase());
        } catch (Exception e) {
            return false;
        }
    }
}
