package com.caopan.platform.geo.cache;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeoCacheKeysTest {

    @Test
    void isGeoDataKey_acceptsDataNamespaces() {
        assertTrue(GeoCacheKeys.isGeoDataKey("platform:geo:countries:"));
        assertTrue(GeoCacheKeys.isGeoDataKey("platform:geo:countries:vn"));
        assertTrue(GeoCacheKeys.isGeoDataKey("platform:geo:children:1"));
        assertTrue(GeoCacheKeys.isGeoDataKey("platform:geo:path:2"));
        assertTrue(GeoCacheKeys.isGeoDataKey("platform:geo:region:3"));
        assertTrue(GeoCacheKeys.isGeoDataKey("platform:geo:tree:VN:0:3"));
    }

    @Test
    void isGeoDataKey_rejectsRateLimitAndAuth() {
        assertFalse(GeoCacheKeys.isGeoDataKey("platform:geo:rl:127.0.0.1:default"));
        assertFalse(GeoCacheKeys.isGeoDataKey("platform:auth:valid:abc"));
        assertFalse(GeoCacheKeys.isGeoDataKey("other:key"));
        assertFalse(GeoCacheKeys.isGeoDataKey(null));
    }
}
