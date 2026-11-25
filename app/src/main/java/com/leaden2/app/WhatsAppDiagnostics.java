package com.leaden2.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Registro técnico local de avisos WhatsApp. No almacena remitentes ni contenido de mensajes. */
public final class WhatsAppDiagnostics {
    private static final String TAG = "ORVEXDiag";
    private static final String PREFS = "orvex_whatsapp_diagnostics";
    private static final String KEY_EVENTS = "events";
    private static final int MAX_EVENTS = 80;

    private WhatsAppDiagnostics() { }

    public static synchronized void record(Context context, String event) {
        if (context == null || event == null || event.trim().isEmpty()) return;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String old = prefs.getString(KEY_EVENTS, "");
            List<String> lines = new ArrayList<>();
            if (old != null && !old.isEmpty()) {
                for (String line : old.split("\\n")) {
                    if (!line.trim().isEmpty()) lines.add(line);
                }
            }
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date());
            lines.add(time + " | " + event.replace('\n', ' '));
            while (lines.size() > MAX_EVENTS) lines.remove(0);
            StringBuilder value = new StringBuilder();
            for (String line : lines) {
                if (value.length() > 0) value.append('\n');
                value.append(line);
            }
            prefs.edit().putString(KEY_EVENTS, value.toString()).apply();
            Log.i(TAG, event);
        } catch (Exception e) {
            Log.w(TAG, "No se pudo guardar el registro técnico de WhatsApp.", e);
        }
    }

    public static String getLog(Context context) {
        if (context == null) return "No se pudo leer el registro.";
        String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_EVENTS, "");
        return value == null || value.trim().isEmpty()
            ? "Todavía no hay eventos registrados. Activa ORVEX y prueba la llegada de mensajes."
            : value;
    }

    public static void clear(Context context) {
        if (context != null) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_EVENTS).apply();
        }
    }
}
