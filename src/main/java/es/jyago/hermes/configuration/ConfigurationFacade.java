/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package es.jyago.hermes.configuration;

import es.jyago.hermes.AbstractFacade;
import javax.ejb.Stateless;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

/**
 *
 * @author Jorge Yago
 */
@Stateless
public class ConfigurationFacade extends AbstractFacade<Configuration> {

    @PersistenceContext(unitName = "HermesWeb_PU")
    private EntityManager em;

    @Override
    public EntityManager getEntityManager() {
        return em;
    }

    public ConfigurationFacade() {
        super(Configuration.class);
    }

}