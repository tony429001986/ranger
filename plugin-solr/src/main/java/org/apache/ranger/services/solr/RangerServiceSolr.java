/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ranger.services.solr;

import org.apache.commons.lang.StringUtils;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItem;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItemAccess;
import org.apache.ranger.plugin.model.RangerService;
import org.apache.ranger.plugin.model.RangerServiceDef;
import org.apache.ranger.plugin.service.RangerBaseService;
import org.apache.ranger.plugin.service.ResourceLookupContext;
import org.apache.ranger.services.solr.client.ServiceSolrClient;
import org.apache.ranger.services.solr.client.ServiceSolrConnectionMgr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RangerServiceSolr extends RangerBaseService {
    private static final Logger LOG = LoggerFactory.getLogger(RangerServiceSolr.class);

    public static final String ACCESS_TYPE_QUERY = RangerSolrConstants.AccessType.QUERY.toString();

    public RangerServiceSolr() {
        super();
    }

    @Override
    public void init(RangerServiceDef serviceDef, RangerService service) {
        super.init(serviceDef, service);
    }

    @Override
    public Map<String, Object> validateConfig() throws Exception {
        Map<String, Object> ret         = new HashMap<>();
        String              serviceName = getServiceName();

        LOG.debug("==> RangerServiceSolr.validateConfig Service: ({} )", serviceName);

        if (configs != null) {
            try {
                ret = ServiceSolrConnectionMgr.connectionTest(serviceName, configs);
            } catch (Exception e) {
                LOG.error("<== RangerServiceSolr.validateConfig Error:", e);

                throw e;
            }
        }

        LOG.debug("<== RangerServiceSolr.validateConfig Response : ({} )", ret);

        return ret;
    }

    @Override
    public List<String> lookupResource(ResourceLookupContext context) throws Exception {
        ServiceSolrClient serviceSolrClient = ServiceSolrConnectionMgr.getSolrClient(serviceName, configs);

        return serviceSolrClient.getResources(context);
    }

    @Override
    public List<RangerPolicy> getDefaultRangerPolicies() throws Exception {
        LOG.debug("==> RangerServiceSolr.getDefaultRangerPolicies()");

        List<RangerPolicy> ret = super.getDefaultRangerPolicies();

        for (RangerPolicy defaultPolicy : ret) {
            if (defaultPolicy.getName().contains("all") && StringUtils.isNotBlank(lookUpUser)) {
                RangerPolicyItem policyItemForLookupUser = new RangerPolicyItem();

                policyItemForLookupUser.setUsers(Collections.singletonList(lookUpUser));
                policyItemForLookupUser.setAccesses(Collections.singletonList(new RangerPolicyItemAccess(ACCESS_TYPE_QUERY)));
                policyItemForLookupUser.setDelegateAdmin(false);

                defaultPolicy.addPolicyItem(policyItemForLookupUser);
            }
        }

        LOG.debug("<== RangerServiceSolr.getDefaultRangerPolicies()");

        return ret;
    }
}
