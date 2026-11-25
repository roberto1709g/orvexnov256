# ORVEX V1.7.0 — CORRECCIÓN DE APERTURA EN SEGUNDO PLANO

## Base
Copia de ORVEX V1.6.9 — DIAGNÓSTICO DE LLAMADAS, derivada a su vez del ZIP original V1.6.8. El ZIP V1.6.8 permanece intacto.

## Objetivo
Corregir la configuración de inicio de `WakeAndLaunchActivity` mediante `PendingIntent` para Android moderno y obtener evidencia pasiva sobre el resultado posterior a seleccionar «Llamada».

## Cambios
- `versionCode 25`, `versionName 1.7.0`; `targetSdk 35` y `compileSdk 35` se conservan.
- `VoiceAssistantService.java`: al crear el `PendingIntent`, configura `setPendingIntentCreatorBackgroundActivityStartMode(MODE_BACKGROUND_ACTIVITY_START_ALLOWED)` en API 34+. Al enviarlo, conserva la autorización independiente del emisor mediante `setPendingIntentBackgroundActivityStartMode(...)`.
- `WhatsAppCallDiagnostics.java`: registra el estado que Android informa mediante `ActivityManager.isBackgroundRestricted()`, sin solicitar permisos adicionales.
- `WakeAndLaunchActivity.java`: agrega registros de `onStart`, `onPause`, `onStop` y `onDestroy`; no cambia la secuencia de desbloqueo ni de apertura de WhatsApp.
- `WhatsAppCallAccessibilityService.java`: después de un clic aceptado sobre «Llamada», mantiene desarmada la automatización para impedir repeticiones y observa pasivamente hasta 20 segundos si WhatsApp expone indicadores accesibles compatibles con una llamada en curso/establecimiento. No vuelve a pulsar controles ni almacena textos personales.
- El registro diferencia evidencia de llamada, ausencia de evidencia e incertidumbre. No interpreta `ACTION_CLICK` aceptado como llamada iniciada.

## Límites deliberados
- No se modifican reconocimiento de voz, confirmación hablada, TTS, agenda/contactos, biblioteca de audio, switches, servicio de notificaciones ni `WhatsAppDiagnostics`.
- No se cambia el mecanismo de selección del contacto ni el botón de llamada.
- La observación posterior es solo diagnóstica: no puede garantizar que todas las versiones de WhatsApp expongan el estado de llamada mediante accesibilidad.
- La autorización BAL del creador es relevante para apps con `targetSdk 35` en Android 15; su inclusión en API 34+ mantiene explícita la configuración, pero no garantiza por sí sola que HyperOS permita cada lanzamiento en segundo plano.

## Prueba recomendada
1. Instalar la APK compilada con la misma firma de la versión actual. No desinstalar ORVEX para evitar perder datos locales.
2. Confirmar que el servicio de accesibilidad continúa habilitado.
3. Borrar el registro de llamadas.
4. Con pantalla encendida y desbloqueada, dejar ORVEX en segundo plano, solicitar una llamada y responder «Sí». Esperar 20 segundos antes de abrir ORVEX.
5. Copiar el registro. Repetir con el teléfono bloqueado.
6. Compartir los registros completos. Buscar especialmente `ACTIVITY: onCreate`, `onStart`, `onResume`, `VERIFICACION` y los eventos de accesibilidad.

## Compilación
El paquete contiene el proyecto fuente Android Studio; no incluye APK precompilada. Se debe compilar en un entorno Android SDK/Gradle.
