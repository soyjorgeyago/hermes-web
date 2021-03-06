package es.jyago.hermes.configuration;

import es.jyago.hermes.person.configuration.PersonConfiguration;
import java.io.Serializable;
import java.util.Collection;
import javax.persistence.Basic;
import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.OneToMany;
import javax.persistence.Table;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;
import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;


@Entity
@Table(name = "configuration")
@XmlRootElement
@NamedQueries({
    @NamedQuery(name = "Configuration.findAll", query = "SELECT c FROM Configuration c"),
    @NamedQuery(name = "Configuration.findByOptionKey", query = "SELECT c FROM Configuration c WHERE c.optionKey = :optionKey")})
public class Configuration implements Serializable {

    private static final long serialVersionUID = 1L;
    @Id
    @Basic(optional = false)
    @NotNull
    @Size(min = 1, max = 50)
    @Column(name = "option_key")
    private String optionKey;
    @Size(max = 100)
    @Column(name = "option_value")
    private String optionValue;
    @OneToMany(cascade = CascadeType.ALL, mappedBy = "option")
    private Collection<PersonConfiguration> personConfigurationCollection;

    public Configuration() {
    }

    public Configuration(String optionKey) {
        this.optionKey = optionKey;
    }

    public Configuration(String optionKey, String optionValue) {
        this.optionKey = optionKey;
        this.optionValue = optionValue;
    }

    public String getOptionKey() {
        return optionKey;
    }

    public void setOptionKey(String optionKey) {
        this.optionKey = optionKey;
    }

    public String getOptionValue() {
        return optionValue;
    }

    public void setOptionValue(String optionValue) {
        this.optionValue = optionValue;
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder(19, 29).
                append(optionKey).
                toHashCode();
    }

    @Override
    public boolean equals(Object object) {
        if (!(object instanceof Configuration)) {
            return false;
        }
        Configuration other = (Configuration) object;

        // Dos elementos serán iguales si tienen el mismo id.
        return new EqualsBuilder().
                append(this.optionKey, other.optionKey).
                isEquals();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append("[");
        sb.append(this.optionKey);
        sb.append(" -> ");
        sb.append(this.optionValue);
        sb.append("]");

        return sb.toString();
    }

    // FIXME: Poner los @XmlTransient en los métodos get de las dependencias, si no los tuviera.
    @XmlTransient
    public Collection<PersonConfiguration> getPersonConfigurationCollection() {
        return personConfigurationCollection;
    }

    public void setPersonConfigurationCollection(Collection<PersonConfiguration> personConfigurationCollection) {
        this.personConfigurationCollection = personConfigurationCollection;
    }

}
