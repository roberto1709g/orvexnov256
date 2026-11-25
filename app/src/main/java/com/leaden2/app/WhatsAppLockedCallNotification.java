package com.leaden2.app;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

/**
 * Notificación visible para continuar una llamada confirmada cuando Android
 * mantiene el dispositivo bloqueado. La apertura de WhatsApp solo ocurre
 * después de que el usuario pulse la acción y el sistema autorice el acceso.
 */
public final class WhatsAppLockedCallNotification {
    private static final String CHANNEL_ID = "orvex_locked_call_action_v173";
    private static final int NOTIFICATION_ID = 7317;

    private WhatsAppLockedCallNotification() { }

    public static boolean show(Context context, String contactName, String phone,
                               String requestId, long expiresAt) {
        if (context == null || phone == null || phone.trim().isEmpty()
                || requestId == null || requestId.trim().isEmpty()) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return false;
        }

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Continuar llamadas de WhatsApp",
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Aviso para continuar una llamada confirmada cuando el teléfono está bloqueado.");
            channel.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
            manager.createNotificationChannel(channel);
            if (manager.getNotificationChannel(CHANNEL_ID) != null
                    && manager.getNotificationChannel(CHANNEL_ID).getImportance() == NotificationManager.IMPORTANCE_NONE) {
                return false;
            }
        }

        Intent actionIntent = new Intent(context, WakeAndLaunchActivity.class);
        actionIntent.putExtra(WakeAndLaunchActivity.EXTRA_CONTACT_NAME,
                contactName == null ? "el contacto" : contactName);
        actionIntent.putExtra(WakeAndLaunchActivity.EXTRA_PHONE, phone);
        actionIntent.putExtra(WakeAndLaunchActivity.EXTRA_REQUEST_ID, requestId);
        actionIntent.putExtra(WakeAndLaunchActivity.EXTRA_USER_INITIATED, true);
        actionIntent.setData(Uri.parse("orvex://whatsapp-call-user-action/" + requestId));
        actionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        int requestCode = requestId.hashCode() & 0x7fffffff;
        PendingIntent actionPendingIntent = PendingIntent.getActivity(
                context,
                requestCode,
                actionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Action.Builder actionBuilder = new NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_call,
                "CONTINUAR",
                actionPendingIntent);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentTitle("ORVEX: llamada pendiente")
                .setContentText("Desbloquea el teléfono y toca «Continuar» para abrir WhatsApp.")
                .setStyle(new NotificationCompat.BigTextStyle().bigText(
                        "La llamada fue confirmada por voz. Por seguridad, desbloquea el teléfono y toca «Desbloquear y continuar»."))
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                // Tocar el cuerpo de la notificación ejecuta la misma ruta segura
                // que la acción CONTINUAR, después de la autenticación del sistema.
                .setContentIntent(actionPendingIntent)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setTimeoutAfter(Math.max(1000L, expiresAt - System.currentTimeMillis()))
                .addAction(actionBuilder.build());

        try {
            manager.notify(NOTIFICATION_ID, builder.build());
            return true;
        } catch (SecurityException exception) {
            return false;
        }
    }

    public static void cancel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(NOTIFICATION_ID);
    }
}
