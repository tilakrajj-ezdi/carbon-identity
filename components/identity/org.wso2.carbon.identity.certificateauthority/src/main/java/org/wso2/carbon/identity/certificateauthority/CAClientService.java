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

package org.wso2.carbon.identity.certificateauthority;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.identity.certificateauthority.dao.CertificateDAO;
import org.wso2.carbon.identity.certificateauthority.dao.CsrDAO;
import org.wso2.carbon.identity.certificateauthority.data.CertificateDTO;
import org.wso2.carbon.identity.certificateauthority.data.CsrDTO;
import org.wso2.carbon.identity.certificateauthority.data.CsrMetaInfo;
import org.wso2.carbon.identity.certificateauthority.utils.CertificateUtils;
import org.wso2.carbon.identity.certificateauthority.utils.CsrUtils;
import org.wso2.carbon.user.core.util.UserCoreUtil;

import java.io.IOException;

public class CAClientService {
    Log log = LogFactory.getLog(CAClientService.class);

    private CsrDAO csrDAO = new CsrDAO();
    private CertificateDAO certificateDAO = new CertificateDAO();

    public String addCsr(String csrContent) throws CaException {
        try {
            String username = PrivilegedCarbonContext.getThreadLocalCarbonContext().getUsername();
            int tenantID = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();
            String userStoreDomain = UserCoreUtil.extractDomainFromName(username);
            return csrDAO.addCsr(csrContent, username, tenantID, userStoreDomain);
        } catch (IOException e) {
            log.error(e);
            throw new CaException("Could not store CSR. " + e.getMessage(), e);
        }
    }

    public CsrDTO getCsr(String serial) throws CaException {
        String username = PrivilegedCarbonContext.getThreadLocalCarbonContext().getUsername();
        int tenantID = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();
        String userStoreDomain = UserCoreUtil.extractDomainFromName(username);
        try {
            return CsrUtils.CsrToCsrDTO(csrDAO.getCSR(serial, userStoreDomain, username, tenantID));
        } catch (IOException e) {
            log.error(e);
            throw new CaException(e);
        }
    }

    public CsrMetaInfo[] getCsrList() throws CaException {
        String username = PrivilegedCarbonContext.getThreadLocalCarbonContext().getUsername();
        int tenantID = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();
        String userStoreDomain = UserCoreUtil.extractDomainFromName(username);
        return csrDAO.getCsrList(tenantID, username, userStoreDomain);
    }

    public CertificateDTO getCertificate(String serialNo) throws CaException {
        String username = PrivilegedCarbonContext.getThreadLocalCarbonContext().getUsername();
        int tenantID = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();
        String userStoreDomain = UserCoreUtil.extractDomainFromName(username);
        return CertificateUtils.getCertificateDTO(certificateDAO.getCertificate(serialNo, tenantID, username,
                userStoreDomain));
    }
}
