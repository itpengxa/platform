package com.caopan.platform.common.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ResultTest {

    @Test
    void ok_setsSuccessPayload() {
        Result<String> r = Result.ok("data");
        assertEquals(0, r.getCode());
        assertEquals("success", r.getMessage());
        assertEquals("data", r.getData());
    }

    @Test
    void fail_clearsData() {
        Result<String> r = Result.fail(40000, "bad");
        assertEquals(40000, r.getCode());
        assertEquals("bad", r.getMessage());
        assertNull(r.getData());
    }
}
