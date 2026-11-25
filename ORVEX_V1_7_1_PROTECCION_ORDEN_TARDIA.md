# ORVEX V1.7.1 — Protección contra órdenes de llamada tardías

## Base
Derivada del proyecto fuente ORVEX V1.7.0 entregado por el usuario. Se conserva la arquitectura, los servicios y los módulos ajenos a la automatización de llamadas.

## Cambios
1. Cada confirmación afirmativa con accesibilidad de llamadas habilitada genera un identificador UUID único (`wa_auto_call_request_id`).
2. El identificador viaja con el `PendingIntent` y en una URI de datos única. El `PendingIntent` usa un código de solicitud derivado del identificador y `FLAG_CANCEL_CURRENT`, evitando reutilizar la misma identidad de solicitud.
3. `WakeAndLaunchActivity` valida antes de continuar y vuelve a validar justo antes de abrir WhatsApp:
   - la orden debe seguir armada;
   - el identificador debe coincidir con la orden vigente;
   - la hora de lanzamiento no debe haber vencido.
4. La ventana para iniciar WhatsApp es de 15 segundos desde la confirmación. Si Android entrega la actividad después de ese plazo (por ejemplo, al volver a desbloquear el teléfono), la solicitud se rechaza y no se abre WhatsApp.
5. Una vez abierto WhatsApp dentro de ese plazo, la automatización de accesibilidad conserva su ventana operativa previa de hasta 90 segundos para localizar el contacto y el control de llamada.
6. La cancelación, el vencimiento de la automatización y los errores de transición limpian los datos de la orden activa para evitar que se reutilicen.
7. Las solicitudes que solo abren un chat, sin automatización de llamada, conservan el comportamiento anterior y no requieren identificador de llamada.

## Límites importantes
- Esta corrección no elimina las restricciones de Android/HyperOS para iniciar interfaces desde segundo plano.
- No garantiza que WhatsApp se abra automáticamente si el sistema bloquea el lanzamiento. La prioridad es impedir que una orden vieja se ejecute inesperadamente.
- La ventana de 15 segundos es deliberadamente corta. Si el usuario no desbloquea/interactúa a tiempo, deberá repetir la orden de voz.
- Los registros de diagnóstico no guardan el número telefónico ni el identificador UUID.

## Versión
- `versionCode`: 26
- `versionName`: `1.7.1`
- `compileSdk`: 35
- `targetSdk`: 35

## Verificación realizada
- Revisión estática de los tres archivos Java modificados.
- Balance de llaves comprobado.
- Estructura ZIP e integridad se deben verificar después de empaquetar.
- No se declara compilación APK: el entorno de trabajo no dispone del Gradle Wrapper en este ZIP ni se ha ejecutado una compilación Android.
