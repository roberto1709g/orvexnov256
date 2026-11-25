package com.leaden2.app;

import android.content.Intent;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.util.Log;
import android.speech.SpeechRecognizer;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.NotificationCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSession.ControllerInfo;
import androidx.media3.session.MediaSessionService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.io.InputStream;

public class PlaybackService extends MediaSessionService {
    public static final String ACTION_ACTIVATE = "com.leaden2.app.ACTION_ACTIVATE";
    public static final String ACTION_ASSISTANT = "com.leaden2.app.ACTION_ASSISTANT";
    public static final String ACTION_ENABLE = "com.leaden2.app.ACTION_ENABLE";
    public static final String ACTION_DEACTIVATE = "com.leaden2.app.ACTION_DEACTIVATE";
    public static final String ACTION_PLAY_LIBRARY_AUDIO = "com.leaden2.app.ACTION_PLAY_LIBRARY_AUDIO";
    public static final String ACTION_STOP_LIBRARY_AUDIO = "com.leaden2.app.ACTION_STOP_LIBRARY_AUDIO";
    public static final String ACTION_WHATSAPP_ALERT = "com.leaden2.app.ACTION_WHATSAPP_ALERT";
    public static final String ACTION_WHATSAPP_READ_DONE = "com.leaden2.app.ACTION_WHATSAPP_READ_DONE";
    public static final String ACTION_WHATSAPP_FLOW_DONE = "com.leaden2.app.ACTION_WHATSAPP_FLOW_DONE";
    public static final String EXTRA_WHATSAPP_SENDER = "com.leaden2.app.EXTRA_WHATSAPP_SENDER";
    public static final String EXTRA_WHATSAPP_TEXT = "com.leaden2.app.EXTRA_WHATSAPP_TEXT";
    public static final String EXTRA_AUDIO_URI = "audio_uri";
    public static final String EXTRA_AUDIO_ID = "audio_id";
    public static final String EXTRA_AUDIO_LOOP = "audio_loop";
    public static final String EXTRA_AUDIO_SEQUENCE_URIS = "audio_sequence_uris";
    private static final String TAG = "ORVEXPlayback";
    private static final String AUDIO_CHANNEL_ID = "leaden2_audio_playback";
    private static final int AUDIO_FOREGROUND_NOTIFICATION_ID = 3305;

    private ExoPlayer exoPlayer;
    private MediaSession mediaSession;
    private boolean activated = false;
    private boolean handlingAssistantAction = false;
    private boolean listenAfterCue = false;
    private boolean libraryAudioPlaying = false;
    private boolean libraryAudioLooping = false;
    private boolean consumingDuplicateMediaAction = false;
    private String pendingWhatsAppSender;
    private String pendingWhatsAppText;
    private boolean pendingWhatsAppAutoRead = false;
    private boolean resumeLibraryAfterWhatsApp = false;
    private boolean whatsappAlertInProgress = false;
    private boolean resumeAssistantListeningAfterWhatsApp = false;
    private final ArrayDeque<WhatsAppMessage> whatsappQueue = new ArrayDeque<>();
    private static final int MAX_PENDING_WHATSAPP = 20;
    private MediaItem savedLibraryMediaItem;
    private ArrayList<MediaItem> savedLibraryMediaItems;
    private int savedLibraryMediaIndex = 0;
    private long savedLibraryPosition = 0L;
    private boolean savedLibraryLooping = false;
    private final Handler mediaActionHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        activated = getSharedPreferences("leaden2_local_data", MODE_PRIVATE)
            .getBoolean("assistant_enabled", false);
        WhatsAppDiagnostics.record(this, "PlaybackService creado; ORVEX=" + (activated ? "ON" : "OFF"));

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build();

        exoPlayer = new ExoPlayer.Builder(this).build();
        exoPlayer.setAudioAttributes(audioAttributes, true);
        exoPlayer.setHandleAudioBecomingNoisy(true);
        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_ENDED && libraryAudioPlaying) {
                    libraryAudioPlaying = false;
                    libraryAudioLooping = false;
                    exoPlayer.setRepeatMode(Player.REPEAT_MODE_OFF);
                    Log.i(TAG, "La pista de la biblioteca terminó.");
                }
                if (playbackState == Player.STATE_ENDED && listenAfterCue) {
                    listenAfterCue = false;
                    if (pendingWhatsAppText != null) {
                        String sender = pendingWhatsAppSender;
                        String text = pendingWhatsAppText;
                        boolean autoRead = pendingWhatsAppAutoRead;
                        pendingWhatsAppSender = null;
                        pendingWhatsAppText = null;
                        pendingWhatsAppAutoRead = false;
                        if (autoRead) requestWhatsAppAutomaticRead(sender, text);
                        else requestWhatsAppConfirmation(sender, text);
                    } else if (!whatsappQueue.isEmpty() && !whatsappAlertInProgress) {
                        // Un aviso pendiente tiene prioridad sobre iniciar una nueva escucha.
                        resumeAssistantListeningAfterWhatsApp = true;
                        processNextWhatsAppAlert();
                    } else {
                        requestVoiceListening();
                    }
                } else if (playbackState == Player.STATE_ENDED && !whatsappAlertInProgress
                        && !whatsappQueue.isEmpty()) {
                    processNextWhatsAppAlert();
                }
            }
        });

        Player sessionPlayer = new ForwardingPlayer(exoPlayer) {
            @Override
            public void play() {
                if (consumeDuplicateMediaAction()) return;
                if (libraryAudioPlaying) {
                    stopLibraryAudioFromGlasses();
                } else if (activated && !handlingAssistantAction) {
                    runAssistantAction("PLAY");
                } else {
                    super.play();
                }
            }

            @Override
            public void pause() {
                if (consumeDuplicateMediaAction()) return;
                if (libraryAudioPlaying) {
                    stopLibraryAudioFromGlasses();
                } else if (activated && !handlingAssistantAction) {
                    runAssistantAction("PAUSE");
                } else {
                    super.pause();
                }
            }

            @Override
            public void setPlayWhenReady(boolean playWhenReady) {
                if (consumeDuplicateMediaAction()) return;
                if (libraryAudioPlaying) {
                    stopLibraryAudioFromGlasses();
                } else if (activated && !handlingAssistantAction) {
                    runAssistantAction(playWhenReady ? "PLAY_WHEN_READY" : "PAUSE_WHEN_READY");
                } else {
                    super.setPlayWhenReady(playWhenReady);
                }
            }
        };

        mediaSession = new MediaSession.Builder(this, sessionPlayer).build();
        Log.i(TAG, "MediaSession nativa creada.");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Android exige que un servicio iniciado con startForegroundService()
        // se promueva inmediatamente. MediaSessionService puede esperar a que
        // el reproductor cambie de estado; esta notificación evita ese timeout.
        if (intent != null && ACTION_PLAY_LIBRARY_AUDIO.equals(intent.getAction())) {
            promoteAudioPlaybackToForeground();
        }
        int result = super.onStartCommand(intent, flags, startId);
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_ACTIVATE.equals(action)) {
                activateAssistant();
            } else if (ACTION_ASSISTANT.equals(action)) {
                runAssistantAction("LOCAL");
            } else if (ACTION_ENABLE.equals(action)) {
                activated = getSharedPreferences("leaden2_local_data", MODE_PRIVATE)
                    .getBoolean("assistant_enabled", true);
                WhatsAppDiagnostics.record(this, "PlaybackService habilitado; ORVEX=" + (activated ? "ON" : "OFF"));
                Log.i(TAG, "ORVEX habilitado sin reproducir el audio inicial.");
            } else if (ACTION_DEACTIVATE.equals(action)) {
                deactivateAssistant();
                stopSelf();
                return START_NOT_STICKY;
            } else if (ACTION_PLAY_LIBRARY_AUDIO.equals(action)) {
                ArrayList<String> sequenceUris = intent.getStringArrayListExtra(EXTRA_AUDIO_SEQUENCE_URIS);
                if (sequenceUris != null && !sequenceUris.isEmpty()) {
                    playLibrarySequence(sequenceUris);
                } else {
                    String audioUri = intent.getStringExtra(EXTRA_AUDIO_URI);
                    playLibraryAudio(
                        audioUri,
                        intent.getIntExtra(EXTRA_AUDIO_ID, -1),
                        intent.getBooleanExtra(EXTRA_AUDIO_LOOP, false)
                    );
                }
            } else if (ACTION_STOP_LIBRARY_AUDIO.equals(action)) {
                stopLibraryAudioFromGlasses();
            } else if (ACTION_WHATSAPP_ALERT.equals(action)) {
                // La variable de instancia puede reiniciarse si Android recrea el servicio.
                activated = getSharedPreferences("leaden2_local_data", MODE_PRIVATE)
                    .getBoolean("assistant_enabled", false);
                if (!activated) {
                    WhatsAppDiagnostics.record(this, "PlaybackService rechazó aviso: preferencia ORVEX OFF.");
                    Log.i(TAG, "Aviso de WhatsApp ignorado: ORVEX no está activo.");
                    return result;
                }
                enqueueWhatsAppAlert(intent.getStringExtra(EXTRA_WHATSAPP_SENDER),
                    intent.getStringExtra(EXTRA_WHATSAPP_TEXT));
            } else if (ACTION_WHATSAPP_READ_DONE.equals(action)) {
                resumeLibraryAudioAfterWhatsApp();
                finishWhatsAppFlow();
            } else if (ACTION_WHATSAPP_FLOW_DONE.equals(action)) {
                finishWhatsAppFlow();
            }
        }
        return result;
    }

    private void activateAssistant() {
        try {
            activated = true;
            File initialAudio = copyAssetIfNeeded("comienza_el_ensamblaje.mp3");
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(initialAudio)));
            exoPlayer.prepare();
            exoPlayer.play();
            Log.i(TAG, "ORVEX activado: reproduciendo audio inicial.");
        } catch (IOException e) {
            Log.e(TAG, "No se pudo preparar el audio inicial.", e);
        }
    }

    private void deactivateAssistant() {
        activated = false;
        whatsappQueue.clear();
        whatsappAlertInProgress = false;
        pendingWhatsAppSender = null;
        pendingWhatsAppText = null;
        pendingWhatsAppAutoRead = false;
        resumeAssistantListeningAfterWhatsApp = false;
        resumeLibraryAfterWhatsApp = false;
        libraryAudioPlaying = false;
        libraryAudioLooping = false;
        if (exoPlayer != null) exoPlayer.setRepeatMode(Player.REPEAT_MODE_OFF);
        listenAfterCue = false;
        handlingAssistantAction = false;
        if (exoPlayer != null) {
            exoPlayer.stop();
            exoPlayer.clearMediaItems();
        }
        stopService(new Intent(this, VoiceAssistantService.class));
        Log.i(TAG, "ORVEX desactivado por el usuario.");
    }

    private void runAssistantAction(String source) {
        if (libraryAudioPlaying) {
            stopLibraryAudioFromGlasses();
            return;
        }
        if (handlingAssistantAction) return;
        handlingAssistantAction = true;
        try {
            File actionAudio = copyAssetIfNeeded("orden.mp3");
            listenAfterCue = true;
            exoPlayer.stop();
            exoPlayer.clearMediaItems();
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(actionAudio)));
            exoPlayer.prepare();
            exoPlayer.play();
            Log.i(TAG, "Acción del asistente iniciada. Origen: " + source);
        } catch (IOException e) {
            listenAfterCue = false;
            Log.e(TAG, "No se pudo reproducir orden.mp3.", e);
            requestVoiceListening();
        } finally {
            handlingAssistantAction = false;
        }
    }


    private static final class WhatsAppMessage {
        final String sender;
        final String text;
        WhatsAppMessage(String sender, String text) {
            this.sender = sender == null ? "" : sender.trim();
            this.text = text == null ? "" : text.trim();
        }
    }

    private void enqueueWhatsAppAlert(String sender, String text) {
        if (text == null || text.trim().isEmpty()) {
            WhatsAppDiagnostics.record(this, "Aviso no encolado: contenido vacío.");
            return;
        }
        if (whatsappQueue.size() >= MAX_PENDING_WHATSAPP) {
            whatsappQueue.removeFirst();
            WhatsAppDiagnostics.record(this, "Cola llena; se descartó el aviso pendiente más antiguo.");
        }
        whatsappQueue.addLast(new WhatsAppMessage(sender, text));
        WhatsAppDiagnostics.record(this, "Aviso agregado a cola; pendientes=" + whatsappQueue.size());
        processNextWhatsAppAlert();
    }

    private void processNextWhatsAppAlert() {
        if (whatsappAlertInProgress || whatsappQueue.isEmpty() || exoPlayer == null) return;
        activated = getSharedPreferences("leaden2_local_data", MODE_PRIVATE)
            .getBoolean("assistant_enabled", false);
        if (!activated) {
            whatsappQueue.clear();
            WhatsAppDiagnostics.record(this, "Cola descartada porque ORVEX está OFF.");
            return;
        }
        // No interrumpir audios del asistente; esperar al final de la pista actual.
        // La biblioteca conserva su tratamiento original: ON pausa y reanuda; OFF mantiene su flujo.
        if (exoPlayer.isPlaying() && !libraryAudioPlaying) {
            WhatsAppDiagnostics.record(this, "Aviso en espera: otro audio de ORVEX está activo.");
            return;
        }
        WhatsAppMessage message = whatsappQueue.removeFirst();
        whatsappAlertInProgress = true;
        pendingWhatsAppSender = message.sender;
        pendingWhatsAppText = message.text;
        pendingWhatsAppAutoRead = getSharedPreferences("leaden2_local_data", MODE_PRIVATE)
            .getBoolean("whatsapp_auto_read", false);
        WhatsAppDiagnostics.record(this, "Procesando aviso; modo="
            + (pendingWhatsAppAutoRead ? "ON-autolectura" : "OFF-confirmación"));
        playWhatsAppAlertNow(message.sender, message.text, pendingWhatsAppAutoRead);
    }

    private void playWhatsAppAlertNow(String sender, String text, boolean autoRead) {
        // En lectura automática conservamos la pista y su posición para reanudarla
        // exactamente donde quedó, incluso si estaba configurada en bucle.
        if (autoRead && libraryAudioPlaying && exoPlayer != null) {
            savedLibraryMediaItem = exoPlayer.getCurrentMediaItem();
            savedLibraryMediaItems = new ArrayList<>();
            for (int mediaIndex = 0; mediaIndex < exoPlayer.getMediaItemCount(); mediaIndex++) {
                savedLibraryMediaItems.add(exoPlayer.getMediaItemAt(mediaIndex));
            }
            savedLibraryMediaIndex = Math.max(0, exoPlayer.getCurrentMediaItemIndex());
            savedLibraryPosition = Math.max(0L, exoPlayer.getCurrentPosition());
            savedLibraryLooping = libraryAudioLooping;
            resumeLibraryAfterWhatsApp = savedLibraryMediaItem != null;
            libraryAudioPlaying = false;
            libraryAudioLooping = false;
            exoPlayer.setRepeatMode(Player.REPEAT_MODE_OFF);
            WhatsAppDiagnostics.record(this, "Biblioteca pausada para aviso; posición=" + savedLibraryPosition);
        } else if (libraryAudioPlaying) {
            boolean interruptedLoop = libraryAudioLooping;
            libraryAudioPlaying = false;
            libraryAudioLooping = false;
            listenAfterCue = false;
            if (exoPlayer != null) {
                exoPlayer.setRepeatMode(Player.REPEAT_MODE_OFF);
                exoPlayer.stop();
                exoPlayer.clearMediaItems();
            }
            WhatsAppDiagnostics.record(this, "Biblioteca interrumpida en modo confirmación; bucle=" + interruptedLoop);
        }

        try {
            File alertAudio = copyAssetIfNeeded("mensaje_whatsapp.mp3");
            listenAfterCue = true;
            exoPlayer.stop();
            exoPlayer.clearMediaItems();
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(alertAudio)));
            exoPlayer.prepare();
            exoPlayer.play();
            Log.i(TAG, "Aviso de WhatsApp reproducido; modo="
                + (autoRead ? "lectura automática" : "confirmación"));
            WhatsAppDiagnostics.record(this, "MP3 de aviso iniciado correctamente.");
        } catch (IOException e) {
            listenAfterCue = false;
            String pendingSender = pendingWhatsAppSender;
            String pendingText = pendingWhatsAppText;
            pendingWhatsAppSender = null;
            pendingWhatsAppText = null;
            pendingWhatsAppAutoRead = false;
            WhatsAppDiagnostics.record(this, "ERROR al cargar MP3; se intentará locución directa.");
            Log.e(TAG, "No se pudo reproducir mensaje_whatsapp.mp3.", e);
            if (autoRead) requestWhatsAppAutomaticRead(pendingSender, pendingText);
            else requestWhatsAppConfirmation(pendingSender, pendingText);
        }
    }

    private void finishWhatsAppFlow() {
        if (!whatsappAlertInProgress) {
            processNextWhatsAppAlert();
            return;
        }
        whatsappAlertInProgress = false;
        pendingWhatsAppSender = null;
        pendingWhatsAppText = null;
        pendingWhatsAppAutoRead = false;
        WhatsAppDiagnostics.record(this, "Aviso completado; pendientes=" + whatsappQueue.size());
        if (!whatsappQueue.isEmpty()) {
            processNextWhatsAppAlert();
        } else if (resumeAssistantListeningAfterWhatsApp) {
            resumeAssistantListeningAfterWhatsApp = false;
            requestVoiceListening();
        }
    }

    private void requestWhatsAppAutomaticRead(String sender, String text) {
        try {
            Intent voiceIntent = new Intent(this, VoiceAssistantService.class);
            voiceIntent.setAction(VoiceAssistantService.ACTION_WHATSAPP_AUTO_READ);
            voiceIntent.putExtra(VoiceAssistantService.EXTRA_WHATSAPP_SENDER, sender == null ? "" : sender);
            voiceIntent.putExtra(VoiceAssistantService.EXTRA_WHATSAPP_TEXT, text == null ? "" : text);
            startService(voiceIntent);
            Log.i(TAG, "Solicitud de lectura automática del mensaje WhatsApp enviada.");
        } catch (Exception e) {
            Log.e(TAG, "No se pudo solicitar la lectura automática de WhatsApp.", e);
            WhatsAppDiagnostics.record(this, "ERROR al iniciar lectura automática.");
            resumeLibraryAudioAfterWhatsApp();
            finishWhatsAppFlow();
        }
    }

    private void resumeLibraryAudioAfterWhatsApp() {
        if (!resumeLibraryAfterWhatsApp || savedLibraryMediaItem == null || exoPlayer == null) return;
        try {
            exoPlayer.stop();
            exoPlayer.clearMediaItems();
            exoPlayer.setRepeatMode(savedLibraryLooping ? Player.REPEAT_MODE_ONE
                : (savedLibraryMediaItems != null && savedLibraryMediaItems.size() > 1
                    ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF));
            if (savedLibraryMediaItems != null && !savedLibraryMediaItems.isEmpty()) {
                exoPlayer.setMediaItems(savedLibraryMediaItems, savedLibraryMediaIndex, savedLibraryPosition);
            } else {
                exoPlayer.setMediaItem(savedLibraryMediaItem);
                exoPlayer.seekTo(savedLibraryPosition);
            }
            exoPlayer.prepare();
            libraryAudioLooping = savedLibraryLooping;
            libraryAudioPlaying = true;
            exoPlayer.play();
            Log.i(TAG, "Pista de biblioteca reanudada después del aviso WhatsApp.");
        } catch (Exception e) {
            libraryAudioPlaying = false;
            libraryAudioLooping = false;
            Log.e(TAG, "No se pudo reanudar la pista después del aviso WhatsApp.", e);
        } finally {
            resumeLibraryAfterWhatsApp = false;
            savedLibraryMediaItem = null;
            savedLibraryMediaItems = null;
            savedLibraryMediaIndex = 0;
            savedLibraryPosition = 0L;
            savedLibraryLooping = false;
        }
    }

    private void requestWhatsAppConfirmation(String sender, String text) {
        try {
            Intent voiceIntent = new Intent(this, VoiceAssistantService.class);
            voiceIntent.setAction(VoiceAssistantService.ACTION_WHATSAPP_CONFIRM);
            voiceIntent.putExtra(VoiceAssistantService.EXTRA_WHATSAPP_SENDER, sender == null ? "" : sender);
            voiceIntent.putExtra(VoiceAssistantService.EXTRA_WHATSAPP_TEXT, text == null ? "" : text);
            startService(voiceIntent);
            Log.i(TAG, "Solicitud de confirmación de WhatsApp enviada a VoiceAssistantService.");
        } catch (Exception e) {
            Log.e(TAG, "No se pudo solicitar confirmación de WhatsApp.", e);
            WhatsAppDiagnostics.record(this, "ERROR al iniciar confirmación de WhatsApp.");
            finishWhatsAppFlow();
        }
    }

    private void requestVoiceListening() {
        try {
            Intent voiceIntent = new Intent(this, VoiceAssistantService.class);
            voiceIntent.setAction(VoiceAssistantService.ACTION_LISTEN);
            startService(voiceIntent);
        } catch (Exception e) {
            Log.e(TAG, "No se pudo activar el reconocimiento de voz.", e);
        }
    }

    private void promoteAudioPlaybackToForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                AUDIO_CHANNEL_ID,
                "Reproducción de audios ORVEX",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Mantiene activa la reproducción de canciones y audiolibros.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(this, AUDIO_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("ORVEX está reproduciendo un audio")
            .setContentText("La reproducción se controla con el botón central de las gafas.")
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                AUDIO_FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            );
        } else {
            startForeground(AUDIO_FOREGROUND_NOTIFICATION_ID, notification);
        }
        Log.i(TAG, "Servicio promovido a primer plano para reproducir audio de biblioteca.");
    }

    private void playLibrarySequence(ArrayList<String> audioUris) {
        if (audioUris == null || audioUris.isEmpty()) return;
        try {
            ArrayList<MediaItem> sequenceItems = new ArrayList<>();
            for (String audioUri : audioUris) {
                if (audioUri == null || audioUri.trim().isEmpty()) {
                    Log.e(TAG, "La secuencia contiene una URI vacía.");
                    return;
                }
                sequenceItems.add(MediaItem.fromUri(Uri.parse(audioUri)));
            }
            listenAfterCue = false;
            handlingAssistantAction = false;
            stopService(new Intent(this, VoiceAssistantService.class));
            exoPlayer.stop();
            exoPlayer.clearMediaItems();
            exoPlayer.setRepeatMode(Player.REPEAT_MODE_ALL);
            exoPlayer.setMediaItems(sequenceItems);
            exoPlayer.prepare();
            libraryAudioLooping = false;
            libraryAudioPlaying = true;
            exoPlayer.play();
            Log.i(TAG, "Reproducción de secuencia cíclica iniciada. Cantidad de pistas="
                + sequenceItems.size());
        } catch (Exception e) {
            libraryAudioPlaying = false;
            libraryAudioLooping = false;
            exoPlayer.setRepeatMode(Player.REPEAT_MODE_OFF);
            Log.e(TAG, "No se pudo reproducir la secuencia de la biblioteca.", e);
        }
    }

    private void playLibraryAudio(String audioUri, int audioId, boolean loopMode) {
        if (audioUri == null || audioUri.trim().isEmpty()) {
            Log.e(TAG, "No se recibió una URI válida para la pista.");
            return;
        }
        try {
            listenAfterCue = false;
            handlingAssistantAction = false;
            stopService(new Intent(this, VoiceAssistantService.class));
            exoPlayer.stop();
            exoPlayer.clearMediaItems();
            exoPlayer.setRepeatMode(loopMode ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(audioUri)));
            exoPlayer.prepare();
            libraryAudioLooping = loopMode;
            libraryAudioPlaying = true;
            exoPlayer.play();
            Log.i(TAG, "Reproduciendo pista de biblioteca id=" + audioId
                + ", modo=" + (loopMode ? "bucle continuo" : "una vez"));
        } catch (Exception e) {
            libraryAudioPlaying = false;
            libraryAudioLooping = false;
            exoPlayer.setRepeatMode(Player.REPEAT_MODE_OFF);
            Log.e(TAG, "No se pudo reproducir la pista de la biblioteca.", e);
        }
    }

    private boolean consumeDuplicateMediaAction() {
        if (!consumingDuplicateMediaAction) return false;
        consumingDuplicateMediaAction = false;
        mediaActionHandler.removeCallbacksAndMessages(null);
        return true;
    }

    private void stopLibraryAudioFromGlasses() {
        if (!libraryAudioPlaying) return;
        libraryAudioPlaying = false;
        libraryAudioLooping = false;
        consumingDuplicateMediaAction = true;
        mediaActionHandler.removeCallbacksAndMessages(null);
        mediaActionHandler.postDelayed(() -> consumingDuplicateMediaAction = false, 300);
        listenAfterCue = false;
        if (exoPlayer != null) {
            exoPlayer.setRepeatMode(Player.REPEAT_MODE_OFF);
            exoPlayer.stop();
            exoPlayer.clearMediaItems();
        }
        Log.i(TAG, "Pista de biblioteca detenida por el botón multimedia de las gafas.");
    }

    private File copyAssetIfNeeded(String assetName) throws IOException {
        File target = new File(getFilesDir(), assetName);
        boolean refreshWhatsAppAsset = "mensaje_whatsapp.mp3".equals(assetName);
        if (!refreshWhatsAppAsset && target.exists() && target.length() > 0) return target;

        try (InputStream input = getAssets().open(assetName);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
        }
        if (!target.exists() || target.length() == 0) {
            throw new IOException("El asset quedó vacío: " + assetName);
        }
        return target;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // La sesión de reproducción puede seguir activa; no se detiene automáticamente.
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        if (exoPlayer != null) {
            exoPlayer.release();
            exoPlayer = null;
        }
        super.onDestroy();
    }

    @Override
    public MediaSession onGetSession(ControllerInfo controllerInfo) {
        return mediaSession;
    }
}
