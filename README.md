# Grabador Oculto

App Android para grabar video con la pantalla apagada (probada para compilar contra Android 15 / moto g56).

**APK listo:** [`apk/GrabadorOculto.apk`](apk/GrabadorOculto.apk)

## Uso
1. Instala el APK (permite "instalar apps desconocidas" para el navegador o Archivos).
2. Abre la app → elige cámara, calidad y audio → **GRABAR**.
3. Apaga la pantalla con el botón de encendido: la grabación sigue.
4. Detén desde la app o con **Detener** en la notificación.

Videos en `Movies/GrabadorOculto` (visibles en Google Fotos / Galería).

## Cómo funciona
- Servicio en primer plano (`camera|microphone`) con CameraX `VideoCapture`, sin vista previa.
- `PARTIAL_WAKE_LOCK` para que la CPU no se duerma.
- Botón para excluir la app de la optimización de batería (evita que Motorola corte la grabación).

## Compilar
```
./gradlew assembleDebug
```
Requiere Android SDK 35 (`local.properties` → `sdk.dir=...`).
