# UrbanGO - Ejemplo Práctico de OpenStreetMap en Android

Este directorio contiene el código fuente completo de la aplicación de demostración en **Android (Kotlin)** para la presentación sobre **OpenStreetMap (OSM)**.

> 📘 **Documento de Arquitectura Completo:** Consulta [Arquitectura_UrbanGO_Demo.md](Arquitectura_UrbanGO_Demo.md) para ver el desglose técnico de archivos, flujo de datos (diagrama de secuencia), consumo de APIs geoespaciales y renderizado visual.

---

## 🛠️ Tecnologías y Librerías Utilizadas

* **Lenguaje:** Kotlin (Android Nativo)
* **Map Engine:** `osmdroid-android` v6.1.18 (Renderizado ráster OpenSource de OpenStreetMap)
* **Localización:** `MyLocationNewOverlay` + `GpsMyLocationProvider`
* **Routing:** Integración HTTP con BRouter (Red Ciclista OSM) y OSRM (Fallback Vehicular)
* **Networking & Concurrencia:** `OkHttp 4.12.0` + `Kotlinx Coroutines`
* **UI:** Android Material Components + ConstraintLayout + ViewBinding

---

## 🔧 Modificaciones Recientes

Durante las últimas sesiones se realizaron ajustes a la configuración del proyecto para intentar resolver problemas de compilación:

1. **Actualización de Plugins:** Se actualizó el plugin de Kotlin de `1.9.24` a `2.0.0` en `build.gradle.kts`.
2. **Actualización de Gradle:** Se actualizó la versión de Gradle a `8.14.6` en el archivo `gradle-wrapper.properties`.
3. **Ajustes de Memoria y JVM:** Se configuraron las propiedades `org.gradle.jvmargs` y `kotlin.daemon.jvmargs` en `gradle.properties` agregando explícitamente `-Djava.version=21.0.2` para mitigar errores de versión de Java.

---

## 🚧 Tareas Pendientes / Problemas Conocidos

* **Compatibilidad de JDK:** El proyecto falla al compilar lanzando un error `IllegalArgumentException: 25.0.3` (relacionado a un error `Daemon compilation failed: null`). Esto sucede porque el entorno está intentando usar Java 25 por defecto, versión que aún no es completamente compatible con el compilador de Kotlin en Gradle.
* **Solución requerida:** Es necesario configurar Android Studio para que utilice un JDK compatible (como JDK 17 o JDK 21). Para ello:
  1. Ir a **File > Settings** (o **Android Studio > Settings**).
  2. Navegar a **Build, Execution, Deployment > Build Tools > Gradle**.
  3. En la opción **Gradle JDK**, seleccionar una versión como `jbr-17` o instalar / apuntar a un **JDK 21** o **JDK 17**.

---

## 📁 Estructura del Proyecto

```
OSMAndroidDemo/
├── README.md
├── build.gradle.kts (Project)
└── app/
    ├── build.gradle.kts (Module: app)
    └── src/
        └── main/
            ├── AndroidManifest.xml
            ├── res/
            │   └── layout/
            │       └── activity_main.xml
            └── java/
                └── com/
                    └── urbango/
                        └── osmdemo/
                            └── MainActivity.kt
```

---

## 🚀 Cómo ejecutar el proyecto en Android Studio

1. Abre **Android Studio**.
2. Selecciona **File > New > Import Project** (o abre la carpeta `OSMAndroidDemo`).
3. Sincroniza el proyecto con Gradle (**Sync Project with Gradle Files**).
4. Asegúrate de tener conexión a Internet para que `osmdroid` pueda descargar los tiles de OpenStreetMap.
5. Ejecuta la aplicación en un emulador Android o dispositivo físico (Android 7.0+ / API 24+).

---

## 💡 Funcionalidades Demostradas en la App

1. **Carga de Mapa Libre OSM:** Renderizado de teselas estándar de OpenStreetMap sin API Key requerida (vía `TileSourceFactory.MAPNIK`).
2. **Geolocalización en Tiempo Real:** Overlay de posición GPS del usuario con seguimiento y centrado dinámico (`MyLocationNewOverlay`).
3. **Puntos de Interés Ciclista (POIs):** Marcadores interactivos para estaciones de BiciPARQUEADEROS (UNAL, Parque 93) y puntos de encuentro (Plaza de Bolívar) con burbujas de información contextual.
4. **Enrutamiento Ciclista Real con Diferenciación Visual:**
   * **Ciclorruta Segura (Verde `#00E676`):** Consulta a **BRouter** con perfil de seguridad (`profile=safety`), priorizando la red de ciclorrutas y vías pacificadas de Bogotá en OSM.
   * **Tráfico Mixto / Vehicular (Naranja `#FF6D00`):** Cuando no existe tramo exclusivo de ciclorruta o se recurre a vías principales, la línea cambia a naranja alertando al ciclista sobre tráfico mixto.
   * Despliega en el panel de telemetría la distancia exacta en km y el tiempo estimado de pedaleo.
5. **Selección Interactiva de Destino por el Usuario:**
   * **Pulsación prolongada (*Long Press*)** en cualquier punto del mapa para colocar un marcador de destino dinámico y calcular la ruta al instante.
6. **Control Inteligente de Ruta en Cámara (`fabRoute`):**
   * **Clic corto:** Encuadra automáticamente toda la ruta en pantalla (*Fit BoundingBox*) para ver el origen, el trayecto y el destino completos sin hacer zoom manual.
   * **Pulsación prolongada (*Long Click*):** Limpia la ruta activa y remueve el marcador de destino para volver a explorar libremente.
7. **Modo Oscuro Nativo 100% Libre (Sin API Key ni Marcas de Agua):**
   * Aplica un filtro de matriz de color nocturno (`ColorMatrixColorFilter`) directamente sobre las teselas de OSM, eliminando la necesidad de proveedores externos con marcas de agua o claves pagas.
