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

import org.apache.log4j.Logger;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest;
import org.wso2.carbon.base.MultitenantConstants;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.core.AbstractAdmin;
import org.wso2.carbon.core.util.KeyStoreManager;
import org.wso2.carbon.identity.certificateauthority.crl.CrlFactory;
import org.wso2.carbon.identity.certificateauthority.crl.RevokedCertInfo;
import org.wso2.carbon.identity.certificateauthority.dao.CertificateDAO;
import org.wso2.carbon.identity.certificateauthority.dao.CsrDAO;
import org.wso2.carbon.identity.certificateauthority.dao.RevocationDAO;
import org.wso2.carbon.identity.certificateauthority.data.*;
import org.wso2.carbon.identity.certificateauthority.utils.CAUtils;
import org.wso2.carbon.identity.certificateauthority.utils.CertificateUtils;
import org.wso2.carbon.identity.certificateauthority.utils.CsrUtils;
import org.wso2.carbon.security.keystore.KeyStoreAdmin;
import org.wso2.carbon.security.keystore.service.KeyStoreData;
import org.wso2.carbon.user.core.util.UserCoreUtil;

import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;

public class CAAdminService extends AbstractAdmin {

    private static final long MILLIS_PER_DAY = 86400000l;
    private static Logger log = Logger.getLogger(CAAdminService.class);
    private CsrDAO csrDAO;
    private CertificateDAO certificateDAO;
    private RevocationDAO revokeDAO;

    public CAAdminService() {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        }
        csrDAO = new CsrDAO();
        certificateDAO = new CertificateDAO();
        revokeDAO = new RevocationDAO();
    }

    public void signCSR(String serialNo, int validity) throws CaException {
        try {
            int tenantID = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();
            String username = PrivilegedCarbonContext.getThreadLocalCarbonContext().getUsername();
            String userStoreDomain = UserCoreUtil.extractDomainFromName(username);
            Csr csr = csrDAO.getCSR(serialNo, tenantID);
            if (csr == null) {
                throw new CaException("Invalid serial no");
            }
            if (!"PENDING".equals(csr.getStatus())) {
                throw new CaException("Certificate already signed, rejected or revoked");
            }
            X509Certificate signedCert = signCSR(serialNo, csr.getCsrRequest(), validity, CAUtils.getConfiguredPrivateKey(), CAUtils.getConfiguredCaCert());
            csrDAO.updateStatus(serialNo, CsrStatus.SIGNED, tenantID);
            certificateDAO.addCertificate(serialNo, signedCert, tenantID, username, userStoreDomain);

        } catch (Exception e) {
            log.error(e.getMessage(), e);
            throw new CaException(e);
        }
    }

    /**
     * to get a list of CS request assigned to a tenant
     *
     * @return list of CSR files assigned to a tenant
     */
    public CsrMetaInfo[] getCsrList() throws CaException {
        int tenantID = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();
        return csrDAO.getCSRList(tenantID);
    }

    public void revokeCert(String serial, int reason) throws Exception {
        String username = PrivilegedCarbonContext.getThreadLocalCarbonContext().getUsername();
        int tenantID = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();
        String tenantDomain = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantDomain();
        CrlFactory crlFactory = new CrlFactory();

        if (revokeDAO.getRevokedCertificate(serial) == null) {
            revokeDAO.addRevokedCertificate(serial, tenantID, reason);
        } else {
            revokeDAO.updateRevocationReason(tenantID, serial, reason);
        }


        if (reason == RevokedCertInfo.REVOCATION_REASON_REMOVEFROMCRL) {
            certificateDAO.updateCertificateStatus(serial, CertificateStatus.ACTIVE.toString());
        } else {
            certificateDAO.updateCertificateStatus(serial, CertificateStatus.REVOKED.toString());
        }

        crlFactory.createAndStoreDeltaCrl(tenantID);

    }

    public String[] listKeyAliases() throws CaException {
        List<String> keyList = new ArrayList<String>();


        try {
            int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();

            String currentKeyStore = CAUtils.getKeyStoreName();
            String currentAlias = CAUtils.getAlias();

            if (currentKeyStore != null && currentAlias != null) {
                keyList.add(currentKeyStore + "/" + currentAlias);  //The current configuration will be the
            }

            KeyStoreAdmin admin = new KeyStoreAdmin(tenantId, getGovernanceSystemRegistry());
            KeyStoreManager keyStoreManager = KeyStoreManager.getInstance(tenantId);
            KeyStoreData[] keyStores = admin.getKeyStores(tenantId == MultitenantConstants.SUPER_TENANT_ID);
            for (KeyStoreData keyStore : keyStores) {
                String keyStoreName = keyStore.getKeyStoreName();
                KeyStore keyStoreManagerKeyStore = keyStoreManager.getKeyStore(keyStoreName);
                Enumeration<String> aliases = keyStoreManagerKeyStore.aliases();
                while (aliases.hasMoreElements()) {
                    String alias = aliases.nextElement();
                    if (keyStoreManagerKeyStore.isKeyEntry(alias)) {
                        keyList.add(keyStoreName + "/" + alias);
                    }
                }
            }
        } catch (Exception e) {
            log.error(e);
            throw new CaException("Error when listing keys");
        }
        return keyList.toArray(new String[keyList.size()]);
    }

    /**
     * delete csr
     *
     * @param serial serial number of the csr
     * @return 1 if the deletion is successful,0 else
     */

    public void deleteCsr(String serial) throws CaException {
        int tenantID = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();
        csrDAO.deleteCSR(serial, tenantID);
    }

    public CertificateDTO getCertificate(String serialNo) throws CaException {
        int tenantID = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();
        return CertificateUtils.getCertificateDTO(certificateDAO.getCertificate(serialNo, tenantID));
    }

    public CertificateMetaInfo[] getTenantIssuedCertificates() throws CaException {
        int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();
        return certificateDAO.getCertificates(tenantId);
    }

    public CertificateMetaInfo[] getCerts(String status) throws CaException {
        int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();
        return certificateDAO.getCertificates(status, tenantId);
    }

    public void rejectCSR(String serial) throws CaException {
        int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();
        csrDAO.updateStatus(serial, CsrStatus.REJECTED, tenantId);

    }

    public CertificateMetaInfo[] getAllCerts() throws CaException {
        int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();
        return certificateDAO.getCertificates(tenantId);
    }

    public CsrMetaInfo[] getAllCsrs() throws CaException {
        int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();
        return csrDAO.getCSRList(tenantId);
    }

    public CsrMetaInfo[] getCsrfromCN(String CN) throws CaException {
        int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();
        return csrDAO.getCsrListfromCN(CN, tenantId);
    }

    public CsrMetaInfo[] getCsrfromORG(String org) throws CaException {
        int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();

        return csrDAO.getCsrListfromCN(org, tenantId);
    }

    public CsrDTO getCsr(String serial) throws CaException {
        int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();
        try {
            return CsrUtils.CsrToCsrDTO(csrDAO.getCSR(serial, tenantId));
        } catch (IOException e) {
            log.error(e);
            throw new CaException(e);
        }
    }


    public void setKeyStoreAndAlias(String keyStore, String alias) throws CaException {
        int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();
        try {
            CaConfigurations.setKeyStoreNameAndAlias(tenantId, keyStore, alias);
            CertificateMetaInfo[] certs = certificateDAO.getCertificates(CertificateStatus.ACTIVE.toString(), tenantId);

            //Revoke all active certificates of the tenant when the user changes the keystore
            for (CertificateMetaInfo cert : certs) {
                try {
                    revokeCert(cert.getSerialNo(), RevokedCertInfo.REVOCATION_REASON_CACOMPROMISE);
                } catch (Exception e) {
                    log.error("Error revoking certificate with serial no : " + cert.getSerialNo(), e);
                }
            }

        } catch (CaException e) {
            log.error("Couldn't change keystore/alias", e);
            throw e;
        }
    }

    public int getRevokedReason(String serial) throws CaException {
        return revokeDAO.getRevokedCertificate(serial).getReason();
    }

    public CsrMetaInfo[] getCsrListWithStatus(String status) throws CaException {
        int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();
        return csrDAO.getCSRListWithStatus(tenantId, status);
    }

    protected X509Certificate signCSR(String serialNo, PKCS10CertificationRequest request,
                                      int validity,
                                      PrivateKey privateKey, X509Certificate caCert) throws CaException {
        try {

            Date issuedDate = new Date();
            Date expiryDate = new Date(System.currentTimeMillis() + validity * MILLIS_PER_DAY);
            JcaPKCS10CertificationRequest jcaRequest = new JcaPKCS10CertificationRequest(request);
            X509v3CertificateBuilder certificateBuilder = new JcaX509v3CertificateBuilder(caCert,
                    new BigInteger(serialNo), issuedDate, expiryDate, jcaRequest.getSubject(), jcaRequest.getPublicKey());
            JcaX509ExtensionUtils extUtils = new JcaX509ExtensionUtils();
            certificateBuilder.addExtension(Extension.authorityKeyIdentifier, false,
                    extUtils.createAuthorityKeyIdentifier(caCert))
                    .addExtension(Extension.subjectKeyIdentifier, false, extUtils.createSubjectKeyIdentifier(jcaRequest
                            .getPublicKey()))
                    .addExtension(Extension.basicConstraints, true, new BasicConstraints(0))
                    .addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage
                            .keyEncipherment))
                    .addExtension(Extension.extendedKeyUsage, true, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
            ContentSigner signer = new JcaContentSignerBuilder("SHA1withRSA").setProvider("BC").build(privateKey);
            //todo add ocsp extension
            int tenantID = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();
            DistributionPointName crlEp = new DistributionPointName(new GeneralNames(new GeneralName(GeneralName
                    .uniformResourceIdentifier, CAUtils.getServerURL() + "/ca/crl/" + tenantID)));
            DistributionPoint disPoint = new DistributionPoint(crlEp, null, null);
            certificateBuilder.addExtension(Extension.cRLDistributionPoints, false,
                    new CRLDistPoint(new DistributionPoint[]{disPoint}));
            AccessDescription ocsp = new AccessDescription(AccessDescription.id_ad_ocsp,
                    new GeneralName(GeneralName.uniformResourceIdentifier, CAUtils.getServerURL() + "/ca/ocsp/" +
                            tenantID)
            );
            ASN1EncodableVector authInfoAccessASN = new ASN1EncodableVector();
            authInfoAccessASN.add(ocsp);
            certificateBuilder.addExtension(Extension.authorityInfoAccess, false, new DERSequence(authInfoAccessASN));
            return new JcaX509CertificateConverter().setProvider("BC").getCertificate(certificateBuilder.build(signer));


//            AccessDescription ocsp = new AccessDescription(ID_AD_OCSP,
//                    new GeneralName(GeneralName.uniformResourceIdentifier,
//                            new DERIA5String(CAUtils.getServerURL()+"/ca/ocsp/" + tenantID))
//            );
//
//            ASN1EncodableVector authInfoAccessASN = new ASN1EncodableVector();
//            authInfoAccessASN.add(ocsp);
//
//            certGen.addExtension(X509Extensions.AuthorityInfoAccess, false, new DERSequence(authInfoAccessASN));
//
//            DistributionPointName crlEP = new DistributionPointName(DNP_TYPE, new GeneralNames(
//                    new GeneralName(GeneralName.uniformResourceIdentifier, CAUtils.getServerURL()+"/ca/crl/" + tenantID)));
//
//            DistributionPoint[] distPoints = new DistributionPoint[1];
//            distPoints[0] = new DistributionPoint(crlEP, null, null);
//
//            certGen.addExtension(X509Extensions.CRLDistributionPoints, false, new CRLDistPoint(distPoints));
//
//            ASN1Set attributes = request.getCertificationRequestInfo().getAttributes();
//            for (int i = 0; i != attributes.size(); i++) {
//                Attribute attr = Attribute.getInstance(attributes.getObjectAt(i));
//
//                if (attr.getAttrType().equals(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest)) {
//                    X509Extensions extensions = X509Extensions.getInstance(attr.getAttrValues().getObjectAt(0));
//
//                    Enumeration e = extensions.oids();
//                    while (e.hasMoreElements()) {
//                        DERObjectIdentifier oid = (DERObjectIdentifier) e.nextElement();
//                        X509Extension ext = extensions.getExtension(oid);
//
//                        certGen.addExtension(oid, ext.isCritical(), ext.getValue().getOctets());
//                    }
//                }
//            }
//            X509Certificate issuedCert = certGen.generateX509Certificate(privateKey);
//            return issuedCert;
        } catch (Exception e) {
            throw new CaException("Error in signing the certificate", e);
        }
    }

}
