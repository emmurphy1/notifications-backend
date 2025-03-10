package com.redhat.cloud.notifications.connector.email.processors.it.pojo.request;

import com.redhat.cloud.notifications.connector.email.processors.it.ITConstants;

import java.util.ArrayList;
import java.util.List;

public class ITUserRequest {

    public ITUserRequestBy by;
    public Include include;

    public ITUserRequest(String orgId, boolean adminsOnly, int firstResult, int maxResults) {
        final ITUserRequestBy by = new ITUserRequestBy();
        AllOf allOf = new AllOf();
        allOf.status = "enabled";
        allOf.accountId = orgId;

        if (adminsOnly) {
            PermissionCode permissionCode = new PermissionCode();
            permissionCode.value = ITConstants.ORG_ADMIN_PERMISSION;
            permissionCode.operand = "eq";
            allOf.permissionCode = permissionCode;
        }
        by.allOf = allOf;

        this.by = by;
        this.by.withPaging = new WithPaging();
        this.by.withPaging.firstResultIndex = firstResult;
        this.by.withPaging.maxResults = maxResults;

        Include include = new Include();

        include.allOf = List.of("authentications", "personal_information");
        List<AccountRelationship> accountRelationships = new ArrayList<>();
        AccountRelationship accountRelationship1 = new AccountRelationship();
        accountRelationship1.allOf = List.of("primary_email");

        final AccountRelationshipBy by1 = new AccountRelationshipBy();
        by1.active = true;
        accountRelationship1.by = by1;
        accountRelationships.add(accountRelationship1);
        include.accountRelationships = accountRelationships;

        this.include = include;
    }
}
