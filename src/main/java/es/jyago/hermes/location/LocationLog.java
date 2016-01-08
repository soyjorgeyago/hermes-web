/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package es.jyago.hermes.location;

import es.jyago.hermes.location.detail.LocationLogDetail;
import es.jyago.hermes.person.Person;
import es.jyago.hermes.util.Constants;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.ResourceBundle;
import javax.persistence.Basic;
import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.OneToMany;
import javax.persistence.OrderBy;
import javax.persistence.PostLoad;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;
import javax.persistence.Transient;
import javax.validation.constraints.NotNull;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;
import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;

/**
 *
 * @author Jorge Yago
 */
@Entity
@Table(name = "location_log")
@XmlRootElement
@NamedQueries({
    @NamedQuery(name = "LocationLog.findAll", query = "SELECT l FROM LocationLog l"),
    @NamedQuery(name = "LocationLog.findByLocationLogId", query = "SELECT l FROM LocationLog l WHERE l.locationLogId = :locationLogId"),
    @NamedQuery(name = "LocationLog.findByDateLog", query = "SELECT l FROM LocationLog l WHERE l.dateLog = :dateLog"),
    @NamedQuery(name = "LocationLog.findByPerson", query = "SELECT l FROM LocationLog l WHERE l.person.personId = :personId")})
public class LocationLog implements Serializable {

    private static final long serialVersionUID = 1L;
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Basic(optional = false)
    @Column(name = "location_log_id")
    private Integer locationLogId;
    @Basic(optional = false)
    @NotNull
    @Column(name = "date_log")
    @Temporal(TemporalType.DATE)
    private Date dateLog;
    @JoinColumn(name = "person_id", referencedColumnName = "person_id")
    @ManyToOne(optional = false)
    private Person person;
    @OneToMany(cascade = CascadeType.ALL, mappedBy = "locationLog", orphanRemoval = true)
    @OrderBy("timeLog ASC")
    private List<LocationLogDetail> locationLogDetailList;
    @Basic(optional = false)
    @NotNull
    @Column(name = "filename")
    private String filename;

    @Transient
    private LocationLogDetail maximumSpeedLocation;
    @Transient
    private LocationLogDetail minimumHeartRateLocation;
    @Transient
    private LocationLogDetail maximumHeartRateLocation;

    @Transient
    private int minHeartRate;
    @Transient
    private int maxHeartRate;
    @Transient
    private double minSpeed;
    @Transient
    private double maxSpeed;

    public LocationLog() {
        locationLogDetailList = new ArrayList<>();
        maximumSpeedLocation = new LocationLogDetail();
        maximumHeartRateLocation = new LocationLogDetail();
        minimumHeartRateLocation = new LocationLogDetail();
    }

    // JYFR: Método que será invocado automáticamente tras cargar los datos de la base de datos y de ser inyectados en los atributos correspondientes.
    @PostLoad
    private void init() {
        findMaxMinValues();
    }

    public Integer getLocationLogId() {
        return locationLogId;
    }

    public void setLocationLogId(Integer locationLogId) {
        this.locationLogId = locationLogId;
    }

    public Date getDateLog() {
        return dateLog;
    }

    public void setDateLog(Date dateLog) {
        this.dateLog = dateLog;
    }

    public Person getPerson() {
        return person;
    }

    public void setPerson(Person person) {
        this.person = person;
    }

    public int getMinHeartRate() {
        return minHeartRate;
    }

    public void setMinHeartRate(int minHeartRate) {
        this.minHeartRate = minHeartRate;
    }

    public int getMaxHeartRate() {
        return maxHeartRate;
    }

    public void setMaxHeartRate(int maxHeartRate) {
        this.maxHeartRate = maxHeartRate;
    }

    public double getMinSpeed() {
        return minSpeed;
    }

    public void setMinSpeed(double minSpeed) {
        this.minSpeed = minSpeed;
    }

    public double getMaxSpeed() {
        return maxSpeed;
    }

    public void setMaxSpeed(double maxSpeed) {
        this.maxSpeed = maxSpeed;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    @XmlTransient
    public List<LocationLogDetail> getLocationLogDetailList() {
        return locationLogDetailList;
    }

    public void setLocationLogDetailList(List<LocationLogDetail> locationLogDetailList) {
        this.locationLogDetailList = locationLogDetailList;
    }

    public LocationLogDetail getMaximumHeartRateLocation() {
        return maximumHeartRateLocation;
    }

    public LocationLogDetail getMinimumHeartRateLocation() {
        return minimumHeartRateLocation;
    }

    public LocationLogDetail getMaximumSpeedLocation() {
        return maximumSpeedLocation;
    }

//    public boolean isShowMaximumSpeedLocation() {
//        return showMaximumSpeedLocation;
//    }
//
//    public void setShowMaximumSpeedLocation(boolean showMaximumSpeedLocation) {
//        this.showMaximumSpeedLocation = showMaximumSpeedLocation;
//        marker = maximumSpeedMarker;
//        maximumSpeedMarker.setVisible(showMaximumSpeedLocation);
//    }
//
//    public boolean isShowMinimumHeartRateLocation() {
//        return showMinimumHeartRateLocation;
//    }
//
//    public void setShowMinimumHeartRateLocation(boolean showMinimumHeartRateLocation) {
//        this.showMinimumHeartRateLocation = showMinimumHeartRateLocation;
//        marker = minimumHeartRateMarker;
//        minimumHeartRateMarker.setVisible(showMinimumHeartRateLocation);
//    }
//
//    public boolean isShowMaximumHeartRateLocation() {
//        return showMaximumHeartRateLocation;
//    }
//
//    public void setShowMaximumHeartRateLocation(boolean showMaximumHeartRateLocation) {
//        this.showMaximumHeartRateLocation = showMaximumHeartRateLocation;
//        marker = maximumHeartRateMarker;
//        maximumHeartRateMarker.setVisible(showMaximumHeartRateLocation);
//    }
//
//    public Marker getMaximumSpeedMarker() {
//        return maximumSpeedMarker;
//    }
//
//    public Marker getMinimumHeartRateMarker() {
//        return minimumHeartRateMarker;
//    }
//
//    public Marker getMaximumHeartRateMarker() {
//        return maximumHeartRateMarker;
//    }
    private void findMaxMinValues() {

        if (locationLogDetailList != null && !locationLogDetailList.isEmpty()) {
            // Para descartar los valores 0 en el ritmo cardíaco que llegan de la aplicación de SmartDriver,
            // establecemos unas pulsaciones mínimas imposibles para un ser humano.
            minimumHeartRateLocation = locationLogDetailList.get(0);
            // FIXME: No vale esto si no usamos un clone().
//            minimumHeartRateLocation.setHeartRate(500);
            maximumHeartRateLocation = locationLogDetailList.get(0);
            maximumSpeedLocation = locationLogDetailList.get(0);

            for (LocationLogDetail detail : locationLogDetailList) {
                // Debemos descartar los valores 0 en el ritmo cardíaco que llegan de la aplicación de SmartDriver.
                if (detail.getHeartRate() > 0 && detail.getHeartRate() < minimumHeartRateLocation.getHeartRate()) {
                    minimumHeartRateLocation = detail;
                }
                if (detail.getHeartRate() > maximumHeartRateLocation.getHeartRate()) {
                    maximumHeartRateLocation = detail;
                }
                if (detail.getSpeed() > maximumSpeedLocation.getSpeed()) {
                    maximumSpeedLocation = detail;
                }
            }
        }
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder(19, 29).
                append(dateLog).
                toHashCode();
    }

    @Override
    public boolean equals(Object object) {
        if (!(object instanceof LocationLog)) {
            return false;
        }
        LocationLog other = (LocationLog) object;

        // Dos elementos serán iguales si tienen el mismo id.
        return new EqualsBuilder().
                append(this.locationLogId, other.locationLogId).
                isEquals();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append("[")
                .append(Constants.df.format(this.dateLog))
                .append(", ")
                .append(this.filename)
                .append(" ->");
        if (maximumHeartRateLocation != null) {
            sb.append(" ")
                    .append(ResourceBundle.getBundle("/Bundle").getString("MaximumHeartRate"))
                    .append(": ")
                    .append(maximumHeartRateLocation.getHeartRate());
        }
        if (minimumHeartRateLocation != null) {
            sb.append(" ")
                    .append(ResourceBundle.getBundle("/Bundle").getString("MinimumHeartRate"))
                    .append(": ")
                    .append(minimumHeartRateLocation.getHeartRate());
        }
        if (maximumSpeedLocation != null) {
            sb.append(" ")
                    .append(ResourceBundle.getBundle("/Bundle").getString("MaximumSpeed"))
                    .append(": ")
                    .append(maximumSpeedLocation.getHeartRate());
        }
        sb.append("]");

        return sb.toString();
    }

    public String getDateTimeStart() {
        StringBuilder sb = new StringBuilder();
        if (dateLog != null) {
            sb.append(Constants.df.format(dateLog));
            if (locationLogDetailList != null && !locationLogDetailList.isEmpty()) {
                sb.append(" - ").append(Constants.dfTime.format(locationLogDetailList.get(0).getTimeLog()));
            }
        }
        return sb.toString();
    }
}
