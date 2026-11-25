# ORVEX V1.7.7 — Envío automático de mensajes WhatsApp

## Cambio
- La orden `mensaje` continúa siendo local y no utiliza n8n.
- ORVEX valida el contacto y captura el texto dictado como en V1.7.6.
- El servicio de accesibilidad recibe la solicitud, intenta abrir WhatsApp y espera la ventana del chat.
- Antes de escribir o enviar, verifica la cabecera del chat usando el nombre y/o número del contacto validado.
- Escribe el texto en el campo de mensaje mediante `ACTION_SET_TEXT` e intenta pulsar el control accesible de envío.
- Si no puede verificar el contacto, escribir el texto o identificar el botón Enviar, no realiza el envío.
- El registro indica que Android aceptó la pulsación, no confirma entrega ni lectura del mensaje.

## Requisito
El servicio de accesibilidad `ORVEX: llamada de WhatsApp` debe estar habilitado en Android. Se reutiliza el servicio existente para no añadir otro servicio de accesibilidad.

## Limitaciones
Android puede impedir que una aplicación abra otra actividad desde segundo plano según la versión, fabricante, permisos y estado del dispositivo. La interfaz de WhatsApp también puede cambiar sus identificadores accesibles. En esos casos ORVEX registra el fallo y no afirma que el mensaje fue enviado. El envío automático requiere que WhatsApp esté instalado, que el chat sea accesible y que el teléfono no esté bloqueado por una pantalla que impida la automatización.

## Protección de otras funciones
No se modifica la clasificación ni el envío a n8n de la acción `ANALYSIS`; tampoco se modifica el flujo de llamadas ni la agenda local.

## Pruebas pendientes
No se dispone de un dispositivo Android conectado para probar la interacción real con WhatsApp. La compilación debe verificarse mediante GitHub Actions antes de instalar el APK. Probar primero con un contacto propio y un mensaje inocuo.
