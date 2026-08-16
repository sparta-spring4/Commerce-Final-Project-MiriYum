package com.miriyum.domain.platformoperator.adminstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.miriyum.MiriyumApplication;
import com.miriyum.domain.platformoperator.adminstore.entity.*;
import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionEnums.*;
import com.miriyum.domain.platformoperator.adminstore.exception.AdminStoreErrorCode;
import com.miriyum.domain.platformoperator.adminstore.repository.*;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAccountRepository;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.RestrictedFeature;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.*;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.ServiceException;
import java.time.*; import java.util.*; import java.util.concurrent.*;
import org.junit.jupiter.api.*; import org.springframework.beans.factory.annotation.*; import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder; import org.springframework.test.context.*; import org.springframework.transaction.*; import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*; import org.testcontainers.mysql.MySQLContainer;

@Tag("integration") @Tag("integration-shard-a") @Testcontainers
@SpringBootTest(classes=MiriyumApplication.class,properties={"spring.jpa.hibernate.ddl-auto=validate","miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes","miriyum.platform-operator.enabled=true","miriyum.platform-operator.reauthentication-fingerprint-secret=test-only-reauthentication-fingerprint-secret","miriyum.platform-operator.temporary-password.validity=PT10M","miriyum.platform-operator.temporary-password.max-failures=3","miriyum.store.schedule.activation-enabled=false","miriyum.reservation.hold-expiration.enabled=false","miriyum.menu.schedule.enabled=false"})
class StoreSanctionConcurrencyIT {
 @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.0.40").withCommand("--log-bin-trust-function-creators=1");
 @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);}
 @Autowired StoreOperatorAccountRepository operatorAccounts; @Autowired StoreRepository stores;
 @Autowired PlatformOperatorAccountRepository platformAccounts; @Autowired StoreSanctionCaseRepository cases;
 @Autowired StoreSanctionRepository sanctions; @Autowired PlatformTransactionManager transactions; @Autowired PasswordEncoder encoder;

 @Test void exactlyOneConcurrentReleaseTransitionSucceeds() throws Exception {
  var owner=operatorAccounts.saveAndFlush(StoreOperatorAccount.create("owner279@example.com","hash","owner"));
  Store store=stores.saveAndFlush(Store.create(owner.getId(),"1234567890",BusinessType.CAFE,"race-store","",Region.SEOUL,"서울","CAFE_BAKERY",Set.of("DATE"),true,true,true,"Asia/Seoul",LocalDateTime.now(),"v1"));
  var admin=platformAccounts.saveAndFlush(PlatformOperatorAccount.createTemporary("admin279@example.com",encoder.encode("Password1!"),"admin",Instant.now().plusSeconds(600)));
  StoreSanctionCase c=cases.saveAndFlush(StoreSanctionCase.create(store.getId(),admin.getId(),"FRAUD",Set.of("evidence://race"),"ADMIN-007-v1",Instant.now()));
  StoreSanction s=sanctions.saveAndFlush(StoreSanction.create(c.getPublicId(),store.getId(),SanctionType.FEATURE_RESTRICTION,Set.of(RestrictedFeature.WAITING),"race",Instant.now(),null,admin.getId(),false,1,Instant.now()));
  CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
  List<Boolean> outcomes; try(var executor=Executors.newFixedThreadPool(2)){
   var a=executor.submit(()->release(s.getId(),c.getPublicId(),store.getId(),ready,start));
   var b=executor.submit(()->release(s.getId(),c.getPublicId(),store.getId(),ready,start));
   assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();outcomes=List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
  }
  assertThat(outcomes).containsExactlyInAnyOrder(true,false);
  assertThat(sanctions.findById(s.getId()).orElseThrow().getSanctionVersion()).isEqualTo(2);
 }
 private boolean release(long id,String caseId,long storeId,CountDownLatch ready,CountDownLatch start){ready.countDown();await(start);try{return new TransactionTemplate(transactions).execute(status->{var value=sanctions.findScopedForUpdate(id,caseId,storeId).orElseThrow();value.release(1,2,Instant.now());return true;});}catch(ServiceException e){assertThat(e.getErrorCode()).isEqualTo(AdminStoreErrorCode.SANCTION_STATE_CONFLICT);return false;}}
 private static void await(CountDownLatch l){try{if(!l.await(10,TimeUnit.SECONDS))throw new AssertionError("timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
}
