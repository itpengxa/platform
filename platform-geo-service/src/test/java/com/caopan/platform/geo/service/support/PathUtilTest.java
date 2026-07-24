package com.caopan.platform.geo.service.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathUtilTest {

    @Test
    void parsePathIds_normal() {
        assertEquals(List.of(1L, 200000001L, 300000010L),
                PathUtil.parsePathIds("/1/200000001/300000010/"));
    }

    @Test
    void parsePathIds_blankOrNull() {
        assertTrue(PathUtil.parsePathIds(null).isEmpty());
        assertTrue(PathUtil.parsePathIds("").isEmpty());
        assertTrue(PathUtil.parsePathIds("   ").isEmpty());
    }

    @Test
    void parsePathIds_skipsInvalidSegments() {
        assertEquals(List.of(1L, 3L), PathUtil.parsePathIds("/1/abc/3/"));
    }
}
