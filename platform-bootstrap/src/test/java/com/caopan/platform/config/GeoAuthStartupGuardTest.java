package com.caopan.platform.config;

import com.caopan.platform.geo.config.GeoAuthProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeoAuthStartupGuardTest {

    @Mock
    private Environment environment;

    @Test
    void authDisabled_allowsStart() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"test"});
        GeoAuthStartupGuard guard = new GeoAuthStartupGuard(environment, auth(false, ""));
        assertDoesNotThrow(() -> guard.run(new DefaultApplicationArguments()));
    }

    @Test
    void authDisabled_onlineProfile_rejects() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"online"});
        GeoAuthStartupGuard guard = new GeoAuthStartupGuard(environment, auth(false, ""));
        assertThrows(IllegalStateException.class,
                () -> guard.run(new DefaultApplicationArguments()));
    }

    @Test
    void authEnabled_onlineWithoutIssueSecret_rejects() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"online"});
        GeoAuthStartupGuard guard = new GeoAuthStartupGuard(environment, auth(true, ""));
        assertThrows(IllegalStateException.class,
                () -> guard.run(new DefaultApplicationArguments()));
    }

    @Test
    void authEnabled_onlineWithIssueSecret_allows() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"online"});
        GeoAuthStartupGuard guard = new GeoAuthStartupGuard(environment, auth(true, "secret"));
        assertDoesNotThrow(() -> guard.run(new DefaultApplicationArguments()));
    }

    private static GeoAuthProperties auth(boolean enabled, String issueSecret) {
        return new GeoAuthProperties(
                enabled, issueSecret, true,
                "platform:auth:issue-lock:", "platform:auth:valid:",
                60L, 8, 50L, 365L);
    }
}
