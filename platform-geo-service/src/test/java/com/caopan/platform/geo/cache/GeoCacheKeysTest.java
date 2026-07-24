package com.caopan.platform.geo.cache;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeoCacheKeysTest {

    @Test
    void countries_nullKeywordUsesEmptySuffix() {
        assertEquals("platform:geo:countries:", GeoCacheKeys.countries(null));
        assertEquals("platform:geo:countries:VN", GeoCacheKeys.countries("VN"));
    }

    @Test
    void regionChildrenPathKeys() {
        assertEquals("platform:geo:region:10", GeoCacheKeys.region(10L));
        assertEquals("platform:geo:children:10", GeoCacheKeys.children(10L));
        assertEquals("platform:geo:path:10", GeoCacheKeys.path(10L));
    }

    @Test
    void tree_nullRootAndDepthNormalized() {
        assertEquals("platform:geo:tree:VN:0:0", GeoCacheKeys.tree("VN", null, null));
        assertEquals("platform:geo:tree:VN:9:3", GeoCacheKeys.tree("VN", 9L, 3));
        assertTrue(GeoCacheKeys.tree("CN", 1L, 2).startsWith(GeoCacheKeys.PREFIX));
    }
}
