package com.miriyum.domain.platformoperator.adminstore.repository;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("integration") @Tag("integration-shard-a") @Testcontainers
class StoreSanctionMigrationIT {
 @Test void v48CreatesStoreScopedSanctionLedger() throws Exception {
  try(MySQLContainer mysql=new MySQLContainer("mysql:8.0.40").withCommand("--log-bin-trust-function-creators=1")){
   mysql.start(); Flyway flyway=Flyway.configure().dataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword()).load();flyway.migrate();
   assertThat(flyway.info().applied()).extracting(MigrationInfo::getScript).contains("V49__create_store_sanctions.sql");
   try(Connection c=mysql.createConnection("")){
    assertThat(tables(c)).contains("store_enforcement_states","store_sanction_cases","store_sanction_impact_previews","store_sanctions","store_sanction_approvals");
    assertThat(columns(c,"stores")).contains("platform_management_allowed");
    assertThat(columns(c,"store_sanctions")).contains("sanction_version","store_enforcement_version","active_store_marker");
    assertThat(columns(c,"store_sanction_impact_previews")).contains("case_version","store_enforcement_version","digest","expires_at");
   }
  }
 }
 private static Set<String> tables(Connection c)throws SQLException{return names(c,"SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()");}
 private static Set<String> columns(Connection c,String table)throws SQLException{try(var s=c.prepareStatement("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=?")){s.setString(1,table);try(var r=s.executeQuery()){Set<String> n=new HashSet<>();while(r.next())n.add(r.getString(1).toLowerCase());return n;}}}
 private static Set<String> names(Connection c,String sql)throws SQLException{try(var s=c.createStatement();var r=s.executeQuery(sql)){Set<String> n=new HashSet<>();while(r.next())n.add(r.getString(1).toLowerCase());return n;}}
}
