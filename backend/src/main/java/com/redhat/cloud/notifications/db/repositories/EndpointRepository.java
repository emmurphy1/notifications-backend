package com.redhat.cloud.notifications.db.repositories;

import com.redhat.cloud.notifications.config.FeatureFlipper;
import com.redhat.cloud.notifications.db.Query;
import com.redhat.cloud.notifications.db.builder.QueryBuilder;
import com.redhat.cloud.notifications.db.builder.WhereBuilder;
import com.redhat.cloud.notifications.models.CamelProperties;
import com.redhat.cloud.notifications.models.CompositeEndpointType;
import com.redhat.cloud.notifications.models.Endpoint;
import com.redhat.cloud.notifications.models.EndpointProperties;
import com.redhat.cloud.notifications.models.EndpointType;
import com.redhat.cloud.notifications.models.SystemSubscriptionProperties;
import com.redhat.cloud.notifications.models.WebhookProperties;
import io.quarkus.logging.Log;

import javax.annotation.Nullable;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.TypedQuery;
import javax.transaction.Transactional;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.NotFoundException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.redhat.cloud.notifications.models.EndpointStatus.READY;

@ApplicationScoped
public class EndpointRepository {
    @Inject
    EntityManager entityManager;

    @Inject
    FeatureFlipper featureFlipper;

    public void checkEndpointNameDuplicate(Endpoint endpoint) {
        if (!featureFlipper.isEnforceIntegrationNameUnicity()) {
            // Check disabled from configuration
            return;
        }

        if (endpoint.getType() != null && endpoint.getType().isSystemEndpointType) {
            // This check does not apply for email subscriptions - as these are managed by us.
            return;
        }

        String hql = "SELECT COUNT(*) FROM Endpoint WHERE name = :name AND orgId = :orgId";
        if (endpoint.getId() != null) {
            hql += " AND id != :endpointId";
        }

        TypedQuery<Long> query = entityManager.createQuery(hql, Long.class)
                .setParameter("name", endpoint.getName())
                .setParameter("orgId", endpoint.getOrgId());

        if (endpoint.getId() != null) {
            query.setParameter("endpointId", endpoint.getId());
        }

        if (query.getSingleResult() > 0) {
            throw new BadRequestException("An endpoint with name [" + endpoint.getName() + "] already exists");
        }
    }

    @Transactional
    public Endpoint createEndpoint(Endpoint endpoint) {
        checkEndpointNameDuplicate(endpoint);
        entityManager.persist(endpoint);
        // If the endpoint properties are null, they won't be persisted.
        if (endpoint.getProperties() != null) {
            /*
             * As weird as it seems, we need the following line because the Endpoint instance was
             * deserialized from JSON and that JSON did not contain any information about the
             * @OneToOne relation from EndpointProperties to Endpoint.
             */
            endpoint.getProperties().setEndpoint(endpoint);
            switch (endpoint.getType()) {
                case ANSIBLE:
                case CAMEL:
                case WEBHOOK:
                case EMAIL_SUBSCRIPTION:
                case DRAWER:
                    entityManager.persist(endpoint.getProperties());
                default:
                    // Do nothing.
                    break;
            }
        }
        return endpoint;
    }

    public List<Endpoint> getEndpointsPerCompositeType(String orgId, @Nullable String name, Set<CompositeEndpointType> type, Boolean activeOnly, Query limiter) {
        if (limiter != null) {
            limiter.setSortFields(Endpoint.SORT_FIELDS);
        }

        Query.Limit limit = limiter == null ? null : limiter.getLimit();
        Optional<Query.Sort> sort = limiter == null ? Optional.empty() : limiter.getSort();
        List<Endpoint> endpoints = EndpointRepository.queryBuilderEndpointsPerType(orgId, name, type, activeOnly)
                .limit(limit)
                .sort(sort)
                .build(entityManager::createQuery)
                .getResultList();
        loadProperties(endpoints);
        return endpoints;
    }

    public EndpointType getEndpointTypeById(String orgId, UUID endpointId) {
        String query = "Select e.compositeType.type from Endpoint e WHERE e.orgId = :orgId AND e.id = :endpointId";
        try {
            return entityManager.createQuery(query, EndpointType.class)
                    .setParameter("orgId", orgId)
                    .setParameter("endpointId", endpointId)
                    .getSingleResult();
        } catch (NoResultException e) {
            throw new NotFoundException("Endpoint not found");
        }
    }

    @Transactional
    public Endpoint getOrCreateSystemSubscriptionEndpoint(String accountId, String orgId, SystemSubscriptionProperties properties, EndpointType endpointType) {
        String label = "Email";
        if (EndpointType.DRAWER == endpointType) {
            label = "Drawer";
        }
        List<Endpoint> endpoints = getEndpointsPerCompositeType(orgId, null, Set.of(new CompositeEndpointType(endpointType)), null, null);
        loadProperties(endpoints);
        Optional<Endpoint> endpointOptional = endpoints
            .stream()
            .filter(endpoint -> properties.hasSameProperties(endpoint.getProperties(SystemSubscriptionProperties.class)))
            .findFirst();
        if (endpointOptional.isPresent()) {
            return endpointOptional.get();
        }
        Endpoint endpoint = new Endpoint();
        endpoint.setProperties(properties);
        endpoint.setAccountId(accountId);
        endpoint.setOrgId(orgId);
        endpoint.setEnabled(true);
        endpoint.setDescription(String.format("System %s endpoint", label.toLowerCase()));
        endpoint.setName(String.format("%s endpoint", label));
        endpoint.setType(endpointType);
        endpoint.setStatus(READY);

        return createEndpoint(endpoint);
    }

    public Long getEndpointsCountPerCompositeType(String orgId, @Nullable String name, Set<CompositeEndpointType> type, Boolean activeOnly) {
        return EndpointRepository.queryBuilderEndpointsPerType(orgId, name, type, activeOnly)
                .buildCount(entityManager::createQuery)
                .getSingleResult();
    }

    public Endpoint getEndpoint(String orgId, UUID id) {
        String query = "SELECT e FROM Endpoint e WHERE e.orgId = :orgId AND e.id = :id";
        try {
            Endpoint endpoint = entityManager.createQuery(query, Endpoint.class)
                    .setParameter("id", id)
                    .setParameter("orgId", orgId)
                    .getSingleResult();
            loadProperties(endpoint);
            return endpoint;
        } catch (NoResultException e) {
            return null;
        }
    }

    @Transactional
    public boolean deleteEndpoint(String orgId, UUID id) {
        String query = "DELETE FROM Endpoint WHERE orgId = :orgId AND id = :id";
        int rowCount = entityManager.createQuery(query)
                .setParameter("id", id)
                .setParameter("orgId", orgId)
                .executeUpdate();
        return rowCount > 0;
        // Actually, the endpoint targeting this should be repeatable
    }

    public boolean disableEndpoint(String orgId, UUID id) {
        return modifyEndpointStatus(orgId, id, false);
    }

    public boolean enableEndpoint(String orgId, UUID id) {
        return modifyEndpointStatus(orgId, id, true);
    }

    @Transactional
    boolean modifyEndpointStatus(String orgId, UUID id, boolean enabled) {
        String query = "UPDATE Endpoint SET enabled = :enabled, serverErrors = 0 WHERE orgId = :orgId AND id = :id";
        int rowCount = entityManager.createQuery(query)
                .setParameter("id", id)
                .setParameter("orgId", orgId)
                .setParameter("enabled", enabled)
                .executeUpdate();
        return rowCount > 0;
    }

    @Transactional
    public boolean updateEndpoint(Endpoint endpoint) {
        checkEndpointNameDuplicate(endpoint);
        // TODO Update could fail because the item did not exist, throw 404 in that case?
        // TODO Fix transaction so that we don't end up with half the updates applied
        String endpointQuery = "UPDATE Endpoint SET name = :name, description = :description, enabled = :enabled, serverErrors = 0 " +
                "WHERE orgId = :orgId AND id = :id";
        String webhookQuery = "UPDATE WebhookProperties SET url = :url, method = :method, " +
                "disableSslVerification = :disableSslVerification, secretToken = :secretToken WHERE endpoint.id = :endpointId";
        String camelQuery = "UPDATE CamelProperties SET url = :url, extras = :extras, " +
                "basicAuthentication = :basicAuthentication, " +
                "disableSslVerification = :disableSslVerification, secretToken = :secretToken WHERE endpoint.id = :endpointId";

        if (endpoint.getType() != null && endpoint.getType().isSystemEndpointType) {
            throw new RuntimeException("Unable to update a system endpoint of type " + endpoint.getType());
        }

        int endpointRowCount = entityManager.createQuery(endpointQuery)
                .setParameter("name", endpoint.getName())
                .setParameter("description", endpoint.getDescription())
                .setParameter("enabled", endpoint.isEnabled())
                .setParameter("orgId", endpoint.getOrgId())
                .setParameter("id", endpoint.getId())
                .executeUpdate();

        if (endpointRowCount == 0) {
            return false;
        } else if (endpoint.getProperties() == null) {
            return true;
        } else {
            switch (endpoint.getType()) {
                case ANSIBLE:
                case WEBHOOK:
                    WebhookProperties properties = endpoint.getProperties(WebhookProperties.class);
                    return entityManager.createQuery(webhookQuery)
                            .setParameter("url", properties.getUrl())
                            .setParameter("method", properties.getMethod())
                            .setParameter("disableSslVerification", properties.getDisableSslVerification())
                            .setParameter("secretToken", properties.getSecretToken())
                            .setParameter("endpointId", endpoint.getId())
                            .executeUpdate() > 0;
                case CAMEL:
                    CamelProperties cAttr = (CamelProperties) endpoint.getProperties();
                    return entityManager.createQuery(camelQuery)
                            .setParameter("url", cAttr.getUrl())
                            .setParameter("disableSslVerification", cAttr.getDisableSslVerification())
                            .setParameter("secretToken", cAttr.getSecretToken())
                            .setParameter("endpointId", endpoint.getId())
                            .setParameter("extras", cAttr.getExtras())
                            .setParameter("basicAuthentication", cAttr.getBasicAuthentication())
                            .executeUpdate() > 0;
                default:
                    return true;
            }
        }
    }

    public void loadProperties(List<Endpoint> endpoints) {
        if (endpoints.isEmpty()) {
            return;
        }

        // Group endpoints in types and load in batches for each type.
        Set<Endpoint> endpointSet = new HashSet<>(endpoints);

        loadTypedProperties(WebhookProperties.class, endpointSet, EndpointType.ANSIBLE);
        loadTypedProperties(WebhookProperties.class, endpointSet, EndpointType.WEBHOOK);
        loadTypedProperties(CamelProperties.class, endpointSet, EndpointType.CAMEL);
        loadTypedProperties(SystemSubscriptionProperties.class, endpointSet, EndpointType.EMAIL_SUBSCRIPTION);
        loadTypedProperties(SystemSubscriptionProperties.class, endpointSet, EndpointType.DRAWER);
    }

    private <T extends EndpointProperties> void loadTypedProperties(Class<T> typedEndpointClass, Set<Endpoint> endpoints, EndpointType type) {
        Map<UUID, Endpoint> endpointsMap = endpoints
                .stream()
                .filter(e -> e.getType().equals(type))
                .collect(Collectors.toMap(Endpoint::getId, Function.identity()));

        if (endpointsMap.size() > 0) {
            String query = "FROM " + typedEndpointClass.getSimpleName() + " WHERE id IN (:ids)";
            List<T> propList = entityManager.createQuery(query, typedEndpointClass)
                    .setParameter("ids", endpointsMap.keySet())
                    .getResultList();
            for (T props : propList) {
                if (props != null) {
                    Endpoint endpoint = endpointsMap.get(props.getId());
                    endpoint.setProperties(props);
                }
            }
        }
    }

    static QueryBuilder<Endpoint> queryBuilderEndpointsPerType(String orgId, @Nullable String name, Set<CompositeEndpointType> type, Boolean activeOnly) {
        Set<EndpointType> basicTypes = type.stream().filter(c -> c.getSubType() == null).map(CompositeEndpointType::getType).collect(Collectors.toSet());
        Set<CompositeEndpointType> compositeTypes = type.stream().filter(c -> c.getSubType() != null).collect(Collectors.toSet());
        return QueryBuilder
                .builder(Endpoint.class)
                .alias("e")
                .where(
                        WhereBuilder.builder()
                                .ifElse(
                                        orgId == null,
                                        WhereBuilder.builder().and("e.orgId IS NULL"),
                                        WhereBuilder.builder().and("e.orgId = :orgId", "orgId", orgId)
                                )
                                .and(
                                        WhereBuilder.builder()
                                                .ifOr(basicTypes.size() > 0, "e.compositeType.type IN (:endpointType)", "endpointType", basicTypes)
                                                .ifOr(compositeTypes.size() > 0, "e.compositeType IN (:compositeTypes)", "compositeTypes", compositeTypes)
                                )
                                .ifAnd(activeOnly != null, "e.enabled = :enabled", "enabled", activeOnly)
                                .ifAnd(
                                        name != null && !name.isEmpty(),
                                        "LOWER(e.name) LIKE :name",
                                        "name", (Supplier<String>) () -> "%" + name.toLowerCase() + "%"
                                )
                );
    }

    public Endpoint loadProperties(Endpoint endpoint) {
        if (endpoint == null) {
            Log.warn("Endpoint properties loading attempt with a null endpoint. It should never happen, this is a bug.");
            return null;
        }
        loadProperties(Collections.singletonList(endpoint));
        return endpoint;
    }

    /**
     * Checks if an endpoint exists in the database.
     * @param endpointUuid the UUID to look by.
     * @param orgId the OrgID to filter the endpoints with.
     * @return true if it exists, false otherwise.
     */
    public boolean existsByUuidAndOrgId(final UUID endpointUuid, final String orgId) {
        final String existsEndpointByUuid =
            "SELECT " +
                "1 " +
            "FROM " +
                "Endpoint AS e " +
            "WHERE " +
                "e.id = :endpointUuid " +
            "AND " +
                "e.orgId = :orgId";

        try {
            this.entityManager.createQuery(existsEndpointByUuid)
                .setParameter("endpointUuid", endpointUuid)
                .setParameter("orgId", orgId)
                .getSingleResult();

            return true;
        } catch (final NoResultException e) {
            return false;
        }
    }

    /**
     * Returns a map of endpoint {@link UUID}s and their related organization
     * ids, which have been identified as having "basic authentication" and
     * "secret token" secrets stored in the database, but no references to
     * those secrets in sources. This signals that the endpoints' secrets need
     * to be migrated to Sources. The function returns just this information
     * because the goal is to then call {@link #getEndpoint(String, UUID)}
     * inside a transactional function, and update those endpoints. That way,
     * when wrapped with a try-catch block, we should be able to catch any
     * errors that occur while migrating the data.
     *
     * @return a map of {@link UUID}s and organization IDs of the endpoints
     * that should be migrated.
     */
    @Deprecated(forRemoval = true)
    public Map<UUID, String> findEndpointWithPropertiesWithStoredSecrets() {
        final String query =
            "SELECT e FROM Endpoint e " +
                "WHERE EXISTS ( " +
                    "SELECT cp FROM CamelProperties cp " +
                    "WHERE cp.id = e.id AND cp.basicAuthenticationSourcesId IS NULL AND cp.secretTokenSourcesId IS NULL " +
                "AND (cp.basicAuthentication IS NOT NULL OR cp.secretToken IS NOT NULL) " +
                ") OR EXISTS ( " +
                    "SELECT wp FROM WebhookProperties wp " +
                    "WHERE wp.id = e.id AND wp.basicAuthenticationSourcesId IS NULL AND wp.secretTokenSourcesId IS NULL " +
                    "AND (wp.basicAuthentication IS NOT NULL OR wp.secretToken IS NOT NULL) " +
                ")";

        final List<Endpoint> endpoints =  this.entityManager
            .createQuery(query, Endpoint.class)
            .getResultList();

        return endpoints
            .stream()
            .collect(Collectors.toMap(Endpoint::getId, Endpoint::getOrgId));
    }
}
