package com.example.e2e.gradle;

import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;

/**
 * Build-script DSL:
 *
 * <pre>
 * e2eDistribution {
 *     testPackage = 'com.example.myapp.e2e'          // optional; default: scan whole classpath
 *     systemProperties = ['e2e.target.url': 'https://staging.internal']  // baked-in defaults
 * }
 * </pre>
 */
public abstract class E2eDistributionExtension {

    /**
     * Package the runner scans for tests. When unset, the runner scans every
     * classpath root, which works but is slower and less intentional.
     */
    public abstract Property<String> getTestPackage();

    /**
     * Default system properties baked into the launch scripts. Operators can
     * override any of them at run time with E2E_TESTS_OPTS.
     */
    public abstract MapProperty<String, String> getSystemProperties();
}
