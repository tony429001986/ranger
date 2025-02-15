/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ranger.biz;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.ranger.authorization.hadoop.config.RangerAdminConfig;
import org.apache.ranger.authorization.utils.JsonUtils;
import org.apache.ranger.biz.ServiceDBStore.REMOVE_REF_TYPE;
import org.apache.ranger.common.ContextUtil;
import org.apache.ranger.common.MessageEnums;
import org.apache.ranger.common.RESTErrorUtil;
import org.apache.ranger.common.RangerConstants;
import org.apache.ranger.common.RangerRoleCache;
import org.apache.ranger.common.UserSessionBase;
import org.apache.ranger.common.db.RangerTransactionSynchronizationAdapter;
import org.apache.ranger.db.RangerDaoManager;
import org.apache.ranger.entity.XXRole;
import org.apache.ranger.entity.XXRoleRefGroup;
import org.apache.ranger.entity.XXRoleRefUser;
import org.apache.ranger.entity.XXService;
import org.apache.ranger.entity.XXServiceVersionInfo;
import org.apache.ranger.plugin.model.RangerRole;
import org.apache.ranger.plugin.store.AbstractPredicateUtil;
import org.apache.ranger.plugin.store.RolePredicateUtil;
import org.apache.ranger.plugin.store.RoleStore;
import org.apache.ranger.plugin.util.RangerRoles;
import org.apache.ranger.plugin.util.SearchFilter;
import org.apache.ranger.service.RangerBaseModelService;
import org.apache.ranger.service.RangerRoleService;
import org.apache.ranger.service.XUserService;
import org.apache.ranger.view.RangerRoleList;
import org.apache.ranger.view.VXUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.apache.ranger.db.XXGlobalStateDao.RANGER_GLOBAL_STATE_NAME_ROLE;

@Component
public class RoleDBStore implements RoleStore {
    private static final Logger LOG = LoggerFactory.getLogger(RoleDBStore.class);

    @Autowired
    RangerRoleService roleService;

    @Autowired
    XUserService xUserService;

    @Autowired
    RangerDaoManager daoMgr;

    @Autowired
    RESTErrorUtil restErrorUtil;

    @Autowired
    RoleRefUpdater roleRefUpdater;

    @Autowired
    RangerTransactionSynchronizationAdapter transactionSynchronizationAdapter;

    @Autowired
    ServiceDBStore svcStore;

    @Autowired
    GdsDBStore gdsStore;

    RangerAdminConfig     config;
    AbstractPredicateUtil predicateUtil;

    public void init() throws Exception {}

    @Override
    public RangerRole createRole(RangerRole role, Boolean createNonExistUserGroupRole) throws Exception {
        LOG.debug("==> RoleDBStore.createRole()");

        XXRole xxRole = daoMgr.getXXRole().findByRoleName(role.getName());

        if (xxRole != null) {
            throw restErrorUtil.createRESTException("role with name: " + role.getName() + " already exists", MessageEnums.ERROR_DUPLICATE_OBJECT);
        }

        Runnable roleVersionUpdater = new RoleVersionUpdater(daoMgr);
        transactionSynchronizationAdapter.executeOnTransactionCommit(roleVersionUpdater);

        roleService.create(role);

        RangerRole createdRole = getRole(role.getName());

        if (createdRole == null) {
            throw new Exception("Cannot create role:[" + role + "]");
        }

        roleRefUpdater.createNewRoleMappingForRefTable(createdRole, createNonExistUserGroupRole);

        roleService.createTransactionLog(createdRole, null, RangerBaseModelService.OPERATION_CREATE_CONTEXT);

        return createdRole;
    }

    @Override
    public RangerRole updateRole(RangerRole role, Boolean createNonExistUserGroupRole) throws Exception {
        XXRole xxRole = daoMgr.getXXRole().findByRoleId(role.getId());

        if (xxRole == null) {
            throw restErrorUtil.createRESTException("role with id: " + role.getId() + " does not exist");
        }

        if (!role.getName().equals(xxRole.getName())) { // ensure only if role name is changed
            ensureRoleNameUpdateAllowed(xxRole.getName());
        }

        RangerRole oldRole = null;

        if (StringUtils.isNotEmpty(xxRole.getRoleText())) {
            oldRole = JsonUtils.jsonToObject(xxRole.getRoleText(), RangerRole.class);
        }

        Runnable roleVersionUpdater = new RoleVersionUpdater(daoMgr);

        transactionSynchronizationAdapter.executeOnTransactionCommit(roleVersionUpdater);

        RangerRole updatedRole = roleService.update(role);

        if (updatedRole == null) {
            throw new Exception("Cannot update role:[" + role + "]");
        }

        roleRefUpdater.createNewRoleMappingForRefTable(updatedRole, createNonExistUserGroupRole);

        roleService.updatePolicyVersions(updatedRole.getId());

        if (ServiceDBStore.isSupportsRolesDownloadByService()) {
            roleService.updateRoleVersions(updatedRole.getId());
        }

        roleService.createTransactionLog(updatedRole, oldRole, RangerBaseModelService.OPERATION_UPDATE_CONTEXT);

        return role;
    }

    @Override
    public void deleteRole(String roleName) throws Exception {
        XXRole xxRole = daoMgr.getXXRole().findByRoleName(roleName);

        if (xxRole == null) {
            throw restErrorUtil.createRESTException("Role with name: " + roleName + " does not exist");
        }

        deleteRole(xxRole.getId());
    }

    @Override
    public void deleteRole(Long roleId) throws Exception {
        RangerRole role = roleService.read(roleId);

        ensureRoleDeleteAllowed(role.getName());

        Runnable roleVersionUpdater = new RoleVersionUpdater(daoMgr);

        transactionSynchronizationAdapter.executeOnTransactionCommit(roleVersionUpdater);

        roleRefUpdater.cleanupRefTables(role);
        // delete role from audit filter configs
        svcStore.updateServiceAuditConfig(role.getName(), REMOVE_REF_TYPE.ROLE);

        // delete gdsObject mapping of role
        gdsStore.deletePrincipalFromGdsAcl(REMOVE_REF_TYPE.ROLE.toString(), role.getName());

        roleService.delete(role);
        roleService.createTransactionLog(role, null, RangerBaseModelService.OPERATION_DELETE_CONTEXT);
    }

    @Override
    public RangerRole getRole(Long id) {
        return roleService.read(id);
    }

    @Override
    public RangerRole getRole(String name) {
        XXRole xxRole = daoMgr.getXXRole().findByRoleName(name);

        if (xxRole == null) {
            throw restErrorUtil.createRESTException("Role with name: " + name + " does not exist");
        }

        return roleService.read(xxRole.getId());
    }

    @Override
    public List<RangerRole> getRoles(SearchFilter filter) throws Exception {
        List<RangerRole> ret     = new ArrayList<>();
        List<XXRole>     xxRoles = daoMgr.getXXRole().getAll();

        if (CollectionUtils.isNotEmpty(xxRoles)) {
            for (XXRole xxRole : xxRoles) {
                ret.add(roleService.read(xxRole.getId()));
            }

            if (predicateUtil != null && filter != null && !filter.isEmpty()) {
                List<RangerRole> copy = new ArrayList<>(ret);

                predicateUtil.applyFilter(copy, filter);

                ret = copy;
            }
        }

        return ret;
    }

    @Override
    public List<String> getRoleNames(SearchFilter filter) {
        return daoMgr.getXXRole().getAllNames();
    }

    @Override
    public RangerRoles getRoles(String serviceName, Long lastKnownRoleVersion) throws Exception {
        RangerRoles ret                   = null;
        Long        rangerRoleVersionInDB = getRoleVersion(serviceName);

        LOG.debug("==> RoleDBStore.getRoles() lastKnownRoleVersion= {} rangerRoleVersionInDB= {}", lastKnownRoleVersion, rangerRoleVersionInDB);

        if (rangerRoleVersionInDB != null) {
            ret = RangerRoleCache.getInstance().getLatestRangerRoleOrCached(serviceName, this, lastKnownRoleVersion, rangerRoleVersionInDB);
        }

        LOG.debug("<= RoleDBStore.getRoles() lastKnownRoleVersion= {} rangerRoleVersionInDB= {} RangerRoles= {}", lastKnownRoleVersion, rangerRoleVersionInDB, ret);

        return ret;
    }

    @Override
    public Long getRoleVersion(String serviceName) {
        Long ret;

        if (ServiceDBStore.isSupportsRolesDownloadByService()) {
            XXServiceVersionInfo xxServiceVersionInfo = daoMgr.getXXServiceVersionInfo().findByServiceName(serviceName);

            ret = (xxServiceVersionInfo != null) ? xxServiceVersionInfo.getRoleVersion() : null;
        } else {
            ret = daoMgr.getXXGlobalState().getAppDataVersion(RANGER_GLOBAL_STATE_NAME_ROLE);
        }

        return ret;
    }

    @Override
    public boolean roleExists(Long id) {
        XXRole role = daoMgr.getXXRole().findByRoleId(id);

        return role != null;
    }

    @Override
    public boolean roleExists(String name) {
        XXRole role = daoMgr.getXXRole().findByRoleName(name);

        return role != null;
    }

    @PostConstruct
    public void initStore() {
        LOG.debug("==> RoleDBStore.initStore()");

        config = RangerAdminConfig.getInstance();

        Boolean populateExistingBaseFields = false;

        roleService.setPopulateExistingBaseFields(populateExistingBaseFields);

        predicateUtil = new RolePredicateUtil();

        LOG.debug("<== RoleDBStore.initStore()");
    }

    public RangerRoleList getRoles(SearchFilter filter, RangerRoleList rangerRoleList) throws Exception {
        List<RangerRole> roles   = new ArrayList<>();
        List<XXRole>     xxRoles = roleService.searchResources(filter, roleService.searchFields, roleService.sortFields, rangerRoleList);

        if (CollectionUtils.isNotEmpty(xxRoles)) {
            for (XXRole xxRole : xxRoles) {
                roles.add(roleService.read(xxRole.getId()));
            }
        }

        rangerRoleList.setRoleList(roles);

        return rangerRoleList;
    }

    public RangerRoleList getRolesForUser(SearchFilter filter, RangerRoleList rangerRoleList) {
        List<RangerRole> roles       = new ArrayList<>();
        UserSessionBase  userSession = ContextUtil.getCurrentUserSession();

        if (userSession != null && userSession.getUserRoleList().size() == 1 && userSession.getUserRoleList().contains(RangerConstants.ROLE_USER) && userSession.getLoginId() != null) {
            VXUser       loggedInVXUser = xUserService.getXUserByUserName(userSession.getLoginId());
            List<XXRole> xxRoles        = daoMgr.getXXRole().findByUserId(loggedInVXUser.getId());

            if (CollectionUtils.isNotEmpty(xxRoles)) {
                for (XXRole xxRole : xxRoles) {
                    roles.add(roleService.read(xxRole.getId()));
                }
            }

            if (predicateUtil != null && filter != null && !filter.isEmpty()) {
                List<RangerRole> copy = new ArrayList<>(roles);

                predicateUtil.applyFilter(copy, filter);

                roles = copy;
            }

            int totalCount = roles.size();
            int startIndex = filter.getStartIndex();
            int pageSize   = filter.getMaxRows();
            int toIndex    = Math.min(startIndex + pageSize, totalCount);

            if (CollectionUtils.isNotEmpty(roles)) {
                roles = roles.subList(startIndex, toIndex);

                rangerRoleList.setResultSize(roles.size());
                rangerRoleList.setPageSize(filter.getMaxRows());
                rangerRoleList.setSortBy(filter.getSortBy());
                rangerRoleList.setSortType(filter.getSortType());
                rangerRoleList.setStartIndex(filter.getStartIndex());
                rangerRoleList.setTotalCount(totalCount);
            }
        } else {
            List<XXRole> xxRoles = roleService.searchResources(filter, roleService.searchFields, roleService.sortFields, rangerRoleList);

            if (CollectionUtils.isNotEmpty(xxRoles)) {
                for (XXRole xxRole : xxRoles) {
                    roles.add(roleService.read(xxRole.getId()));
                }
            }
        }

        rangerRoleList.setRoleList(roles);

        return rangerRoleList;
    }

    public Set<RangerRole> getRoleNames(String userName, Set<String> userGroups) {
        Set<RangerRole> ret = new HashSet<>();

        if (StringUtils.isNotEmpty(userName)) {
            List<XXRoleRefUser> xxRoleRefUsers = roleRefUpdater.getRangerDaoManager().getXXRoleRefUser().findByUserName(userName);

            for (XXRoleRefUser xxRoleRefUser : xxRoleRefUsers) {
                ret.add(getRole(xxRoleRefUser.getRoleId()));
            }
        }

        for (String userGroup : userGroups) {
            List<XXRoleRefGroup> xxRoleRefGroups = roleRefUpdater.getRangerDaoManager().getXXRoleRefGroup().findByGroupName(userGroup);

            for (XXRoleRefGroup xxRoleRefGroup : xxRoleRefGroups) {
                ret.add(getRole(xxRoleRefGroup.getRoleId()));
            }
        }

        return ret;
    }

    public List<RangerRole> getRoles(String serviceName) {
        List<RangerRole> ret = Collections.emptyList();

        if (StringUtils.isNotEmpty(serviceName)) {
            XXService xxService = daoMgr.getXXService().findByName(serviceName);

            ret = getRoles(xxService);
        }

        return ret;
    }

    public List<RangerRole> getRoles(Long serviceId) {
        List<RangerRole> ret = Collections.emptyList();

        if (serviceId != null) {
            String serviceTypeName = daoMgr.getXXServiceDef().findServiceDefTypeByServiceId(serviceId);

            LOG.debug("Service Type for serviceId ({}) = {}", serviceId, serviceTypeName);

            String serviceTypesToGetAllRoles = config.get("ranger.admin.service.types.for.returning.all.roles", "solr");

            boolean getAllRoles = false;

            if (StringUtils.isNotEmpty(serviceTypesToGetAllRoles)) {
                String[] allRolesServiceTypes = StringUtils.split(serviceTypesToGetAllRoles, ",");

                if (allRolesServiceTypes != null) {
                    for (String allRolesServiceType : allRolesServiceTypes) {
                        if (StringUtils.equalsIgnoreCase(serviceTypeName, allRolesServiceType)) {
                            getAllRoles = true;
                            break;
                        }
                    }
                }
            }

            List<XXRole> rolesFromDb = getAllRoles ? daoMgr.getXXRole().getAll() : daoMgr.getXXRole().findByServiceId(serviceId);

            if (CollectionUtils.isNotEmpty(rolesFromDb)) {
                ret = new ArrayList<>();

                for (XXRole xxRole : rolesFromDb) {
                    ret.add(roleService.read(xxRole.getId()));
                }
            }
        }

        return ret;
    }

    public List<RangerRole> getRoles(XXService service) {
        return service == null ? Collections.emptyList() : getRoles(service.getId());
    }

    private void ensureRoleNameUpdateAllowed(String roleName) throws Exception {
        boolean roleNotInPolicy = ensureRoleNotInPolicy(roleName);

        if (!roleNotInPolicy) {
            throw new Exception("Rolename for '" + roleName + "' can not be updated as it is referenced in one or more policies");
        }

        boolean roleNotInOtherRole = ensureRoleNotInRole(roleName);

        if (!roleNotInOtherRole) {
            throw new Exception("Rolename for '" + roleName + "' can not be updated as it is referenced in one or more other roles");
        }

        boolean rleNotInZone = ensureRoleNotInZone(roleName);

        if (!rleNotInZone) {
            throw new Exception("Rolename for '" + roleName + "' can not be updated as it is referenced in one or more security zones");
        }
    }

    private void ensureRoleDeleteAllowed(String roleName) throws Exception {
        boolean roleNotInPolicy = ensureRoleNotInPolicy(roleName);

        if (!roleNotInPolicy) {
            throw new Exception("Role '" + roleName + "' can not be deleted as it is referenced in one or more policies");
        }

        boolean roleNotInOtherRole = ensureRoleNotInRole(roleName);

        if (!roleNotInOtherRole) {
            throw new Exception("Role '" + roleName + "' can not be deleted as it is referenced in one or more other roles");
        }

        boolean rleNotInZone = ensureRoleNotInZone(roleName);

        if (!rleNotInZone) {
            throw new Exception("Role '" + roleName + "' can not be deleted as it is referenced in one or more security zones");
        }
    }

    private boolean ensureRoleNotInPolicy(String roleName) {
        Long roleRefPolicyCount = daoMgr.getXXPolicyRefRole().findRoleRefPolicyCount(roleName);

        return roleRefPolicyCount < 1;
    }

    private boolean ensureRoleNotInRole(String roleName) {
        Long roleRefRoleCount = daoMgr.getXXRoleRefRole().findRoleRefRoleCount(roleName);

        return roleRefRoleCount < 1;
    }

    private boolean ensureRoleNotInZone(String roleName) {
        Long roleRefZoneCount = daoMgr.getXXSecurityZoneRefRole().findRoleRefZoneCount(roleName);

        return roleRefZoneCount < 1;
    }

    public static class RoleVersionUpdater implements Runnable {
        final RangerDaoManager daoManager;

        public RoleVersionUpdater(RangerDaoManager daoManager) {
            this.daoManager = daoManager;
        }

        @Override
        public void run() {
            try {
                this.daoManager.getXXGlobalState().onGlobalAppDataChange(RANGER_GLOBAL_STATE_NAME_ROLE);
            } catch (Exception e) {
                LOG.error("Cannot update GlobalState version for state:[{}]", RANGER_GLOBAL_STATE_NAME_ROLE, e);
            }
        }
    }
}
