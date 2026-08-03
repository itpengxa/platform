package com.caopan.platform.geo.config.runtime;

import com.caopan.platform.geo.cache.GeoCacheProperties;
import com.caopan.platform.geo.config.GeoAccessLogProperties;
import com.caopan.platform.geo.config.GeoAdminProperties;
import com.caopan.platform.geo.config.GeoAuthProperties;
import com.caopan.platform.geo.config.GeoReportProperties;
import com.caopan.platform.geo.entity.PlatformRuntimeConfig;
import com.caopan.platform.geo.entity.PlatformRuntimeConfigAudit;
import com.caopan.platform.geo.mapper.PlatformRuntimeConfigAuditMapper;
import com.caopan.platform.geo.mapper.PlatformRuntimeConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuntimeConfigServiceTest {

    @Mock
    private PlatformRuntimeConfigMapper configMapper;
    @Mock
    private PlatformRuntimeConfigAuditMapper auditMapper;
    @Mock
    private ConfigChangeBroadcaster broadcaster;

    private EffectiveConfigRegistry registry;
    private RuntimeConfigService service;
    private ConfigCrypto crypto;

    @BeforeEach
    void setUp() {
        registry = new EffectiveConfigRegistry();
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        crypto = new ConfigCrypto(env, "unit-test-crypto");
        GeoReportProperties report = new GeoReportProperties(
                "nominatim", "", 50.0, true, 100, "platform-geo-test");
        GeoAuthProperties auth = new GeoAuthProperties(
                true, "issue", true,
                "platform:auth:issue-lock:", "platform:auth:valid:",
                60L, 8, 50L, 365L);
        GeoAccessLogProperties accessLog = new GeoAccessLogProperties(true, true, true, 2048);
        GeoAdminProperties admin = new GeoAdminProperties(true, "", "/admin", "admin", "admin", 7);
        GeoCacheProperties cache = new GeoCacheProperties(
                true, 10_000L, 10L, 24L, 24L, 24L, 24L, 12L, 300L, 30L, 20_000, 4);
        service = new RuntimeConfigService(
                registry, crypto, configMapper, auditMapper, broadcaster, env,
                report, auth, accessLog, admin, cache);
        when(configMapper.findAll()).thenReturn(List.of());
        service.reloadFromDb();
    }

    @Test
    void list_masksSecrets() {
        RuntimeConfigService.ConfigItemView view = service.listGroup("auth").stream()
                .filter(v -> v.key().endsWith("issue-secret"))
                .findFirst()
                .orElseThrow();
        assertTrue(view.secret());
        assertTrue(view.masked());
        assertEquals("******", view.value());
        assertTrue(view.hasValue());
        assertEquals("DEFAULT", view.source());
    }

    @Test
    void save_overridesAndHotReloads() {
        when(configMapper.findByKey(any())).thenReturn(null);
        when(configMapper.findAll()).thenAnswer(inv -> {
            PlatformRuntimeConfig row = new PlatformRuntimeConfig();
            row.setConfigKey("platform.geo.report.max-parent-distance-km");
            row.setConfigValue("12.5");
            row.setSecret(0);
            return List.of(row);
        });

        List<String> restart = service.save(List.of(
                new RuntimeConfigService.ConfigWriteItem(
                        "platform.geo.report.max-parent-distance-km", "12.5")), "admin");

        assertTrue(restart.isEmpty());
        ArgumentCaptor<PlatformRuntimeConfig> cap = ArgumentCaptor.forClass(PlatformRuntimeConfig.class);
        verify(configMapper).upsert(cap.capture());
        assertEquals("12.5", cap.getValue().getConfigValue());
        assertEquals(12.5, new EffectiveReportSettings(registry).maxParentDistanceKm());
        assertTrue(registry.isOverridden("platform.geo.report.max-parent-distance-km"));
        verify(broadcaster).publish();
        verify(auditMapper).insert(any(PlatformRuntimeConfigAudit.class));
    }

    @Test
    void save_secretEmpty_skips() {
        service.save(List.of(
                new RuntimeConfigService.ConfigWriteItem("platform.geo.auth.issue-secret", "")), "admin");
        verify(configMapper, never()).upsert(any());
    }

    @Test
    void save_sameAsEffective_skipsUpsert() {
        service.save(List.of(
                new RuntimeConfigService.ConfigWriteItem(
                        "platform.geo.report.max-parent-distance-km", "50.0")), "admin");
        verify(configMapper, never()).upsert(any());
    }

    @Test
    void reset_clearsOverride() {
        PlatformRuntimeConfig existing = new PlatformRuntimeConfig();
        existing.setConfigKey("platform.geo.report.max-parent-distance-km");
        existing.setConfigValue("99");
        existing.setSecret(0);
        when(configMapper.findByKey("platform.geo.report.max-parent-distance-km")).thenReturn(existing);
        when(configMapper.findAll()).thenReturn(List.of());

        service.reset(List.of("platform.geo.report.max-parent-distance-km"), "admin");
        verify(configMapper).deleteByKey("platform.geo.report.max-parent-distance-km");
        assertFalse(registry.isOverridden("platform.geo.report.max-parent-distance-km"));
        assertEquals(50.0, new EffectiveReportSettings(registry).maxParentDistanceKm());
    }
}
