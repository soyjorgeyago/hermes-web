package es.jyago.hermes.simulator;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
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
import es.jyago.hermes.smartDriver.LocationHermesZtreamyFacade;
import es.jyago.hermes.util.Constants;
import es.jyago.hermes.util.HermesException;
import es.jyago.hermes.util.Util;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Type;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
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
import org.joda.time.LocalTime;
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
    // FIXME: Quitar. Solo es de prueba.
    private ArrayList<LocationLogWrapper> locationLogList;

    private static Timer simulationTimer;
    private long elapsedTime;
    private int simulatedSmartDrivers;

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
        createSimulatedUser = false;
        simulationMethod = Track_Simulation_Method.GOOGLE;
        marker = new Marker(new LatLng(SEVILLE.getLat(), SEVILLE.getLng()));
        generateSimulatedTracks();
    }

    public String getMarkerLatitudeLongitude() {
        return marker.getLatlng().getLat() + "," + marker.getLatlng().getLng();
    }

    public void generateSimulatedTracks() {
        simulatedMapModel = new DefaultMapModel();
        trackInfoList = new ArrayList();
        locationLogList = new ArrayList<>();

        for (int i = 0; i < tracksAmount; i++) {
            Location origin = getRandomLocation(SEVILLE.getLat(), SEVILLE.getLng(), distanceFromSevilleCenter);
            Location destination = getRandomLocation(origin.getLat(), origin.getLng(), distance);
            TrackInfo trackInfo = null;
            Date currentTime = new Date();

            // Creamos un objeto de localizaciones de 'SmartDriver'.
            LocationLogWrapper llw = new LocationLogWrapper();
            llw.setDateLog(currentTime);

            if (simulationMethod.equals(Track_Simulation_Method.GOOGLE)) {
                Future future = GoogleWebService.submitTask(new Callable() {
                    @Override
                    public String call() {
                        String json = null;
                        try {
                            json = IOUtils.toString(new URL("https://maps.googleapis.com/maps/api/directions/json?origin=" + origin.getLat() + "," + origin.getLng() + "&destination=" + destination.getLat() + "," + destination.getLng()), "UTF-8");
                        } catch (IOException ex) {
                            LOG.log(Level.SEVERE, "Error al obtener el JSON de la ruta", ex);
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
                    trackInfo = createTrack(gcwp, llw);
                } catch (InterruptedException | ExecutionException | JsonSyntaxException ex) {
                    LOG.log(Level.SEVERE, "Error al decodificar el JSON de la ruta", ex);
                }
            } else if (simulationMethod.equals(Track_Simulation_Method.OPENSTREETMAP)) {
                try {
                    String json = IOUtils.toString(new URL("http://cronos.lbd.org.es/hermes/api/smartdriver/network/route?fromLat=" + origin.getLat() + "&fromLng=" + origin.getLng() + "&toLat=" + destination.getLat() + "&toLng=" + destination.getLng()), "UTF-8");
                    Type listType = new TypeToken<ArrayList<GeomWaySteps>>() {
                    }.getType();
                    List<GeomWaySteps> gws = new Gson().fromJson(json, listType);
                    trackInfo = createTrack(gws, llw);
                } catch (IOException | JsonSyntaxException ex) {
                    LOG.log(Level.SEVERE, "Error al decodificar el JSON de la ruta", ex);
                }
            }

            // Si alguna petición fallase, saltamos esta iteración del bucle.
            if (trackInfo == null || trackInfo.getTotalLocations() == 0) {
                continue;
            }

            if (createSimulatedUser) {
                // Creamos un usuario simulado, al que le asignaremos el trayecto.
                Person person = createSimPerson(currentTime.getTime());

                llw.setFilename(person.getFullName());
                llw.setPerson(person);

                personFacade.create(person);
                locationLogFacade.create(llw);
            }

            llw.setBaseTime(llw.getLocationLogDetailList().get(0).getTimeLog().getTime());
            llw.setDetailPosition(0);

            locationLogList.add(llw);

            if (trackInfo != null) {
                trackInfoList.add(trackInfo);
            }
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

    private TrackInfo createTrack(List<GeomWaySteps> geomWayStepsList, LocationLog ll) {
        TrackInfo trackInfo = new TrackInfo();

        if (geomWayStepsList != null && !geomWayStepsList.isEmpty()) {
            Polyline polyline = new Polyline();
            polyline.setStrokeWeight(4);
            polyline.setStrokeOpacity(0.7);

            Random rand = new Random();

            // Hacemos que las rutas sean variaciones de azul.
            polyline.setStrokeColor("#0000" + String.format("%02x", rand.nextInt(0x100)));

            // Resumen que mostraremos por pantalla.
            SectionInfo summary = trackInfo.getSummary();
//                    summary.setDistance(.getDistance().getValue());
//                    summary.setDuration(l.getDuration().getValue());
//                    summary.setStartLocation(l.getStartLocation());
//                    summary.setStartAddress(l.getStartAddress());
//                    summary.setEndLocation(l.getEndLocation());
//                    summary.setEndAddress(l.getEndAddress());

            // Listado de posiciones que componen el trayecto de SmartDriver.
            ArrayList<LocationLogDetail> locationLogDetailList = new ArrayList<>();

            double speed;

            // Analizamos la información obtenida de la consulta a OpenStreetMap.
            for (GeomWaySteps gws : geomWayStepsList) {
                GeomWay gw = gws.getGeomWay();

                for (int i = 0; i < gw.getCoordinates().size(); i++) {
                    // Viene en formato: longitud, latitud.
                    List<Double> coordinates = gw.getCoordinates().get(i);

                    // Añadimos un nuevo punto en la polilínea que se dibujará por pantalla.
                    LatLng latlng = new LatLng(coordinates.get(1), coordinates.get(0));
                    polyline.getPaths().add(latlng);

                    // Creamos una marca con la información detallada, para poder mostrarla cuando se pulse en la posición en Google Maps.
                    StringBuilder sb = new StringBuilder();
//        sb.append(ResourceBundle.getBundle("/Bundle").getString("Time")).append(": ").append(Constants.dfTime.format(location.getTimeLog()));
//        sb.append(" ");
//        sb.append(ResourceBundle.getBundle("/Bundle").getString("HeartRate")).append(": ").append(Integer.toString(location.getHeartRate()));
//        sb.append(" ");
//        sb.append(ResourceBundle.getBundle("/Bundle").getString("Speed")).append(": ").append(location.getSpeed());
//        sb.append(" (").append(location.getLatitude()).append(", ").append(location.getLongitude()).append(")");

// FIXME: ¿Poner de nuevo?
//                    Marker m = new Marker(latlng, sb.toString(), "https://maps.google.com/mapfiles/ms/micons/blue.png");
//                    m.setVisible(false);
//
//                    simulatedMapModel.addOverlay(m);
                }

                simulatedMapModel.addOverlay(polyline);
            }
            trackInfo.setTotalLocations(locationLogDetailList.size());
            trackInfo.setAverageLocationsDistance(summary.getDistance() / locationLogDetailList.size());

            // Asignamos el listado de posiciones.
            ll.setLocationLogDetailList(locationLogDetailList);
        }

        return trackInfo;
    }

    private TrackInfo createTrack(GeocodedWaypoints gcwp, LocationLog ll) {
        TrackInfo trackInfo = new TrackInfo();

        if (gcwp.getRoutes() != null) {
            Polyline polyline = new Polyline();
            polyline.setStrokeWeight(4);
            polyline.setStrokeOpacity(0.7);

            Random rand = new Random();
            // Hacemos que las rutas sean variaciones de verde.
            polyline.setStrokeColor("#00" + String.format("%02x", rand.nextInt(0x100)) + "00");

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
                    summary.setDuration(l.getDuration().getValue());
                    summary.setStartLocation(new LocationInfo(l.getStartLocation().getLat(), l.getStartLocation().getLng()));
                    summary.setStartAddress(l.getStartAddress());
                    summary.setEndLocation(new LocationInfo(l.getEndLocation().getLat(), l.getEndLocation().getLng()));
                    summary.setEndAddress(l.getEndAddress());

                    double speed;
                    LocalTime localTime = new LocalTime();
                    ArrayList<Location> locationList = PolylineDecoder.decodePoly(r.getOverviewPolyline().getPoints());
                    Location previous = locationList.get(0);

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

                        // Calculamos la distancia entre los puntos previo y actual, así como el tiempo necesario para recorrer dicha distancia.
                        Double pointDistance = Util.distanceHaversine(previous.getLat(), previous.getLng(), location.getLat(), location.getLng());
                        Double pointDuration = l.getDuration().getValue() * pointDistance / l.getDistance().getValue();

                        // Convertimos la velocidad a Km/h.
                        speed = pointDuration > 0 ? pointDistance * 3.6 / pointDuration : 0.0d;
                        lld.setSpeed(speed);
                        // Añadimos los segundos correspondientes a la distancia recorrida entre puntos.
                        localTime = localTime.plusSeconds(pointDuration.intValue());
                        lld.setTimeLog(localTime.toDateTimeToday().toDate());

                        locationLogDetailList.add(lld);

                        // Asignamos el actual al anterior, para poder seguir calculando las distancias y tiempos respecto al punto previo.
                        previous = location;

                        // Creamos una marca con la información detallada, para poder mostrarla cuando se pulse en la posición en Google Maps.
                        StringBuilder sb = new StringBuilder();
                        sb.append(ResourceBundle.getBundle("/Bundle").getString("Time")).append(": ").append(Constants.dfTime.format(lld.getTimeLog()));
                        sb.append(" ");
//                        sb.append(ResourceBundle.getBundle("/Bundle").getString("HeartRate")).append(": ").append(Integer.toString(location.getHeartRate()));
//                        sb.append(" ");
                        sb.append(ResourceBundle.getBundle("/Bundle").getString("Speed")).append(": ").append(lld.getSpeed());
                        sb.append(" (").append(lld.getLatitude()).append(", ").append(lld.getLongitude()).append(")");

                        // FIXME: ¿Poner de nuevo?
//                        Marker m = new Marker(latlng, sb.toString(), "https://maps.google.com/mapfiles/ms/micons/red.png");
//                        m.setVisible(false);
//
//                        simulatedMapModel.addOverlay(m);
                    }

                    simulatedMapModel.addOverlay(polyline);

                    trackInfo.setTotalLocations(locationLogDetailList.size());
                    trackInfo.setAverageLocationsDistance(summary.getDistance() / locationLogDetailList.size());

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

//    public void ajaxPoll() {
//        for (int i = 0; i < simulatedMapModel.getMarkers().size(); i++) {
////            RequestContext.getCurrentInstance().addCallbackParam("marker" + i, simulatedMapModel.getMarkers().get(i));
//            LatLng latLng = simulatedMapModel.getMarkers().get(i).getLatlng();
//            System.out.println(latLng.getLat() + " - " + latLng.getLng());
//        }
//    }
    public boolean isSimulating() {
        return simulationTimer != null;
    }

    public void realTimeSimulate() {
        // Si el temporizador está instanciado, es que hay una simulación en marcha y se quiere parar.
        if (simulationTimer != null) {
            simulationTimer.cancel();
            simulationTimer = null;
        } else {
            // No hay simulación en marcha, la comenzamos.
            elapsedTime = 0l;
            simulationTimer = new Timer();
            simulationTimer.scheduleAtFixedRate(new TimerTask() {

                @Override
                public void run() {
                    simulatedMapModel.getMarkers().clear();
                    for (LocationLogWrapper llw : locationLogList) {
                        LocationLogDetail currentLocationLogDetail = null;
                        for (int i = llw.getDetailPosition(); i < llw.getLocationLogDetailList().size(); i++) {
                            currentLocationLogDetail = llw.getLocationLogDetailList().get(i);
                            if ((currentLocationLogDetail.getTimeLog().getTime() - llw.getBaseTime()) >= elapsedTime) {
                                llw.setDetailPosition(i);
                                break;
                            }
                        }

                        if (currentLocationLogDetail == null) {
                            // Ha terminado el trayecto. Asignamos la última posición.
                            currentLocationLogDetail = llw.getLocationLogDetailList().get(llw.getLocationLogDetailList().size() - 1);
                            llw.setFinished(true);
                        }

                        LatLng latLng = new LatLng(currentLocationLogDetail.getLatitude(), currentLocationLogDetail.getLongitude());
                        Marker m = new Marker(latLng, "", "https://maps.google.com/mapfiles/ms/micons/red.png");
                        m.setVisible(true);
                        simulatedMapModel.addOverlay(m);

                        if (elapsedTime % 10 == 0) {
                            sendToZtreamy(currentLocationLogDetail);
                        }
                    }

                    elapsedTime += 1000;
                }
            }, 0, 1000);
        }

//        for (LocationLog ll : lll) {
//            LOG.log(Level.INFO, "realTimeSimulate() - Simulación trayecto con total de puntos: " + ll.getLocationLogDetailList().size());
//            RealTimeSimulator.submitTask(new Runnable() {
//                @Override
//                public void run() {
//                    long baseTime = ll.getLocationLogDetailList().get(0).getTimeLog().getTime();
//                    for (LocationLogDetail lld : ll.getLocationLogDetailList()) {
//                        try {
//                            Thread.sleep(lld.getTimeLog().getTime() - baseTime);
//                        } catch (InterruptedException ex) {
//                            Logger.getLogger(SimulatorController.class.getName()).log(Level.SEVERE, null, ex);
//                        }
//                    }
//                }
//            });
//        }
//        Future future = RealTimeSimulator.submitTask(new Callable() {
//            @Override
//            public String call() {
//                String result = "";
//                long baseTime = ll.getLocationLogDetailList().get(0).getTimeLog().getTime();
//                for (LocationLogDetail lld : ll.getLocationLogDetailList())
//                {
//                    try {
//                        Thread.sleep(lld.getTimeLog().getTime() - baseTime);
//                    } catch (InterruptedException ex) {
//                        Logger.getLogger(SimulatorController.class.getName()).log(Level.SEVERE, null, ex);
//                    }
//                }
//                LOG.log(Level.FINE, "Bien");
//                return result;
//            }
//
//        });
//
//        try {
//            String status = (String) future.get();  // blocks
//        } catch (Exception e) {
//        }
    }

    private void sendToZtreamy(LocationLogDetail lld) {

        try {
            // Los parámetros de configuración de Ztreamy estarán en la tabla de configuración.

            // FIXME!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
//            String url = Constants.getInstance().getConfigurationValueByKey("ZtreamyUrl");
            String url = "";

            Person simPerson = createSimPerson(System.currentTimeMillis());

            es.jyago.hermes.smartDriver.Location smartDriverLocation = new es.jyago.hermes.smartDriver.Location();
            smartDriverLocation.setLatitude(lld.getLatitude());
            smartDriverLocation.setLongitude(lld.getLongitude());
            // TODO: Simular 
            smartDriverLocation.setSpeed(0.0);
            smartDriverLocation.setAccuracy(0);
            smartDriverLocation.setScore(0);
            smartDriverLocation.setTimeStamp(Constants.dfFitbitFull.format(lld.getTimeLog()));

            LocationHermesZtreamyFacade locationZtreamy = new LocationHermesZtreamyFacade(smartDriverLocation, simPerson, url);

            // Inicialmente, vamos a hacer un envío como si fuera un autobus con X usuarios de SmartDriver, que iniciasen la aplicación al mismo tiempo.
            for (int i = 0; i < simulatedSmartDrivers; i++) {
                if (locationZtreamy.send()) {
                    LOG.log(Level.FINER, "sendToZtreamy() - Localización de trayecto simulado enviada correctamante. SmartDriver: {0}", i);
                } else {
                    LOG.log(Level.SEVERE, "sendToZtreamy() - Error al enviar la localización del trayacto simulado. SmartDriver: {0}", i);
                }
            }
        } catch (MalformedURLException ex) {
            LOG.log(Level.SEVERE, "sendToZtreamy() - Error en la URL", ex);
        } catch (IOException ex) {
            LOG.log(Level.SEVERE, "sendToZtreamy() - Error de I/O", ex);
        } catch (HermesException ex) {
            LOG.log(Level.SEVERE, "sendToZtreamy() - Error al enviar datos a Ztreamy");
        }
    }

    class LocationLogWrapper extends LocationLog {

        private int detailPosition;
        private long baseTime;
        private boolean finished;

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
        
    }
}