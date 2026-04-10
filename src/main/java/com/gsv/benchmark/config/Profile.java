package com.gsv.benchmark.config;

/**
 * Runtime profile that controls which database connections are used.
 *
 * <p>Resolved in priority order (first match wins):
 * <ol>
 *   <li>Java system property: {@code -Dbenchmark.profile=prod}</li>
 *   <li>Environment variable: {@code BENCHMARK_PROFILE=prod}</li>
 *   <li>Default: {@code local}</li>
 * </ol>
 *
 * <p>Available profiles:
 * <ul>
 *   <li>{@code local} — Docker Compose (plain username/password, no SSL).</li>
 *   <li>{@code prod}  — AWS RDS PostgreSQL (IAM auth token + SSL) and
 *                       Amazon DocumentDB (TLS + env-var credentials).</li>
 * </ul>
 *
 * <p>Examples:
 * <pre>
 *   # Java system property (recommended — mirrors Spring's -Dspring.profiles.active)
 *   java -Dbenchmark.profile=prod -jar db-benchmark-1.0.0.jar
 *
 *   # Environment variable (useful in containers / CI pipelines)
 *   BENCHMARK_PROFILE=prod java -jar db-benchmark-1.0.0.jar
 * </pre>
 */
public enum Profile {
    LOCAL, PROD;

    /** Java system property key — {@code -Dbenchmark.profile=prod} */
    private static final String SYS_PROP = "benchmark.profile";

    /** Environment variable key — {@code BENCHMARK_PROFILE=prod} */
    private static final String ENV_VAR  = "BENCHMARK_PROFILE";

    /**
     * Resolves the active profile.
     * System property ({@code -Dbenchmark.profile}) takes priority over the
     * {@code BENCHMARK_PROFILE} environment variable; falls back to {@code LOCAL}.
     */
    public static Profile resolve() {
        // 1. Java system property: -Dbenchmark.profile=prod
        String sysProp = System.getProperty(SYS_PROP);
        if (sysProp != null && !sysProp.isBlank()) {
            return parse(sysProp.trim(), SYS_PROP);
        }

        // 2. Environment variable: BENCHMARK_PROFILE=prod
        String envVal = System.getenv(ENV_VAR);
        if (envVal != null && !envVal.isBlank()) {
            return parse(envVal.trim(), ENV_VAR);
        }

        // 3. Default
        return LOCAL;
    }

    private static Profile parse(String value, String source) {
        if (value.equalsIgnoreCase("prod"))  return PROD;
        if (value.equalsIgnoreCase("local")) return LOCAL;
        throw new IllegalArgumentException(
            String.format("Unknown profile '%s' from '%s'. Valid values: local, prod.", value, source));
    }
}
