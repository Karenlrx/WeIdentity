/*
 *       Copyright© (2018-2019) WeBank Co., Ltd.
 *
 *       This file is part of weid-java-sdk.
 *
 *       weid-java-sdk is free software: you can redistribute it and/or modify
 *       it under the terms of the GNU Lesser General Public License as published by
 *       the Free Software Foundation, either version 3 of the License, or
 *       (at your option) any later version.
 *
 *       weid-java-sdk is distributed in the hope that it will be useful,
 *       but WITHOUT ANY WARRANTY; without even the implied warranty of
 *       MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *       GNU Lesser General Public License for more details.
 *
 *       You should have received a copy of the GNU Lesser General Public License
 *       along with weid-java-sdk.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.webank.weid.service.impl.engine.fiscov2;

import java.io.IOException;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.fisco.bcos.web3j.abi.EventEncoder;
import org.fisco.bcos.web3j.protocol.Web3j;
import org.fisco.bcos.web3j.protocol.core.DefaultBlockParameterNumber;
import org.fisco.bcos.web3j.protocol.core.methods.response.BcosBlock;
import org.fisco.bcos.web3j.protocol.core.methods.response.BcosTransactionReceipt;
import org.fisco.bcos.web3j.protocol.core.methods.response.Log;
import org.fisco.bcos.web3j.protocol.core.methods.response.Transaction;
import org.fisco.bcos.web3j.protocol.core.methods.response.TransactionReceipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webank.weid.constant.ErrorCode;
import com.webank.weid.constant.ResolveEventLogStatus;
import com.webank.weid.constant.WeIdConstant;
import com.webank.weid.constant.WeIdConstant.PublicKeyType;
import com.webank.weid.constant.WeIdEventConstant;
import com.webank.weid.contract.v2.WeIdContract;
import com.webank.weid.contract.v2.WeIdContract.WeIdAttributeChangedEventResponse;
import com.webank.weid.exception.DataTypeCastException;
import com.webank.weid.exception.ResolveAttributeException;
import com.webank.weid.protocol.base.AuthenticationProperty;
import com.webank.weid.protocol.base.PublicKeyProperty;
import com.webank.weid.protocol.base.ServiceProperty;
import com.webank.weid.protocol.base.WeIdDocument;
import com.webank.weid.protocol.response.ResolveEventLogResult;
import com.webank.weid.protocol.response.ResponseData;
import com.webank.weid.protocol.response.TransactionInfo;
import com.webank.weid.service.impl.engine.BaseEngine;
import com.webank.weid.service.impl.engine.WeIdServiceEngine;
import com.webank.weid.util.DataToolUtils;
import com.webank.weid.util.DateUtils;
import com.webank.weid.util.WeIdUtils;

/**
 * WeIdServiceEngine call weid contract which runs on FISCO BCOS 2.0.
 *
 * @author tonychen 2019年6月21日
 */
public class WeIdServiceEngineV2 extends BaseEngine implements WeIdServiceEngine {

    private static final Logger logger = LoggerFactory.getLogger(WeIdServiceEngineV2.class);

    /**
     * The topic map.
     */
    private static final HashMap<String, String> topicMap;

    /**
     * Block number for stopping parsing.
     */
    private static final int STOP_RESOLVE_BLOCK_NUMBER = 0;

    /**
     * WeIdentity DID contract object, for calling weIdentity DID contract.
     */
    private static WeIdContract weIdContract;

    static {
        // initialize the event topic
        topicMap = new HashMap<String, String>();

        topicMap.put(
            EventEncoder.encode(WeIdContract.WEIDATTRIBUTECHANGED_EVENT),
            WeIdEventConstant.WEID_EVENT_ATTRIBUTE_CHANGE
        );
    }

    /**
     * 构造函数.
     */
    public WeIdServiceEngineV2() {
        if (weIdContract == null) {
            reload();
        }
    }

    private static ResolveEventLogResult resolveAttributeEvent(
        String weId,
        TransactionReceipt receipt,
        WeIdDocument result) {

        List<WeIdAttributeChangedEventResponse> eventlog =
            weIdContract.getWeIdAttributeChangedEvents(receipt);
        ResolveEventLogResult response = new ResolveEventLogResult();

        if (CollectionUtils.isEmpty(eventlog)) {
            response.setResolveEventLogStatus(ResolveEventLogStatus.STATUS_EVENTLOG_NULL);
            return response;
        }

        int previousBlock = 0;
        for (WeIdAttributeChangedEventResponse res : eventlog) {
            if (res.identity == null || res.updated == null || res.previousBlock == null) {
                response.setResolveEventLogStatus(ResolveEventLogStatus.STATUS_RES_NULL);
                return response;
            }

            String identity = res.identity.toString();
            if (result.getUpdated() == null) {
                long timeStamp = res.updated.longValue();
                result.setUpdated(timeStamp);
            }
            String weAddress = WeIdUtils.convertWeIdToAddress(weId);
            if (!StringUtils.equals(weAddress, identity)) {
                response.setResolveEventLogStatus(ResolveEventLogStatus.STATUS_KEY_NOT_MATCH);
                return response;
            }

            String key = new String(res.key);
            String value = new String(res.value);
            previousBlock = res.previousBlock.intValue();
            buildupWeIdAttribute(key, value, weId, result);
        }

        response.setPreviousBlock(previousBlock);
        response.setResolveEventLogStatus(ResolveEventLogStatus.STATUS_SUCCESS);
        return response;
    }

    private static void buildupWeIdAttribute(
        String key, String value, String weId, WeIdDocument result) {
        if (StringUtils.startsWith(key, WeIdConstant.WEID_DOC_PUBLICKEY_PREFIX)) {
            buildWeIdPublicKeys(key, value, weId, result);
        } else if (StringUtils.startsWith(key, WeIdConstant.WEID_DOC_AUTHENTICATE_PREFIX)) {
            if (!value.contains(WeIdConstant.REMOVED_PUBKEY_TAG)) {
                buildWeIdPublicKeys(null, value, weId, result);
            }
            buildWeIdAuthentication(value, weId, result);
        } else if (StringUtils.startsWith(key, WeIdConstant.WEID_DOC_SERVICE_PREFIX)) {
            buildWeIdService(key, value, weId, result);
        } else {
            buildWeIdAttributeDefault(key, value, weId, result);
        }
    }

    private static void buildWeIdPublicKeys(String key, String value, String weId,
        WeIdDocument result) {

        logger.info("method buildWeIdPublicKeys() parameter::value:{}, weId:{}, "
            + "result:{}", value, weId, result);
        List<PublicKeyProperty> pubkeyList = result.getPublicKey();

        String type = PublicKeyType.SECP256K1.getTypeName();
        // Identify explicit type from key
        if (!StringUtils.isEmpty(key)) {
            String[] keyArray = StringUtils.splitByWholeSeparator(key, "/");
            if (keyArray.length > 2) {
                type = keyArray[2];
            }
        }

        // Only store the latest public key
        // OBSOLETE and non-OBSOLETE public keys are regarded as the same
        String trimmedPubKey = StringUtils
            .splitByWholeSeparator(value.replace(WeIdConstant.REMOVED_PUBKEY_TAG, ""), "/")[0];
        for (PublicKeyProperty pr : pubkeyList) {
            if (pr.getPublicKey().contains(trimmedPubKey)) {
                return;
            }
        }
        PublicKeyProperty pubKey = new PublicKeyProperty();
        pubKey.setId(
            new StringBuffer()
                .append(weId)
                .append("#keys-")
                .append(result.getPublicKey().size())
                .toString()
        );
        String[] publicKeyData = StringUtils.splitByWholeSeparator(value, "/");
        if (publicKeyData != null && publicKeyData.length == 2) {
            pubKey.setPublicKey(publicKeyData[0]);
            String weAddress = publicKeyData[1];
            String owner = WeIdUtils.convertAddressToWeId(weAddress);
            pubKey.setOwner(owner);
        }
        pubKey.setType(type);
        result.getPublicKey().add(pubKey);
    }

    private static void buildWeIdAuthentication(String value, String weId, WeIdDocument result) {

        logger.info("method buildWeIdAuthentication() parameter::value:{}, weId:{}, "
            + "result:{}", value, weId, result);
        AuthenticationProperty auth = new AuthenticationProperty();
        List<PublicKeyProperty> keys = result.getPublicKey();
        List<AuthenticationProperty> authList = result.getAuthentication();

        // Firstly, if this is an obsolete auth, directly append it and return unless a same
        // one exists; if this is a normal auth, then check whether there is an existing obsolete
        // one. if so, return. if not, go down further.
        if (value.contains(WeIdConstant.REMOVED_AUTHENTICATION_TAG)) {
            for (AuthenticationProperty ap : authList) {
                String pubKeyId = ap.getPublicKey();
                for (PublicKeyProperty pkp : keys) {
                    if (pubKeyId.equalsIgnoreCase(pkp.getId()) && value
                        .contains(pkp.getPublicKey())) {
                        return;
                    }
                }
            }
            auth.setPublicKey(value);
            result.getAuthentication().add(auth);
        } else {
            for (AuthenticationProperty ap : authList) {
                if (ap.getPublicKey()
                    .replace(WeIdConstant.REMOVED_AUTHENTICATION_TAG, "")
                    .contains(value) && ap.getPublicKey()
                    .contains(WeIdConstant.REMOVED_AUTHENTICATION_TAG)) {
                    return;
                }
            }
        }

        for (PublicKeyProperty r : keys) {
            if (StringUtils.contains(value, r.getPublicKey())) {
                for (AuthenticationProperty ar : authList) {
                    if (StringUtils.equals(ar.getPublicKey(), r.getId())) {
                        return;
                    }
                }
                auth.setPublicKey(r.getId());
                result.getAuthentication().add(auth);
            }
        }
    }

    private static void buildWeIdService(String key, String value, String weId,
        WeIdDocument result) {

        logger.info("method buildWeIdService() parameter::key{}, value:{}, weId:{}, "
            + "result:{}", key, value, weId, result);
        String service = StringUtils.splitByWholeSeparator(key, "/")[2];
        List<ServiceProperty> serviceList = result.getService();
        for (ServiceProperty sr : serviceList) {
            if (StringUtils.equals(service, sr.getType())) {
                return;
            }
        }
        ServiceProperty serviceResult = new ServiceProperty();
        serviceResult.setType(service);
        serviceResult.setServiceEndpoint(value);
        result.getService().add(serviceResult);
    }

    private static void buildWeIdAttributeDefault(
        String key, String value, String weId, WeIdDocument result) {

        logger.info("method buildWeIdAttributeDefault() parameter::key{}, value:{}, weId:{}, "
            + "result:{}", key, value, weId, result);
        switch (key) {
            case WeIdConstant.WEID_DOC_CREATED:
                result.setCreated(Long.valueOf(value));
                break;
            default:
                break;
        }
    }

    private static ResolveEventLogResult resolveEventLog(
        String weId, Log log, TransactionReceipt receipt, WeIdDocument result) {
        String topic = log.getTopics().get(0);
        String event = topicMap.get(topic);

        if (StringUtils.isNotBlank(event)) {
            switch (event) {
                case WeIdConstant.WEID_EVENT_ATTRIBUTE_CHANGE:
                    return resolveAttributeEvent(weId, receipt, result);
                default:
            }
        }
        ResolveEventLogResult response = new ResolveEventLogResult();
        response.setResolveEventLogStatus(ResolveEventLogStatus.STATUS_EVENT_NULL);
        return response;
    }

    private static void resolveTransaction(
        String weId,
        int blockNumber,
        WeIdDocument result) {

        int previousBlock = blockNumber;
        while (previousBlock != STOP_RESOLVE_BLOCK_NUMBER) {
            int currentBlockNumber = previousBlock;
            BcosBlock latestBlock = null;
            try {
                latestBlock =
                    ((Web3j) getWeb3j())
                        .getBlockByNumber(
                            new DefaultBlockParameterNumber(currentBlockNumber),
                            true
                        )
                        .send();
            } catch (IOException e) {
                logger.error(
                    "[resolveTransaction]:get block by number :{} failed. Exception message:{}",
                    currentBlockNumber,
                    e
                );
            }
            if (latestBlock == null) {
                logger.info(
                    "[resolveTransaction]:get block by number :{} . latestBlock is null",
                    currentBlockNumber
                );
                return;
            }
            List<Transaction> transList =
                latestBlock
                    .getBlock()
                    .getTransactions()
                    .stream()
                    .map(transactionResult -> (Transaction) transactionResult.get())
                    .collect(Collectors.toList());

            previousBlock = 0;
            try {
                for (Transaction transaction : transList) {
                    String transHash = transaction.getHash();

                    BcosTransactionReceipt rec1 = ((Web3j) getWeb3j())
                        .getTransactionReceipt(transHash)
                        .send();
                    TransactionReceipt receipt = rec1.getTransactionReceipt().get();
                    List<Log> logs = rec1.getResult().getLogs();
                    for (Log log : logs) {
                        ResolveEventLogResult returnValue =
                            resolveEventLog(weId, log, receipt, result);
                        if (returnValue.getResultStatus().equals(
                            ResolveEventLogStatus.STATUS_SUCCESS)) {
                            if (returnValue.getPreviousBlock() == currentBlockNumber) {
                                continue;
                            }
                            previousBlock = returnValue.getPreviousBlock();
                        }
                    }
                }
            } catch (IOException | DataTypeCastException e) {
                logger.error(
                    "[resolveTransaction]: get TransactionReceipt by weId :{} failed.",
                    weId,
                    e
                );
                throw new ResolveAttributeException(
                    ErrorCode.TRANSACTION_EXECUTE_ERROR.getCode(),
                    ErrorCode.TRANSACTION_EXECUTE_ERROR.getCodeDesc());
            }
        }
    }

    /**
     * 重新加载静态合约对象.
     */
    public void reload() {
        weIdContract = getContractService(fiscoConfig.getWeIdAddress(), WeIdContract.class);
    }

    /* (non-Javadoc)
     * @see com.webank.weid.service.impl.engine.WeIdController#isWeIdExist(java.lang.String)
     */
    @Override
    public ResponseData<Boolean> isWeIdExist(String weId) {
        try {

            boolean isExist = weIdContract
                .isIdentityExist(WeIdUtils.convertWeIdToAddress(weId)).send().booleanValue();
            return new ResponseData<>(isExist, ErrorCode.SUCCESS);
        } catch (Exception e) {
            logger.error("[isWeIdExist] execute failed. Error message :{}", e);
            return new ResponseData<>(false, ErrorCode.UNKNOW_ERROR);
        }
    }

    /* (non-Javadoc)
     * @see com.webank.weid.service.impl.engine.WeIdController#getWeIdDocument(java.lang.String)
     */
    @Override
    public ResponseData<WeIdDocument> getWeIdDocument(String weId) {
        WeIdDocument result = new WeIdDocument();
        result.setId(weId);
        int latestBlockNumber = 0;
        try {
            String identityAddr = WeIdUtils.convertWeIdToAddress(weId);
            latestBlockNumber = weIdContract
                .getLatestRelatedBlock(identityAddr).send().intValue();
            if (0 == latestBlockNumber) {
                return new ResponseData<>(null, ErrorCode.WEID_DOES_NOT_EXIST);
            }

            resolveTransaction(weId, latestBlockNumber, result);
            return new ResponseData<WeIdDocument>(result, ErrorCode.SUCCESS);
        } catch (InterruptedException | ExecutionException e) {
            logger.error("Set weId service failed. Error message :{}", e);
            return new ResponseData<>(null, ErrorCode.TRANSACTION_EXECUTE_ERROR);
        } catch (TimeoutException e) {
            logger.error("Set weId service timeout. Error message :{}", e);
            return new ResponseData<>(null, ErrorCode.TRANSACTION_TIMEOUT);
        } catch (ResolveAttributeException e) {
            logger.error("[getWeIdDocument]: resolveTransaction failed. "
                    + "weId: {}, errorCode:{}",
                weId,
                e.getErrorCode(),
                e);
            return new ResponseData<WeIdDocument>(result,
                ErrorCode.getTypeByErrorCode(e.getErrorCode()));
        } catch (Exception e) {
            logger.error("[getWeIdDocument]: exception.", e);
            return new ResponseData<>(null, ErrorCode.UNKNOW_ERROR);
        }
    }

    /* (non-Javadoc)
     * @see com.webank.weid.service.impl.engine.WeIdController
     * #createWeId(java.lang.String, java.lang.String, java.lang.String)
     */
    @Override
    public ResponseData<Boolean> createWeId(
        String weAddress,
        String publicKey,
        String privateKey,
        boolean isDelegate) {

        String auth = new StringBuffer()
            .append(publicKey)
            .append(WeIdConstant.SEPARATOR)
            .append(weAddress)
            .toString();
        String created = DateUtils.getNoMillisecondTimeStampString();
        TransactionReceipt receipt;
        WeIdContract weIdContract =
            reloadContract(fiscoConfig.getWeIdAddress(), privateKey, WeIdContract.class);
        try {
            if (isDelegate) {
                receipt = weIdContract.delegateCreateWeId(
                    weAddress,
                    DataToolUtils.stringToByteArray(auth),
                    DataToolUtils.stringToByteArray(created),
                    BigInteger.valueOf(DateUtils.getNoMillisecondTimeStamp())
                ).send();
            } else {

                receipt = weIdContract.createWeId(
                    weAddress,
                    DataToolUtils.stringToByteArray(auth),
                    DataToolUtils.stringToByteArray(created),
                    BigInteger.valueOf(DateUtils.getNoMillisecondTimeStamp())
                ).send();
            }

            TransactionInfo info = new TransactionInfo(receipt);
            List<WeIdAttributeChangedEventResponse> response =
                weIdContract.getWeIdAttributeChangedEvents(receipt);
            if (CollectionUtils.isEmpty(response)) {
                logger.error(
                    "The input private key does not match the current weid, operation of "
                        + "modifying weid is not allowed. we address is {}",
                    weAddress
                );
                return new ResponseData(false, ErrorCode.WEID_PRIVATEKEY_DOES_NOT_MATCH, info);
            }
            return new ResponseData(true, ErrorCode.SUCCESS, info);
        } catch (Exception e) {
            logger.error("[createWeId] create weid has error, Error Message：{}", e);
            return new ResponseData(false, ErrorCode.WEID_PRIVATEKEY_DOES_NOT_MATCH);
        }
    }

    /* (non-Javadoc)
     * @see com.webank.weid.service.impl.engine.WeIdController
     * #setAttribute(java.lang.String, java.lang.String, java.lang.String)
     */
    @Override
    public ResponseData<Boolean> setAttribute(
        String weAddress,
        String attributeKey,
        String value,
        String privateKey,
        boolean isDelegate) {

        try {
            WeIdContract weIdContract =
                reloadContract(fiscoConfig.getWeIdAddress(), privateKey, WeIdContract.class);
            byte[] attrValue = value.getBytes();
            BigInteger updated = BigInteger.valueOf(DateUtils.getNoMillisecondTimeStamp());
            TransactionReceipt transactionReceipt = null;
            if (isDelegate) {
                transactionReceipt = weIdContract.delegateSetAttribute(
                    weAddress,
                    DataToolUtils.stringToByte32Array(attributeKey),
                    attrValue,
                    updated
                ).send();
            } else {
                transactionReceipt =
                    weIdContract.setAttribute(
                        weAddress,
                        DataToolUtils.stringToByte32Array(attributeKey),
                        attrValue,
                        updated
                    ).send();
            }

            TransactionInfo info = new TransactionInfo(transactionReceipt);
            List<WeIdAttributeChangedEventResponse> response =
                weIdContract.getWeIdAttributeChangedEvents(transactionReceipt);
            if (CollectionUtils.isNotEmpty(response)) {
                return new ResponseData<>(true, ErrorCode.SUCCESS, info);
            } else {
                return new ResponseData<>(false, ErrorCode.WEID_PRIVATEKEY_DOES_NOT_MATCH,
                    info);
            }
        } catch (Exception e) {
            logger.error("[setAttribute] set Attribute has error, Error Message：{}", e);
            return new ResponseData<>(false, ErrorCode.UNKNOW_ERROR);
        }
    }
}
