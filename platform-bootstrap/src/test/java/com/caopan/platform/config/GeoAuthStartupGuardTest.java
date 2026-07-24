package com.caopan.platform.config;

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
        GeoAuthStartupGuard guard = new GeoAuthStartupGuard(environment, false, "");
        assertDoesNotThrow(() -> guard.run(new DefaultApplicationArguments()));
    }

    @Test
    void authEnabled_emptyToken_rejects() {
        GeoAuthStartupGuard guard = new GeoAuthStartupGuard(environment, true, "  ");
        assertThrows(IllegalStateException.class,
                () -> guard.run(new DefaultApplicationArguments()));
    }

    @Test
    void authEnabled_shortToken_rejects() {
        GeoAuthStartupGuard guard = new GeoAuthStartupGuard(environment, true, "short");
        assertThrows(IllegalStateException.class,
                () -> guard.run(new DefaultApplicationArguments()));
    }

    @Test
    void authEnabled_validToken_allows() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"online"});
        GeoAuthStartupGuard guard = new GeoAuthStartupGuard(
                environment, true, "0123456789abcdef");
        assertDoesNotThrow(() -> guard.run(new DefaultApplicationArguments()));
    }
}
