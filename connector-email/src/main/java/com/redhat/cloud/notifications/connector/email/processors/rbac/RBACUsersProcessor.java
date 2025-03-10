package com.redhat.cloud.notifications.connector.email.processors.rbac;

import com.redhat.cloud.notifications.connector.email.constants.ExchangeProperty;
import com.redhat.cloud.notifications.connector.email.model.settings.RecipientSettings;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import javax.enterprise.context.ApplicationScoped;
import java.util.Set;

@ApplicationScoped
public class RBACUsersProcessor implements Processor {
    /**
     * Processes the incoming payload from the RBAC service into a filtered
     * set of usernames. In the case that the users are part of an RBAC group
     * it is looked if only admins are allowed and if the user is active before
     * adding it to the set.
     * @param exchange the exchange of the pipeline.
     */
    @Override
    public void process(final Exchange exchange) {
        final String responseBody = exchange.getIn().getBody(String.class);
        final JsonObject responseBodyJson = new JsonObject(responseBody);

        // Extract the data we need to process.
        final JsonArray data = responseBodyJson.getJsonArray("data");

        // Get the list of usernames we will fill with the extracted usernames
        // from RBAC's response.
        final Set<String> usernames = exchange.getProperty(ExchangeProperty.USERNAMES, Set.class);

        // In case we are processing the RBAC users from the "get principals
        // from an RBAC group" call, we need to perform a few more checks
        // before including the users.
        final RecipientSettings currentRecipientSettings = exchange.getProperty(ExchangeProperty.CURRENT_RECIPIENT_SETTINGS, RecipientSettings.class);
        final boolean adminsOnly = currentRecipientSettings.isAdminsOnly();

        for (int i = 0; i < data.size(); i++) {
            final JsonObject rbacUser = data.getJsonObject(i);

            if (currentRecipientSettings.getGroupUUID() != null) {
                final boolean isAdmin = rbacUser.getBoolean("is_org_admin");
                final boolean isActive = rbacUser.getBoolean("is_active");

                if (adminsOnly && !isAdmin) {
                    continue;
                }

                if (!isActive) {
                    continue;
                }
            }

            usernames.add(rbacUser.getString("username"));
        }

        // Store the number of elements that were returned from the page, so
        // that the predicate can decide whether to keep fetching things or not.
        final int count = responseBodyJson.getJsonObject("meta").getInteger("count");
        exchange.setProperty(ExchangeProperty.ELEMENTS_COUNT, count);

        // Update the offset in case we need to fetch the next page.
        final int limit = exchange.getProperty(ExchangeProperty.LIMIT, Integer.class);
        if (count == limit) {
            final int offset = (int) exchange.getProperty(ExchangeProperty.OFFSET);
            exchange.setProperty(ExchangeProperty.OFFSET, offset + limit);
        }
    }
}
