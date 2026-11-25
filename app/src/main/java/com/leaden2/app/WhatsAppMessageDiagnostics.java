package com.leaden2.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Registro local del flujo de mensajes salientes; no almacena el contenido dictado. */
public final class WhatsAppMessageDiagnostics {
    private static final String PREFS = "orvex_message_diagnostics";
    private static final String KEY = "events";
    private static final int MAX = 100;
    private static final String TAG = "ORVEXMessageDiag";
    private WhatsAppMessageDiagnostics() { }
    public static synchronized void record(Context context, String event) {
        if (context == null || event == null || event.trim().isEmpty()) return;
        try {
            SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            List<String> lines = new ArrayList<>();
            String old = p.getString(KEY, "");
            if (!old.isEmpty()) for (String line : old.split("\\n")) if (!line.trim().isEmpty()) lines.add(line);
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            lines.add(time + " | " + event.replace('\n', ' '));
            while (lines.size() > MAX) lines.remove(0);
            p.edit().putString(KEY, String.join("\n", lines)).apply();
            Log.i(TAG, event);
        } catch (Exception e) { Log.w(TAG, "No se pudo guardar registro de mensajes", e); }
    }
    public static String getLog(Context context) {
        if (context == null) return "No se pudo leer el registro.";
        String s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "");
        return s.trim().isEmpty() ? "Todavía no hay eventos registrados. Prueba la instrucción mensaje a [contacto]." : s;
    }
    public static void clear(Context context) {
        if (context != null) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply();
    }
}
