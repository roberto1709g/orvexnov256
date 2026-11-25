# ORVEX V1.6.9 — DIAGNÓSTICO DE LLAMADAS

## Base
Copia de ORVEX V1.6.8 — RECONEXIÓN NOTIFICATION LISTENER.

## Objetivo
Añadir trazabilidad local al flujo de llamadas de WhatsApp para identificar dónde se interrumpe cuando ORVEX está en segundo plano o el teléfono está bloqueado.

## Cambios
- Nuevo `WhatsAppCallDiagnostics.java`, con preferencias independientes (`orvex_whatsapp_call_diagnostics`) y hasta 200 eventos.
- Nuevo botón `VER REGISTRO DE LLAMADAS DE WHATSAPP` en `MainActivity`, con acciones COPIAR, BORRAR y CERRAR.
- Instrumentación de la confirmación y envío de `PendingIntent` en `VoiceAssistantService`.
- Instrumentación de ciclo de vida, estado de Keyguard, callbacks de desbloqueo y resultados de intentos de apertura en `WakeAndLaunchActivity`.
- Instrumentación de conexión, eventos de ventana, verificación de contacto, detección de controles y acciones en `WhatsAppCallAccessibilityService`.
- Versión Android: `versionCode 24`, `versionName 1.6.9`.

## Límites deliberados
- No se cambió el algoritmo de reconocimiento de voz ni el texto de confirmación.
- No se cambió la búsqueda ni validación de contactos.
- No se cambió el orden de las llamadas a `speak()`, `PendingIntent`, `startActivity()`, `performAction()`, `dispatchGesture()`, reintentos ni desarmado.
- No se cambió el servicio de notificaciones ni su almacenamiento `WhatsAppDiagnostics`.
- El registro NO guarda el nombre ni el número del contacto.
- Que `PendingIntent.send()`, `startActivity()`, `ACTION_CLICK` o `dispatchGesture()` no arrojen error no significa que WhatsApp haya mostrado la pantalla o que la llamada se haya iniciado. El registro distingue expresamente esos hechos.

## Prueba sugerida
1. Instalar la APK de diagnóstico sobre la versión actual (si Android permite actualizar con la misma firma).
2. Abrir ORVEX una vez y comprobar que sus permisos continúan activos.
3. Borrar el registro de llamadas.
4. Probar desde la pantalla principal: botón de gafas → orden → `Sí`.
5. Volver a ORVEX y copiar el registro.
6. Borrar el registro y repetir con el teléfono bloqueado. Si Android exige autenticación, completarla normalmente y luego copiar el registro.
7. Compartir ambos registros para identificar la etapa exacta.

## Compilación
El ZIP contiene el proyecto Android Studio. La compilación de APK debe ejecutarse en un entorno con Android SDK y Gradle configurados. No se incluye una APK precompilada en este paquete.
