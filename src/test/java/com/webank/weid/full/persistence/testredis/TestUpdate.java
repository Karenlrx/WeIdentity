package com.webank.weid.full.persistence.testredis;

import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webank.weid.common.LogUtil;
import com.webank.weid.constant.ErrorCode;
import com.webank.weid.full.transportation.TestBaseTransportation;
import com.webank.weid.protocol.response.ResponseData;
import com.webank.weid.suite.api.persistence.inf.Persistence;
import com.webank.weid.suite.persistence.redis.driver.RedisDriver;

public class TestUpdate extends TestBaseTransportation {

    private static final Logger logger = LoggerFactory.getLogger(TestUpdate.class);

    private static String domain = "domain.defaultInfo";

    private static String id = "123456";

    private static final String data = "data123456";

    private Persistence persistence = null;

    @Override
    public synchronized void testInit() {

        persistence = new RedisDriver();
        persistence.delete(domain, id);
        ResponseData<Integer> ret = persistence.add(domain, id, data);
        Assert.assertEquals(ErrorCode.SUCCESS.getCode(), ret.getErrorCode().intValue());

    }

    @Test
    /**
     * case:test update.
     */
    public void testUpdate_success() {

        ResponseData<Integer> res = persistence.update(
            domain, id, data + " update");
        LogUtil.info(logger, "persistence", res);

        Assert.assertEquals(ErrorCode.SUCCESS.getCode(), res.getErrorCode().intValue());

        ResponseData<String> result = persistence.get(domain, id);
        Assert.assertEquals(data + " update", result.getResult());
    }

    @Test
    /**
     * case:test update domain is null.
     */
    public void testUpdate_domainNull() {
        String afterData = data + Math.random();
        ResponseData<Integer> res = persistence.update(null, id, afterData);
        LogUtil.info(logger, "persistence", res);

        Assert.assertEquals(ErrorCode.SUCCESS.getCode(), res.getErrorCode().intValue());

        ResponseData<String> result = persistence.get(domain, id);
        Assert.assertEquals(afterData, result.getResult());
    }

    @Test
    /**
     * case:domain is blank.
     */
    public void testUpdate_domainBlank() {
        String afterData = data + Math.random();
        ResponseData<Integer> res = persistence.update("", id, afterData);
        LogUtil.info(logger, "persistence", res);

        Assert.assertEquals(ErrorCode.SUCCESS.getCode(), res.getErrorCode().intValue());

        ResponseData<String> result = persistence.get(domain, id);
        Assert.assertEquals(afterData, result.getResult());
    }

    @Test
    /**
     * case:test update table is not exist.
     */
    public void testUpdate_idNull() {

        ResponseData<Integer> res = persistence.update(domain, null, data);
        LogUtil.info(logger, "persistence", res);

        Assert.assertEquals(ErrorCode.PRESISTENCE_DATA_KEY_INVALID.getCode(),
            res.getErrorCode().intValue());
    }

    @Test
    /**
     * case:test update id is blank.
     */
    public void testUpdate_idBlank() {

        ResponseData<Integer> res = persistence.update(
            "datasource9999:sdk_all_data", "", "data123456");
        LogUtil.info(logger, "persistence", res);

        Assert.assertEquals(ErrorCode.PRESISTENCE_DATA_KEY_INVALID.getCode(),
            res.getErrorCode().intValue());
    }

    @Test
    /**
     * case:test update data is null.
     */
    public void testUpdate_dataNull() {

        ResponseData<Integer> res = persistence.update(
            domain, id, null);
        LogUtil.info(logger, "persistence", res);

        Assert.assertEquals(ErrorCode.SUCCESS.getCode(), res.getErrorCode().intValue());
        Assert.assertEquals(1, res.getResult().intValue());
    }

    @Test
    /**
     * case:test update data is blank.
     */
    public void testUpdate_dataBlank() {

        ResponseData<Integer> res = persistence.update(
            domain, id, "");
        LogUtil.info(logger, "persistence", res);

        Assert.assertEquals(ErrorCode.SUCCESS.getCode(), res.getErrorCode().intValue());

        ResponseData<String> result = persistence.get(domain, id);
        Assert.assertEquals("", result.getResult());
    }

}
