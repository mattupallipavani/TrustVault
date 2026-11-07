package com.trustvault.config;

import com.trustvault.entity.User;
import com.trustvault.enums.Role;
import com.trustvault.repository.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Creates one account per role on an empty database so the deployed demo can
 * be signed into immediately.
 *
 * Only runs when there are no users at all, so it can never overwrite real
 * data. Disable with SEED_ENABLED=false. The demo password is supplied by
 * DEMO_PASSWORD; the default in application.properties is documented in the
 * README and is obviously not a production credential.
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final boolean enabled;
    private final String demoPassword;

    public DataSeeder(UserRepository userRepository,
                      PasswordEncoder passwordEncoder,
                      @Value("${trustvault.seed.enabled:true}") boolean enabled,
                      @Value("${trustvault.demo-password}") String demoPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.enabled = enabled;
        this.demoPassword = demoPassword;
    }

    @Override
    public void run(ApplicationArguments args) {

        if (!enabled) {
            return;
        }

        if (userRepository.count() > 0) {
            return;
        }

        create("TrustVault Admin", "admin@trustvault.local", Role.ADMIN);
        create("Priya Owner", "owner@trustvault.local", Role.OWNER);
        create("Sam Viewer", "viewer@trustvault.local", Role.VIEWER);

        log.info("Seeded demo accounts for roles ADMIN, OWNER and VIEWER");
    }

    private void create(String fullName, String email, Role role) {

        User user = new User();
        user.setFullName(fullName);
        user.setEmail(email.toLowerCase(Locale.ROOT));
        user.setPasswordHash(passwordEncoder.encode(demoPassword));
        user.setRole(role);

        userRepository.save(user);
    }
}
