# Buknuwo Battery Monitor

Aplicación Android nativa (Kotlin + Jetpack Compose + Material 3) para monitorizar una batería LiFePO4 Buknuwo de 12.8 V / 100 Ah mediante BLE y un BMS que responde MODBUS RTU sobre notificaciones GATT.

**La aplicación no simula telemetría.** Si no llega una respuesta válida del BMS, la interfaz muestra `SIN DATOS`.

## Entorno y versiones fijadas

| Componente | Versión |
|---|---|
| JDK | Liberica JDK 21 |
| Gradle | 8.9 (wrapper) |
| Android Gradle Plugin | 8.6.1 |
| Kotlin | 2.0.20 |
| KSP | 2.0.20-1.0.25 |
| Compose BOM | 2024.09.03 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 |
| Room | 2.7.0 |
| Hilt | 2.52 |
| WorkManager | 2.10.0 |
| Navigation Compose | 2.8.5 |

SDK detectado: `E:\Android_SDK` (plataformas 34/35, build-tools 35, cmdline-tools latest). No había dispositivos Android conectados ni AVD creados al inicio.

## Arquitectura

Paquetes separados por responsabilidad:

- `bluetooth`: escaneo, permisos, conexión GATT, descubrimiento, CCCD, cola serializada de operaciones, sonda diagnóstica de lectura RX.
- `protocol`: CRC-16/MODBUS, cuatro comandos de lectura, validación y reensamblado de tramas.
- `telemetry`: decodificación, fusión de snapshot, catálogo dinámico de variables e historial en memoria + Room.
- `monitoring`: servicio en primer plano, ciclo de consultas, watchdog y reconexión.
- `alerts`: motor determinista, persistencia de reglas/estado/eventos, notificaciones, sonido y vibración.
- `database`: Room.
- `settings`: DataStore.
- `diagnostics`: registro BLE, limpieza periódica con WorkManager.
- `ui`: 12 pantallas Compose con Navigation Compose y Hilt.

### Comandos permitidos (único tráfico de escritura al BMS)

```
01 03 00 00 00 0D 84 0F   -> estado principal (0x0000, 13 registros)
01 03 00 39 00 01 54 07   -> temperatura MOSFET (0x0039, 1)
01 03 00 2E 00 0B 64 04   -> sensores de temperatura (0x002E, 11)
01 03 00 53 00 10 B4 17   -> voltajes de celda (0x0053, 16)
```

Además de estos cuatro comandos, la única escritura es el CCCD (`ENABLE_NOTIFICATION_VALUE`). No existe ninguna escritura de configuración, MOS, protecciones ni firmware.

### Flujo de recepción

1. Registro del callback `onCharacteristicChanged` en el `BluetoothGattCallback` antes de activar notificaciones.
2. `setCharacteristicNotification(rx, true)` local.
3. Escritura del CCCD y espera de `onDescriptorWrite`.
4. Escrituras TX con `WRITE_TYPE_NO_RESPONSE`, una sola operación GATT en vuelo (cola con `Mutex`).
5. Reensamblado de fragmentos, validación de CRC, esclavo `0x01`, función `0x03` y byte count conocido.
6. Correlación de la respuesta con la solicitud por dirección de registro y marca de tiempo.
7. Decodificación y publicación de un snapshot; emisión a alertas solo cuando el número de secuencia avanza.

`readCharacteristic()` **no** se usa como fuente de telemetría. La pantalla Diagnóstico incluye una sonda manual para comprobar experimentalmente si el BMS responde a lecturas RX; el resultado se muestra como texto diagnóstico, nunca como dato de batería.

## Motor de alertas

- Catálogo de variables único: el constructor de alertas y el motor consumen la misma lista. Para añadir una variable basta con un elemento nuevo en `TelemetryCatalog`.
- Operadores: comparaciones, BETWEEN/OUTSIDE, RISE_BY/FALL_BY/PERCENT_CHANGE con ventana, STAYS_ABOVE/BELOW, IS_ACTIVE/IS_INACTIVE, CHANGED y NO_DATA_FOR.
- Condiciones compuestas: árbol de grupos AND/OR anidables.
- Máquina de estados: `INACTIVE → PENDING → ACTIVE → RECOVERED`, con duración sostenida, histéresis, deduplicación por secuencia, datos obsoletos, recuperación y reactivación.
- Sonido por prioridad, archivos SAF, volumen maestro, repetición, vibración configurable y canales de notificación por prioridad.
- Alarmas propias en el servicio + notificaciones: no se depende solo de enviar nuevas notificaciones para sonar.
- Pantalla «Prueba de alertas» para verificar presentación sin batería; está etiquetada explícitamente como prueba, nunca como telemetría.

## Estado de verificación

Implementado y cubierto por pruebas unitarias: CRC de los cuatro comandos, reensamblado fragmentado, CRC inválido, decodificación de corriente con signo, temperaturas y celdas, y los casos obligatorios del motor (SOC bajo, voltaje alto, temperatura crítica sostenida, corriente negativa, AND, OR, histéresis, recuperación, reactivación, datos obsoletos, duplicados, NO_DATA_FOR, BETWEEN/OUTSIDE, reglas desactivadas/horario).

**Pendiente de verificación física (no se puede simular):**
- Comunicación real con el dispositivo `CDZG202603260020004255` y validación de offsets/registros contra tramas reales.
- Sonido/vibración con pantalla apagada y en segundo plano, canales silenciados por el usuario y políticas de batería de OEM.
- Reconexión real, Bluetooth desactivado, reinicio del teléfono y consumo.
- Instalación del APK en dispositivo/emulador: el entorno no tenía AVD ni dispositivos conectados; el emulador disponible solo tiene imágenes x86 antiguas (API 25–30) y no soporta BLE real.

No se afirmará que la comunicación BLE funciona hasta comprobarla con la batería física.

## Incertidumbres documentadas

- Los offsets de registros siguen la tabla facilitada, pero no han sido confirmados con tramas reales. Los registros `0x0005`, `0x0008` y `0x000C` no tienen nombre asignado: se conservan como valores raw en diagnóstico.
- Los bits de protección `0x0009`–`0x000B` se muestran como palabras/bits raw. No se les atribuye significado sin documentación verificada.
- Los bits de MOS de carga/descarga tampoco están confirmados: la app los deja como «desconocido» hasta que el usuario configura el mapeo en Ajustes a partir de tramas reales.

## Limitaciones reales de Android

- Los permisos BLE se solicitan en tiempo de ejecución: `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` en Android 12+, `ACCESS_FINE_LOCATION` en Android 11 e inferior, `POST_NOTIFICATIONS` en Android 13+.
- El servicio en primer plano usa `foregroundServiceType="connectedDevice"` (Android 14+). Android 12+ restringe iniciar servicios en primer plano desde segundo plano; el receptor de arranque registra el fallo en Diagnóstico en lugar de fingir éxito.
- No se usa `USE_EXACT_ALARM`: las repeticiones se gestionan dentro del servicio; no se promete ejecución ilimitada en segundo plano.
- El watchdog de ausencia de telemetría complementa (no sustituye) los eventos de desconexión GATT que el sistema entregue.
- El apagado físico del teléfono o la kills del proceso por el sistema no pueden detectarse desde la propia app; tras reinicio se intenta reanudar la monitorización si está activada la opción correspondiente.

## Compilación

```powershell
cd BuknuwoBatteryMonitor
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
.\gradlew.bat :app:testDebugUnitTest
```

El directorio `local.properties` debe contener `sdk.dir=E\:\\Android_SDK` (o la ruta real del SDK).

Si el wrapper no puede descargar la distribución, hay una copia local de Gradle 8.9 en
`.gradle-dist/gradle-8.9/bin/gradle.bat` y se puede invocar directamente con `-p <ruta del proyecto>`.

## Mejoras de interfaz v1.2.0

- Dashboard adaptativo con `BoxWithConstraints`: en pantallas estrechas el medidor SOC se reduce a 112 dp, las tarjetas usan igual ancho y las celdas/temperaturas se muestran en cuadrícula de 2 columnas (4 en tabletas). Etiquetas de una sola línea para evitar textos partidos.
- Editor de alertas reorganizado en pasos numerados con descripciones sencillas: plantillas iniciales, resumen en lenguaje natural («Cuando SOC baja de 20 % → aviso de prioridad ALTA»), secciones «Qué vigilar / Cómo avisar / Horario» y opciones avanzadas plegables.
- Botones con textos claros («TODAS (AND)», «ALGUNA (OR)», «Debe cumplirse durante…») en lugar de términos técnicos aislados.

## Corrección v1.1.1

Se corregió un fallo detectado gracias a un informe real: `connectGatt` y el descubrimiento de servicios funcionaban, pero el `CompletableDeferred` que espera `connect()` **no se completaba al recibir `STATE_CONNECTED`**. La app esperaba 15 s, daba `Timeout durante la conexión/descubrimiento GATT` y cerraba el GATT antes de escribir el CCCD, por lo que nunca se activaban las notificaciones RX. Ahora el callback completa el deferred inmediatamente, los mensajes de timeout indican la etapa exacta (conexión / descubrimiento / suscripción CCCD) y se avisa cuando la señal del BMS es muy débil (RSSI < -85 dBm).

## Generador de informes de soporte (v1.1.0)

La pantalla **Diagnóstico BLE → Informe para soporte** permite al usuario:

1. Seguir una guía rápida: activar Bluetooth, conceder permisos, escanear, seleccionar la batería y esperar 30 s.
2. Escribir qué hizo y qué falló.
3. Pulsar **Generar y compartir informe .log**. La app crea `buknuwo_report_yyyyMMdd_HHmmss.log` en `filesDir/reports/` y abre la hoja de compartir de Android con **WhatsApp como primera opción** (si está instalado; si no, cualquier otra app).

El informe incluye: versión de app y dispositivo, estado de cada permiso, estado del adaptador/GATT, último error, configuración BLE, telemetría real (o `SIN DATOS`), comentario del usuario, últimos eventos de alertas, registro BLE en memoria y las últimas líneas del registro persistente. El registro persistente (`files/logs/buknuwo_ble.log`, máx. 512 KB) también captura excepciones no controladas, por lo que se puede exportar incluso después de un crash. El archivo se comparte mediante `FileProvider` con URI temporal y permiso de lectura; la app no solicita permisos de almacenamiento.

## APK firmado listo para compartir

| Dato | Valor |
|---|---|
| Ruta para enviar | `D:\Proyectos\Bateria\BuknuwoBatteryMonitor-v1.1.0-release-signed.apk` |
| Ruta original del build | `app/build/outputs/apk/release/app-release.apk` |
| Package | `com.buknuwo.battery` |
| Versión | 1.1.0 (versionCode 2) |
| Tamaño | 1 974 894 bytes (~1.9 MB) |
| Firma | v2 (APK Signature Scheme v2), RSA 2048 |
| Certificado | CN=Buknuwo Battery Monitor, OU=Development, O=Buknuwo, C=ES |
| SHA-256 del APK | `5678CEED28D34EA1785730F890AC58152F2F0AE5983754EEB3197E5ED9B26796` |

La v1.0.0 sigue disponible en el release de GitHub; no incluía el generador de informes.

La keystore **no se incluye en el repositorio público** (`.gitignore` excluye `*.keystore`, `*.jks`, `local.properties` y `*.apk`). Para generar tu propia firma:

```powershell
keytool -genkeypair -v -keystore app/release.keystore -alias buknuwo -keyalg RSA -keysize 2048 -validity 10000
```

y añade a `local.properties` (nunca versionado):

```properties
buknuwo.storePassword=tu_password
buknuwo.keyAlias=buknuwo
buknuwo.keyPassword=tu_password
```

El APK compartido se firmó con una keystore autofirmada de desarrollo, apta para instalación manual (sideload); Google Play exige una clave de producción propia. Conserva tu keystore: sin ella no podrás publicar actualizaciones con la misma identidad.

Verificación realizada:

- `apksigner verify --verbose --print-certs`: **Verifies**, 1 firmante, esquema v2 válido.
- `aapt dump badging`: package/versión/permisos correctos.
- `:app:testDebugUnitTest`: **22 tests, 0 fallos** (14 motor de alertas, 4 protocolo, 4 decodificación).
- `:app:assembleDebug` y `:app:assembleRelease`: **BUILD SUCCESSFUL**.

Para instalarlo en un teléfono con ADB:

```powershell
adb install -r "D:\Proyectos\Bateria\BuknuwoBatteryMonitor-release-signed.apk"
```

El teléfono debe permitir «Instalar aplicaciones desconocidas» desde el origen desde el que se envíe el APK. Recuerda que la comunicación BLE real con la batería sigue pendiente de comprobarse con el dispositivo físico; la app mostrará `SIN DATOS` hasta recibir tramas válidas del BMS.
