/*
 * Copyright (c) 2005-2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.certificateauthority.internal;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.service.component.ComponentContext;
import org.wso2.carbon.identity.certificateauthority.scheduledTask.CrlUpdater;
import org.wso2.carbon.user.core.service.RealmService;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @scr.component name="identity.certificateauthority" immediate="true"
 * @scr.reference name="user.realmservice.default" interface="org.wso2.carbon.user.core.service.RealmService"
 * cardinality="1..1" policy="dynamic" bind="setRealmService"
 * unbind="unsetRealmService"
 */
public class CAServiceComponent {


    private static RealmService realmService;
    private static Log log = LogFactory.getLog(CAServiceComponent.class);
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    public static RealmService getRealmService() {
        return realmService;
    }

    protected void setRealmService(RealmService realmService) {
        CAServiceComponent.realmService = realmService;
    }

    protected void unsetRealmService(RealmService realmService) {
        setRealmService(null);
    }

    protected void activate(ComponentContext ctxt) {
        log.info("starting scheduled task for creating CRLs for tenants");
        scheduler.scheduleAtFixedRate(new CrlUpdater(), 30, 60 * 60 * 24, TimeUnit.SECONDS);
    }

    protected void deactivate(ComponentContext ctxt) {
        if (log.isDebugEnabled()) {
            log.info("CA component is deactivating ...");
        }
    }
}
