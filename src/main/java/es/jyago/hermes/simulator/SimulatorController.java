package es.jyago.hermes.simulator;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import es.jyago.hermes.bean.LocaleBean;
import es.jyago.hermes.location.detail.LocationLogDetail;
import es.jyago.hermes.google.directions.GeocodedWaypoints;
import es.jyago.hermes.google.directions.Leg;
import es.jyago.hermes.google.directions.Location;
import es.jyago.hermes.google.directions.PolylineDecoder;
import es.jyago.hermes.google.directions.Route;
import es.jyago.hermes.location.LocationLog;
import es.jyago.hermes.location.LocationLogFacade;
import es.jyago.hermes.openStreetMap.GeomWay;
import es.jyago.hermes.openStreetMap.GeomWaySteps;
import es.jyago.hermes.person.Person;
import es.jyago.hermes.person.PersonFacade;
import es.jyago.hermes.smartDriver.DataSection;
import es.jyago.hermes.smartDriver.DataSectionHermesZtreamyFacade;
import es.jyago.hermes.smartDriver.LocationHermesZtreamyFacade;
import es.jyago.hermes.smartDriver.RoadSection;
import es.jyago.hermes.util.Constants;
import es.jyago.hermes.util.HermesException;
import es.jyago.hermes.util.Util;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Type;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.ResourceBundle;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.PostConstruct;
import javax.enterprise.context.SessionScoped;
import javax.inject.Inject;
import javax.inject.Named;
import org.apache.commons.io.IOUtils;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.joda.time.LocalTime;
import org.primefaces.context.RequestContext;
import org.primefaces.event.map.OverlaySelectEvent;
import org.primefaces.model.map.DefaultMapModel;
import org.primefaces.model.map.LatLng;
import org.primefaces.model.map.MapModel;
import org.primefaces.model.map.Marker;
import org.primefaces.model.map.Polyline;

@Named("simulatorController")
@SessionScoped
public class SimulatorController implements Serializable {

    private static final Logger LOG = Logger.getLogger(SimulatorController.class.getName());

    private static final Location SEVILLE = new Location(37.3898358, -5.986069);
    private static final String MARKER_ICON_PATH = "http://maps.google.com/mapfiles/kml/pal4/icon15.png";
    private static final String MARKER_START_ICON_PATH = "http://maps.google.com/mapfiles/kml/pal3/icon56.png";
    private static final String MARKER_FINISH_ICON_PATH = "http://maps.google.com/mapfiles/kml/pal5/icon13.png";
    // FIXME: ¿Dividir simulaciones entre los hilos o que cada hilo cree la misma cantidad de trayectos?
    private volatile int ztreamyErrors;
    private volatile int zTreamySends;
    private volatile int runningThreads;
    private volatile String simulationFinishedMessage;
    private boolean realTime;
    private int timeRate;
    private int simulatedSpeed;
    // Parámetros recogidos de SmartDriver.
    private static final String APPLICATION_ID = "SmartDriver";
    private static final int ZTREAMY_SEND_INTERVAL_MILLISECONDS = 10000;
    private static final int ZTREAMY_SEND_INTERVAL_METERS = 500;
    private static final double HIGH_ACCELERATION_THRESHOLD = 2.5d;
    private static final double HIGH_DECELERATION_THRESHOLD = -3.5d;

    public static enum Track_Simulation_Method {
        GOOGLE, OPENSTREETMAP
    };

    private Marker marker;

    private int distance;
    private int distanceFromSevilleCenter;
    private int tracksAmount;
    private boolean createSimulatedUser;
    private List<TrackInfo> trackInfoList;
    private MapModel simulatedMapModel;
    private Track_Simulation_Method simulationMethod;
    private ArrayList<LocationLog> locationLogList;

    private Map<String, Timer> simulationTimers;
    private int simulatedSmartDrivers;
    private String url;

    @Inject
    private PersonFacade personFacade;

    @Inject
    private LocationLogFacade locationLogFacade;

    public SimulatorController() {
    }

    @PostConstruct
    public void init() {
        distanceFromSevilleCenter = 1;
        distance = 10;
        tracksAmount = 1;
        simulatedSmartDrivers = 1;
        createSimulatedUser = false;
        simulationMethod = Track_Simulation_Method.GOOGLE;
        marker = new Marker(new LatLng(SEVILLE.getLat(), SEVILLE.getLng()));
        marker.setDraggable(false);
        ztreamyErrors = 0;
        zTreamySends = 0;
        runningThreads = 0;
        realTime = true;
        timeRate = 1000; // En milisegundos.
        simulatedSpeed = 0;

        // En este caso, no cogemos la configuración de Ztreamy, sino que enviamos los datos a una URL con un Ztreamy para pruebas.
//        url = Constants.getInstance().getConfigurationValueByKey("ZtreamyUrl");
        url = "http://hermes1.gast.it.uc3m.es:9220/collector/publish";
        generateSimulatedTracks();
    }

    public String getMarkerLatitudeLongitude() {
        if (marker != null) {
            return marker.getLatlng().getLat() + "," + marker.getLatlng().getLng();
        }

        return "";
    }

    public Marker getMarker() {
        return marker;
    }

    public void generateSimulatedTracks() {
        simulatedMapModel = new DefaultMapModel();
        // Eliminamos los 'Marker' existentes.
        simulatedMapModel.getMarkers().clear();
        trackInfoList = new ArrayList();
        locationLogList = new ArrayList<>();

        for (int i = 0; i < tracksAmount; i++) {
            LocationLog ll = new LocationLog();

            Location origin = getRandomLocation(SEVILLE.getLat(), SEVILLE.getLng(), distanceFromSevilleCenter);
            Location destination = getRandomLocation(origin.getLat(), origin.getLng(), distance);
            TrackInfo trackInfo = null;
            Date currentTime = new Date();

            // Creamos un objeto de localizaciones de 'SmartDriver'.
            ll.setDateLog(currentTime);

            if (simulationMethod.equals(Track_Simulation_Method.GOOGLE)) {
                Future future = GoogleWebService.submitTask(new Callable() {
                    @Override
                    public String call() {
                        String json = null;
                        try {
                            json = IOUtils.toString(new URL("https://maps.googleapis.com/maps/api/directions/json?origin=" + origin.getLat() + "," + origin.getLng() + "&destination=" + destination.getLat() + "," + destination.getLng()), "UTF-8");
                        } catch (IOException ex) {
                            LOG.log(Level.SEVERE, "generateSimulatedTracks() - Google Maps - Error I/O: {0}", ex.getMessage());
                        }

                        return json;
                    }
                });

                try {
                    String json = (String) future.get();
                    Gson gson = new GsonBuilder()
                            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                            .create();
                    GeocodedWaypoints gcwp = gson.fromJson(json, GeocodedWaypoints.class);
                    trackInfo = createTrackGoogleMaps(gcwp, ll);
                } catch (InterruptedException | ExecutionException | JsonSyntaxException ex) {
                    LOG.log(Level.SEVERE, "Error al decodificar el JSON de la ruta", ex);
                }
            } else if (simulationMethod.equals(Track_Simulation_Method.OPENSTREETMAP)) {
                Future future = GoogleWebService.submitTask(new Callable() {
                    @Override
                    public String call() {
                        String json = null;
                        Location o = origin;
                        Location d = destination;
                        while (json == null) {
                            try {
                                json = IOUtils.toString(new URL("http://cronos.lbd.org.es/hermes/api/smartdriver/network/route?fromLat=" + o.getLat() + "&fromLng=" + o.getLng() + "&toLat=" + d.getLat() + "&toLng=" + d.getLng()), "UTF-8");
                            } catch (IOException ex) {
                                LOG.log(Level.SEVERE, "generateSimulatedTracks() - OpenStreet Maps - Error I/O: {0}", ex.getMessage());
                                // Generamos nuevos puntos aleatorios hasta que sean aceptados.
                                o = getRandomLocation(SEVILLE.getLat(), SEVILLE.getLng(), distanceFromSevilleCenter);
                                d = getRandomLocation(origin.getLat(), origin.getLng(), distance);
                            }
                        }

                        return json;
                    }
                });

                try {
                    String json = (String) future.get();
                    Type listType = new TypeToken<ArrayList<GeomWaySteps>>() {
                    }.getType();
                    List<GeomWaySteps> gws = new Gson().fromJson(json, listType);
                    trackInfo = createTrackOpenStreetMaps(gws, ll);
                } catch (InterruptedException | ExecutionException | JsonSyntaxException ex) {
                    LOG.log(Level.SEVERE, "Error al decodificar el JSON de la ruta", ex);
                }
            }

            // Si alguna petición fallase, saltamos esta iteración del bucle.
            if (trackInfo == null || trackInfo.getTotalLocations() == 0) {
                continue;
            }

            // Creamos un usuario simulado, al que le asignaremos el trayecto.
            Person person = createSimPerson(currentTime.getTime());
            ll.setPerson(person);
            ll.setFilename(person.getFullName());

            if (createSimulatedUser) {
                // Pero sólo lo grabaremos en la B.D. si lo indica el usuario.
                personFacade.create(person);
                locationLogFacade.create(ll);
            }

            locationLogList.add(ll);
            trackInfoList.add(trackInfo);
        }
    }

    private Person createSimPerson(long currentTime) {
        Person person = new Person();
        String name = "Sim_" + currentTime;
        person.setFullName(name);
        person.setUsername(name);
        person.setPassword("hermes");
        person.setEmail(name + "@sim.com");

        return person;
    }

    public MapModel getSimulatedMapModel() {
        return simulatedMapModel;
    }

    private TrackInfo createTrackOpenStreetMaps(List<GeomWaySteps> geomWayStepsList, LocationLog ll) {
        TrackInfo trackInfo = new TrackInfo();

        if (geomWayStepsList != null && !geomWayStepsList.isEmpty()) {
            Polyline polyline = new Polyline();
            polyline.setStrokeWeight(4);
            polyline.setStrokeOpacity(0.7);

            Random rand = new Random();

            // Hacemos que las rutas sean variaciones de azul.
            polyline.setStrokeColor("#2222" + String.format("%02x", rand.nextInt(0x100)));

            // Resumen que mostraremos por pantalla.
            SectionInfo summary = trackInfo.getSummary();

            // Listado de posiciones que componen el trayecto de SmartDriver.
            ArrayList<LocationLogDetail> locationLogDetailList = new ArrayList<>();

            double trackDistance = 0.0d;
            double trackDuration = 0.0d;
            double maximumLocationDistance = 0.0d;
            LatLng previous = null;
            LocalTime localTime = new LocalTime();

            // Analizamos la información obtenida de la consulta a OpenStreetMap.
            for (GeomWaySteps gws : geomWayStepsList) {
                trackDistance += (gws.getLength() * 1000); // Almacenamos la distancia en metros.

                // Es como un fragmento de la ruta.
                GeomWay gw = gws.getGeomWay();
                if (gw.getCoordinates() != null) {
                    // Iteramos sobre los puntos que componen ese fragmento.
                    // FIXME: Parece que los puntos que forman el fragmento no vienen ordenados.
//                    for (int i = 0; i < gw.getCoordinates().size(); i++) {
                    int i = 0;
                    // Viene en formato: longitud, latitud.
                    List<Double> coordinates = gw.getCoordinates().get(i);

                    // Añadimos un nuevo punto en la polilínea que se dibujará por pantalla.
                    LatLng latlng = new LatLng(coordinates.get(1), coordinates.get(0));
                    polyline.getPaths().add(latlng);

                    // Creamos un nodo del trayecto, como si usásemos SmartDriver.
                    LocationLogDetail lld = new LocationLogDetail();
                    lld.setLocationLog(ll);
                    lld.setLatitude(latlng.getLat());
                    lld.setLongitude(latlng.getLng());
                    // FIXME: Inicialmente pondremos que vaya a la velocidad máxima de cada nodo.
                    lld.setSpeed(gws.getMaxSpeed());

                    if (previous != null) {
                        // Calculamos la distancia en metros entre los puntos previo y actual, así como el tiempo necesario para recorrer dicha distancia.
                        Double pointDistance = Util.distanceHaversine(previous.getLat(), previous.getLng(), latlng.getLat(), latlng.getLng());
                        if (pointDistance > maximumLocationDistance) {
                            maximumLocationDistance = pointDistance;
                        }
                        // Convertimos los Km/h en m/s.
                        double currentSpeedMS = lld.getSpeed() / 3.6d;
                        // Añadimos los segundos correspondientes a la distancia recorrida entre puntos.
                        double seconds = (pointDistance / currentSpeedMS);
                        trackDuration += seconds;
                        localTime = localTime.plusSeconds((int) Math.ceil(seconds));
                    }

                    lld.setTimeLog(localTime.toDateTimeToday().toDate());

                    locationLogDetailList.add(lld);

                    // Asignamos el actual al anterior, para poder seguir calculando las distancias y tiempos respecto al punto previo.
                    previous = latlng;
//                }

                    simulatedMapModel.addOverlay(polyline);
                }
            }
            summary.setDistance((int) trackDistance);
            summary.setDuration((int) trackDuration);
//                    summary.setStartLocation(l.getStartLocation());
            summary.setStartAddress(geomWayStepsList.get(0).getLinkName());
//                    summary.setEndLocation(l.getEndLocation());
            summary.setEndAddress(geomWayStepsList.get(geomWayStepsList.size() - 1).getLinkName());

            trackInfo.setTotalLocations(locationLogDetailList.size());
            trackInfo.setAverageLocationsDistance(summary.getDistance() / locationLogDetailList.size());
            trackInfo.setMaximumLocationsDistance(maximumLocationDistance);

            // Asignamos un 'marker' con la posición inicial y final de cada trayecto.
            LocationLogDetail startPosition = locationLogDetailList.get(0);
            LocationLogDetail endPosition = locationLogDetailList.get(locationLogDetailList.size() - 1);
            LatLng startLatLng = new LatLng(startPosition.getLatitude(), startPosition.getLongitude());
            LatLng endLatLng = new LatLng(endPosition.getLatitude(), endPosition.getLongitude());
            Marker startMarker = new Marker(startLatLng);
            startMarker.setVisible(true);
            startMarker.setDraggable(false);
            startMarker.setTitle(getMarkerTitle(startPosition));
            startMarker.setIcon(MARKER_START_ICON_PATH);
            simulatedMapModel.addOverlay(startMarker);
            Marker endMarker = new Marker(endLatLng);
            endMarker.setVisible(true);
            endMarker.setDraggable(false);
            endMarker.setTitle(getMarkerTitle(endPosition));
            endMarker.setIcon(MARKER_FINISH_ICON_PATH);
            simulatedMapModel.addOverlay(endMarker);

            // Asignamos el listado de posiciones.
            ll.setLocationLogDetailList(locationLogDetailList);
        }

        return trackInfo;
    }

    private TrackInfo createTrackGoogleMaps(GeocodedWaypoints gcwp, LocationLog ll) {
        TrackInfo trackInfo = new TrackInfo();

        if (gcwp.getRoutes() != null) {
            Polyline polyline = new Polyline();
            polyline.setStrokeWeight(4);
            polyline.setStrokeOpacity(0.7);

            Random rand = new Random();
            // Hacemos que las rutas sean variaciones de verde.
            polyline.setStrokeColor("#22" + String.format("%02x", rand.nextInt(0x100)) + "22");

            // Listado de posiciones que componen el trayecto de SmartDriver.
            ArrayList<LocationLogDetail> locationLogDetailList = new ArrayList<>();

            // Analizamos la información obtenida de la consulta a Google Directions.
            // Nuestra petición sólo devolverá una ruta.
            if (gcwp.getRoutes() != null && !gcwp.getRoutes().isEmpty()) {
                Route r = gcwp.getRoutes().get(0);
                // Comprobamos que traiga información de la ruta.
                if (r.getLegs() != null) {
                    Leg l = r.getLegs().get(0);

                    // Resumen que mostraremos por pantalla.
                    SectionInfo summary = trackInfo.getSummary();
                    summary.setDistance(l.getDistance().getValue());
                    summary.setDuration((int) (l.getDuration().getValue()));
                    summary.setStartLocation(new LocationInfo(l.getStartLocation().getLat(), l.getStartLocation().getLng()));
                    summary.setStartAddress(l.getStartAddress());
                    summary.setEndLocation(new LocationInfo(l.getEndLocation().getLat(), l.getEndLocation().getLng()));
                    summary.setEndAddress(l.getEndAddress());

                    // TODO: ¿Velocidades?
                    double speed;
                    LocalTime localTime = new LocalTime();
                    ArrayList<Location> locationList = PolylineDecoder.decodePoly(r.getOverviewPolyline().getPoints());
                    Location previous = locationList.get(0);
                    double maximumLocationDistance = 0.0d;

                    // FIXME: ¿Interpolación de velocidades? Otra opción es consultar a Google Distance Matrix para consultar el tiempo que se tarda entre 2 puntos (le afecta el tráfico) y sacar la velocidad.
//                PolynomialFunction p = new PolynomialFunction(new double[]{speed, averagePolylineSpeed,});
                    for (int i = 0; i < locationList.size(); i++) {
                        Location location = locationList.get(i);

                        // Añadimos un nuevo punto en la polilínea que se dibujará por pantalla.
                        LatLng latlng = new LatLng(location.getLat(), location.getLng());
                        polyline.getPaths().add(latlng);

                        // Creamos un nodo del trayecto, como si usásemos SmartDriver.
                        LocationLogDetail lld = new LocationLogDetail();
                        lld.setLocationLog(ll);
                        lld.setLatitude(location.getLat());
                        lld.setLongitude(location.getLng());

                        // Calculamos la distancia en metros entre los puntos previo y actual, así como el tiempo necesario para recorrer dicha distancia.
                        Double pointDistance = Util.distanceHaversine(previous.getLat(), previous.getLng(), location.getLat(), location.getLng());
                        if (pointDistance > maximumLocationDistance) {
                            maximumLocationDistance = pointDistance;
                        }
                        // Calculamos el tiempo en segundos que tarda en recorrer la distancia entre los puntos.
                        Double pointDuration = summary.getDuration() * pointDistance / summary.getDistance();

                        // Convertimos la velocidad a Km/h.
                        speed = pointDuration > 0 ? pointDistance * 3.6 / pointDuration : 0.0d;
                        lld.setSpeed(speed);
                        // Añadimos los segundos correspondientes a la distancia recorrida entre puntos.
                        localTime = localTime.plusSeconds(pointDuration.intValue());
                        lld.setTimeLog(localTime.toDateTimeToday().toDate());

                        locationLogDetailList.add(lld);

                        // Asignamos el actual al anterior, para poder seguir calculando las distancias y tiempos respecto al punto previo.
                        previous = location;
                    }

                    simulatedMapModel.addOverlay(polyline);

                    // Asignamos un 'marker' con la posición inicial y final de cada trayecto.
                    LocationLogDetail startPosition = locationLogDetailList.get(0);
                    LocationLogDetail endPosition = locationLogDetailList.get(locationLogDetailList.size() - 1);
                    LatLng startLatLng = new LatLng(startPosition.getLatitude(), startPosition.getLongitude());
                    LatLng endLatLng = new LatLng(endPosition.getLatitude(), endPosition.getLongitude());
                    Marker startMarker = new Marker(startLatLng);
                    startMarker.setVisible(true);
                    startMarker.setDraggable(false);
                    startMarker.setTitle(getMarkerTitle(startPosition));
                    startMarker.setIcon(MARKER_START_ICON_PATH);
                    simulatedMapModel.addOverlay(startMarker);
                    Marker endMarker = new Marker(endLatLng);
                    endMarker.setVisible(true);
                    endMarker.setDraggable(false);
                    endMarker.setTitle(getMarkerTitle(endPosition));
                    endMarker.setIcon(MARKER_FINISH_ICON_PATH);
                    simulatedMapModel.addOverlay(endMarker);

                    trackInfo.setTotalLocations(locationLogDetailList.size());
                    trackInfo.setAverageLocationsDistance(summary.getDistance() / locationLogDetailList.size());
                    trackInfo.setMaximumLocationsDistance(maximumLocationDistance);

                    // Asignamos el listado de posiciones.
                    ll.setLocationLogDetailList(locationLogDetailList);
                }
            }
        }

        return trackInfo;
    }

    private Location getRandomLocation(double latitude, double longitude, int radius) {
        Random random = new Random();

        // TODO: Comprobar que es una localización que no sea 'unnamed'
        // El radio se considerará en kilómetros. Lo convertimos a grados.
        double radiusInDegrees = radius / 111f;

        double u = random.nextDouble();
        double v = random.nextDouble();
        double w = radiusInDegrees * Math.sqrt(u);
        double t = 2 * Math.PI * v;
        double x = w * Math.cos(t);
        double y = w * Math.sin(t);

        double new_x = x / Math.cos(latitude);

        double foundLongitude = new_x + longitude;
        double foundLatitude = y + latitude;

        LOG.log(Level.FINE, "getRandomLocation() - Longitud: {0}, Latitud: {1}", new Object[]{foundLongitude, foundLatitude});

        Location result = new Location();
        result.setLat(foundLatitude);
        result.setLng(foundLongitude);

        return result;
    }

    public void onMarkerSelect(OverlaySelectEvent event) {
        try {
            marker = (Marker) event.getOverlay();
            if (marker != null) {
                // FIXME: Ver si se puede añadir salto de línea. No funciona '\n' ni '<br/>'
                String sb = marker.getTitle();
                marker.setTitle(sb);
            }
        } catch (ClassCastException ex) {
        }
    }

    public int getDistance() {
        return distance;
    }

    public void setDistance(int distance) {
        this.distance = distance;
    }

    public int getDistanceFromSevilleCenter() {
        return distanceFromSevilleCenter;
    }

    public void setDistanceFromSevilleCenter(int distanceFromSevilleCenter) {
        this.distanceFromSevilleCenter = distanceFromSevilleCenter;
    }

    public int getTracksAmount() {
        return tracksAmount;
    }

    public void setTracksAmount(int tracksAmount) {
        this.tracksAmount = tracksAmount;
    }

    public List<TrackInfo> getTrackInfoList() {
        return trackInfoList;
    }

    public boolean isCreateSimulatedUser() {
        return createSimulatedUser;
    }

    public void setCreateSimulatedUser(boolean createSimulatedUser) {
        this.createSimulatedUser = createSimulatedUser;
    }

    public int getSimulationMethod() {
        return simulationMethod.ordinal();
    }

    public void setSimulationMethod(int value) {
        switch (value) {
            case 0:
                simulationMethod = Track_Simulation_Method.GOOGLE;
                break;
            case 1:
                simulationMethod = Track_Simulation_Method.OPENSTREETMAP;
                break;
            default:
                simulationMethod = Track_Simulation_Method.GOOGLE;
        }
    }

    public int getSimulatedSmartDrivers() {
        return simulatedSmartDrivers;
    }

    public void setSimulatedSmartDrivers(int simulatedSmartDrivers) {
        this.simulatedSmartDrivers = simulatedSmartDrivers;
    }

    public boolean isSimulating() {
        return simulationTimers != null && !simulationTimers.isEmpty();
    }

    public void getCurrentLatLng() {
        try {
            if (isSimulating()) {
                for (Marker m : simulatedMapModel.getMarkers()) {
                    LOG.log(Level.FINE, "getCurrentLatLng() - Id del marker: {0}", m.getId());
                    LatLng latLng = m.getLatlng();
                    RequestContext context = RequestContext.getCurrentInstance();
                    // Posición.
                    if (latLng != null) {
                        context.addCallbackParam("latLng_" + m.getId(), latLng.getLat() + "," + latLng.getLng());
                    }
                    // Icono
                    String icon = m.getIcon();
                    if (icon != null) {
                        context.addCallbackParam("icon_" + m.getId(), icon);
                    }
                    // Información
                    String title = m.getTitle();
                    if (title != null) {
                        context.addCallbackParam("title_" + m.getId(), title);
                    }
                }
            } else {
                resetCarMarkers();
            }
        } catch (Exception ex) {
        }
    }

    public void realTimeSimulate() {
        // Si el temporizador está instanciado, es que hay una simulación en marcha y se quiere parar.
        if (simulationTimers != null && !simulationTimers.isEmpty()) {
            simulationFinishedMessage = MessageFormat.format(LocaleBean.getBundle().getString("ZtreamyTimeouts"), ztreamyErrors);
            if (ztreamyErrors > 0) {
                LOG.log(Level.SEVERE, "realTimeSimulate() - RESULTADO: Errores: {0} / Total: {1}. Hilos restantes: {2}", new Object[]{ztreamyErrors, zTreamySends, runningThreads});
            } else {
                LOG.log(Level.INFO, "realTimeSimulate() - RESULTADO: Los envíos a Ztreamy se han realizado correctamente. Hilos restantes: {0}", runningThreads);
            }

            for (Timer timer : simulationTimers.values()) {
                if (timer != null) {
                    timer.cancel();
                    timer = null;
                }
            }

            resetSimulation();
            LOG.log(Level.INFO, "realTimeSimulate() - Fin de la simulación: {0}", Constants.dfISO8601.format(System.currentTimeMillis()));
            LOG.log(Level.INFO, "realTimeSimulate() - Se han enviado {0} tramas, de las que {1} han fallado", new Object[]{zTreamySends, ztreamyErrors});
        } else {
            resetSimulation();
            LOG.log(Level.INFO, "realTimeSimulate() - Comienzo de la simulación: {0}", Constants.dfISO8601.format(System.currentTimeMillis()));
            LOG.log(Level.INFO, "realTimeSimulate() - Condiciones: Actualización cada: {0} milisegundos. Ejecución en tiempo real: {1}", new Object[]{timeRate, realTime});
            runningThreads = simulatedSmartDrivers * locationLogList.size();
            LOG.log(Level.INFO, "realTimeSimulate() - Se crean: {0} hilos de ejecución", runningThreads);
            for (int i = 0; i < locationLogList.size(); i++) {
                LocationLog ll = locationLogList.get(i);
                LocationLogDetail startPosition = ll.getLocationLogDetailList().get(0);
                LatLng latLng = new LatLng(startPosition.getLatitude(), startPosition.getLongitude());

                for (int j = 0; j < simulatedSmartDrivers; j++) {
                    Marker m = new Marker(latLng, getMarkerTitle(startPosition), null, MARKER_ICON_PATH);
                    m.setVisible(true);
                    m.setDraggable(false);

                    Timer timer = new Timer();
                    timer.scheduleAtFixedRate(new SimTimerTask(ll, m), 0, timeRate);
                    simulationTimers.put(i + "_" + j, timer);

                    simulatedMapModel.addOverlay(m);
                }
            }
        }
    }

    private void resetSimulation() {

        resetCarMarkers();
        simulationTimers = new HashMap<>();
        zTreamySends = 0;
        ztreamyErrors = 0;
        simulationFinishedMessage = "";
    }

    private void resetCarMarkers() {
        for (int i = simulatedMapModel.getMarkers().size() - 1; i >= 0; i--) {
            Marker m = simulatedMapModel.getMarkers().get(i);
            if (m.getIcon().equals(MARKER_ICON_PATH)) {
                simulatedMapModel.getMarkers().remove(i);
            }
        }
    }

    private void sendLocationToZtreamy(LocationLogDetail lld, double randomFactor) {
        try {
            // Creamos un objeto de tipo 'Location' de los que 'SmartDriver' envía a 'Ztreamy'.
            es.jyago.hermes.smartDriver.Location smartDriverLocation = new es.jyago.hermes.smartDriver.Location();
            smartDriverLocation.setLatitude(lld.getLatitude());
            smartDriverLocation.setLongitude(lld.getLongitude());
            smartDriverLocation.setSpeed(lld.getSpeed() / randomFactor);
            smartDriverLocation.setAccuracy(0);
            smartDriverLocation.setScore(0);
            smartDriverLocation.setTimeStamp(Constants.dfISO8601.format(lld.getTimeLog().getTime()));

            // Inicialmente, vamos a hacer un envío como si fuera un autobus con X usuarios de SmartDriver, que iniciasen la aplicación al mismo tiempo.
            for (int i = 0; i < simulatedSmartDrivers; i++) {
                LocationHermesZtreamyFacade locationZtreamy = new LocationHermesZtreamyFacade(APPLICATION_ID, smartDriverLocation, lld.getLocationLog().getPerson().getSha() + i, url);

                zTreamySends++;
                if (locationZtreamy.send()) {
                    LOG.log(Level.FINER, "sendLocationToZtreamy() - Localización de trayecto simulado enviada correctamante. SmartDriver: {0}", i);
                } else {
                    ztreamyErrors++;
                    LOG.log(Level.SEVERE, "sendLocationToZtreamy() - Error SEND: Trama: {0} - Enviada a las: {1} - Errores: {2} / Total: {3}", new Object[]{Constants.dfISO8601.format(lld.getTimeLog()), Constants.dfISO8601.format(System.currentTimeMillis()), ztreamyErrors, zTreamySends});
                }
            }
        } catch (MalformedURLException ex) {
            LOG.log(Level.SEVERE, "sendLocationToZtreamy() - Error en la URL", ex);
        } catch (IOException ex) {
            ztreamyErrors++;
            LOG.log(Level.SEVERE, "sendLocationToZtreamy() - Error I/O: {0} - Trama: {1} - Enviada a las: {2} - Errores: {3} / Total: {4}", new Object[]{ex.getMessage(), Constants.dfISO8601.format(lld.getTimeLog()), Constants.dfISO8601.format(System.currentTimeMillis()), ztreamyErrors, zTreamySends});
        } catch (HermesException ex) {
            ztreamyErrors++;
            LOG.log(Level.SEVERE, "sendLocationToZtreamy() - Error HERMES: {0} - Trama: {1} - Enviada a las: {2} - Errores: {3} / Total: {4}", new Object[]{ex.getMessage(), Constants.dfISO8601.format(lld.getTimeLog()), Constants.dfISO8601.format(System.currentTimeMillis()), ztreamyErrors, zTreamySends});
        }
    }

    private void sendDataSectionToZtreamy(LocationLogWrapper llw) {
        // Creamos un objeto de tipo 'DataSection' de los que 'SmartDriver' envía a 'Ztreamy'.
        DataSection dataSection = new DataSection();
        try {
            dataSection.setRoadSection(llw.getRoadSectionList());

            DescriptiveStatistics speedStats = new DescriptiveStatistics();
            DescriptiveStatistics heartRateStats = new DescriptiveStatistics();
            DescriptiveStatistics rrStats = new DescriptiveStatistics();
            DescriptiveStatistics accelerationStats = new DescriptiveStatistics();
            DescriptiveStatistics decelerationStats = new DescriptiveStatistics();
            RoadSection rdPrevious = llw.getRoadSectionList().get(0);
            speedStats.addValue(rdPrevious.getSpeed() * llw.getRandomFactor());
            int numHighAccelerations = 0;
            int numHighDecelerations = 0;

            for (int i = 1; i < llw.getRoadSectionList().size(); i++) {
                RoadSection rd = llw.getRoadSectionList().get(i);
                speedStats.addValue(rd.getSpeed());

                double vDiff = rd.getSpeed() - rdPrevious.getSpeed();
                double tDiff = (rd.getTime() - rdPrevious.getTime()) / 3600000.0;
                double acceleration = tDiff > 0.0d ? vDiff / tDiff : 0.0d;

                if (acceleration > 0.0d) {
                    accelerationStats.addValue(acceleration);
                    if (acceleration > HIGH_ACCELERATION_THRESHOLD) {
                        numHighAccelerations++;
                    }
                } else if (acceleration < 0.0d) {
                    decelerationStats.addValue(acceleration);
                    if (numHighDecelerations < HIGH_DECELERATION_THRESHOLD) {
                        numHighDecelerations++;
                    }
                }

                // FIXME: Simular datos de ritmo cardíaco.
                heartRateStats.addValue(80);
                rrStats.addValue(580);

                rdPrevious = rd;
            }

            dataSection.setAverageAcceleration(accelerationStats.getN() > 0 ? accelerationStats.getMean() : 0.0d);
            dataSection.setAverageDeceleration(decelerationStats.getN() > 0 ? decelerationStats.getMean() : 0.0d);
            dataSection.setAverageHeartRate(heartRateStats.getN() > 0 ? heartRateStats.getMean() : 0.0d);
            dataSection.setAverageRR(rrStats.getN() > 0 ? rrStats.getMean() : 0.0d);
            dataSection.setAverageSpeed(speedStats.getN() > 0 ? speedStats.getMean() : 0.0d);
            dataSection.setNumHighAccelerations(numHighAccelerations);
            dataSection.setNumHighDecelerations(numHighDecelerations);
            dataSection.setMaxSpeed(speedStats.getN() > 0 ? speedStats.getMax() : 0.0d);
            dataSection.setMedianSpeed(speedStats.getN() > 0 ? speedStats.getPercentile(50) : 0.0d);
            dataSection.setMinSpeed(speedStats.getN() > 0 ? speedStats.getMin() : 0.0d);
            dataSection.setPke(llw.getSectionDistance() > 0.0d ? (llw.getCummulativePositiveSpeeds() / llw.getSectionDistance()) : 0.0d);
            List<Integer> rrSectionList = new ArrayList();
            for (double rr : rrStats.getValues()) {
                rrSectionList.add((int) rr);
            }
            dataSection.setRrSection(rrSectionList);
            dataSection.setStandardDeviationHeartRate(heartRateStats.getN() > 0 ? heartRateStats.getStandardDeviation() : 0.0d);
            dataSection.setStandardDeviationRR(rrStats.getN() > 0 ? rrStats.getStandardDeviation() : 0.0d);
            dataSection.setStandardDeviationSpeed(speedStats.getN() > 0 ? speedStats.getStandardDeviation() : 0.0d);

            // Asignamos la lista de datos del tramo.
            dataSection.setRoadSection(llw.getRoadSectionList());

            DataSectionHermesZtreamyFacade dataSectionHermesZtreamy = new DataSectionHermesZtreamyFacade(APPLICATION_ID, dataSection, llw.getLocationLog().getPerson().getSha(), url);

            // Inicialmente, vamos a hacer un envío como si fuera un autobus con X usuarios de SmartDriver, que iniciasen la aplicación al mismo tiempo.
            for (int i = 0; i < simulatedSmartDrivers; i++) {
                zTreamySends++;
                if (dataSectionHermesZtreamy.send()) {
                    LOG.log(Level.FINER, "sendDataSectionToZtreamy() - Localización de trayecto simulado enviada correctamante. SmartDriver: {0}", i);
                } else {
                    ztreamyErrors++;
                    LOG.log(Level.SEVERE, "sendDataSectionToZtreamy() - Error SEND: Primera trama de la sección: {0} - Enviada a las: {1} - Errores: {2} / Total: {3}", new Object[]{dataSection.getRoadSection().get(0).getTimeStamp(), Constants.dfISO8601.format(System.currentTimeMillis()), ztreamyErrors, zTreamySends});
                }
            }
        } catch (MalformedURLException ex) {
            LOG.log(Level.SEVERE, "sendDataSectionToZtreamy() - Error en la URL", ex);
        } catch (IOException ex) {
            ztreamyErrors++;
            LOG.log(Level.SEVERE, "sendLocationToZtreamy() - Error I/O: {0} - Primera trama de la sección: {1} - Enviada a las: {2} - Errores: {3} / Total: {4}", new Object[]{ex.getMessage(), dataSection.getRoadSection().get(0).getTimeStamp(), Constants.dfISO8601.format(System.currentTimeMillis()), ztreamyErrors, zTreamySends});
        } catch (HermesException ex) {
            ztreamyErrors++;
            LOG.log(Level.SEVERE, "sendDataSectionToZtreamy() - Error HERMES: {0} - Primera trama de la sección: {1} - Enviada a las: {2} - Errores: {3} / Total: {4}", new Object[]{ex.getMessage(), dataSection.getRoadSection().get(0).getTimeStamp(), Constants.dfISO8601.format(System.currentTimeMillis()), ztreamyErrors, zTreamySends});
        }
    }

    private double analyzePKE(LocationLogDetail lld, LocationLogDetail lldPrev) {
        // Convertimos los Km/h en m/s.
        double currentSpeedMS = lld.getSpeed() / 3.6d;
        double previousSpeedMS = lldPrev.getSpeed() / 3.6d;

        double speedDifference = currentSpeedMS - previousSpeedMS;
        // Analizamos la diferencia de velocidad.
        if (speedDifference > 0.0d) {
            // Si la diferencia de velocidades es positiva, se tiene en cuenta para el sumatorio.
            return Math.pow(currentSpeedMS, 2) - Math.pow(previousSpeedMS, 2);
        }

        return 0.0d;
    }

    public boolean isAllSimulationsFinished() {
        return runningThreads == 0;
    }

    public String getSimulationFinishedMessage() {
        return simulationFinishedMessage;

    }

    public int getSimulatedSpeed() {
        return simulatedSpeed;
    }

    public String getSimulatedSpeedFormatted() {
        switch (this.simulatedSpeed) {
            case 0:
                return "1x";
            case 1:
                return "10x";
            case 2:
                return "100x";
            case 3:
                return "1000x";
            default:
                return "1x";
        }
    }

    public void setSimulatedSpeed(int simulatedSpeed) {
        this.simulatedSpeed = simulatedSpeed;

        switch (this.simulatedSpeed) {
            case 0:
                // Tiempo real.
                realTime = true;
                timeRate = 1000;
                break;
            case 1:
                // 10x
                realTime = false;
                timeRate = 100;
                break;
            case 2:
                // 100x
                realTime = false;
                timeRate = 10;
                break;
            case 3:
                // 1000x
                realTime = false;
                timeRate = 1;
                break;

            default:
                // Tiempo real.
                realTime = true;
                timeRate = 1000;
                break;
        }
    }

    class LocationLogWrapper {

        private int detailPosition;
        private long baseTime;
        private boolean finished;
        private LocationLog locationLog;
        private double sectionDistance;
        private List<RoadSection> roadSectionList;
        private double cummulativePositiveSpeeds;
        double randomFactor;

        public LocationLogWrapper() {
            this.detailPosition = 0;
            this.baseTime = 0l;
            this.finished = false;
            this.locationLog = null;
            this.sectionDistance = 0.0d;
            this.roadSectionList = new ArrayList();
            this.cummulativePositiveSpeeds = 0.0d;
            this.randomFactor = 1.0d + new Random().nextDouble(); // De este modo, la simulación puede tardar desde lo estimado por Google (1.0) hasta prácticamente el doble (<2.0).
        }

        public int getDetailPosition() {
            return detailPosition;
        }

        public void setDetailPosition(int detailPosition) {
            this.detailPosition = detailPosition;
        }

        public long getBaseTime() {
            return baseTime;
        }

        public void setBaseTime(long baseTime) {
            this.baseTime = baseTime;
        }

        public boolean isFinished() {
            return finished;
        }

        public void setFinished(boolean finished) {
            this.finished = finished;
        }

        public LocationLog getLocationLog() {
            return locationLog;
        }

        public void setLocationLog(LocationLog locationLog) {
            this.locationLog = locationLog;
        }

        public double getSectionDistance() {
            return sectionDistance;
        }

        public void resetSectionDistance() {
            this.sectionDistance = 0.0d;
        }

        public void addSectionDistance(double sectionDistance) {
            this.sectionDistance += sectionDistance;
        }

        public List<RoadSection> getRoadSectionList() {
            return roadSectionList;
        }

        public void setRoadSectionList(List<RoadSection> roadSectionList) {
            this.roadSectionList = roadSectionList;
        }

        public double getCummulativePositiveSpeeds() {
            return cummulativePositiveSpeeds;
        }

        public void addCummulativePositiveSpeeds(double cummulativePositiveSpeeds) {
            this.cummulativePositiveSpeeds += cummulativePositiveSpeeds;
        }

        public void resetCummulativePositiveSpeeds() {
            this.cummulativePositiveSpeeds = 0.0d;
        }

        public double getRandomFactor() {
            return randomFactor;
        }

        public void reset() {
            this.detailPosition = 0;
            this.finished = false;
            this.sectionDistance = 0.0d;
            this.roadSectionList = new ArrayList();
            this.cummulativePositiveSpeeds = 0.0d;
        }
    }

    private String getMarkerTitle(LocationLogDetail currentLocationLogDetail) {
        StringBuilder sb = new StringBuilder();
        sb.append(ResourceBundle.getBundle("/Bundle").getString("Time")).append(": ").append(Constants.dfTime.format(currentLocationLogDetail.getTimeLog()));
        sb.append(" ");
//        sb.append(ResourceBundle.getBundle("/Bundle").getString("HeartRate")).append(": ").append(Integer.toString(location.getHeartRate()));
//        sb.append(" ");
        sb.append(ResourceBundle.getBundle("/Bundle").getString("Speed")).append(": ").append(Constants.df2Decimals.format(currentLocationLogDetail.getSpeed())).append(" Km/h");
        sb.append(" (").append(currentLocationLogDetail.getLatitude()).append(", ").append(currentLocationLogDetail.getLongitude()).append(")");

        return sb.toString();

    }

    class SimTimerTask extends TimerTask {

        private final LocationLogWrapper llw;
        private final Marker trackMarker;
        private long elapsedTime;

        public SimTimerTask(LocationLog ll, Marker trackMarker) {
            this.llw = new LocationLogWrapper();
            this.llw.setLocationLog(ll);
            this.llw.setBaseTime(ll.getLocationLogDetailList().get(0).getTimeLog().getTime());
            this.trackMarker = trackMarker;
            this.elapsedTime = realTime ? 0l : 864000000l; // Un día, así me garantizo que se ejecutará cualquier trayecto inmediatamente.
        }

        @Override
        public void run() {

            LocationLog currentLocationLog = llw.getLocationLog();
            LocationLogDetail currentLocationLogDetail = null;
            double distance = 0.0d;

            if (!llw.isFinished()) {
                for (int d = llw.getDetailPosition(); d < currentLocationLog.getLocationLogDetailList().size(); d++) {
                    currentLocationLogDetail = currentLocationLog.getLocationLogDetailList().get(d);
                    // Con el tiempo transcurrido, obtenemos la posición por la que estará.
                    if (((currentLocationLogDetail.getTimeLog().getTime() - llw.getBaseTime()) <= (elapsedTime * llw.getRandomFactor()))
                            && (d > llw.getDetailPosition())) {
                        // Avanzamos de posición.
                        int previousPosition = llw.getDetailPosition();
                        llw.setDetailPosition(d);
                        trackMarker.setLatlng(new LatLng(currentLocationLogDetail.getLatitude(), currentLocationLogDetail.getLongitude()));
                        LocationLogDetail previousLocationLogDetail = currentLocationLog.getLocationLogDetailList().get(previousPosition);
                        // Acumulamos la distancia recorrida.
                        distance = Util.distanceHaversine(previousLocationLogDetail.getLatitude(), previousLocationLogDetail.getLongitude(), currentLocationLogDetail.getLatitude(), currentLocationLogDetail.getLongitude());
                        llw.addSectionDistance(distance);
                        // Análisis del PKE (Positive Kinetic Energy)
                        llw.addCummulativePositiveSpeeds(analyzePKE(currentLocationLogDetail, previousLocationLogDetail));

                        // Comprobamos si hemos llegado al destino.
                        if (llw.getDetailPosition() == currentLocationLog.getLocationLogDetailList().size() - 1) {
                            llw.setFinished(true);
                            runningThreads--;
                            simulatedMapModel.getMarkers().remove(trackMarker);
                            LOG.log(Level.INFO, "realTimeSimulate() - Hilos de ejecución restantes: {0}", runningThreads);
                            this.cancel();
                        }

                        // Información.
                        trackMarker.setTitle(getMarkerTitle(currentLocationLogDetail));
                        break;
                    }
                }
            }

            if (currentLocationLogDetail != null) {
                // Creamos un elementos de tipo 'RoadSection', para añadirlo al 'DataSection' que se envía a 'Ztreamy' cada 500 metros.
                RoadSection rd = new RoadSection();
                long delay = ((long) (elapsedTime * llw.getRandomFactor())) - llw.getBaseTime();
                rd.setTime(currentLocationLogDetail.getTimeLog().getTime() + delay);
                rd.setLatitude(currentLocationLogDetail.getLatitude());
                rd.setLongitude(currentLocationLogDetail.getLongitude());
                rd.setSpeed(distance * 3.6 / rd.getTime());

                llw.getRoadSectionList().add(rd);
            }

            // Se envían los datos a Ztreamy cada 10 segundos.
            if (!llw.isFinished()) {
                if (elapsedTime % ZTREAMY_SEND_INTERVAL_MILLISECONDS == 0) {
                    sendLocationToZtreamy(currentLocationLogDetail, llw.getRandomFactor());
                }

                // Se enviará un resumen cada 500 metros.
                if (llw.getSectionDistance() > ZTREAMY_SEND_INTERVAL_METERS) {
                    sendDataSectionToZtreamy(llw);
                    llw.getRoadSectionList().clear();
                    llw.resetCummulativePositiveSpeeds();
                    llw.resetSectionDistance();
                }
            }

            // Comprobamos si han terminado todas las simulaciones para parar los 'Timer'
            if (runningThreads == 0) {
                LOG.log(Level.INFO, "realTimeSimulate() - Todos los hilos completados");
                realTimeSimulate();
            }

            elapsedTime += 1000;
        }
    }
}
