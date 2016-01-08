package es.jyago.hermes.person;

import es.jyago.hermes.activityLog.ActivityLog;
import es.jyago.hermes.activityLog.ActivityLogController;
import es.jyago.hermes.activityLog.ActivityLogHermesZtreamyFacade;
import es.jyago.hermes.bean.LocaleBean;
import es.jyago.hermes.util.Constants;
import es.jyago.hermes.util.JsfUtil;
import es.jyago.hermes.util.JsfUtil.PersistAction;
import es.jyago.hermes.fitbit.FitbitResetRequestsScheduledTask;
import es.jyago.hermes.fitbit.HermesFitbitControllerOauth2;
import es.jyago.hermes.fitbit.HermesFitbitException;
import es.jyago.hermes.fitbit.IFitbitFacade;
import es.jyago.hermes.healthLog.HealthLog;
import es.jyago.hermes.healthLog.HealthLogHermesZtreamyFacade;
import es.jyago.hermes.heartLog.HeartLog;
import es.jyago.hermes.location.LocationLog;
import es.jyago.hermes.location.LocationLogCSVController;
import es.jyago.hermes.location.LocationLogCSVController2;
import es.jyago.hermes.location.LocationLogController;
import es.jyago.hermes.location.detail.LocationLogDetail;
import es.jyago.hermes.login.LoginController;
import es.jyago.hermes.sleepLog.SleepLog;
import es.jyago.hermes.sleepLog.SleepLogHermesZtreamyFacade;
import es.jyago.hermes.util.HermesException;
import es.jyago.hermes.util.Util;
import java.io.IOException;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.ejb.EJBException;
import javax.enterprise.context.SessionScoped;
import javax.faces.application.FacesMessage;
import javax.faces.component.UIComponent;
import javax.faces.context.ExternalContext;
import javax.faces.context.FacesContext;
import javax.faces.convert.Converter;
import javax.faces.convert.FacesConverter;
import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeConstants;
import org.joda.time.Days;
import org.joda.time.LocalDate;
import org.joda.time.LocalTime;
import org.primefaces.context.RequestContext;
import org.primefaces.event.FileUploadEvent;
import org.primefaces.event.ItemSelectEvent;
import org.primefaces.event.SelectEvent;
import org.primefaces.event.map.OverlaySelectEvent;
import org.primefaces.model.UploadedFile;
import org.primefaces.model.chart.Axis;
import org.primefaces.model.chart.AxisType;
import org.primefaces.model.chart.BarChartModel;
import org.primefaces.model.chart.BarChartSeries;
import org.primefaces.model.chart.CategoryAxis;
import org.primefaces.model.chart.ChartSeries;
import org.primefaces.model.chart.DateAxis;
import org.primefaces.model.chart.LineChartModel;
import org.primefaces.model.chart.LineChartSeries;

@Named("personController")
@SessionScoped
public class PersonController implements Serializable, IFitbitFacade {

    private static final Logger log = Logger.getLogger(PersonController.class.getName());

    @EJB
    private PersonFacade ejbFacade;
    private List<Person> items;
    private Person selected;
    private HermesFitbitControllerOauth2 hermesFitbitController;
    private String authorizeUrl;
    private Date startDate;
    private Date endDate;
    private Date fitbitEndDate;
    private String aggregation;
    private ActivityLog selectedActivity;
    private List<ActivityLog> chartMonthActivityLogList;
    private HealthLog selectedHealthLog;
    private List<HealthLog> chartMonthHealthLogList;
    private int dateSelector;

    @Inject
    private ActivityLogController activityLogController;
    @Inject
    private LocationLogController locationLogController;
    private String lastCode;

    // Gráficos
    private LineChartModel stepsLogLineChartModel;
    private BarChartModel sleepLogBarChartModel;
    private LineChartModel heartLogLineChartModel;

    public PersonController() {
        log.log(Level.INFO, "PersonController() - Inicialización del controlador de personas");

        selected = null;
        authorizeUrl = null;
        items = null;
        hermesFitbitController = null;
        startDate = Calendar.getInstance().getTime();
        endDate = Calendar.getInstance().getTime();
        dateSelector = 1;
        lastCode = null;
    }

    public Person getSelected() {
        return selected;
    }

    public void setSelected(Person selected) {
        this.selected = selected;
        // Inicializamos los controladores.
        initControllers();
        // Inicializamos los gráficos.
        initCharts();
    }

    private void initControllers() {
        locationLogController.setPerson(selected);
    }

    private void initCharts() {
        initChartDates();
        initStepsLogLineChartModel();
        initSleepLogChartModel();
        initHeartRateChartModel();
        initMap();
    }

    private void initMap() {
        locationLogController.initLocationLogMapModel(selected.getLocationLogList());
    }

    protected void setEmbeddableKeys() {
    }

    protected void initializeEmbeddableKey() {
    }

    private PersonFacade getFacade() {
        return ejbFacade;
    }

    public Person prepareCreate() {
        selected = new Person();
        initializeEmbeddableKey();

        return selected;
    }

    public Person getPerson(String username, String password) {
        return (Person) ejbFacade.getEntityManager().createNamedQuery("Person.findByUsernamePassword")
                .setParameter("username", username)
                .setParameter("password", password)
                .getSingleResult();
    }

    public void create() {
        HttpServletRequest request = (HttpServletRequest) FacesContext.getCurrentInstance().getExternalContext().getRequest();
        selected.setIp(request.getParameter("ip"));
        selected.setCity(request.getParameter("city"));
        selected.setRegion(request.getParameter("region"));
        String country = request.getParameter("country");
        Locale locale = new Locale("", country);
        selected.setCountry(locale.getDisplayCountry());
        persist(PersistAction.CREATE, LocaleBean.getBundle().getString("PersonCreated"));
        if (!JsfUtil.isValidationFailed()) {
            items = null;    // Invalidate list of items to trigger re-query.
        }
    }

    public String createAndRedirect() {
        if (selected.hasFitbitCredentials()) {
            create();
        } else {
            // Activamos la bandera para indicar que ha habido un error.
            FacesContext.getCurrentInstance().validationFailed();
            JsfUtil.addErrorMessage(LocaleBean.getBundle().getString("Fitbit.error.noCredentials"));
        }
        // Si no ha fallado la validación, nos registramos en la aplicación y vamos a la pantalla principal del usuario.
        if (!JsfUtil.isValidationFailed()) {
            HttpSession session = (HttpSession) FacesContext.getCurrentInstance().getExternalContext().getSession(true);
            session.setAttribute("username", selected.getUsername());
            session.setAttribute("password", selected.getPassword());
            // JYFR: Para mantener los mensajes en una redirección.
//        ExternalContext ec = FacesContext.getCurrentInstance().getExternalContext();
//        ec.getFlash().setKeepMessages(true);

            return "/faces/index.xhtml?faces-redirect=true";
        } else {
            return null;
        }
    }

    public void update() {
        update(true);
    }

//    public void updateWithImports() {
//        if (selected.getLocationLogList() == null) {
//            selected.setLocationLogList(new ArrayList<>());
//        }
//
//        // Sustituimos los coincidentes.
//        for (int i = selected.getLocationLogList().size() - 1; i >= 0; i--) {
//            LocationLog existingLocationLog = selected.getLocationLogList().get(i);
//            // Si tenemos registrado el mismo archivo, lo eliminamos.
//            LocationLog importedLocationLog = importedLocationLogs.get(existingLocationLog.getFilename());
//            if (importedLocationLog != null) {
//                selected.getLocationLogList().remove(i);
//            }
//        }
//        // FIXME: Solucionar UniqueConstrain
//
////        List<LocationLog> locationLogList = selected.getLocationLogList();
////        selected.setLocationLogList(null);
//
////        update(false);
//        // Añadimos los no coincidentes y los que se sustituyen.
////        locationLogList.addAll(importedLocationLogs.values());
//        selected.getLocationLogList().addAll(importedLocationLogs.values());
//        // Actualizamos el listado con las nuevas importaciones.
////        selected.setLocationLogList(locationLogList);
//        update(true);
//        // Vaciamos el contenedor de los archivos importados.
//        this.importedLocationLogs.clear();
//    }
//
//    public boolean hasImportedLocationLogs() {
//        return !this.importedLocationLogs.isEmpty();
//    }
    public void update(boolean showMessage) {
        String message = null;
        if (showMessage) {
            message = LocaleBean.getBundle().getString("PersonUpdated");
        }
        persist(PersistAction.UPDATE, message);
    }

    public void destroy() {
        persist(PersistAction.DELETE, LocaleBean.getBundle().getString("PersonDeleted"));
        if (!JsfUtil.isValidationFailed()) {
            selected = null; // Remove selection
            items = null;    // Invalidate list of items to trigger re-query.
        }
    }

    public List<Person> getItems() {

        if (items == null) {
            HttpServletRequest request = (HttpServletRequest) FacesContext.getCurrentInstance().getExternalContext().getRequest();
            LoginController loginBean = (LoginController) request.getSession().getAttribute("loginController");

            if (loginBean.getPerson().isAdmin() || loginBean.getPerson().isDoctor()) {
                items = getFacade().findAll();
                // Procesamos todos los elementos para añadirle las opciones de configuración.

            } else {
                items = new ArrayList<>();
                items.add(loginBean.getPerson());
            }
            // Mostramos el mensaje de ayuda si es la primera vez que accede (que será si no existe la cookie)
            JsfUtil.showHelpMessage("initialMessagePersonList", LocaleBean.getBundle().getString("ContextMenuInfo"));
        }

        return items;
    }

    public void checkFitbitReturnCode() {
        // Obtenemos los datos del usuario registrado.
        HttpServletRequest request = (HttpServletRequest) FacesContext.getCurrentInstance().getExternalContext().getRequest();
        LoginController loginBean = (LoginController) request.getSession().getAttribute("loginController");
        // Asignamos la persona asociada al usuario.
        selected = loginBean != null ? loginBean.getPerson() : null;

        if (selected != null) {
            // Comprobamos si llega información de Fitbit.
            String code = request.getParameter(HermesFitbitControllerOauth2.OAUTH2_CODE);
            if (code != null) {
                if (lastCode == null || !lastCode.equals(code)) {
                    if (completeAuthorization(code)) {
                        update(false);
                        lastCode = code;
                    }
                }
            }
        }
    }

    private void persist(PersistAction persistAction, String successMessage) {
        if (selected != null) {
            setEmbeddableKeys();
            try {
                if (persistAction != PersistAction.DELETE) {
                    // Cuando vayamos a guardar los datos de la persona, calculamos el SHA del e-mail.
                    selected.setSha(new String(Hex.encodeHex(DigestUtils.sha256(selected.getEmail()))));
                    getFacade().edit(selected);
                } else {
                    getFacade().remove(selected);
                }
                if (successMessage != null) {
                    JsfUtil.addSuccessMessage(successMessage);
                    items = null;    // Invalidate list of items to trigger re-query.
                }
            } catch (EJBException ex) {
                // Activamos la bandera para indicar que ha habido un error.
                FacesContext.getCurrentInstance().validationFailed();
                JsfUtil.showDetailedExceptionCauseMessage(ex);
            } catch (Exception ex) {
                FacesContext.getCurrentInstance().validationFailed();
                log.log(Level.SEVERE, "persist() - Error al registrar los cambios", ex);
                // TODO: Usar esta forma para mostrar los mensajes!
                // TODO: Incluso poner el 'bundle' en JsfUtil y enviar sólo la key.
                JsfUtil.addErrorMessage(ex, LocaleBean.getBundle().getString("PersistenceErrorOccured"));
            }
        }
    }

    public Person getPerson(java.lang.Integer id) {
        return getFacade().find(id);
    }

    public Date getStartDate() {
        return startDate;
    }

    public void setStartDate(Date startDate) {
        this.startDate = startDate;
    }

    public Date getEndDate() {
        return endDate;
    }

    public Date getFitbitEndDate() {
        return fitbitEndDate;
    }

    public void setEndDate(Date endDate) {
        this.endDate = endDate;
    }

    public void initSynchronizationDates() {
        if (selected != null) {
            initFitbitController(selected);
            initEndSyncDate();
            initStartSyncDate();
        }
    }

    public void openSynchronizationDialog() {
        initSynchronizationDates();
        if (!FacesContext.getCurrentInstance().isValidationFailed()) {
            RequestContext.getCurrentInstance().execute("PF('PersonSynchronizeDialog').show()");
        }
    }

    public void openSynchronizationDialog(Person person) {
        selected = person;
        openSynchronizationDialog();
    }

    public void checkEmail() {
        if (!Util.validateEmail(selected.getEmail())) {
            log.log(Level.SEVERE, "checkEmail() - El email de la persona no es válido: {0}", selected.getEmail());
            FacesContext.getCurrentInstance().validationFailed();
            JsfUtil.addErrorMessage(LocaleBean.getBundle().getString("CheckEmail"));
            RequestContext.getCurrentInstance().execute("PF('PersonEditDialog').show()");
        } else {
            try {
                ExternalContext ec = FacesContext.getCurrentInstance().getExternalContext();
                ec.redirect(ec.getRequestContextPath() + "/faces/secured/alert/List.xhtml");
            } catch (IOException ex) {
                log.log(Level.SEVERE, "checkEmail() - Error al redirigir al listado de alertas", ex);
            }
        }
    }

    public void checkEmail(Person person) {
        selected = person;
        checkEmail();
    }

    private void initStartSyncDate() {
        // La fecha de inicio de sincronización será la más antigua entre la última registrada para la persona
        // y la última sincronización de la pulsera de Fitbit.
        // Si la persona no tuviera ninguna sincronización, tomamos como fecha de partida el primer día del año actual.
        Date personLastSynchronization = selected.getLastFitbitSynchronization();
        Date lastFitbitSynchronization = endDate;
        if (personLastSynchronization == null) {
            Calendar firstDayOfYear = Calendar.getInstance();
            firstDayOfYear.set(Calendar.MONTH, 0);
            firstDayOfYear.set(Calendar.DAY_OF_MONTH, 1);
            personLastSynchronization = firstDayOfYear.getTime();
        }

        startDate = personLastSynchronization.before(lastFitbitSynchronization) ? personLastSynchronization : lastFitbitSynchronization;
    }

    private void initEndSyncDate() {
        endDate = new Date();
        fitbitEndDate = new Date();

        try {
            if (hermesFitbitController != null) {
                // La sincronización se hará hasta el último día de sincronización de la pulsera de Fitbit.
                endDate = hermesFitbitController.getLastSyncDate();
                fitbitEndDate = endDate;
            }
        } catch (HermesFitbitException ex) {
            log.log(Level.SEVERE, "getEndSyncDate() - Error al autorizar la petición a Fitbit");
            FacesContext.getCurrentInstance().validationFailed();
            redirectToFitbit();
        } catch (HermesException ex) {
            log.log(Level.SEVERE, "getEndSyncDate() - Error al obtener la fecha de fin de sincronización");
            FacesContext.getCurrentInstance().validationFailed();
            FacesContext.getCurrentInstance().addMessage("messages", new FacesMessage(FacesMessage.SEVERITY_ERROR, ex.getMessage(), LocaleBean.getBundle().getString("PleaseTryAgainLater")));
            // Para que se mantenga el mensaje.
            FacesContext.getCurrentInstance().getExternalContext().getFlash().setKeepMessages(true);
        }
    }

    private void redirectToFitbit() {
        log.log(Level.INFO, "redirectToFitbit() - Redirigir a la página de autorización de Fitbit");

        FacesContext.getCurrentInstance().validationFailed();
        authorize("");
        ExternalContext ec = FacesContext.getCurrentInstance().getExternalContext();
        try {
            ec.redirect(getAuthorizeUrlAndReset());
        } catch (IOException e) {
            log.log(Level.SEVERE, "redirectToFitbit() - Error al redirigir a la página de autorización de Fitbit");
        }
    }

    private void initFitbitController(Person person) {
        hermesFitbitController = new HermesFitbitControllerOauth2(this);
    }

    public void authorize(String nextPage) {
        try {
            initFitbitController(selected);
            authorizeUrl = hermesFitbitController.getAuthorizeURL((HttpServletRequest) FacesContext.getCurrentInstance().getExternalContext().getRequest(), nextPage);
        } catch (HermesException ex) {
            log.log(Level.SEVERE, "authorize() - Error al autorizar a la persona {0}", selected.getFullName());
            FacesContext.getCurrentInstance().addMessage("messages", new FacesMessage(FacesMessage.SEVERITY_ERROR, ex.getMessage(), LocaleBean.getBundle().getString("PleaseTryAgainLater")));
        }
    }

    public void authorize(Person person, String nextPage) {
        selected = person;
        authorize(nextPage);
    }

    public void prepareRegister() {
        prepareCreate();
        if (selected != null) {
            initFitbitController(selected);
            authorize("/reg");
        } else {
            log.log(Level.SEVERE, "prepareRegister() - Error al preparar el registro de la persona {0}", selected.getFullName());
            FacesContext.getCurrentInstance().addMessage("messages", new FacesMessage(FacesMessage.SEVERITY_ERROR, LocaleBean.getBundle().getString("Error"), LocaleBean.getBundle().getString("Fitbit.error.noCredentials")));
        }
    }

    public void onWelcomePageLoad() {
        // JYFR: Controlamos que sólo se ejecute la primera vez que se carga la página de registro y no por un cambio de pestaña de un formulario, por ejemplo.
        if (!FacesContext.getCurrentInstance().isPostback()) {
            String configValue = Constants.getConfigurationValueByKey("WelcomeHits");
            Integer hits = configValue != null ? Integer.parseInt(configValue) : 0;
            Constants.setConfigurationValueByKey("WelcomeHits", Integer.toString(++hits));
        }
    }

    public void onRegisterPageLoad() {
        // JYFR: Controlamos que sólo se ejecute la primera vez que se carga la página de registro y no por un cambio de pestaña de un formulario, por ejemplo.
        if (!FacesContext.getCurrentInstance().isPostback()) {
            String configValue = Constants.getConfigurationValueByKey("RegisterHits");
            Integer hits = configValue != null ? Integer.parseInt(configValue) : 0;
            Constants.setConfigurationValueByKey("RegisterHits", Integer.toString(++hits));

            try {
                if (hermesFitbitController != null) {
                    FacesContext ctx = FacesContext.getCurrentInstance();
                    HttpServletRequest request = (HttpServletRequest) ctx.getExternalContext().getRequest();
                    String code = request.getParameter(HermesFitbitControllerOauth2.OAUTH2_CODE);
                    hermesFitbitController.completeAuthorization(code);
                    hermesFitbitController.transferUserInfoToPerson();
                } else {
                    log.log(Level.SEVERE, "onRegisterPageLoad() - Error al completar el registro");
                    FacesContext.getCurrentInstance().addMessage("messages", new FacesMessage(FacesMessage.SEVERITY_ERROR, LocaleBean.getBundle().getString("Fitbit.error.noAuthorization"), LocaleBean.getBundle().getString("PleaseTryAgainLater")));
                }
            } catch (HermesException ex) {
                log.log(Level.SEVERE, "onRegisterPageLoad() - Error al completar el registro");
                FacesContext.getCurrentInstance().addMessage("messages", new FacesMessage(FacesMessage.SEVERITY_ERROR, ex.getMessage(), LocaleBean.getBundle().getString("PleaseTryAgainLater")));
            }
        }
    }

    private boolean completeAuthorization(String code) {
        if (selected != null) {
            try {
                hermesFitbitController.completeAuthorization(code);
                JsfUtil.addSuccessMessage(LocaleBean.getBundle().getString("Fitbit.info.authorized"), LocaleBean.getBundle().getString("Fitbit.info.canOperate"));
                // Para que se mantenga el mensaje.
                FacesContext.getCurrentInstance().getExternalContext().getFlash().setKeepMessages(true);
                return true;
            } catch (HermesException ex) {
                log.log(Level.SEVERE, "completeAuthorization() - Error al completar la autorización");
                FacesContext.getCurrentInstance().addMessage("messages", new FacesMessage(FacesMessage.SEVERITY_ERROR, ex.getMessage(), LocaleBean.getBundle().getString("PleaseTryAgainLater")));
            }
        }

        return false;
    }

    public String getAuthorizeUrl() {
        return authorizeUrl;
    }

    public String getAuthorizeUrlAndReset() {
        // La URL de autorización sólo puede ser usada una vez.
        String temp = authorizeUrl;
        log.log(Level.FINEST, "getAuthorizeUrlAndReset() - URL temporal de registro: {0}", authorizeUrl);
        authorizeUrl = null;
        return temp;
    }

    public void setAuthorizeUrl(String authorizeUrl) {
        this.authorizeUrl = authorizeUrl;
    }

    public void synchronize() {
        try {
            hermesFitbitController.synchronize(startDate, endDate);
            HttpServletRequest request = (HttpServletRequest) FacesContext.getCurrentInstance().getExternalContext().getRequest();
            LoginController loginBean = (LoginController) request.getSession().getAttribute("loginController");
            // Comprobamos si el usuario es administrador y si está sincronizando los datos de otra persona a través del listado.
            if (loginBean.getPerson().isAdmin() && !loginBean.getPerson().equals(selected)) {
                // Refrescamos el listado de personas.
                items = null;
            } else {
                // Refrescamos el panel principal.
                RequestContext.getCurrentInstance().update("MainForm");
            }
        } catch (HermesException ex) {
            log.log(Level.SEVERE, "synchronize() - Error al sincronizar los datos de Fitbit de la persona {0}", selected.getFullName());
            FacesContext.getCurrentInstance().addMessage("messages", new FacesMessage(FacesMessage.SEVERITY_ERROR, ex.getMessage(), LocaleBean.getBundle().getString("PleaseTryAgainLater")));
        }
    }

    public void validate() {
        FacesContext fc = FacesContext.getCurrentInstance();
        if (fc.isValidationFailed()) {
            // FIXME: Poner de una forma más elegante.
            // FIXME: El componente de TabView no enfoca la pestaña en la que ha fallado la validación
            //       ¿Combinación con p:focus? 
            //    this.tabView.setActiveIndex(0);
        }
    }

    public void sendToZtreamy() {
        FacesMessage message;

        try {
            // Los parámetros de configuración de Ztreamy estarán en la tabla de configuración.
            String url = Constants.getConfigurationValueByKey("ZtreamyStepsApplicationId");
            List<ActivityLog> activityLogList = this.getSelected().getActivityLogList(startDate, endDate, aggregation);
            ActivityLogHermesZtreamyFacade activityLogZtreamy = new ActivityLogHermesZtreamyFacade(activityLogList, this.getSelected(), url);
            List<SleepLog> sleepLogList = this.getSelected().getSleepLogList(startDate, endDate);
            SleepLogHermesZtreamyFacade sleepLogZtreamy = new SleepLogHermesZtreamyFacade(sleepLogList, this.getSelected(), url);
            List<HealthLog> healthLogList = this.getSelected().getHealthLogList(startDate, endDate, aggregation);
            HealthLogHermesZtreamyFacade healthLogZtreamy = new HealthLogHermesZtreamyFacade(healthLogList, this.getSelected(), url);

            if (activityLogZtreamy.send() && sleepLogZtreamy.send() && healthLogZtreamy.send()) {
                for (ActivityLog activityLog : activityLogList) {
                    activityLog.setSendDate(new Date());
                }
                for (SleepLog sleepLog : sleepLogList) {
                    sleepLog.setSendDate(new Date());
                }
                for (HealthLog healthLog : healthLogList) {
                    healthLog.setSendDate(new Date());
                }
                update(false);
                message = new FacesMessage(FacesMessage.SEVERITY_INFO, LocaleBean.getBundle().getString("Ztreamy"), LocaleBean.getBundle().getString("ZtreamySendOK"));
            } else {
                message = new FacesMessage(FacesMessage.SEVERITY_ERROR, LocaleBean.getBundle().getString("Error"), LocaleBean.getBundle().getString("Ztreamy.error"));
            }
        } catch (MalformedURLException ex) {
            message = new FacesMessage(FacesMessage.SEVERITY_ERROR, LocaleBean.getBundle().getString("Error"), LocaleBean.getBundle().getString("Ztreamy.error.Url"));
            log.log(Level.SEVERE, "sendToZtreamy() - Error en la URL", ex);
        } catch (IOException ex) {
            message = new FacesMessage(FacesMessage.SEVERITY_ERROR, LocaleBean.getBundle().getString("Error"), LocaleBean.getBundle().getString("Ztreamy.error"));
            log.log(Level.SEVERE, "sendToZtreamy() - Error de I/O", ex);
        } catch (HermesException ex) {
            message = new FacesMessage(FacesMessage.SEVERITY_ERROR, LocaleBean.getBundle().getString("Error"), ex.getMessage());
            log.log(Level.SEVERE, "sendToZtreamy() - Error al enviar");
        }

        FacesContext.getCurrentInstance().addMessage("messages", message);
    }

    public String getAggregation() {
        // La agregación por defecto, será 'Horas'.
        if (aggregation == null) {
            aggregation = Constants.TimeAggregations.Hours.toString();
        }
        return aggregation;
    }

    public void setAggregation(String aggregation) {
        this.aggregation = aggregation;
    }

    public void initChartDates() {
        if (selected != null) {
            // Situaremos el gráfico en los últimos datos sincronizados del usuario.
            LocalDate start = new LocalDate(selected.getLastFitbitSynchronization());
            start = start.dayOfMonth().withMinimumValue();
            startDate = start.toDate();
            LocalDate end = new LocalDate(start);
            end = end.dayOfMonth().withMaximumValue();
            endDate = end.toDate();
        }
    }

    public LineChartModel getStepsLogLineChartModel() {
        return stepsLogLineChartModel;
    }

    private void initStepsLogLineChartModel() {
        if (selected != null) {
            // Para el gráfico de actividad de la persona daremos los siguientes parámetros:
            // - Fecha de inicio...: Primer día del mes actual
            // - Fecha de fin......: Último día del mes actual
            // - Agregación........: Por días
            if (startDate == null && endDate == null) {
                Calendar cal = Calendar.getInstance();

                cal.set(Calendar.DATE, cal.getActualMinimum(Calendar.DATE));
                startDate = cal.getTime();

                cal.set(Calendar.DATE, cal.getActualMaximum(Calendar.DATE));
                endDate = cal.getTime();
            }

            chartMonthActivityLogList = selected.getActivityLogList(startDate, endDate, Constants.TimeAggregations.Days.toString());

            LinkedHashMap<Date, Integer> values = new LinkedHashMap();
            if (chartMonthActivityLogList != null && !chartMonthActivityLogList.isEmpty()) {
                for (ActivityLog activityLog : chartMonthActivityLogList) {
                    values.put(activityLog.getDateLog(), activityLog.getTotal());
                }
            }

            LinkedHashMap<Date, Integer> filledValues = (LinkedHashMap<Date, Integer>) fillEmptyDays(values);

            stepsLogLineChartModel = new LineChartModel();
            LineChartSeries series = new LineChartSeries();

            // Rellenamos la serie con las fechas y los totales de pasos.
            for (Date key : filledValues.keySet()) {
                series.set(key.getTime(), filledValues.get(key) != null ? filledValues.get(key) : 0);
            }

            // Indicamos el texto de la leyenda.
            series.setLabel(LocaleBean.getBundle().getString("Steps"));

            stepsLogLineChartModel.setTitle(Constants.df.format(startDate) + " - " + Constants.df.format(endDate));
            stepsLogLineChartModel.setLegendPosition("ne");
            stepsLogLineChartModel.setShowPointLabels(true);
            stepsLogLineChartModel.setShowDatatip(true);
            stepsLogLineChartModel.setMouseoverHighlight(true);
            stepsLogLineChartModel.setDatatipFormat("%1$s -> %2$d");
            stepsLogLineChartModel.setSeriesColors("2DC800");
            stepsLogLineChartModel.setAnimate(true);
            stepsLogLineChartModel.setZoom(true);

            DateAxis xAxis = new DateAxis(LocaleBean.getBundle().getString("Days"));
            xAxis.setTickAngle(-45);
            xAxis.setTickFormat("%d/%m/%Y");
            stepsLogLineChartModel.getAxes().put(AxisType.X, xAxis);

            Axis yAxis = stepsLogLineChartModel.getAxis(AxisType.Y);
            yAxis.setLabel(LocaleBean.getBundle().getString("Steps"));
            yAxis.setMin(0);

            if (!series.getData().isEmpty()) {
                stepsLogLineChartModel.addSeries(series);
            }
        }
    }

    private void initSleepLogChartModel() {
        if (selected != null) {
            // Para el gráfico de sueño de la persona daremos los siguientes parámetros:
            // - Fecha de inicio...: Primer día del mes actual
            // - Fecha de fin......: Último día del mes actual
            // - Agregación........: Por días
            if (startDate == null && endDate == null) {
                Calendar cal = Calendar.getInstance();

                cal.set(Calendar.DATE, cal.getActualMinimum(Calendar.DATE));
                startDate = cal.getTime();

                cal.set(Calendar.DATE, cal.getActualMaximum(Calendar.DATE));
                endDate = cal.getTime();
            }

            List<SleepLog> sleepLogList = selected.getSleepLogList(startDate, endDate);

            LinkedHashMap<Date, SleepLog> values = new LinkedHashMap();
            if (sleepLogList != null && !sleepLogList.isEmpty()) {
                for (SleepLog sleepLog : sleepLogList) {
                    values.put(sleepLog.getDateLog(), sleepLog);
                }
            }

            LinkedHashMap<Date, SleepLog> filledValues = (LinkedHashMap<Date, SleepLog>) fillEmptyDays(values);
            sleepLogBarChartModel = new BarChartModel();

            BarChartSeries hoursAsleepSeries = new BarChartSeries();
            BarChartSeries hoursInBedSeries = new BarChartSeries();
            BarChartSeries awakeningsSeries = new BarChartSeries();

            ChartSeries startHourSeries = new ChartSeries();
            ChartSeries startMinuteSeries = new ChartSeries();
            ChartSeries endHourSeries = new ChartSeries();
            ChartSeries endMinuteSeries = new ChartSeries();
            // Rellenamos la serie con las fechas y los datos de sueño.
            for (Date key : filledValues.keySet()) {
                SleepLog value = filledValues.get(key);
                String onlyDate = Constants.df.format(key);
                if (value != null) {
                    hoursAsleepSeries.set(onlyDate, value.getMinutesAsleep() / 60.0f);
                    hoursInBedSeries.set(onlyDate, value.getMinutesInBed() / 60.0f);
                    awakeningsSeries.set(onlyDate, (float) value.getAwakenings());
                    LocalTime lts = new LocalTime(value.getStartTime());
                    startHourSeries.set(onlyDate, lts.getHourOfDay());
                    startMinuteSeries.set(onlyDate, lts.getMinuteOfHour());
                    LocalTime lte = new LocalTime(value.getEndTime());
                    endHourSeries.set(onlyDate, lte.getHourOfDay());
                    endMinuteSeries.set(onlyDate, lte.getMinuteOfHour());
                } else {
                    hoursAsleepSeries.set(onlyDate, 0.0f);
                    hoursInBedSeries.set(onlyDate, 0.0f);
                    awakeningsSeries.set(onlyDate, 0.0f);
                    startHourSeries.set(onlyDate, 0);
                    startMinuteSeries.set(onlyDate, 0);
                    endHourSeries.set(onlyDate, 0);
                    endMinuteSeries.set(onlyDate, 0);
                }
            }

            // Indicamos el texto de la leyenda.
            hoursAsleepSeries.setLabel(LocaleBean.getBundle().getString("HoursAsleep"));
            hoursInBedSeries.setLabel(LocaleBean.getBundle().getString("HoursInBed"));
            awakeningsSeries.setLabel(LocaleBean.getBundle().getString("Awakenings"));

            sleepLogBarChartModel.setTitle(Constants.df.format(startDate) + " - " + Constants.df.format(endDate));
            sleepLogBarChartModel.setLegendPosition("ne");
            sleepLogBarChartModel.setShowPointLabels(true);
            sleepLogBarChartModel.setShowDatatip(true);
            sleepLogBarChartModel.setMouseoverHighlight(true);
//        model.setDatatipFormat("%1$s -> %2$d");
//        model.setSeriesColors("FFB347, B19CD9, 77DD77, FF6961");
//        model.setDatatipFormat("%2$#.1f");
            sleepLogBarChartModel.setSeriesColors("DDF9D9, FFE5BC, F6CDE6");
            sleepLogBarChartModel.setAnimate(true);
            sleepLogBarChartModel.setShadow(false);
            sleepLogBarChartModel.setZoom(false);
            sleepLogBarChartModel.setLegendRows(1);
            sleepLogBarChartModel.setLegendCols(3);

            // Eje de abscisas.
            CategoryAxis xAxis = new CategoryAxis(LocaleBean.getBundle().getString("Days"));
            xAxis.setTickAngle(90);
            xAxis.setTickFormat("%d/%m/%Y");
            sleepLogBarChartModel.getAxes().put(AxisType.X, xAxis);

            Axis yAxis = sleepLogBarChartModel.getAxis(AxisType.Y);
            yAxis.setMin(0.0f);

            // FIXME: Arreglar, si interesa, cuando se corrija el bug de Primefaces que hace que no se pueda usar
            // DateAxis en un BarChart. La ventaja de poder usar DateAxis es que se podría hacer zoom en el gráfico.
//        DateAxis xAxis = new DateAxis(LocaleBean.getBundle().getString("Days"));
//        xAxis.setMin(Constants.dfFitbit.format(minimumDate));
//        xAxis.setMax(Constants.dfFitbit.format(maximumDate));
//        xAxis.setTickAngle(90);
//        xAxis.setTickFormat("%d/%m/%Y");
//        model.getAxes().put(AxisType.X, xAxis);
            if (!hoursAsleepSeries.getData().isEmpty()) {
                sleepLogBarChartModel.addSeries(hoursAsleepSeries);
            }
            if (!hoursInBedSeries.getData().isEmpty()) {
                sleepLogBarChartModel.addSeries(hoursInBedSeries);
            }
            if (!awakeningsSeries.getData().isEmpty()) {
                sleepLogBarChartModel.addSeries(awakeningsSeries);
            }
            if (!startHourSeries.getData().isEmpty()) {
                sleepLogBarChartModel.addSeries(startHourSeries);
            }
            if (!startMinuteSeries.getData().isEmpty()) {
                sleepLogBarChartModel.addSeries(startMinuteSeries);
            }
            if (!endHourSeries.getData().isEmpty()) {
                sleepLogBarChartModel.addSeries(endHourSeries);
            }
            if (!endMinuteSeries.getData().isEmpty()) {
                sleepLogBarChartModel.addSeries(endMinuteSeries);
            }

            // JYFR: Extensión para gráficos. Así podemos cambiar más características. Ver las opciones en la web de 'jqPlot'.
            sleepLogBarChartModel.setExtender("customSleepMonthChartExtender");
        }
    }

    public BarChartModel getSleepLogBarChartModel() {
        return sleepLogBarChartModel;
    }

    private LineChartModel getHeartRateLineModel(LinkedHashMap<Date, Integer> values, String title) {
        LineChartModel model = new LineChartModel();
        LineChartSeries series = new LineChartSeries();

        // Rellenamos la serie con las fechas y los ritmos cardíacos medios.
        for (Date key : values.keySet()) {
            series.set(key.getTime(), values.get(key) != null ? values.get(key) : 0);
        }

        // Indicamos el texto de la leyenda.
        series.setLabel(LocaleBean.getBundle().getString("HeartRate"));

        model.setTitle(title);
        model.setLegendPosition("ne");
        model.setShowPointLabels(true);
        model.setShowDatatip(true);
        model.setMouseoverHighlight(true);
        model.setDatatipFormat("%1$s -> %2$d");
        model.setSeriesColors("FF392E");
        model.setAnimate(true);
        model.setZoom(true);

        DateAxis xAxis = new DateAxis(LocaleBean.getBundle().getString("Days"));
        xAxis.setTickAngle(-45);
        xAxis.setTickFormat("%d/%m/%Y");
        model.getAxes().put(AxisType.X, xAxis);

        Axis yAxis = model.getAxis(AxisType.Y);
        yAxis.setLabel(LocaleBean.getBundle().getString("AverageHeartRate"));
        yAxis.setMin(0);

        if (!series.getData().isEmpty()) {
            model.addSeries(series);
        }

        return model;
    }

    private void initHeartRateChartModel() {
        if (selected != null) {
            // Para el gráfico de ritmo cardíaco de la persona daremos los siguientes parámetros:
            // - Fecha de inicio...: Primer día del mes actual
            // - Fecha de fin......: Último día del mes actual
            // - Agregación........: Por días
            if (startDate == null && endDate == null) {
                Calendar cal = Calendar.getInstance();

                cal.set(Calendar.DATE, cal.getActualMinimum(Calendar.DATE));
                startDate = cal.getTime();

                cal.set(Calendar.DATE, cal.getActualMaximum(Calendar.DATE));
                endDate = cal.getTime();
            }

            chartMonthHealthLogList = selected.getHealthLogList(startDate, endDate, Constants.TimeAggregations.Days.toString());

            LinkedHashMap<Date, Integer> values = new LinkedHashMap();
            if (chartMonthHealthLogList != null && !chartMonthHealthLogList.isEmpty()) {
                for (HealthLog healthLog : chartMonthHealthLogList) {
                    values.put(healthLog.getDateLog(), healthLog.getAverage());
                }
            }

            heartLogLineChartModel = getHeartRateLineModel((LinkedHashMap<Date, Integer>) fillEmptyDays(values), Constants.df.format(startDate) + " - " + Constants.df.format(endDate));
        }
    }

    public LineChartModel getHeartLogLineChartModel() {
        return heartLogLineChartModel;
    }

    public LineChartModel getHeartRateDayChartModel() {
        if (selectedHealthLog != null) {

            LinkedHashMap<Date, Integer> values = new LinkedHashMap();
            if (selectedHealthLog.getHeartLogList() != null && !selectedHealthLog.getHeartLogList().isEmpty()) {
                for (HeartLog heartLog : selectedHealthLog.getHeartLogList()) {
                    values.put(heartLog.getTimeLog(), heartLog.getRate());
                }
            }

            return getHeartRateLineModel(values, Constants.df.format(selectedHealthLog.getDateLog()));
        }

        return null;
    }

    private LinkedHashMap<Date, ?> fillEmptyDays(LinkedHashMap<Date, ?> values) {
        LocalDate start = new LocalDate(startDate);
        LocalDate end = new LocalDate(endDate);
        int days = Days.daysBetween(start, end).getDays();

        if (values.size() < days) {
            LocalDate tempDate = new LocalDate(startDate);

            for (int i = 0; i <= days; i++) {
                if (values.get(tempDate.toDate()) == null) {
                    values.put(tempDate.toDate(), null);
                }
                tempDate = tempDate.plusDays(1);
            }
        }

        TreeMap<Date, ?> treeMap = new TreeMap<>(values);

        LinkedHashMap tempLinkedHashMap = new LinkedHashMap<>();

        for (Map.Entry<Date, ?> entry : treeMap.entrySet()) {
            tempLinkedHashMap.put(entry.getKey(), entry.getValue());
        }

        return tempLinkedHashMap;
    }

//    public BarChartModel getSessionsBarChartModel() {
//        if (selected != null) {
//            // Para el gráfico de sesiones de la persona daremos los siguientes parámetros:
//            // - Fecha de inicio...: Primer día de la semana
//            // - Fecha de fin......: Último día de la semana
//            // - Agregación........: Por días
//            if (startDate == null && endDate == null) {
//                LocalDate localDate = new LocalDate();
//                startDate = localDate.withDayOfWeek(DateTimeConstants.MONDAY).toDate();
//                endDate = localDate.withDayOfWeek(DateTimeConstants.SUNDAY).toDate();
//            }
//            
//            LocalDate start = new LocalDate(startDate);
//            LocalDate end = new LocalDate(endDate);
//            int days = Days.daysBetween(start, end).getDays();
//
//            List<ActivityLog> weekActivityList = selected.getActivityLogList(startDate, endDate, Constants.TimeAggregations.Days.toString());
//
//            LinkedHashMap<Date, Integer> values = new LinkedHashMap();
//            LinkedHashMap<Date, Integer> sessions = new LinkedHashMap();
//            LinkedHashMap<Date, Integer> continuousSteps = new LinkedHashMap();
//            
//            if (weekActivityList != null && weekActivityList.size() > 0) {
//                for (ActivityLog activityLog : weekActivityList) {
//                    values.put(activityLog.getDateLog(), activityLog.getTotal());
//                    continuousSteps.put(activityLog.getDateLog(), activityLog.getSessionsContinuousStepsTotal());
//                    sessions.put(activityLog.getDateLog(), activityLog.getSessionsTotal());
//                }
//            }
//            
//            if (values.size() < days) {
//                LocalDate tempDate;
//
//                if (values.isEmpty()) {
//                    tempDate = new LocalDate(start.plusDays(-1));
//                } else {
//                    tempDate = new LocalDate(values.keySet().iterator().next());
//                }
//
//                int total = days - values.size();
//                
//                // No hay datos para todo el rango indicado. Para evitar que no se pueda representar, añadimos datos con 0 en los restantes.
//                for (int i = 0; i <= total; i++) {
//                    tempDate = tempDate.plusDays(1);
//                    values.put(tempDate.toDate(), 0);
//                    continuousSteps.put(tempDate.toDate(), 0);
//                    sessions.put(tempDate.toDate(), 0);
//                }
//            }
//
//            return selected.getSessionsBarChartModel(values, continuousSteps, sessions, Constants.df.format(startDate) + " - " + Constants.df.format(endDate));
//        }
//
//        return null;
//    }
    public BarChartModel getSessionsBarChartModel() {
        if (selected != null) {
            // Para el gráfico de sesiones de la persona daremos los siguientes parámetros:
            // - Fecha de inicio...: Primer día de la semana
            // - Fecha de fin......: Último día de la semana
            // - Agregación........: Por días
            if (startDate == null && endDate == null) {
                LocalDate localDate = new LocalDate();
                startDate = localDate.withDayOfWeek(DateTimeConstants.MONDAY).toDate();
                endDate = localDate.withDayOfWeek(DateTimeConstants.SUNDAY).toDate();
            }

            LocalDate start = new LocalDate(startDate);
            LocalDate end = new LocalDate(endDate);

            List<ActivityLog> weekActivityList = selected.getActivityLogList(startDate, endDate, Constants.TimeAggregations.Days.toString());

            LinkedHashMap<Date, Integer> activeSessions = new LinkedHashMap();
            LinkedHashMap<String, Integer> activeSessionsSteps = new LinkedHashMap();
            LinkedHashMap<String, Integer> continuousSteps = new LinkedHashMap();

            if (weekActivityList != null && !weekActivityList.isEmpty()) {
                for (ActivityLog activityLog : weekActivityList) {
                    activeSessions.putAll(activityLog.getActiveSessions());
                }
                if (!activeSessions.isEmpty()) {
                    Iterator it = activeSessions.keySet().iterator();
                    Date currentDate = (Date) it.next();
                    DateTime previousDate = new DateTime(currentDate);
                    StringBuilder sb = new StringBuilder(Constants.df.format(currentDate));
                    sb.append(" (");
                    sb.append(Constants.dfTime.format(currentDate));

                    int activeSessionTotalSteps = activeSessions.get(currentDate);
                    int activeSessionNonStopSteps = activeSessions.get(currentDate);
                    int currentNonStopStepsAmount = activeSessions.get(currentDate);
                    boolean inSession = true;
                    while (it.hasNext()) {
                        currentDate = (Date) it.next();
                        DateTime tempCurrentDate = new DateTime(currentDate);

                        if (previousDate.plusMinutes(1).equals(tempCurrentDate)) {
                            if (!inSession) {
                                inSession = true;
                                sb.append(Constants.df.format(previousDate.toDate()));
                                sb.append(" (");
                                sb.append(Constants.dfTime.format(previousDate.toDate()));
                            }
                            int currentSteps = activeSessions.get(currentDate);
                            activeSessionTotalSteps += currentSteps;
                            currentNonStopStepsAmount += currentSteps;
                            if (currentSteps == 0) {
                                if (currentNonStopStepsAmount > activeSessionNonStopSteps) {
                                    activeSessionNonStopSteps = currentNonStopStepsAmount;
                                }
                                currentNonStopStepsAmount = 0;
                            }
                        } else if (inSession) {
                            // Fin de la sesión
                            sb.append(" - ");
                            sb.append(Constants.dfTime.format(previousDate.toDate()));
                            sb.append(")");
                            activeSessionsSteps.put(sb.toString(), activeSessionTotalSteps);
                            continuousSteps.put(sb.toString(), activeSessionNonStopSteps);
                            activeSessionTotalSteps = 0;
                            activeSessionNonStopSteps = 0;
                            currentNonStopStepsAmount = 0;
                            sb.setLength(0);
                            inSession = false;
                        }
                        previousDate = tempCurrentDate;
                    }
                }
            } else {
                LocalDate tempDate = new LocalDate(start.minusDays(1));
                for (int i = 0; i < 7; i++) {
                    tempDate = tempDate.plusDays(1);
                    continuousSteps.put(Constants.df.format(tempDate.toDate()), 0);
                    activeSessionsSteps.put(Constants.df.format(tempDate.toDate()), 0);
                }
            }

            return getSessionsBarChartModel(activeSessionsSteps, continuousSteps, Constants.df.format(startDate) + " - " + Constants.df.format(endDate));
        }

        return null;
    }

    public boolean hasPreviousMonth() {
        LocalDate localDate = new LocalDate(startDate);
        localDate = localDate.plusMonths(-1);
        return selected.getActivityLogList(localDate.dayOfMonth().withMinimumValue().toDate(), localDate.dayOfMonth().withMaximumValue().toDate(), null).isEmpty();
    }

    public boolean hasNextMonth() {
        LocalDate localDate = new LocalDate(startDate);
        localDate = localDate.plusMonths(1);
        return selected.getActivityLogList(localDate.dayOfMonth().withMinimumValue().toDate(), localDate.dayOfMonth().withMaximumValue().toDate(), null).isEmpty();
    }

    public boolean hasPreviousWeek() {
        LocalDate localDate = new LocalDate(startDate);
        localDate = localDate.plusWeeks(-1);
        return selected.getActivityLogList(localDate.dayOfWeek().withMinimumValue().toDate(), localDate.dayOfWeek().withMaximumValue().toDate(), null).isEmpty();
    }

    public boolean hasNextWeek() {
        LocalDate localDate = new LocalDate(startDate);
        localDate = localDate.plusWeeks(1);
        return selected.getActivityLogList(localDate.dayOfMonth().withMinimumValue().toDate(), localDate.dayOfMonth().withMaximumValue().toDate(), null).isEmpty();
    }

    public void previousMonthStepLogChart() {
        addMont(-1);
        initStepsLogLineChartModel();
    }

    public void nextMonthStepLogChart() {
        addMont(1);
        initStepsLogLineChartModel();
    }

    public void previousMonthHeartLogChart() {
        addMont(-1);
        initHeartRateChartModel();
    }

    public void nextMonthHeartLogChart() {
        addMont(1);
        initHeartRateChartModel();
    }

    public void previousMonthSleepLogChart() {
        addMont(-1);
        initSleepLogChartModel();
    }

    public void nextMonthSleepLogChart() {
        addMont(1);
        initSleepLogChartModel();
    }

    public void previousWeekChart() {
        addWeek(-1);
    }

    public void nextWeekChart() {
        addWeek(1);
    }

    public void updateStartDate(SelectEvent selectEvent) {
        Date selectedDate = (Date) selectEvent.getObject();
        if (!selectedDate.after(endDate)) {
            startDate = selectedDate;
        }
        initStepsLogLineChartModel();
    }

    public void updateEndDate(SelectEvent selectEvent) {
        Date selectedDate = (Date) selectEvent.getObject();
        if (!selectedDate.before(startDate)) {
            endDate = selectedDate;
        }
        initStepsLogLineChartModel();
    }

    public int getDateSelector() {
        return dateSelector;
    }

    public void setDateSelector(int dateSelector) {
        this.dateSelector = dateSelector;
    }

    private void addMont(int months) {
        LocalDate start = new LocalDate(startDate);
        start = start.plusMonths(months);
        start = start.dayOfMonth().withMinimumValue();
        startDate = start.toDate();
        LocalDate end = new LocalDate(start);
        end = end.dayOfMonth().withMaximumValue();
        endDate = end.toDate();
    }

    private void addWeek(int weeks) {
        LocalDate start = new LocalDate(startDate);
        start = start.plusWeeks(weeks);
        start = start.dayOfWeek().withMinimumValue();
        startDate = start.toDate();
        LocalDate end = new LocalDate(start);
        end = end.dayOfWeek().withMaximumValue();
        endDate = end.toDate();
    }

    public void itemSelectSessions(ItemSelectEvent event) {
        try {
            selectedActivity = chartMonthActivityLogList.get(event.getItemIndex());
            log.log(Level.INFO, "itemSelectSessions() - Obtener las sesiones del día: {0}", Constants.df.format(selectedActivity.getDateLog()));
            RequestContext.getCurrentInstance().execute("PF('ActivityLogSessionsChartDialog').show()");
        } catch (IndexOutOfBoundsException ex) {
            FacesContext.getCurrentInstance().addMessage("messages", new FacesMessage(FacesMessage.SEVERITY_INFO, LocaleBean.getBundle().getString("NoDataForDate"), null));
            selectedActivity = null;
        }
    }

    public void itemSelectSessions2(ItemSelectEvent event) {
        try {
            selectedActivity = chartMonthActivityLogList.get(event.getItemIndex());
            log.log(Level.INFO, "itemSelectSessions() - Obtener las sesiones del día: {0}", Constants.df.format(selectedActivity.getDateLog()));
        } catch (IndexOutOfBoundsException ex) {
            FacesContext.getCurrentInstance().addMessage("messages", new FacesMessage(FacesMessage.SEVERITY_INFO, LocaleBean.getBundle().getString("NoDataForDate"), null));
            selectedActivity = null;
        }
    }

    public void itemSelectHeartLog(ItemSelectEvent event) {
        try {
            selectedHealthLog = chartMonthHealthLogList.get(event.getItemIndex());
            log.log(Level.INFO, "itemSelectHeartLog() - Obtener el detalle del ritmo cardíaco del día: {0}", Constants.df.format(selectedHealthLog.getDateLog()));
            RequestContext.getCurrentInstance().execute("PF('HeartRateDayChartDlg').show()");
        } catch (IndexOutOfBoundsException ex) {
            FacesContext.getCurrentInstance().addMessage("messages", new FacesMessage(FacesMessage.SEVERITY_INFO, LocaleBean.getBundle().getString("NoDataForDate"), null));
            selectedHealthLog = null;
        }
    }

    public ActivityLog getSelectedActivity() {
        return selectedActivity;
    }

    public HealthLog getSelectedHealthLog() {
        return selectedHealthLog;
    }

    public LineChartModel getActivityLogSessionsChartModel() {
        if (selectedActivity != null) {
            selectedActivity.calculateSessions(false);
            return selectedActivity.getAreaModel(Constants.df.format(selectedActivity.getDateLog()));
        }

        return null;
    }

    public LineChartModel getActivityLogLineChartModel() {
        if (selectedActivity != null) {
            selectedActivity.setAggregation(Constants.TimeAggregations.Minutes.toString());
            return selectedActivity.getLineModel(Constants.df.format(selectedActivity.getDateLog()));
        }

        return null;
    }

    public LineChartModel getActivityLogDayLineChartModel() {
        if (selectedActivity != null) {
            return selectedActivity.getLineModel(Constants.df.format(selectedActivity.getDateLog()));
        }

        return null;
    }

    public void onRowSelect(SelectEvent event) throws IOException {
        activityLogController.initListFromPerson(selected.getPersonId());
        ExternalContext ec = FacesContext.getCurrentInstance().getExternalContext();
        ec.redirect(ec.getRequestContextPath() + "/faces/secured/activityLog/List.xhtml");

        // JYFR - Otra alternativa, pero mejor usar la de arriba.
//        FacesContext fc = FacesContext.getCurrentInstance();
//        NavigationHandler nh = fc.getApplication().getNavigationHandler();
//        nh.handleNavigation(fc, null, "/faces/secured/activityLog/List.xhtml?faces-redirect=true");
    }

    public void onPathSelect(OverlaySelectEvent event) {
        // TODO: Mostrar un globo emergente con los datos de esa posición.
        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_INFO, "Se ha pulsado en la ruta", null));
    }

    public boolean isAllowNewUsers() {
        String allowed = Constants.getConfigurationValueByKey("AllowNewUsers");
        return allowed != null ? Boolean.valueOf(allowed) : false;
    }

    public int getFitbitNeededRequestsBetweenDates() {
        return hermesFitbitController != null ? hermesFitbitController.getNeededRequestsBetweenDates(startDate, endDate) : 0;
    }

    @Override
    public Person getPerson() {
        return getSelected();
    }

    @Override
    public void updatePerson() {
        update(false);

    }

    @FacesConverter(forClass = Person.class)
    public static class PersonControllerConverter implements Converter {

        @Override
        public Object getAsObject(FacesContext facesContext, UIComponent component, String value) {
            if (value == null || value.length() == 0) {
                return null;
            }
            PersonController controller = (PersonController) facesContext.getApplication().getELResolver().
                    getValue(facesContext.getELContext(), null, "personController");
            return controller.getPerson(getKey(value));
        }

        java.lang.Integer getKey(String value) {
            java.lang.Integer key;
            key = Integer.valueOf(value);
            return key;
        }

        String getStringKey(java.lang.Integer value) {
            StringBuilder sb = new StringBuilder();
            sb.append(value);
            return sb.toString();
        }

        @Override
        public String getAsString(FacesContext facesContext, UIComponent component, Object object) {
            if (object == null) {
                return null;
            }
            if (object instanceof Person) {
                Person o = (Person) object;
                return getStringKey(o.getPersonId());
            } else {
                log.log(Level.SEVERE, "object {0} is of type {1}; expected type: {2}", new Object[]{object, object.getClass().getName(), Person.class.getName()});
                return null;
            }
        }

    }

    public int getFitbitRemainingRequests() {
        return (hermesFitbitController != null && selected != null) ? FitbitResetRequestsScheduledTask.getRemainingRequests(selected.getFitbitUserId()) : 0;
    }

    public int getFitbitRemainingRequestsAsDays() {
        return (getFitbitRemainingRequests() / Constants.FitbitServices.values().length) - 1;
    }

    public Date getSleepStart() {

        return new Date();
    }

    private BarChartModel getSessionsBarChartModel(LinkedHashMap<String, Integer> activeSessionsSteps, LinkedHashMap<String, Integer> continuousSteps, String title) {
        BarChartModel model = new BarChartModel();
        ChartSeries sessionsStepsSeries = new ChartSeries();
        ChartSeries continuousStepsSeries = new ChartSeries();

        // TODO: Ver si quitamos los parámetros para coger los datos directamente de la clase.
        // TODO: Poner/quitar 'interface' para gráficos de barras.
        // TODO: Si pasamos los 'hashmaps', que tengan el formato necesario para representarlos y así no hay que hacer estos 3 bucles for.
        // Rellenamos la serie con las fechas y los totales de pasos de la sesión.
        for (Map.Entry<String, Integer> entry : activeSessionsSteps.entrySet()) {
            // FIXME: Cuando arreglen el bug en Primefaces
//            stepsSeries.set(entry.getKey().getTime(), entry.getValue());
            sessionsStepsSeries.set(entry.getKey(), entry.getValue() != null ? entry.getValue() : 0);
        }

        // Rellenamos la serie con las fechas y los pasos en continuo de las sesiones.
        for (Map.Entry<String, Integer> entry : continuousSteps.entrySet()) {
            // FIXME: Cuando arreglen el bug en Primefaces
//            continuousStepsSeries.set(entry.getKey().getTime(), entry.getValue());
            continuousStepsSeries.set(entry.getKey(), entry.getValue() != null ? entry.getValue() : 0);
        }

        // Indicamos el texto de la leyenda.
        sessionsStepsSeries.setLabel(LocaleBean.getBundle().getString("StepsPerSession"));
        continuousStepsSeries.setLabel(LocaleBean.getBundle().getString("ContinuousSteps"));

        model.setTitle(title);
        model.setLegendPosition("ne");
        model.setShowPointLabels(true);
        model.setShowDatatip(true);
        model.setMouseoverHighlight(false);
//        model.setDatatipFormat("%1$s -> %2$d");
        model.setSeriesColors("AEC6CF, FFB347");
        model.setAnimate(true);
        model.setZoom(false);
//        model.setStacked(true);

        // FIXME: No funciona por un bug de Primefaces. Está marcado como registrado el 16 de abril de 2015.
//        DateAxis xAxis = new DateAxis(LocaleBean.getBundle().getString("Days"));
//        xAxis.setTickAngle(-45);
//        xAxis.setTickFormat("%d/%m/%Y");
//        model.getAxes().put(AxisType.X, xAxis);
        CategoryAxis xAxis = new CategoryAxis(LocaleBean.getBundle().getString("Days"));
        xAxis.setTickAngle(90);
//        xAxis.setTickFormat("%d/%m/%Y");
        model.getAxes().put(AxisType.X, xAxis);

        Axis yAxis = model.getAxis(AxisType.Y);
        yAxis.setLabel(LocaleBean.getBundle().getString("Steps"));
        yAxis.setMin(0);

        if (!sessionsStepsSeries.getData().isEmpty()) {
            model.addSeries(sessionsStepsSeries);
        }
        if (!continuousStepsSeries.getData().isEmpty()) {
            model.addSeries(continuousStepsSeries);
        }

        return model;
    }
}
