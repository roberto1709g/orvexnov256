# ORVEX V1.7.6 – Mensajes locales por WhatsApp

## Cambios
- La instrucción `mensaje` cuando aparece entre las primeras tres palabras se procesa dentro de ORVEX y ya no se envía al webhook de n8n.
- Se busca el contacto indicado exclusivamente en la lista de contactos de ORVEX y se valida su teléfono móvil ecuatoriano.
- ORVEX anuncia el contacto validado e inicia una segunda captura de voz para dictar el contenido.
- Se prepara el chat de WhatsApp con destinatario y texto mediante el enlace `wa.me`. El usuario revisa y toca Enviar en WhatsApp. ORVEX no afirma que el mensaje fue enviado.
- Nuevo botón `VER REGISTRO DE MENSAJES ENVIADOS`, con registro local de eventos, copia y borrado.
- El registro no guarda el contenido dictado; registra el número de caracteres, el contacto, etapas y errores.

## Protecciones / alcance
- No se modifica el flujo de la acción ANALISIS ni su webhook de n8n.
- Las llamadas, avisos recibidos, biblioteca de audio, plan IA y demás funciones existentes se dejan sin cambios intencionales.

## Versión
- `versionCode`: 31
- `versionName`: 1.7.6

## Prueba sugerida
1. Subir este proyecto a GitHub y ejecutar el workflow de compilación.
2. Instalar el APK y activar ORVEX.
3. Decir `mensaje a [nombre del contacto]`.
4. Tras el aviso del contacto, dictar el contenido.
5. Verificar que se abre WhatsApp con destinatario/texto; tocar Enviar manualmente.
6. Revisar el botón `VER REGISTRO DE MENSAJES ENVIADOS` y compartir el registro para analizar fallos.
7. Probar separadamente la orden `análisis` y llamadas, para confirmar que se mantienen como en la versión previa.
