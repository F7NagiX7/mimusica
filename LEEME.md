# Mi Música (APK para Android)

Reproductor de música local y sin internet. App nativa en Java, sin librerías externas, así que el APK pesa muy poco y consume poca RAM.

## Cómo obtener el APK sin instalar nada en tu laptop

Solo necesitas el navegador. GitHub compila el APK por ti, gratis.

1. Entra a github.com y crea un repositorio nuevo (por ejemplo `mimusica`).
2. Dentro del repo: **Add file → Upload files**.
3. Arrastra **todo el contenido** de esta carpeta (incluida la carpeta oculta `.github`). Pulsa **Commit changes**.
   - Si la carpeta `.github` no se sube: **Add file → Create new file**, escribe `.github/workflows/build.yml` como nombre y pega el contenido de ese archivo.
4. Ve a la pestaña **Actions**. Verás "Compilar APK" ejecutándose (tarda 3 a 5 minutos).
5. Cuando salga el check verde, entra a esa ejecución. Abajo, en **Artifacts**, descarga **MiMusica-apk**.
6. Descomprime el .zip: dentro está `app-debug.apk`. Pásalo a tu celular (cable, WhatsApp, Drive…).
7. En el celular ábrelo e instálalo (permite "instalar apps de origen desconocido" si te lo pide).

## Primer uso

Al abrir, concede el permiso de música (y el de notificaciones en Android 13+). Toca una canción para reproducir. El botón ↻ vuelve a leer la música del teléfono.

## Qué incluye

- Lee todas las canciones del teléfono con su título y artista reales.
- Búsqueda, aleatorio, repetir (todo / una), barra de tiempo.
- Sigue sonando con la pantalla apagada o con la app cerrada.
- Controles en la notificación, en la pantalla de bloqueo y con los botones de los audífonos.
- Se pausa al recibir una llamada o al desconectar los audífonos.
- Solo mantiene una canción cargada en memoria a la vez.

Requiere Android 6.0 o superior.

## Actualizar a una versión nueva

Cada compilación de GitHub firma el APK con una llave distinta, así que Android no deja instalar una versión encima de otra. Antes de instalar el APK nuevo, desinstala la app anterior (mantén pulsado el icono → Desinstalar). Tu música no se borra; solo se pierden las favoritas y el historial de la app.
