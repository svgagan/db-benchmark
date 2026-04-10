package com.gsv.benchmark.config;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsUtilities;
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest;

/**
 * Generates a short-lived IAM authentication token for an Amazon RDS
 * PostgreSQL instance.
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>Uses the AWS Default Credential Chain to obtain credentials —
 *       on EC2/ECS/EKS this resolves to the attached IAM role automatically.</li>
 *   <li>Calls {@code RdsUtilities.generateAuthenticationToken()} which signs a
 *       pre-signed URL with the credentials. The result is used as the JDBC
 *       password.</li>
 *   <li>The token is valid for <b>15 minutes</b>. For long-running benchmark
 *       runs, regenerate as needed (the benchmark completes well within that
 *       window).</li>
 * </ol>
 *
 * <h3>Required IAM permissions</h3>
 * The IAM role attached to the compute (EC2 / ECS task / Lambda) must have:
 * <pre>
 * {
 *   "Effect": "Allow",
 *   "Action": "rds-db:connect",
 *   "Resource": "arn:aws:rds-db:REGION:ACCOUNT_ID:dbuser:DB_RESOURCE_ID/DB_USERNAME"
 * }
 * </pre>
 *
 * <h3>RDS prerequisite</h3>
 * The PostgreSQL DB user must have the {@code rds_iam} role granted:
 * <pre>
 *   GRANT rds_iam TO your_db_user;
 * </pre>
 */
public class RdsIamTokenProvider {

    private RdsIamTokenProvider() {}

    /**
     * Generates an IAM authentication token for the given RDS endpoint.
     *
     * @param hostname  RDS instance endpoint (e.g. {@code mydb.xxxx.us-east-1.rds.amazonaws.com})
     * @param port      Database port (typically 5432 for PostgreSQL)
     * @param username  Database username that has {@code rds_iam} role
     * @param region    AWS region (e.g. {@code us-east-1})
     * @return          A signed token string to be used as the JDBC password
     */
    public static String generateToken(String hostname, int port, String username, String region) {
        RdsUtilities rdsUtilities = RdsUtilities.builder()
            .region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build();

        GenerateAuthenticationTokenRequest request = GenerateAuthenticationTokenRequest.builder()
            .hostname(hostname)
            .port(port)
            .username(username)
            .build();

        String token = rdsUtilities.generateAuthenticationToken(request);
        System.out.printf("  [RDS IAM] Auth token generated for user '%s' on %s:%d%n",
                          username, hostname, port);
        return token;
    }
}
