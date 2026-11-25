package com.leaden2.app;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Registro local e independiente para diagnosticar exclusivamente el flujo de llamadas WhatsApp. */
public final class WhatsAppCallDiagnostics {
    private static final String TAG = "ORVEXCallDiag";
    private static final String PREFS = "orvex_whatsapp_call_diagnostics";
    private static final String KEY_EVENTS = "call_events";
    private static final int MAX_EVENTS = 200;

    private WhatsAppCallDiagnostics() { }

    public static synchronized void record(Context context, String event) {
        if (context == null || event == null || event.trim().isEmpty()) return;
        try {
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                    .format(new Date());
            String line = time + " | " + event.replace('\n', ' ');
            android.content.SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String old = prefs.getString(KEY_EVENTS, "");
            List<String> lines = new ArrayList<>();
            if (old != null && !old.isEmpty()) {
                for (String item : old.split("\n")) if (!item.trim().isEmpty()) lines.add(item);
            }
            lines.add(line);
            while (lines.size() > MAX_EVENTS) lines.remove(0);
            StringBuilder value = new StringBuilder();
            for (String item : lines) {
                if (value.length() > 0) value.append('\n');
                value.append(item);
            }
            prefs.edit().putString(KEY_EVENTS, value.toString()).apply();
            Log.i(TAG, event);
        } catch (Exception e) {
            Log.w(TAG, "No se pudo guardar el diagnóstico de llamadas.", e);
        }
    }

    public static void recordDeviceState(Context context, String stage) {
        if (context == null) return;
        String screen = "desconocido";
        String lock = "desconocido";
        try {
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (power != null) screen = power.isInteractive() ? "encendida" : "apagada";
        } catch (Exception ignored) { }
        try {
            KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
            if (keyguard != null) lock = keyguard.isKeyguardLocked() ? "bloqueado" : "desbloqueado";
        } catch (Exception ignored) { }
        record(context, stage + " | pantalla=" + screen + " | bloqueo=" + lock);
    }

    /** Registra si Android marca la aplicación como restringida en segundo plano. */
    public static void recordBackgroundExecutionState(Context context) {
        if (context == null) return;
        String restriction = "no disponible";
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                if (manager != null) {
                    restriction = manager.isBackgroundRestricted() ? "restringida" : "no restringida";
                }
            } else {
                restriction = "API anterior a 28";
            }
        } catch (Exception e) {
            restriction = "error al consultar";
        }
        record(context, "ESTADO ANDROID: ejecución de la aplicación en segundo plano=" + restriction + ".");
    }

    public static String getLog(Context context) {
        if (context == null) return "No se pudo leer el registro de llamadas.";
        String value = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_EVENTS, "");
        return value == null || value.trim().isEmpty()
                ? "Todavía no hay eventos de llamadas.\n\nRealiza una prueba diciendo ‘llamar a [contacto]’, confirma con ‘Sí’ y vuelve aquí para consultar el registro."
                : value;
    }

    public static void clear(Context context) {
        if (context != null) context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_EVENTS).apply();
    }
}
