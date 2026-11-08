package com.example.hotelmanagement.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class HotelSchemaMigrationTest {

    @Test
    void freshDatabaseMatchesEveryTableAndColumnInTheDesign() throws Exception {
        try (HikariDataSource dataSource = dataSource(h2Url(), "sa", "")) {
            Flyway flyway = migrate(dataSource);
            try (Connection connection = dataSource.getConnection()) {
                assertSchemaMatchesDesign(connection);
                assertReferenceData(connection);
                assertConstraints(connection);
            }
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        }
    }

    @Test
    void upgradesV1WithoutChangingExistingAccountsRolesOrRefreshTokens() throws Exception {
        assertV1UpgradePreservesData(h2Url(), "sa", "");
    }

    // Explicit opt-in against an empty, separately named local MySQL test schema.
    // The normal Maven suite never connects to the application's MySQL database.
    @Test
    @EnabledIfEnvironmentVariable(named = "HOTEL_MYSQL_TEST_URL", matches = ".+")
    void upgradesAndVerifiesTheDesignOnMySql() throws Exception {
        String url = System.getenv("HOTEL_MYSQL_TEST_URL");
        assertThat(url)
                .matches(
                        "jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):[0-9]+/hotel_migration_test_[a-z0-9_]+(?:\\?.*)?");
        String username = System.getenv().getOrDefault("HOTEL_MYSQL_TEST_USER", "root");
        String password = System.getenv().getOrDefault("HOTEL_MYSQL_TEST_PASSWORD", "");
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            assertThat(tableNames(connection)).isEmpty();
        }
        assertV1UpgradePreservesData(url, username, password);
    }

    private static String h2Url() {
        return "jdbc:h2:mem:hotel_migration_"
                + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE";
    }

    private static HikariDataSource dataSource(String url, String username, String password) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setMaximumPoolSize(2);
        return dataSource;
    }

    private static Flyway migrate(HikariDataSource dataSource) {
        Flyway flyway =
                Flyway.configure()
                        .dataSource(dataSource)
                        .locations("classpath:db/migration")
                        .load();
        flyway.migrate();
        return flyway;
    }

    private static void assertV1UpgradePreservesData(String url, String username, String password)
            throws Exception {
        try (HikariDataSource dataSource = dataSource(url, username, password)) {
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("1"))
                    .load()
                    .migrate();
            try (Connection c = dataSource.getConnection();
                    Statement s = c.createStatement()) {
                s.executeUpdate(
                        """
                        INSERT INTO users (id, full_name, email, password_hash, status)
                        VALUES (101, 'Existing Customer', 'existing@example.test', 'unchanged-password-hash', 'INACTIVE')
                        """);
                s.executeUpdate(
                        "INSERT INTO user_roles (user_id, role_id) SELECT 101, id FROM roles WHERE"
                                + " code='USER'");
                s.executeUpdate(
                        """
                        INSERT INTO refresh_tokens (user_id, token_hash, family_id, expires_at)
                        VALUES (101, 'existing-token-hash', 'existing-family', '2027-01-01 00:00:00')
                        """);
            }
            Flyway flyway = migrate(dataSource);
            try (Connection c = dataSource.getConnection();
                    Statement s = c.createStatement()) {
                assertSchemaMatchesDesign(c);
                assertReferenceData(c);
                assertThat(scalar(c, "SELECT password_hash FROM users WHERE id=101"))
                        .isEqualTo("unchanged-password-hash");
                assertThat(scalar(c, "SELECT status FROM users WHERE id=101"))
                        .isEqualTo("INACTIVE");
                assertThat(scalar(c, "SELECT token_hash FROM refresh_tokens WHERE user_id=101"))
                        .isEqualTo("existing-token-hash");
                assertThat(scalar(c, "SELECT COUNT(*) FROM user_roles WHERE user_id=101"))
                        .isEqualTo("1");
                s.executeUpdate("UPDATE users SET status='DISABLED' WHERE id=101");
                assertThat(scalar(c, "SELECT status FROM users WHERE id=101"))
                        .isEqualTo("DISABLED");
                assertConstraints(c);
            }
            flyway.validate();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        }
    }

    private static Map<String, Set<String>> designColumns() throws Exception {
        Pattern table = Pattern.compile("^Table ([a-z_]+) \\{");
        Pattern field =
                Pattern.compile(
                        "^  ([a-z_][a-z0-9_]*) (?:bigint|int|varchar\\([^)]*\\)|"
                            + "decimal\\([^)]*\\)|datetime\\([^)]*\\)|char\\([^)]*\\)|text|json|date|time|boolean)(?:\\s|$)");
        Map<String, Set<String>> design = new LinkedHashMap<>();
        Set<String> columns = null;
        for (String line : Files.readAllLines(Path.of("docs", "database.txt"))) {
            Matcher tableMatch = table.matcher(line);
            if (tableMatch.find()) {
                columns = new LinkedHashSet<>();
                design.put(tableMatch.group(1), columns);
            } else if (line.equals("}")) {
                columns = null;
            } else if (columns != null) {
                Matcher fieldMatch = field.matcher(line);
                if (fieldMatch.find()) {
                    columns.add(fieldMatch.group(1));
                }
            }
        }
        assertThat(design).hasSize(62);
        // V4 extends the original DBML contract for the CMS security and identity fields.
        design.get("users").add("security_version");
        design.get("customers")
                .addAll(
                        Set.of(
                                "document_type",
                                "document_number_encrypted",
                                "document_number_hash"));
        return design;
    }

    private static Set<String> tableNames(Connection c) throws SQLException {
        Set<String> tables = new LinkedHashSet<>();
        try (ResultSet rs =
                c.getMetaData()
                        .getTables(c.getCatalog(), c.getSchema(), "%", new String[] {"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME").toLowerCase();
                if (!name.equals("flyway_schema_history")) {
                    tables.add(name);
                }
            }
        }
        return tables;
    }

    private static void assertSchemaMatchesDesign(Connection c) throws Exception {
        Map<String, Set<String>> design = designColumns();
        assertThat(tableNames(c)).containsExactlyInAnyOrderElementsOf(design.keySet());
        for (Map.Entry<String, Set<String>> entry : design.entrySet()) {
            Set<String> columns = new LinkedHashSet<>();
            try (ResultSet rs =
                    c.getMetaData()
                            .getColumns(c.getCatalog(), c.getSchema(), entry.getKey(), "%")) {
                while (rs.next()) {
                    columns.add(rs.getString("COLUMN_NAME").toLowerCase());
                }
            }
            assertThat(columns)
                    .as("Columns of %s", entry.getKey())
                    .containsExactlyInAnyOrderElementsOf(entry.getValue());
        }
        String schemaFunction =
                c.getMetaData().getDatabaseProductName().equals("MySQL")
                        ? "DATABASE()"
                        : "CURRENT_SCHEMA()";
        assertThat(
                        scalar(
                                c,
                                "SELECT COUNT(*) FROM information_schema.table_constraints WHERE"
                                        + " constraint_schema="
                                        + schemaFunction
                                        + " AND constraint_type='FOREIGN KEY'"))
                .isEqualTo("133");
        assertThat(
                        scalar(
                                c,
                                "SELECT COUNT(*) FROM information_schema.table_constraints WHERE"
                                        + " constraint_schema="
                                        + schemaFunction
                                        + " AND constraint_type='CHECK'"))
                .isEqualTo("85");
    }

    private static void assertReferenceData(Connection c) throws SQLException {
        assertThat(
                        scalar(
                                c,
                                "SELECT COUNT(*) FROM roles WHERE code IN"
                                        + " ('ADMIN','USER','RECEPTIONIST')"))
                .isEqualTo("3");
        assertThat(scalar(c, "SELECT COUNT(*) FROM permissions")).isEqualTo("45");
        assertThat(
                        scalar(
                                c,
                                """
                SELECT COUNT(*) FROM role_permissions rp JOIN roles r ON r.id=rp.role_id
                JOIN permissions p ON p.id=rp.permission_id
                WHERE r.code='USER' AND p.code IN ('payment.refund','pricing.manage','report.revenue')
                """))
                .isEqualTo("0");
    }

    private static void assertConstraints(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate(
                    """
                    INSERT INTO users (full_name,email,password_hash,status)
                    VALUES ('Valid','valid@example.test','test-hash','ACTIVE')
                    """);
            s.executeUpdate(
                    """
                    INSERT INTO room_types (id,code,name,base_price,max_adults,max_children,max_occupancy)
                    VALUES (901,'TEST_TYPE','Test Room Type',800000,2,1,3)
                    """);
            s.executeUpdate(
                    """
                    INSERT INTO room_type_inventory (room_type_id,stay_date,total_rooms,sellable_rooms)
                    VALUES (901,'2026-11-03',1,1)
                    """);
            assertThatThrownBy(
                            () ->
                                    s.executeUpdate(
                                            """
                    UPDATE room_type_inventory SET held_rooms=2 WHERE room_type_id=901
                    """))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(
                            () ->
                                    s.executeUpdate(
                                            """
                    INSERT INTO room_type_inventory (room_type_id,stay_date,total_rooms,sellable_rooms)
                    VALUES (901,'2026-11-03',1,1)
                    """))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(
                            () ->
                                    s.executeUpdate(
                                            """
                    INSERT INTO room_type_images (room_type_id,image_url,sort_order)
                    VALUES (999999,'/missing-parent.png',0)
                    """))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(
                            () ->
                                    s.executeUpdate(
                                            """
                    INSERT INTO users (full_name,email,password_hash,status)
                    VALUES ('Invalid','invalid@example.test','test-hash','INVALID_STATUS')
                    """))
                    .isInstanceOf(SQLException.class);
        }
    }

    private static String scalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }
}
