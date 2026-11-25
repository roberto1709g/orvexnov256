# ORVEX V1.7.4 – Configuración guiada de permisos

## Base

Esta versión parte del proyecto fuente ORVEX V1.7.3. Se conserva su flujo funcional de reconocimiento de voz, ejecución en primer y segundo plano, apertura de WhatsApp Business y continuación desde la notificación en pantalla bloqueada.

## Cambios

Se incorpora en la pantalla principal una sección **5. CONFIGURACIÓN DE PERMISOS DE ORVEX**, con cinco accesos numerados:

1. **Permisos y otros permisos de ORVEX:** abre directamente la ficha de información de la aplicación ORVEX. Desde allí, en HyperOS, se pueden revisar permisos, permisos restringidos y Otros permisos, incluidos los accesos de ventanas, pantalla de bloqueo y pantallas de inicio cuando el sistema los ofrece.
2. **Accesibilidad:** abre los ajustes de accesibilidad de Android. El usuario debe localizar ORVEX en Aplicaciones descargadas y habilitar el servicio de llamadas de WhatsApp.
3. **Avisos de WhatsApp:** conserva el acceso existente a la configuración de acceso a notificaciones.
4. **Inicio automático:** intenta abrir la pantalla de inicio automático de Xiaomi/HyperOS. Si el dispositivo no admite ese intent, abre la ficha de ORVEX y muestra la ruta manual.
5. **Batería y segundo plano:** abre la pantalla de Android para administrar la exclusión de optimización de batería. La selección de ORVEX se realiza manualmente en esa pantalla.

Los botones solo abren las pantallas de configuración; no conceden permisos automáticamente. Los nombres y disponibilidad de algunas opciones dependen de la versión de HyperOS.

## Alcance técnico

- `versionCode`: 29
- `versionName`: 1.7.4
- Cambios de código: `MainActivity.java` y `app/build.gradle`.
- No se modifican `VoiceAssistantService.java`, `WakeAndLaunchActivity.java`, `WhatsAppLockedCallNotification.java`, `WhatsAppCallAccessibilityService.java` ni el manifiesto.
- La compilación de la APK debe realizarse con GitHub Actions o Android Studio; este paquete contiene el proyecto fuente.
