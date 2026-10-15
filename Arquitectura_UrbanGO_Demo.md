# Documento de Arquitectura de Software: UrbanGO Demo (Android & OpenStreetMap)

Este documento describe detalladamente la **arquitectura técnica**, el **rol de cada archivo**, el **flujo de datos de extremo a extremo**, las **APIs geoespaciales consumidas** y la **representación visual en pantalla** de la aplicación móvil **UrbanGO Demo**.

---

## 1. Visión General de la Solución

**UrbanGO Demo** es una aplicación móvil nativa para Android desarrollada en **Kotlin** para la presentación y validación del proyecto UrbanGO ("el Waze ciclista para Bogotá") en las asignaturas de Geomática y Desarrollo de Aplicaciones Móviles (DADM) en la Universidad Nacional de Colombia.

### Principios de Diseño
* **Soberanía y $0 Costos de Licencia:** Prescinde de plataformas privativas (Google Maps Platform, Mapbox comercial), utilizando el ecosistema abierto de **OpenStreetMap (OSM)**.
* **Cero API Keys Requeridas:** Todas las consultas cartográficas, de enrutamiento y geolocalización operan sin depender de tokens pagos o registros con tarjeta de crédito.
* **Enrutamiento Consciente del Ciclista (Bikeability / LTS):** Diferenciación visual entre infraestructura segregada (ciclorrutas seguras LTS 1) y vías de tráfico mixto vehicular.
* **Resiliencia y Modo Offline:** Almacenamiento local en caché de teselas y mecanismos de fallback ante pérdida de señal satelital o de red.

### 1.1 Diagrama de Arquitectura de la Solución Actual

La aplicación implementa una **arquitectura por capas desacopladas (Layered Spatial Architecture)**, articulando la interfaz reactiva, el controlador del ciclo de vida, el motor cartográfico, los overlays espaciales y los servicios web abiertos:

```mermaid
graph TD
    subgraph UI ["1. Capa de Presentación (UI / Layout)"]
        ActivityLayout["activity_main.xml (ConstraintLayout)"]
        MapViewWidget["MapView (Lienzo Táctil de Navegación)"]
        CardStatus["MaterialCardView (Telemetría de Ruta, Km y Minutos)"]
        subgraph Controls ["Botones Flotantes de Acción (FABs)"]
            BtnLocation["fabLocation: Centrado y Seguimiento GPS"]
            BtnRoute["fabRoute: Encuadre Total (BoundingBox) / Limpiar Ruta"]
            BtnStyle["fabTileStyle: Alternancia Modo Claro / Modo Oscuro"]
        end
    end

    subgraph Controller ["2. Capa de Control y Lógica de Negocio"]
        MainActivityComp["MainActivity.kt (Lifecycle, Permisos Runtime)"]
        Coroutines["Kotlin Coroutines (Dispatchers.Main / Dispatchers.IO)"]
    end

    subgraph Engine ["3. Motor Cartográfico (osmdroid)"]
        MapController["MapController (Zoom, Pan, Animaciones)"]
        TilesOverlay["TilesOverlay (Renderizador Ráster + ColorMatrix Filter)"]
        TileCache["Caché de Dos Niveles (Memoria RAM LRU + SQLite Local)"]
    end

    subgraph Overlays ["4. Capa Geoespacial y Elementos Gráficos (Overlays)"]
        TouchOverlay["MapEventsOverlay (Detección de Long Press para Destino)"]
        LocationOverlay["MyLocationNewOverlay + GpsMyLocationProvider"]
        MarkerOverlay["Marker Overlays (BiciPARQUEADEROS y Destino Seleccionado)"]
        RouteOverlay["Polyline Overlay (Trazado: Verde Ciclorruta vs. Naranja Tráfico Mixto)"]
    end

    subgraph Network ["5. Capa de Red y Servicios Libres Externos"]
        HttpClient["OkHttpClient (User-Agent: com.urbango.osmdemo)"]
        BRouterAPI["BRouter API (Perfil de Seguridad Ciclista en OSM)"]
        OSRMAPI["OSRM API (Fallback Vehicular / Tráfico Mixto)"]
        OSMTiles["Servidores de Teselas de OpenStreetMap Foundation"]
        GPSSensors["Sensores de Hardware (GPS / GLONASS / Antenas de Celular)"]
    end

    %% Relaciones UI a Controlador
    Controls --> MainActivityComp
    ActivityLayout --> MainActivityComp
    MainActivityComp --> Coroutines

    %% Relaciones Controlador a Overlays y Motor
    MainActivityComp --> MapController
    MainActivityComp --> Overlays
    MapController --> MapViewWidget
    TilesOverlay --> MapViewWidget
    Overlays --> MapViewWidget

    %% Relaciones Motor y Caché
    TilesOverlay --> TileCache
    TileCache <--> HttpClient
    HttpClient <--> OSMTiles

    %% Relaciones de Red y Lógica de Enrutamiento
    Coroutines --> HttpClient
    HttpClient <--> BRouterAPI
    HttpClient <--> OSRMAPI
    GPSSensors --> LocationOverlay
    BRouterAPI -.-> RouteOverlay
    OSRMAPI -.-> RouteOverlay
    TouchOverlay --> MainActivityComp
```

---

## 2. Mapa y Rol de Archivos del Proyecto

```
Presentacion/OSMAndroidDemo/
├── build.gradle.kts (Nivel Proyecto)
├── settings.gradle.kts
├── README.md
└── app/
    ├── build.gradle.kts (Módulo Aplicación)
    └── src/
        └── main/
            ├── AndroidManifest.xml
            ├── res/
            │   └── layout/
            │       └── activity_main.xml
            └── java/
                └── com/urbango/osmdemo/
                    └── MainActivity.kt
```

| Archivo | Responsabilidad y Componentes Clave |
| :--- | :--- |
| [MainActivity.kt](./OSMAndroidDemo/app/src/main/java/com/urbango/osmdemo/MainActivity.kt) | **Controlador central de la aplicación:**<br/>• Administra el ciclo de vida de la Activity y del `MapView` (`onResume`, `onPause`, `onDestroy`).<br/>• Gestiona los permisos de localización en tiempo de ejecución (`ACCESS_FINE_LOCATION`).<br/>• Coordina el hilo de interfaz con hilos de fondo mediante **Kotlin Coroutines** (`Dispatchers.IO` / `Dispatchers.Main`).<br/>• Implementa la lógica de red HTTP con **OkHttp** para consultar BRouter y OSRM.<br/>• Configura los `Overlays` de mapa (sensores GPS, marcadores, polilíneas y eventos táctiles). |
| [activity_main.xml](./OSMAndroidDemo/app/src/main/res/layout/activity_main.xml) | **Definición de Interfaz de Usuario (UI):**<br/>• `ConstraintLayout` como contenedor raíz fluido.<br/>• `org.osmdroid.views.MapView`: Ocupa el 100% de la pantalla para el renderizado cartográfico.<br/>• `MaterialCardView` superior: Muestra dinámicamente la telemetría, distancia, tiempo y tipo de ruta.<br/>• Tres `FloatingActionButton` (FABs): Disparadores de acciones espaciales (GPS, encuadre de ruta y modo oscuro). |
| [AndroidManifest.xml](./OSMAndroidDemo/app/src/main/AndroidManifest.xml) | **Manifiesto del Sistema Android:**<br/>• Declara los permisos de red (`INTERNET`, `ACCESS_NETWORK_STATE`).<br/>• Declara los permisos de posicionamiento satelital y por celdas (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`).<br/>• Declara el tema visual Material 3 (`Theme.Material3.DayNight.NoActionBar`). |
| [app/build.gradle.kts](./OSMAndroidDemo/app/build.gradle.kts) | **Configuración de Dependencias y Compilación:**<br/>• `compileSdk = 34`, `minSdk = 24`, Java 17.<br/>• Habilitación de `viewBinding = true`.<br/>• Inclusión de `osmdroid-android:6.1.18` (motor cartográfico).<br/>• Inclusión de `okhttp:4.12.0` (cliente HTTP para APIs de enrutamiento).<br/>• Inclusión de `kotlinx-coroutines-android:1.7.3` (concurrencia reactiva). |

---

## 3. Diagrama de Flujo de Datos

El siguiente diagrama ilustra el flujo de información desde la interacción táctil del usuario y los sensores hasta la renderización en el canvas:

```mermaid
sequenceDiagram
    autonumber
    actor Ciclista as Usuario / Ciclista
    participant UI as MainActivity / UI (FABs, Toasts)
    participant MapView as MapView & Overlays (osmdroid)
    participant Sensors as Sensores GPS (Hardware)
    participant NetWorker as Coroutine Worker (Dispatchers.IO)
    participant BRouter as BRouter API (Red Ciclista OSM)
    participant OSRM as OSRM API (Fallback Vehicular)
    participant TileServer as Servidores Oficiales OSM (Tiles)

    Note over Ciclista, TileServer: 1. Carga Inicial del Mapa
    UI->>MapView: Inicializar con MAPNIK y User-Agent
    MapView->>TileServer: HTTP GET /tile.openstreetmap.org/{z}/{x}/{y}.png
    TileServer-->>MapView: Teselas Ráster 256x256 PNG
    MapView->>MapView: Guardar en Caché Local SQLite y Dibujar

    Note over Ciclista, TileServer: 2. Geolocalización en Tiempo Real
    Sensors->>UI: Tramas NMEA / Lat-Lon / Precisión
    UI->>MapView: Actualizar MyLocationNewOverlay (Punto azul + Radio de error)

    Note over Ciclista, TileServer: 3. Selección de Destino e Interacción
    Ciclista->>MapView: Mantener presionado (Long Press) en Bogotá
    MapView->>UI: Evento longPressHelper(GeoPoint)
    UI->>MapView: Añadir Marker "🎯 Destino Seleccionado"
    
    Note over Ciclista, TileServer: 4. Consulta de Enrutamiento Dinámico
    UI->>NetWorker: Solicitar ruta (Origen GPS -> Destino)
    NetWorker->>BRouter: GET /brouter?lonlats=...&profile=safety&format=geojson
    alt Ruta Ciclista Segura encontrada (BRouter OK)
        BRouter-->>NetWorker: GeoJSON con red de ciclorrutas (distance, time, coordinates)
        NetWorker->>UI: RouteResult(isDedicatedBikePath = true)
        UI->>MapView: Dibujar Polyline VERDE (#00E676)
        UI->>UI: Actualizar tvStatus: "🚲 Ciclorruta Segura: X km (~Y min)"
    else Sin ciclorruta dedicada / Falla BRouter (Fallback)
        NetWorker->>OSRM: GET /route/v1/driving/... (Red Vial General)
        OSRM-->>NetWorker: GeoJSON vehicular
        NetWorker->>UI: RouteResult(isDedicatedBikePath = false)
        UI->>MapView: Dibujar Polyline NARANJA (#FF6D00)
        UI->>UI: Actualizar tvStatus: "⚠️ Tráfico Mixto: X km (~Y min)"
    end

    Note over Ciclista, TileServer: 5. Control de Cámara y Modo Oscuro
    Ciclista->>UI: Clic en fabRoute
    UI->>MapView: zoomToBoundingBox(ruta, animate=true) [Encuadre Total]
    Ciclista->>UI: Clic en fabTileStyle
    UI->>MapView: tilesOverlay.setColorFilter(DARK_MODE_FILTER) [Modo Oscuro]
```

---

## 4. APIs y Servicios Remotos Consumidos

### A. Servidor Oficial de Teselas de OpenStreetMap Foundation (OSMF)
* **Objetivo:** Proporcionar las imágenes ráster del mapa base de Bogotá en tiempo real.
* **Protocolo:** HTTP/HTTPS GET basado en la especificación **Slippy Map ($z/x/y$)**.
* **URL:**  
  `https://tile.openstreetmap.org/{z}/{x}/{y}.png`
* **Proyecciones Cartográficas:**
  * Entrada: Coordenadas geográficas **WGS84 (EPSG:4326)**.
  * Renderizado: Proyección conforme cilíndrica **Web Mercator (EPSG:3857)**.
* **Políticas de Uso (Tile Usage Policy):**
  Es obligatorio identificar la aplicación mediante una cabecera `User-Agent`. En `MainActivity.kt` se inyecta programáticamente:
  ```kotlin
  Configuration.getInstance().userAgentValue = packageName
  ```

---

### B. BRouter API (Enrutador Especializado en Bicicletas de OSM)
* **Objetivo:** Calcular la ruta ciclista óptima respetando la red de ciclorrutas protegidas, andenes autorizados y vías de bajo tráfico vehicular de Bogotá.
* **Endpoint:**  
  `https://brouter.de/brouter?lonlats={lon1},{lat1}%7C{lon2},{lat2}&profile=safety&alternativeidx=0&format=geojson`
* **Parámetros Clave:**
  * `lonlats`: Coordenadas de origen y destino en formato `longitud,latitud` separadas por `%7C` (tubería codificada).
  * `profile=safety`: Perfil de costo que penaliza vías primarias sin ciclorruta e incentiva tramos con etiquetas `highway=cycleway`, `cycleway=track` y `bicycle=yes`.
  * `format=geojson`: Retorna la respuesta en formato estándar RFC 7946 GeoJSON.
* **Estructura del Payload Recibido:**
  ```json
  {
    "type": "FeatureCollection",
    "features": [
      {
        "type": "Feature",
        "properties": {
          "track-length": "6751", // Distancia en metros
          "total-time": "989"     // Tiempo estimado en segundos
        },
        "geometry": {
          "type": "LineString",
          "coordinates": [
            [-74.074819, 4.599131, 2614.0],
            [-74.074808, 4.599149, 2614.0],
            ...
          ]
        }
      }
    ]
  }
  ```

---

### C. OSRM API (Open Source Routing Machine - Fallback Vehicular)
* **Objetivo:** Servidor de contingencia (*fallback*) cuando un punto no cuenta con conexión de ciclorruta directa o cuando el servicio ciclista presenta indisponibilidad.
* **Endpoint:**  
  `https://router.project-osrm.org/route/v1/driving/{lon1},{lat1};{lon2},{lat2}?overview=full&geometries=geojson`
* **Comportamiento en la App:** Si la ruta se resuelve mediante este motor, la aplicación detecta que el ciclista deberá compartir vía con vehículos motorizados y activa la alerta visual en color **Naranja (`#FF6D00`)**.

---

## 5. Representación y Renderizado Espacial en Pantalla

El sistema de capas en pantalla se gestiona mediante la lista de `Overlays` de `MapView`, evaluados en orden de prioridad de dibujo e interacción:

```mermaid
graph BT
    L0["Capa 0: MapEventsOverlay<br/>(Captura Long Press táctil en pantalla)"]
    L1["Capa 1: TilesOverlay (MAPNIK)<br/>(Teselas Ráster de Fondo + Filtro Oscuro)"]
    L2["Capa 2: Polyline Overlay<br/>(Trazado Curva a Curva de la Ruta)"]
    L3["Capa 3: Marker Overlays<br/>(POIs Ciclistas + Destino Seleccionado)"]
    L4["Capa 4: MyLocationNewOverlay<br/>(Sensor GPS, Brújula y Círculo de Precisión)"]

    L0 --> L1
    L1 --> L2
    L2 --> L3
    L3 --> L4
```

### 1. Codificación Semántica por Colores (Bikeability / LTS)
* **Verde Neón Brillante (`#00E676`):** Representa infraestructura segregada segura (**LTS 1 / LTS 2** - Ciclorrutas exclusivas, bicicarriles protegidos, ciclovía dominical).
* **Naranja Alerta Intenso (`#FF6D00`):** Representa calzada vehicular mixta (**LTS 3 / LTS 4** - Vías donde el ciclista comparte espacio con buses o automóviles).

### 2. Modo Oscuro Nativo por Filtro Matricial
En lugar de depender de proveedores externos de teselas oscuras con marcas de agua o API keys de pago, la app aplica una transformación matricial en el espacio de color sobre el canal de teselas:
```kotlin
val darkColorMatrix = ColorMatrix(floatArrayOf(
    -0.85f,  0.0f,   0.0f,   0.0f, 240.0f, // R
     0.0f,  -0.85f,  0.0f,   0.0f, 240.0f, // G
     0.0f,   0.0f,  -0.80f,  0.0f, 245.0f, // B
     0.0f,   0.0f,   0.0f,   1.0f,   0.0f  // Alpha
))
mapView.overlayManager.tilesOverlay.setColorFilter(ColorMatrixColorFilter(darkColorMatrix))
```
Esta transformación se procesa en el pipeline gráfico local del dispositivo a 0 ms de latencia y sin consumir ancho de banda de red.

### 3. Encuadre Inteligente de Cámara (`BoundingBox`)
Al presionar el botón `fabRoute`, el sistema toma la lista de vértices de la ruta activa y calcula el polígono envolvente mínimo:
```kotlin
val boundingBox = BoundingBox.fromGeoPoints(currentRoutePoints)
mapView.zoomToBoundingBox(boundingBox, true, 140)
```
La cámara se ajusta con una animación suave (*easing*) dejando 140 píxeles de margen (*padding*) para que el origen, el destino y los giros queden visibles simultáneamente en el viewport.

---

## 6. Manejo de Errores y Robustez

1. **Pérdida de Señal GPS:** Si el sensor satelital no ha triangulado la posición o la app se ejecuta en un emulador sin mock location, la app toma automáticamente las coordenadas de la **Plaza de Bolívar ($4.5981^\circ, -74.0760^\circ$)** como punto de origen de respaldo informando al usuario vía `Toast`.
2. **Ciclo de Vida y Prevención de Fugas de Memoria:**
   En `onDestroy()`, se invoca `coroutineScope.cancel()` para cancelar inmediatamente cualquier petición HTTP en curso si el usuario cierra la aplicación, previniendo fugas de memoria (*memory leaks*).
3. **Caché en Modo Avión:**
   Las teselas consultadas se almacenan automáticamente en la base de datos interna SQLite de Android. Si se corta el internet, la zona de Bogotá previamente visualizada sigue navegable en pantalla.
