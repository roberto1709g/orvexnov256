# ORVEX V1.7.2 — Continuación desde pantalla bloqueada

## Objetivo
Permitir que una llamada confirmada por voz mientras el teléfono está bloqueado pueda continuar mediante una acción explícita del usuario, sin intentar forzar la apertura de WhatsApp desde segundo plano.

## Cambios
- Se conserva el flujo V1.7.1 para teléfono desbloqueado, tanto con ORVEX en primer plano como en segundo plano.
- Si `WakeAndLaunchActivity` aún no puede abrirse porque Android informa que el dispositivo está bloqueado, ORVEX ya no envía el PendingIntent de apertura directa.
- Se muestra una notificación pública y genérica: no revela el nombre ni el número del contacto.
- La notificación incorpora la acción **«Desbloquear y continuar»**. En Android 12 o superior la acción exige autenticación antes de enviar su PendingIntent.
- La acción abre `WakeAndLaunchActivity` con el identificador de la orden y una marca que distingue la interacción explícita del usuario.
- La actividad vuelve a validar que la orden siga armada, que el identificador coincida y que la ventana de la notificación no haya vencido.
- La notificación vence aproximadamente a los 85 segundos. Se mantiene el límite de 90 segundos del servicio de accesibilidad.
- Si las notificaciones están deshabilitadas o no se pueden publicar, ORVEX invalida la orden y lo informa por voz; no intenta una apertura automática alternativa.
- Se solicita el permiso de notificaciones en Android 13 o superior, una sola vez. Si se deniega, debe habilitarse desde los ajustes de Android.
- Se limpia el vencimiento adicional al invalidar o desarmar la orden.

## Archivos modificados
- `app/src/main/java/com/leaden2/app/WhatsAppLockedCallNotification.java` (nuevo)
- `app/src/main/java/com/leaden2/app/VoiceAssistantService.java`
- `app/src/main/java/com/leaden2/app/WakeAndLaunchActivity.java`
- `app/src/main/java/com/leaden2/app/WhatsAppCallAccessibilityService.java`
- `app/src/main/java/com/leaden2/app/MainActivity.java`
- `app/build.gradle`

## Versión
- `versionCode`: 27
- `versionName`: 1.7.2
- `compileSdk`: 35
- `targetSdk`: 35

## Límites conocidos
La notificación y su acción dependen de que Android/HyperOS permita mostrar notificaciones en la pantalla bloqueada. El usuario debe desbloquear el teléfono y pulsar la acción; esta versión no promete ni intenta realizar una apertura silenciosa o automática mientras el dispositivo permanece bloqueado. La llamada sigue dependiendo de que WhatsApp y el servicio de accesibilidad completen su flujo.

## Verificación
El ZIP fuente debe compilarse mediante GitHub Actions o Android Studio. La generación de este paquete no equivale a una compilación APK.
