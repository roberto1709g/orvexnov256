package com.leaden2.app;

import android.Manifest;
import android.app.Notification;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.net.Uri;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.provider.Settings;
import android.text.TextUtils;
import android.content.ComponentName;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VoiceAssistantService extends Service implements TextToSpeech.OnInitListener {
    public static final String ACTION_PREPARE = "com.leaden2.app.voice.PREPARE";
    public static final String ACTION_LISTEN = "com.leaden2.app.voice.LISTEN";
    public static final String ACTION_WHATSAPP_CONFIRM = "com.leaden2.app.voice.WHATSAPP_CONFIRM";
    public static final String ACTION_WHATSAPP_AUTO_READ = "com.leaden2.app.voice.WHATSAPP_AUTO_READ";
    public static final String EXTRA_WHATSAPP_SENDER = "com.leaden2.app.voice.EXTRA_WHATSAPP_SENDER";
    public static final String EXTRA_WHATSAPP_TEXT = "com.leaden2.app.voice.EXTRA_WHATSAPP_TEXT";
    private static final String TAG = "ORVEXVoice";
    private static final String CHANNEL_ID = "leaden2_voice_service";
    private static final int NOTIFICATION_ID = 2202;
    private static final String PREFS = "leaden2_local_data";
    private static final String WEBHOOK_URL = "https://n8n.serviapps.duckdns.org/webhook/asistente-leaden2";

    private SpeechRecognizer speechRecognizer;
    private TextToSpeech textToSpeech;
    private boolean ttsReady = false;
    private boolean listening = false;
    private boolean ttsBusy = false;
    private String pendingSpeech;
    private boolean awaitingCallConfirmation = false;
    private boolean awaitingMessageContent = false;
    private String pendingMessageContactName;
    private String pendingMessagePhone;
    private boolean awaitingWhatsAppConfirmation = false;
    private boolean whatsappAutoReadUtterancePending = false;
    private boolean whatsappFlowDoneUtterancePending = false;
    private String pendingWhatsAppMessageSender;
    private String pendingWhatsAppMessageText;
    private String pendingCallContactName;
    private String pendingCallPhone;
    private String pendingWhatsAppContactName;
    private String pendingWhatsAppPhone;
    private String pendingWhatsAppRequestId;
    private boolean pendingWhatsAppAutoCall = false;
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        textToSpeech = new TextToSpeech(this, this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        promoteToForeground();
        String action = intent == null ? ACTION_PREPARE : intent.getAction();
        if (ACTION_WHATSAPP_CONFIRM.equals(action) || ACTION_WHATSAPP_AUTO_READ.equals(action)) {
            WhatsAppDiagnostics.record(this, "VoiceAssistantService recibió solicitud: "
                + (ACTION_WHATSAPP_AUTO_READ.equals(action) ? "lectura automática" : "confirmación"));
        }
        if (ACTION_LISTEN.equals(action)) {
            startListening();
        } else if (ACTION_WHATSAPP_CONFIRM.equals(action)) {
            prepareWhatsAppConfirmation(
                intent.getStringExtra(EXTRA_WHATSAPP_SENDER),
                intent.getStringExtra(EXTRA_WHATSAPP_TEXT));
        } else if (ACTION_WHATSAPP_AUTO_READ.equals(action)) {
            prepareWhatsAppAutomaticRead(
                intent.getStringExtra(EXTRA_WHATSAPP_SENDER),
                intent.getStringExtra(EXTRA_WHATSAPP_TEXT));
        }
        return START_STICKY;
    }

    private void promoteToForeground() {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("ORVEX está activo")
            .setContentText("Servicio de voz preparado para las gafas")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            speak("Para usar el asistente, debes permitir el acceso al micrófono.");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("El reconocimiento de voz no está disponible en este teléfono.");
            return;
        }
        if (listening) {
            try { speechRecognizer.cancel(); } catch (Exception ignored) { }
            listening = false;
        }
        if (speechRecognizer != null) {
            try { speechRecognizer.destroy(); } catch (Exception ignored) { }
            speechRecognizer = null;
        }
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { Log.i(TAG, "Reconocimiento preparado."); }
                @Override public void onBeginningOfSpeech() { Log.i(TAG, "Usuario comenzó a hablar."); }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { listening = false; }
                @Override public void onError(int error) {
                    listening = false;
                    Log.w(TAG, "Error de reconocimiento: " + error);
                    if (awaitingWhatsAppConfirmation) {
                        clearPendingWhatsAppConfirmation();
                        speakWhatsAppFlowDone("No pude escuchar tu confirmación. No reproduciré el mensaje de WhatsApp.");
                    } else if (awaitingCallConfirmation) {
                        clearPendingCallConfirmation();
                        speak("No pude escuchar tu confirmación. He cancelado la operación por seguridad.");
                    } else {
                        speak(errorMessage(error));
                    }
                }
                @Override public void onResults(Bundle results) {
                    listening = false;
                    ArrayList<String> matches = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    String recognized = matches == null || matches.isEmpty() ? "" : matches.get(0).trim();
                    if (recognized.isEmpty()) speak("No logré entender la consulta. Inténtalo otra vez.");
                    else {
                        Log.i(TAG, "Consulta de voz reconocida; caracteres=" + recognized.length());
                        if (awaitingWhatsAppConfirmation) {
                            processWhatsAppMessageConfirmation(recognized);
                            return;
                        }
                        if (awaitingCallConfirmation) {
                            processCallConfirmation(recognized);
                            return;
                        }
                        if (awaitingMessageContent) {
                            processLocalWhatsAppMessageContent(recognized);
                            return;
                        }
                        // Las órdenes de biblioteca se procesan localmente y nunca llegan a n8n.
                        if (handleAudioCommand(recognized)) return;
                        ActionType action = classify(recognized);
                        if (action == ActionType.MESSAGE) {
                            processLocalWhatsAppMessageRequest(recognized);
                        } else if (action == ActionType.CALL) {
                            // Procesamiento local: las solicitudes CALL nunca se envían a n8n.
                            Log.i(TAG, "Solicitud CALL interceptada localmente; no se enviará al webhook.");
                            processLocalCallRequest(recognized);
                        } else {
                            sendToWebhook(recognized);
                        }
                    }
                }
                @Override public void onPartialResults(Bundle partialResults) { }
                @Override public void onEvent(int eventType, Bundle params) { }
            });
            Intent recognitionIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            recognitionIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            recognitionIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-EC");
            recognitionIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-EC");
            recognitionIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            recognitionIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);

            // Confirmaciones de WhatsApp y llamadas: damos el mismo tiempo adicional
            // para que el usuario pueda responder "sí/no" sin tener que hacerlo rápidamente.
            // El resto del reconocimiento de ORVEX conserva su comportamiento actual.
            if (awaitingWhatsAppConfirmation || awaitingCallConfirmation) {
                recognitionIntent.putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3500L);
                recognitionIntent.putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L);
                recognitionIntent.putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
            }

            listening = true;
            speechRecognizer.startListening(recognitionIntent);
            Log.i(TAG, "Escucha iniciada.");
        } catch (Exception e) {
            listening = false;
            Log.e(TAG, "No se pudo iniciar el reconocimiento de voz.", e);
            speak("No pude iniciar el reconocimiento de voz. Comprueba el permiso del micrófono.");
        }
    }



    private void prepareWhatsAppAutomaticRead(String sender, String text) {
        if (!getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("assistant_enabled", false)) {
            Log.i(TAG, "Lectura automática de WhatsApp ignorada: ORVEX no está activo.");
            notifyWhatsAppAutomaticReadFinished();
            return;
        }
        if (text == null || text.trim().isEmpty()) {
            notifyWhatsAppAutomaticReadFinished();
            return;
        }
        awaitingWhatsAppConfirmation = false;
        if (speechRecognizer != null && listening) {
            try { speechRecognizer.cancel(); } catch (Exception ignored) { }
            listening = false;
        }
        if (textToSpeech != null) {
            try { textToSpeech.stop(); } catch (Exception ignored) { }
        }
        String cleanSender = sender == null || sender.trim().isEmpty() ? "un contacto" : sender.trim();
        whatsappAutoReadUtterancePending = true;
        Log.i(TAG, "Leyendo automáticamente mensaje WhatsApp; caracteres=" + text.length());
        WhatsAppDiagnostics.record(this, "Locución automática iniciada; caracteres=" + text.length());
        speak(cleanSender + " te ha enviado un mensaje: " + text.trim());
    }

    private void notifyWhatsAppAutomaticReadFinished() {
        WhatsAppDiagnostics.record(this, "Locución automática finalizada o interrumpida; se libera la cola.");
        try {
            Intent doneIntent = new Intent(this, PlaybackService.class);
            doneIntent.setAction(PlaybackService.ACTION_WHATSAPP_READ_DONE);
            startService(doneIntent);
        } catch (Exception e) {
            Log.e(TAG, "No se pudo notificar el fin de la lectura automática de WhatsApp.", e);
        }
    }

    private void notifyWhatsAppFlowFinished() {
        WhatsAppDiagnostics.record(this, "Flujo de confirmación finalizado; se libera la cola.");
        try {
            Intent doneIntent = new Intent(this, PlaybackService.class);
            doneIntent.setAction(PlaybackService.ACTION_WHATSAPP_FLOW_DONE);
            startService(doneIntent);
        } catch (Exception e) {
            Log.e(TAG, "No se pudo notificar el fin de la confirmación WhatsApp.", e);
        }
    }

    private void speakWhatsAppFlowDone(String text) {
        if (text == null || text.trim().isEmpty()) {
            notifyWhatsAppFlowFinished();
            return;
        }
        mainHandler.post(() -> {
            pendingSpeech = text.trim();
            whatsappFlowDoneUtterancePending = true;
            if (ttsReady && textToSpeech != null) speakPending();
        });
    }

    private void prepareWhatsAppConfirmation(String sender, String text) {
        if (!getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("assistant_enabled", false)) {
            Log.i(TAG, "Confirmación de WhatsApp ignorada: ORVEX no está activo.");
            notifyWhatsAppFlowFinished();
            return;
        }
        if (text == null || text.trim().isEmpty()) {
            notifyWhatsAppFlowFinished();
            return;
        }
        pendingWhatsAppMessageSender = sender == null || sender.trim().isEmpty() ? "un contacto" : sender.trim();
        pendingWhatsAppMessageText = text.trim();
        awaitingWhatsAppConfirmation = true;
        if (speechRecognizer != null && listening) {
            try { speechRecognizer.cancel(); } catch (Exception ignored) { }
            listening = false;
        }
        if (textToSpeech != null) {
            try { textToSpeech.stop(); } catch (Exception ignored) { }
        }
        WhatsAppDiagnostics.record(this, "Pregunta de confirmación preparada; caracteres=" + text.length());
        speak("Mensaje de " + pendingWhatsAppMessageSender + ". ¿Deseas escucharlo?");
    }

    private void processWhatsAppMessageConfirmation(String recognizedText) {
        String response = normalizeWords(recognizedText);
        if (response.equals("si") || response.equals("si quiero") || response.equals("si confirmo")
                || response.equals("confirmo") || response.equals("adelante") || response.equals("escuchar")
                || response.equals("escucharlo") || response.equals("de acuerdo")) {
            String sender = pendingWhatsAppMessageSender == null ? "el contacto" : pendingWhatsAppMessageSender;
            String text = pendingWhatsAppMessageText == null ? "" : pendingWhatsAppMessageText;
            clearPendingWhatsAppConfirmation();
            speakWhatsAppFlowDone(sender + " dice: " + text);
            Log.i(TAG, "Mensaje de WhatsApp confirmado para reproducción; caracteres=" + text.length());
            return;
        }
        if (response.equals("no") || response.equals("no quiero") || response.equals("cancelar")
                || response.equals("cancela") || response.equals("mejor no")) {
            clearPendingWhatsAppConfirmation();
            speakWhatsAppFlowDone("De acuerdo. No reproduciré el mensaje de WhatsApp.");
            Log.i(TAG, "Reproducción del mensaje de WhatsApp cancelada por el usuario.");
            return;
        }
        clearPendingWhatsAppConfirmation();
        speakWhatsAppFlowDone("No entendí la respuesta. He cancelado la reproducción por seguridad.");
    }

    private void clearPendingWhatsAppConfirmation() {
        awaitingWhatsAppConfirmation = false;
        pendingWhatsAppMessageSender = null;
        pendingWhatsAppMessageText = null;
    }

    private boolean handleAudioCommand(String recognizedText) {
        String normalized = Normalizer.normalize(recognizedText.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "");
        String[] words = normalized.split("[^a-z0-9]+", -1);
        int wordPosition = 0;
        for (int i = 0; i < words.length; i++) {
            if (words[i].isEmpty()) continue;
            wordPosition++;
            if (wordPosition > 3) return false;

            boolean loopMode = words[i].equals("bucle");
            boolean singleMode = words[i].equals("audio");
            boolean sequenceMode = words[i].equals("secuencia");
            if (!loopMode && !singleMode && !sequenceMode) continue;

            ArrayList<Integer> audioIds = new ArrayList<>();
            int next = i + 1;
            while (next < words.length && words[next].isEmpty()) next++;
            while (next < words.length) {
                if (words[next].isEmpty()) {
                    next++;
                    continue;
                }
                // En modo secuencia el usuario dicta una lista de dígitos individuales (1-9).
                // Algunos reconocedores concatenan varios dígitos en un solo resultado:
                // por ejemplo, "dos seis tres" puede llegar como "263" y "uno tres" como "13".
                // En ese caso se separan los dígitos para recuperar la secuencia solicitada.
                if (sequenceMode) {
                    int parsed = parseAudioNumber(words[next]);
                    if (parsed < 1) break;
                    if (parsed > 9) {
                        String combinedDigits = String.valueOf(parsed);
                        boolean validIndividualDigits = true;
                        for (int digitIndex = 0; digitIndex < combinedDigits.length(); digitIndex++) {
                            char digitChar = combinedDigits.charAt(digitIndex);
                            if (digitChar < '1' || digitChar > '9') {
                                validIndividualDigits = false;
                                break;
                            }
                        }
                        if (!validIndividualDigits) {
                            speak("La secuencia solo admite audios del uno al nueve.");
                            return true;
                        }
                        for (int digitIndex = 0; digitIndex < combinedDigits.length(); digitIndex++) {
                            audioIds.add(combinedDigits.charAt(digitIndex) - '0');
                        }
                    } else {
                        audioIds.add(parsed);
                    }
                    next++;
                    continue;
                }

                int parsed = parseAudioNumber(words[next]);
                if (parsed < 1) break;
                audioIds.add(parsed);
                next++;
                if (!sequenceMode) break;
            }
            if (audioIds.isEmpty()) {
                speak(sequenceMode
                    ? "Indica los números de los audios que deseas reproducir en secuencia."
                    : (loopMode
                        ? "Indica el número de la pista que deseas repetir en bucle."
                        : "Indica el número del audio que deseas reproducir."));
                return true;
            }

            ArrayList<String> audioUris = new ArrayList<>();
            for (int audioId : audioIds) {
                JSONObject item = AudioLibraryStore.findById(this, audioId);
                if (item == null || item.optString("uri", "").trim().isEmpty()) {
                    speak("No existe el " + AudioLibraryStore.label(audioId) + " en tu biblioteca.");
                    return true;
                }
                audioUris.add(item.optString("uri", ""));
            }

            Intent playIntent = new Intent(this, PlaybackService.class);
            playIntent.setAction(PlaybackService.ACTION_PLAY_LIBRARY_AUDIO);
            if (sequenceMode) {
                playIntent.putStringArrayListExtra(PlaybackService.EXTRA_AUDIO_SEQUENCE_URIS, audioUris);
            } else {
                playIntent.putExtra(PlaybackService.EXTRA_AUDIO_URI, audioUris.get(0));
                playIntent.putExtra(PlaybackService.EXTRA_AUDIO_ID, audioIds.get(0));
                playIntent.putExtra(PlaybackService.EXTRA_AUDIO_LOOP, loopMode);
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(playIntent);
                else startService(playIntent);
                Log.i(TAG, "Orden de biblioteca procesada localmente: audios=" + audioIds
                    + ", modo=" + (sequenceMode ? "secuencia cíclica" : (loopMode ? "bucle" : "una vez")));
            } catch (Exception e) {
                Log.e(TAG, "No se pudo iniciar la reproducción local.", e);
                speak("No pude iniciar ese audio. Abre la biblioteca y comprueba los archivos.");
            }
            return true;
        }
        return false;
    }

    private int parseAudioNumber(String word) {
        switch (word) {
            case "1": case "uno": case "un": case "una": return 1;
            case "2": case "dos": return 2;
            case "3": case "tres": return 3;
            case "4": case "cuatro": return 4;
            case "5": case "cinco": return 5;
            case "6": case "seis": return 6;
            case "7": case "siete": return 7;
            case "8": case "ocho": return 8;
            case "9": case "nueve": return 9;
            case "10": case "diez": return 10;
            case "11": case "once": return 11;
            case "12": case "doce": return 12;
            case "13": case "trece": return 13;
            case "14": case "catorce": return 14;
            case "15": case "quince": return 15;
            case "16": case "dieciseis": return 16;
            case "17": case "diecisiete": return 17;
            case "18": case "dieciocho": return 18;
            case "19": case "diecinueve": return 19;
            case "20": case "veinte": return 20;
            default:
                try {
                    int number = Integer.parseInt(word);
                    return number > 0 && number <= 999 ? number : -1;
                } catch (NumberFormatException e) {
                    return -1;
                }
        }
    }

    private void sendToWebhook(String recognizedText) {
        // El plan de IA es una condición global para las consultas de voz a n8n.
        // Las acciones locales (audio, bucle y llamadas) se resuelven antes de llegar aquí.
        if (!getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("ai_plan_enabled", false)) {
            Log.i(TAG, "Consulta no enviada a n8n: plan de inteligencia artificial desactivado.");
            speak("El plan de inteligencia artificial está desactivado. Actívalo desde la pantalla principal de ORVEX.");
            return;
        }
        networkExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
                JSONObject profile = new JSONObject(prefs.getString("profile", "{}"));
                if (profile.optString("nombre").trim().isEmpty()
                    || profile.optString("cedula").trim().isEmpty()
                    || profile.optString("celular").trim().isEmpty()
                    || profile.optString("correo").trim().isEmpty()) {
                    speak("Completa y guarda los datos del usuario en ORVEX antes de hacer una consulta.");
                    return;
                }

                JSONObject payload = new JSONObject();
                payload.put("nombre", profile.optString("nombre"));
                payload.put("cedula", profile.optString("cedula"));
                payload.put("celular", profile.optString("celular"));
                payload.put("correo", profile.optString("correo"));
                payload.put("text", recognizedText);

                ActionType action = classify(recognizedText);
                if (action == ActionType.ANALYSIS) {
                    payload.put("analisis", "analisis");
                } else if (action == ActionType.MESSAGE) {
                    payload.put("contactos", new JSONArray(prefs.getString("contacts", "[]")));
                }

                URL url = new URL(WEBHOOK_URL);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("Accept", "application/json");
                byte[] body = payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(body); }
                int status = connection.getResponseCode();
                InputStream stream = status >= 200 && status < 400 ? connection.getInputStream() : connection.getErrorStream();
                String response = readAll(stream);
                if (status < 200 || status >= 300) {
                    Log.e(TAG, "Webhook devolvió HTTP " + status);
                    speak("El servidor del asistente devolvió un error. Inténtalo más tarde.");
                    return;
                }
                String outputText = extractOutput(response);
                if (outputText.isEmpty()) {
                    Log.w(TAG, "La respuesta no contiene el campo output.");
                    speak("Recibí una respuesta del servidor, pero no encontré el texto para leer.");
                } else {
                    Log.i(TAG, "Respuesta del webhook recibida correctamente.");
                    speak(outputText);
                }
            } catch (Exception e) {
                Log.e(TAG, "Falló la comunicación con el webhook ORVEX.", e);
                speak("No pude comunicarme con el asistente. Comprueba la conexión a internet.");
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }


    /** Acción MESSAGE local: valida contacto de ORVEX y pide dictar el contenido, sin n8n. */
    private void processLocalWhatsAppMessageRequest(String recognizedText) {
        String requestedName = extractRequestedMessageContactName(recognizedText);
        WhatsAppMessageDiagnostics.record(this, "ORDEN mensaje detectada; se inicia procesamiento local.");
        if (requestedName.isEmpty()) {
            WhatsAppMessageDiagnostics.record(this, "ERROR: no se pudo extraer el nombre del contacto de la instrucción.");
            speak("Di mensaje a y el nombre del contacto registrado.");
            return;
        }
        JSONArray contacts;
        try {
            contacts = new JSONArray(getSharedPreferences(PREFS, MODE_PRIVATE).getString("contacts", "[]"));
        } catch (Exception e) {
            WhatsAppMessageDiagnostics.record(this, "ERROR: no se pudo leer la agenda local.");
            speak("No pude leer la agenda de ORVEX.");
            return;
        }
        ArrayList<JSONObject> exact = new ArrayList<>();
        ArrayList<JSONObject> partial = new ArrayList<>();
        String wanted = normalizeWords(requestedName);
        for (int i = 0; i < contacts.length(); i++) {
            JSONObject c = contacts.optJSONObject(i);
            if (c == null) continue;
            String name = c.optString("nombre", "").trim();
            if (name.isEmpty()) continue;
            String n = normalizeWords(name);
            if (n.equals(wanted)) exact.add(c);
            else if (n.startsWith(wanted + " ") || wanted.startsWith(n + " ")) partial.add(c);
        }
        ArrayList<JSONObject> matches = exact.isEmpty() ? partial : exact;
        if (matches.isEmpty()) {
            WhatsAppMessageDiagnostics.record(this, "CONTACTO NO ENCONTRADO: " + requestedName);
            speak("No encontré a " + requestedName + " en los contactos de ORVEX.");
            return;
        }
        if (matches.size() != 1) {
            WhatsAppMessageDiagnostics.record(this, "CONTACTO AMBIGUO: coincidencias=" + matches.size());
            speak("Hay varios contactos con ese nombre. Di el nombre completo y vuelve a intentarlo.");
            return;
        }
        JSONObject contact = matches.get(0);
        String name = contact.optString("nombre", requestedName).trim();
        String phone = normalizeEcuadorMobile(contact.optString("telefono", ""));
        if (phone == null) {
            WhatsAppMessageDiagnostics.record(this, "ERROR: número inválido para contacto=" + name);
            speak("El número de " + name + " no es válido. Corrígelo en los contactos de ORVEX.");
            return;
        }
        pendingMessageContactName = name;
        pendingMessagePhone = phone;
        awaitingMessageContent = true;
        WhatsAppMessageDiagnostics.record(this, "CONTACTO VALIDADO: " + name + "; inicia captura del texto dictado.");
        speak("Contacto " + name + ". Ahora dicta el mensaje.");
    }

    private void processLocalWhatsAppMessageContent(String dictatedText) {
        awaitingMessageContent = false;
        String name = pendingMessageContactName;
        String phone = pendingMessagePhone;
        pendingMessageContactName = null;
        pendingMessagePhone = null;
        if (name == null || phone == null || dictatedText == null || dictatedText.trim().isEmpty()) {
            WhatsAppMessageDiagnostics.record(this, "ERROR: faltan datos del destinatario o texto dictado.");
            speak("No pude completar el mensaje. Vuelve a intentarlo.");
            return;
        }
        String messageText = dictatedText.trim();
        WhatsAppMessageDiagnostics.record(this, "DICTADO RECONOCIDO: caracteres=" + messageText.length() + "; destinatario=" + name);
        if (!isWhatsAppCallAccessibilityEnabled()) {
            WhatsAppMessageDiagnostics.record(this, "ERROR: el servicio de accesibilidad de ORVEX no está habilitado; no se puede automatizar el envío.");
            speak("Para enviar mensajes automáticamente, habilita el servicio de accesibilidad de ORVEX en ajustes.");
            return;
        }
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            prefs.edit()
                    .putBoolean("wa_auto_message_armed", true)
                    .putString("wa_auto_message_name", name)
                    .putString("wa_auto_message_phone", phone)
                    .putString("wa_auto_message_text", messageText)
                    .putLong("wa_auto_message_armed_at", System.currentTimeMillis())
                    .apply();
            Intent launch = new Intent(WhatsAppCallAccessibilityService.ACTION_START_AUTO_MESSAGE);
            launch.setPackage(getPackageName());
            sendBroadcast(launch);
            WhatsAppMessageDiagnostics.record(this, "MENSAJE ARMADO: se solicitó apertura de WhatsApp y automatización local para " + name + ".");
            speak("Preparando y enviando el mensaje a " + name + ".");
        } catch (Exception e) {
            WhatsAppMessageDiagnostics.record(this, "ERROR AL PREPARAR ENVÍO AUTOMÁTICO: " + e.getClass().getSimpleName());
            Log.e(TAG, "No se pudo preparar el envío automático de WhatsApp.", e);
            speak("No pude preparar el envío automático a WhatsApp.");
        }
    }

    private String extractRequestedMessageContactName(String recognizedText) {
        String normalized = normalizeWords(recognizedText);
        String[] words = normalized.split("\\s+");
        int messageIndex = -1;
        for (int i = 0; i < words.length && i < 3; i++) if (words[i].equals("mensaje")) { messageIndex = i; break; }
        if (messageIndex < 0) return "";
        ArrayList<String> name = new ArrayList<>();
        for (int i = messageIndex + 1; i < words.length; i++) {
            String w = words[i];
            if (w.equals("a") || w.equals("para") || w.equals("whatsapp")) continue;
            name.add(w);
        }
        return String.join(" ", name).trim();
    }

    /** Busca el contacto solicitado en la agenda local de ORVEX. No inicia llamadas. */
    private void processLocalCallRequest(String recognizedText) {
        String requestedName = extractRequestedContactName(recognizedText);
        if (requestedName.isEmpty()) {
            speak("No entendí el nombre del contacto. Di, por ejemplo, llamar a Roberto.");
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        JSONArray contacts;
        try {
            contacts = new JSONArray(prefs.getString("contacts", "[]"));
        } catch (JSONException e) {
            Log.e(TAG, "La agenda local no contiene un JSON válido.", e);
            speak("No pude leer la agenda local de contactos.");
            return;
        }

        ArrayList<JSONObject> exactMatches = new ArrayList<>();
        ArrayList<JSONObject> partialMatches = new ArrayList<>();
        String requestedNormalized = normalizeWords(requestedName);
        for (int i = 0; i < contacts.length(); i++) {
            JSONObject contact = contacts.optJSONObject(i);
            if (contact == null) continue;
            String contactName = contact.optString("nombre", "").trim();
            if (contactName.isEmpty()) continue;
            String contactNormalized = normalizeWords(contactName);
            if (contactNormalized.equals(requestedNormalized)) {
                exactMatches.add(contact);
            } else if (contactNormalized.startsWith(requestedNormalized + " ")
                    || requestedNormalized.startsWith(contactNormalized + " ")) {
                partialMatches.add(contact);
            }
        }

        ArrayList<JSONObject> matches = exactMatches.isEmpty() ? partialMatches : exactMatches;
        if (matches.isEmpty()) {
            Log.i(TAG, "Contacto no encontrado en agenda local: " + requestedNormalized);
            speak("No encontré a " + requestedName + " en la agenda de ORVEX.");
            return;
        }
        if (matches.size() > 1) {
            StringBuilder names = new StringBuilder();
            for (JSONObject match : matches) {
                if (names.length() > 0) names.append(", ");
                names.append(match.optString("nombre", "contacto"));
            }
            Log.i(TAG, "Búsqueda de contacto ambigua; coincidencias=" + matches.size());
            speak("Encontré varios contactos que coinciden: " + names + ". Di el nombre completo.");
            return;
        }

        JSONObject contact = matches.get(0);
        String contactName = contact.optString("nombre", requestedName).trim();
        String normalizedPhone = normalizeEcuadorMobile(contact.optString("telefono", ""));
        if (normalizedPhone == null) {
            Log.w(TAG, "El contacto encontrado tiene un teléfono móvil ecuatoriano inválido: " + contactName);
            speak("Encontré a " + contactName + ", pero su número no es un móvil ecuatoriano válido. Corrígelo en la agenda.");
            return;
        }

        Log.i(TAG, "Contacto local encontrado; nombre=" + contactName + ", número válido=" + normalizedPhone);
        pendingCallContactName = contactName;
        pendingCallPhone = normalizedPhone;
        awaitingCallConfirmation = true;
        speak("He encontrado a " + contactName + ". ¿Deseas continuar?");
    }

    /** Procesa la confirmación localmente y abre el chat de WhatsApp; la llamada requiere tocar su icono. */
    private void processCallConfirmation(String recognizedText) {
        String response = normalizeWords(recognizedText);
        if (response.equals("si") || response.equals("si confirmo") || response.equals("confirmo")
                || response.equals("correcto") || response.equals("adelante") || response.equals("de acuerdo")) {
            String contactName = pendingCallContactName == null ? "el contacto" : pendingCallContactName;
            String phone = pendingCallPhone == null ? "" : pendingCallPhone;
            clearPendingCallConfirmation();
            if (phone.isEmpty()) {
                Log.w(TAG, "Confirmación recibida sin número pendiente; no se abrirá WhatsApp.");
                speak("No tengo un número validado para este contacto. No abrí WhatsApp.");
                return;
            }
            pendingWhatsAppContactName = contactName;
            pendingWhatsAppPhone = phone;
            pendingWhatsAppAutoCall = isWhatsAppCallAccessibilityEnabled();
            WhatsAppCallDiagnostics.recordDeviceState(this,
                    "CONFIRMACION_SI recibida; accesibilidad habilitada=" + pendingWhatsAppAutoCall);
            if (pendingWhatsAppAutoCall) {
                String requestId = UUID.randomUUID().toString();
                long armedAt = System.currentTimeMillis();
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("wa_auto_call_armed", true)
                    .putBoolean("wa_call_menu_opened", false)
                    .putString("wa_auto_call_name", contactName)
                    .putString("wa_auto_call_phone", phone)
                    .putString("wa_auto_call_request_id", requestId)
                    .putLong("wa_auto_call_armed_at", armedAt)
                    .putLong("wa_auto_call_launch_expires_at", armedAt + 60000L)
                    .apply();
                pendingWhatsAppRequestId = requestId;
                Log.i(TAG, "Confirmación afirmativa recibida para " + contactName + "; automatización de llamada armada para el número validado.");
                WhatsAppCallDiagnostics.record(this, "CONFIRMACION: orden wa_auto_call_armed=true; datos validados guardados (sin registrar el número).");
                speak("Confirmado.");
            } else {
                invalidatePendingWhatsAppCall();
                WhatsAppCallDiagnostics.record(this, "CONFIRMACION: accesibilidad de llamadas no habilitada; la orden automática quedó desarmada.");
                Log.i(TAG, "Servicio de accesibilidad de llamadas no habilitado; se abrirá únicamente el chat.");
                speak("Confirmado.");
            }
            return;
        }
        if (response.equals("no") || response.equals("no confirmo") || response.equals("cancelar")
                || response.equals("cancela") || response.equals("cancelalo") || response.equals("mejor no")) {
            String contactName = pendingCallContactName == null ? "la llamada" : "la llamada a " + pendingCallContactName;
            clearPendingCallConfirmation();
            invalidatePendingWhatsAppCall();
            Log.i(TAG, "Confirmación de llamada cancelada por el usuario.");
            speak("De acuerdo. He cancelado " + contactName + ".");
            return;
        }
        clearPendingCallConfirmation();
        invalidatePendingWhatsAppCall();
        Log.i(TAG, "Respuesta de confirmación no reconocida; operación cancelada por seguridad.");
        speak("No entendí la respuesta. He cancelado la operación por seguridad. Si deseas intentarlo, vuelve a decir llamar y el nombre del contacto.");
    }

    /** Invalida la orden pendiente para impedir aperturas o llamadas tardías. */
    private void invalidatePendingWhatsAppCall() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean("wa_auto_call_armed", false)
                .remove("wa_auto_call_name")
                .remove("wa_auto_call_phone")
                .remove("wa_auto_call_request_id")
                .remove("wa_auto_call_armed_at")
                .remove("wa_auto_call_launch_expires_at")
                .remove("wa_auto_call_notification_expires_at")
                .putBoolean("wa_call_menu_opened", false)
                .apply();
        pendingWhatsAppRequestId = null;
    }

    /** Comprueba si el usuario habilitó explícitamente nuestro servicio de accesibilidad. */
    private boolean isWhatsAppCallAccessibilityEnabled() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            ComponentName component = new ComponentName(this, WhatsAppCallAccessibilityService.class);
            return !TextUtils.isEmpty(enabled) && enabled.toLowerCase(Locale.ROOT)
                .contains(component.flattenToString().toLowerCase(Locale.ROOT));
        } catch (Exception e) {
            Log.w(TAG, "No se pudo verificar el servicio de accesibilidad.", e);
            return false;
        }
    }

    /**
     * Pide a una actividad de transición que encienda la pantalla, permita el desbloqueo
     * normal del usuario y después abra el chat del número validado.
     */
    private void openPendingWhatsAppChat() {
        String contactName = pendingWhatsAppContactName == null ? "el contacto" : pendingWhatsAppContactName;
        String phone = pendingWhatsAppPhone == null ? "" : pendingWhatsAppPhone;
        String requestId = pendingWhatsAppRequestId;
        pendingWhatsAppContactName = null;
        pendingWhatsAppPhone = null;
        pendingWhatsAppRequestId = null;
        WhatsAppCallDiagnostics.recordDeviceState(this,
                "TRANSICION: openPendingWhatsAppChat invocado; automatizacion=" + pendingWhatsAppAutoCall);
        WhatsAppCallDiagnostics.recordBackgroundExecutionState(this);

        if (phone.isEmpty()) {
            WhatsAppCallDiagnostics.record(this, "TRANSICION ERROR: no había teléfono pendiente; no se envió la actividad.");
            if (pendingWhatsAppAutoCall) invalidatePendingWhatsAppCall();
            speak("No tengo un número válido para abrir WhatsApp.");
            return;
        }

        if (pendingWhatsAppAutoCall && requestId != null) {
            android.app.KeyguardManager keyguard =
                    (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (keyguard != null && keyguard.isKeyguardLocked()) {
                long notificationExpiresAt = System.currentTimeMillis() + 85000L;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putLong("wa_auto_call_notification_expires_at", notificationExpiresAt)
                        .apply();
                boolean shown = WhatsAppLockedCallNotification.show(
                        this, contactName, phone, requestId, notificationExpiresAt);
                if (shown) {
                    WhatsAppCallDiagnostics.recordDeviceState(this,
                            "BLOQUEO: se mostró una notificación con acción explícita; WhatsApp esperará a que el usuario desbloquee y pulse Continuar.");
                    return;
                }
                WhatsAppCallDiagnostics.recordDeviceState(this,
                        "BLOQUEO: no se pudo mostrar la notificación de continuación; se cancela la orden por seguridad. Verifica el permiso y los avisos de ORVEX.");
                invalidatePendingWhatsAppCall();
                speak("No puedo mostrar el aviso para continuar. Habilita las notificaciones de ORVEX y vuelve a intentarlo.");
                return;
            }
        }

        Intent launchIntent = new Intent(this, WakeAndLaunchActivity.class);
        launchIntent.putExtra(WakeAndLaunchActivity.EXTRA_CONTACT_NAME, contactName);
        launchIntent.putExtra(WakeAndLaunchActivity.EXTRA_PHONE, phone);
        if (pendingWhatsAppAutoCall && requestId != null) {
            launchIntent.putExtra(WakeAndLaunchActivity.EXTRA_REQUEST_ID, requestId);
            launchIntent.setData(android.net.Uri.parse("orvex://whatsapp-call/" + requestId));
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        try {
            // Android 15 (targetSdk 35) requiere que el CREADOR del PendingIntent
            // conceda explícitamente sus privilegios BAL. Se configura también en
            // Android 14 para mantener un único flujo compatible desde API 34.
            Bundle creatorOptionsBundle = null;
            if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions creatorOptions = ActivityOptions.makeBasic();
                creatorOptions.setPendingIntentCreatorBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                creatorOptionsBundle = creatorOptions.toBundle();
                WhatsAppCallDiagnostics.record(this,
                        "TRANSICION: creador del PendingIntent autorizó explícitamente BAL (Android 14+; requerido para targetSdk 35 en Android 15+).");
            }

            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this,
                    requestId == null ? 1530 : (requestId.hashCode() & 0x7fffffff),
                    launchIntent,
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                    creatorOptionsBundle
            );

            WhatsAppCallDiagnostics.record(this, "TRANSICION: PendingIntent de WakeAndLaunchActivity creado.");
            if (Build.VERSION.SDK_INT >= 34) {
                // El emisor conserva su autorización explícita, independiente de la del creador.
                ActivityOptions senderOptions = ActivityOptions.makeBasic();
                senderOptions.setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                WhatsAppCallDiagnostics.record(this,
                        "TRANSICION: emisor autorizado explícitamente para iniciar actividad en segundo plano; enviando PendingIntent.");
                pendingIntent.send(this, 0, null, null, null, null, senderOptions.toBundle());
            } else {
                WhatsAppCallDiagnostics.record(this, "TRANSICION: enviando PendingIntent con mecanismo anterior a Android 14.");
                pendingIntent.send();
            }
            WhatsAppCallDiagnostics.recordDeviceState(this,
                    "TRANSICION: PendingIntent.send() terminó sin excepción; esto no confirma que WakeAndLaunchActivity se haya mostrado.");

            Log.i(TAG, "Se solicitó la transición a WhatsApp para " + contactName
                    + ". Automatización armada=" + pendingWhatsAppAutoCall + ".");
        } catch (PendingIntent.CanceledException | SecurityException e) {
            WhatsAppCallDiagnostics.record(this, "TRANSICION ERROR: " + e.getClass().getSimpleName() + " al enviar PendingIntent: " + String.valueOf(e.getMessage()));
            Log.e(TAG, "Android no permitió iniciar la transición a WhatsApp.", e);
            if (pendingWhatsAppAutoCall) invalidatePendingWhatsAppCall();
            speak("Android no permitió abrir WhatsApp. Desbloquea el teléfono y vuelve a intentarlo.");
        }
    }

    private void clearPendingCallConfirmation() {
        awaitingCallConfirmation = false;
        pendingCallContactName = null;
        pendingCallPhone = null;
    }

    /** Devuelve el texto que sigue a la palabra llamar, eliminando conectores conocidos. */
    private String extractRequestedContactName(String recognizedText) {
        String normalized = normalizeWords(recognizedText);
        String[] words = normalized.split("\\s+");
        int callIndex = -1;
        for (int i = 0; i < words.length && i < 3; i++) {
            if (words[i].equals("llamar")) { callIndex = i; break; }
        }
        if (callIndex < 0) return "";
        ArrayList<String> nameWords = new ArrayList<>();
        for (int i = callIndex + 1; i < words.length; i++) {
            String word = words[i];
            if (word.isEmpty() || word.equals("a") || word.equals("por") || word.equals("favor") || word.equals("whatsapp")) continue;
            nameWords.add(word);
        }
        StringBuilder result = new StringBuilder();
        for (String word : nameWords) {
            if (result.length() > 0) result.append(' ');
            result.append(word);
        }
        return result.toString().trim();
    }

    /** Normaliza texto para comparar nombres sin tildes, signos ni diferencias de mayúsculas. */
    private String normalizeWords(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "");
        return normalized.replaceAll("[^a-z0-9]+", " ").trim().replaceAll("\\s+", " ");
    }

    /** Acepta 0991234567, 991234567, 593991234567 y +593991234567. */
    private String normalizeEcuadorMobile(String rawPhone) {
        if (rawPhone == null) return null;
        String digits = rawPhone.replaceAll("\\D", "");
        if (digits.startsWith("593")) digits = digits.substring(3);
        if (digits.length() == 10 && digits.startsWith("09")) digits = digits.substring(1);
        if (digits.length() != 9 || !digits.startsWith("9")) return null;
        return "+593" + digits;
    }

    private ActionType classify(String text) {
        String normalized = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "");
        String[] words = normalized.split("[^a-z0-9]+", -1);
        int callPosition = -1;
        int analysisPosition = -1;
        int messagePosition = -1;
        int wordCount = 0;
        for (String word : words) {
            if (word.isEmpty()) continue;
            wordCount++;
            if (wordCount > 3) break;
            if (callPosition < 0 && word.equals("llamar")) callPosition = wordCount;
            if (analysisPosition < 0 && word.equals("analisis")
                && getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getBoolean("analysis_command_enabled", true)) {
                analysisPosition = wordCount;
            }
            if (messagePosition < 0 && word.equals("mensaje")) messagePosition = wordCount;
        }
        // CALL tiene prioridad si "llamar" aparece entre las tres primeras palabras.
        if (callPosition >= 0) return ActionType.CALL;
        // Si ambas palabras clave aparecen entre las tres primeras, se usa la que aparece antes.
        if (analysisPosition >= 0 && (messagePosition < 0 || analysisPosition < messagePosition)) return ActionType.ANALYSIS;
        if (messagePosition >= 0) return ActionType.MESSAGE;
        return ActionType.GENERAL;
    }

private String extractOutput(String response) throws JSONException {
    if (response == null || response.trim().isEmpty()) return "";

    Object parsed = new org.json.JSONTokener(response).nextValue();
    JSONObject object = null;

    if (parsed instanceof JSONArray) {
        JSONArray array = (JSONArray) parsed;
        if (array.length() > 0) {
            object = array.optJSONObject(0);
        }
    } else if (parsed instanceof JSONObject) {
        object = (JSONObject) parsed;
    }

    if (object == null) return "";

    // Acepta el formato actual del webhook y el formato anterior.
    String output = object.optString("texto", "").trim();

    if (output.isEmpty()) {
        output = object.optString("output", "").trim();
    }

    return output;
}

   
    private String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toString("UTF-8");
        }
    }

    private String errorMessage(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO: return "Hubo un problema con el audio del micrófono.";
            case SpeechRecognizer.ERROR_CLIENT: return "Se interrumpió el reconocimiento de voz. Inténtalo otra vez.";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "Falta el permiso del micrófono.";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "El servicio de reconocimiento necesita conexión a internet.";
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "No detecté una frase clara. Inténtalo otra vez.";
            default: return "El reconocimiento de voz falló. Inténtalo otra vez.";
        }
    }

    private void speak(String text) {
        if (text == null || text.trim().isEmpty()) return;
        String message = text.trim();
        mainHandler.post(() -> {
            pendingSpeech = message;
            if (ttsReady && textToSpeech != null) speakPending();
        });
    }

    private void speakPending() {
        if (!ttsReady || textToSpeech == null || pendingSpeech == null) return;
        String text = pendingSpeech;
        pendingSpeech = null;
        textToSpeech.setLanguage(new Locale("es", "EC"));
        textToSpeech.setSpeechRate(1.0f);
        String utteranceId;
        if (whatsappAutoReadUtterancePending) {
            whatsappAutoReadUtterancePending = false;
            utteranceId = "orvex-whatsapp-auto-read";
        } else if (whatsappFlowDoneUtterancePending) {
            whatsappFlowDoneUtterancePending = false;
            utteranceId = "orvex-whatsapp-flow-done";
        } else if (awaitingWhatsAppConfirmation && text.toLowerCase(Locale.ROOT).startsWith("mensaje de ")) {
            textToSpeech.setSpeechRate(1.12f);
            utteranceId = "orvex-whatsapp-confirmation";
        } else if (awaitingCallConfirmation
                && text.toLowerCase(Locale.ROOT).startsWith("he encontrado a ")) {
            // Al terminar la pregunta breve, escuchar la respuesta sí/no.
            utteranceId = "leaden2-call-confirmation";
        } else if (awaitingMessageContent && text.toLowerCase(Locale.ROOT).startsWith("contacto ")) {
            // Al anunciar el destinatario validado, inicia automáticamente el dictado del mensaje.
            utteranceId = "orvex-message-content-listen";
        } else if (pendingWhatsAppPhone != null
                && text.equalsIgnoreCase("Confirmado.")) {
            // La frase audible se mantiene breve; al terminar, continuar con WhatsApp.
            // El servicio de accesibilidad verificará el contacto antes de seleccionar llamada.
            utteranceId = "leaden2-whatsapp-open";
        } else {
            utteranceId = "leaden2-response";
        }
        if (!"orvex-whatsapp-confirmation".equals(utteranceId)) {
            textToSpeech.setSpeechRate(1.0f);
        }
        if (Build.VERSION.SDK_INT >= 21) textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        else textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null);
    }

    @Override
    public void onInit(int status) {
        ttsReady = status == TextToSpeech.SUCCESS;
        if (ttsReady) {
            textToSpeech.setLanguage(new Locale("es", "EC"));
            textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) { }
                @Override public void onDone(String utteranceId) {
                    if ("orvex-whatsapp-auto-read".equals(utteranceId)) {
                        mainHandler.post(() -> notifyWhatsAppAutomaticReadFinished());
                    } else if ("orvex-whatsapp-flow-done".equals(utteranceId)) {
                        mainHandler.post(() -> notifyWhatsAppFlowFinished());
                    } else if ("orvex-whatsapp-confirmation".equals(utteranceId)) {
                        mainHandler.post(() -> {
                            if (awaitingWhatsAppConfirmation) startListening();
                        });
                    } else if ("leaden2-call-confirmation".equals(utteranceId)) {
                        mainHandler.post(() -> {
                            if (awaitingCallConfirmation) startListening();
                        });
                    } else if ("orvex-message-content-listen".equals(utteranceId)) {
                        mainHandler.post(() -> {
                            if (awaitingMessageContent) startListening();
                        });
                    } else if ("leaden2-whatsapp-open".equals(utteranceId)) {
                        mainHandler.post(() -> openPendingWhatsAppChat());
                    }
                }
                @Override public void onError(String utteranceId) {
                    if ("orvex-whatsapp-auto-read".equals(utteranceId)) {
                        mainHandler.post(() -> {
                            Log.w(TAG, "Falló la lectura automática del mensaje WhatsApp; se reanudará el audio.");
                            notifyWhatsAppAutomaticReadFinished();
                        });
                    } else if ("orvex-whatsapp-flow-done".equals(utteranceId)) {
                        mainHandler.post(() -> notifyWhatsAppFlowFinished());
                    } else if ("orvex-whatsapp-confirmation".equals(utteranceId)) {
                        mainHandler.post(() -> {
                            if (awaitingWhatsAppConfirmation) {
                                clearPendingWhatsAppConfirmation();
                                speakWhatsAppFlowDone("No pude reproducir la pregunta. No reproduciré el mensaje de WhatsApp.");
                            }
                        });
                    } else if ("leaden2-call-confirmation".equals(utteranceId)) {
                        mainHandler.post(() -> {
                            if (awaitingCallConfirmation) {
                                clearPendingCallConfirmation();
                                speak("No pude reproducir la pregunta de confirmación. La operación ha sido cancelada.");
                            }
                        });
                    } else if ("orvex-message-content-listen".equals(utteranceId)) {
                        mainHandler.post(() -> {
                            awaitingMessageContent = false;
                            pendingMessageContactName = null;
                            pendingMessagePhone = null;
                            WhatsAppMessageDiagnostics.record(VoiceAssistantService.this, "ERROR: fallo TTS de aviso; flujo de dictado cancelado.");
                        });
                    } else if ("leaden2-whatsapp-open".equals(utteranceId)) {
                        mainHandler.post(() -> {
                            pendingWhatsAppContactName = null;
                            pendingWhatsAppPhone = null;
                            speak("No pude reproducir la instrucción. No abrí WhatsApp ni inicié una llamada.");
                        });
                    }
                }
            });
            speakPending();
        } else Log.e(TAG, "No se pudo inicializar TextToSpeech.");
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Servicio de voz ORVEX", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Mantiene preparado el asistente de voz para las gafas Bluetooth.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        if (speechRecognizer != null) {
            try { speechRecognizer.cancel(); speechRecognizer.destroy(); } catch (Exception ignored) { }
            speechRecognizer = null;
        }
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            textToSpeech = null;
        }
        networkExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private enum ActionType { CALL, ANALYSIS, MESSAGE, GENERAL }
}
