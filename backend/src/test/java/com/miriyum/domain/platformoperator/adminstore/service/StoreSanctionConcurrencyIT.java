package com.miriyum.domain.platformoperator.adminstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.miriyum.MiriyumApplication;
import com.miriyum.domain.platformoperator.adminstore.entity.*;
import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionEnums.*;
import com.miriyum.domain.platformoperator.adminstore.exception.AdminStoreErrorCode;
import com.miriyum.domain.platformoperator.adminstore.repository.*;
import com.miriyum.domain.platformoperator.adminstore.model.StoreSanctionPolicyCatalog;
import com.miriyum.domain.platformoperator.adminstore.dto.AdminStoreRequests.SanctionShape;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAccountRepository;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.RestrictedFeature;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.StoreBaseSettings;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.PermanentClosureCause;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.PermanentClosureCommand;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.*;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.store.service.StoreAdministrationService;
import com.miriyum.domain.store.service.StoreTransactionEligibilityService;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.ReleaseCommand;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.ServiceException;
import java.time.*; import java.util.*; import java.util.concurrent.*;
import org.junit.jupiter.api.*; import org.springframework.beans.factory.annotation.*; import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder; import org.springframework.test.context.*; import org.springframework.transaction.*; import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.*; import org.testcontainers.mysql.MySQLContainer;

@Tag("integration") @Tag("integration-shard-a") @Testcontainers
@SpringBootTest(classes=MiriyumApplication.class,properties={"spring.jpa.hibernate.ddl-auto=validate","miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes","miriyum.platform-operator.enabled=true","miriyum.platform-operator.reauthentication-fingerprint-secret=test-only-reauthentication-fingerprint-secret","miriyum.platform-operator.temporary-password.validity=PT10M","miriyum.platform-operator.temporary-password.max-failures=3","miriyum.store.schedule.activation-enabled=false","miriyum.reservation.hold-expiration.enabled=false","miriyum.menu.schedule.enabled=false"})
class StoreSanctionConcurrencyIT {
 @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.0.40").withCommand("--log-bin-trust-function-creators=1");
 @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);}
 @Autowired StoreOperatorAccountRepository operatorAccounts; @Autowired StoreRepository stores;
 @Autowired PlatformOperatorAccountRepository platformAccounts; @Autowired StoreSanctionCaseRepository cases;
 @Autowired StoreSanctionRepository sanctions; @Autowired PlatformTransactionManager transactions; @Autowired PasswordEncoder encoder;
 @Autowired StoreAdministrationService storeAdministration; @Autowired StoreSanctionPolicyCatalog policy;
 @Autowired StoreTransactionEligibilityService transactionEligibility; @Autowired StoreSanctionApprovalRepository approvals;
 @Autowired StoreSanctionExpiryTransaction expiryTransaction; @Autowired JdbcTemplate jdbc;

 @Test void exactlyOneConcurrentReleaseTransitionSucceeds() throws Exception {
  var owner=operatorAccounts.saveAndFlush(StoreOperatorAccount.create("owner279@example.com","hash","owner"));
  Store store=stores.saveAndFlush(Store.create(owner.getId(),"1234567890","race-store","",Region.SEOUL,"서울","CAFE_BAKERY",Set.of("DATE"),true,true,true,"Asia/Seoul",LocalDateTime.now(),"v1"));
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
 @Test void automaticExpiryAndManualReleaseAllowOnlyOneTerminalTransition() throws Exception {
  var owner=operatorAccounts.saveAndFlush(StoreOperatorAccount.create("owner-expiry279@example.com","hash","owner"));
  Store store=stores.saveAndFlush(Store.create(owner.getId(),"1234567891","expiry-race-store","",Region.SEOUL,"서울","CAFE_BAKERY",Set.of("DATE"),true,true,true,"Asia/Seoul",LocalDateTime.now(),"v1"));
  var admin=platformAccounts.saveAndFlush(PlatformOperatorAccount.createTemporary("admin-expiry279@example.com",encoder.encode("Password1!"),"admin",Instant.now().plusSeconds(600)));
  StoreSanctionCase c=cases.saveAndFlush(StoreSanctionCase.create(store.getId(),admin.getId(),"FRAUD",Set.of("evidence://expiry-race"),"ADMIN-007-v1",Instant.now()));
  StoreSanction s=sanctions.saveAndFlush(StoreSanction.create(c.getPublicId(),store.getId(),SanctionType.TEMPORARY_SUSPENSION,Set.of(),"race",Instant.now().minusSeconds(60),Instant.now().minusSeconds(1),admin.getId(),false,1,Instant.now().minusSeconds(60)));
  CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);List<Boolean> outcomes;
  try(var executor=Executors.newFixedThreadPool(2)){var expire=executor.submit(()->terminal(s.getId(),c.getPublicId(),store.getId(),true,ready,start));var release=executor.submit(()->terminal(s.getId(),c.getPublicId(),store.getId(),false,ready,start));assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();outcomes=List.of(expire.get(10,TimeUnit.SECONDS),release.get(10,TimeUnit.SECONDS));}
  assertThat(outcomes).containsExactlyInAnyOrder(true,false);assertThat(sanctions.findById(s.getId()).orElseThrow().getSanctionVersion()).isEqualTo(2);
 }
 @Test void overlappingSanctionsKeepOnlyTheRemainingEffectInBothReleaseOrders() {
  assertOverlappingReleaseOrder("latest",false);
  assertOverlappingReleaseOrder("earlier",true);
 }
 @Test void automaticExpiryPersistsStoreSnapshotAuditInTheSameTransaction() {
  var owner=operatorAccounts.saveAndFlush(StoreOperatorAccount.create("owner-expiry-audit@example.com","hash","owner"));
  Store store=stores.saveAndFlush(Store.create(owner.getId(),"1234567894","expiry-audit","",Region.SEOUL,"서울","CAFE_BAKERY",Set.of("DATE"),true,true,true,"Asia/Seoul",LocalDateTime.now(),"v1"));
  var admin=platformAccounts.saveAndFlush(PlatformOperatorAccount.createTemporary("admin-expiry-audit@example.com",encoder.encode("Password1!"),"admin",Instant.now().plusSeconds(600)));
  StoreSanctionCase c=cases.saveAndFlush(StoreSanctionCase.create(store.getId(),admin.getId(),"FRAUD",Set.of("evidence://expiry-audit"),"ADMIN-007-v1",Instant.now()));
  StoreSanction sanction=sanctions.saveAndFlush(StoreSanction.create(c.getPublicId(),store.getId(),SanctionType.TEMPORARY_SUSPENSION,Set.of(),"expiry-audit",Instant.now().minusSeconds(600),Instant.now().minusSeconds(1),admin.getId(),false,0,Instant.now().minusSeconds(600)));
  apply(sanction);

  assertThat(expiryTransaction.expire(sanction.getId(),Instant.now())).isTrue();

  Integer count=jdbc.queryForObject("select count(*) from platform_operator_audit_events where action='STORE_SANCTION_EXPIRED' and store_sanction_id=? and before_snapshot is not null and after_snapshot is not null",Integer.class,sanction.getId());
  assertThat(count).isEqualTo(1);
 }
 @Test void activeRestrictionSurvivesOperatorModeUpdateAndReleaseRestoresLatestBase() {
  var owner=operatorAccounts.saveAndFlush(StoreOperatorAccount.create("owner-base279@example.com","hash","owner"));
  Store store=stores.saveAndFlush(Store.create(owner.getId(),"1234567895","base-effective","",Region.SEOUL,"서울","CAFE_BAKERY",Set.of("DATE"),true,true,true,"Asia/Seoul",LocalDateTime.now(),"v1"));
  var admin=platformAccounts.saveAndFlush(PlatformOperatorAccount.createTemporary("admin-base279@example.com",encoder.encode("Password1!"),"admin",Instant.now().plusSeconds(600)));
  StoreSanctionCase c=cases.saveAndFlush(StoreSanctionCase.create(store.getId(),admin.getId(),"FRAUD",Set.of("evidence://base"),"ADMIN-007-v1",Instant.now()));
  StoreSanction restriction=sanctions.saveAndFlush(StoreSanction.create(c.getPublicId(),store.getId(),SanctionType.FEATURE_RESTRICTION,Set.of(RestrictedFeature.RESERVATION),"reservation",Instant.now(),null,admin.getId(),false,0,Instant.now()));
  apply(restriction);
  new TransactionTemplate(transactions).executeWithoutResult(status->{
   Store locked=stores.findByIdForUpdate(store.getId()).orElseThrow();
   locked.update(null,null,null,null,null,null,true,null,null,null);
   storeAdministration.recomposeAfterOperatorUpdate(store.getId(),new StoreBaseSettings(null,true,null,null));
  });

  assertThatThrownBy(()->new TransactionTemplate(transactions).executeWithoutResult(status->
          transactionEligibility.requireReservationTransactionEligibility(store.getId())))
          .isInstanceOfSatisfying(ServiceException.class,error->assertThat(error.getErrorCode())
                  .isEqualTo(com.miriyum.domain.store.error.StoreErrorCode.STORE_FEATURE_RESTRICTED));

  releaseApplied(restriction);
  assertThat(storeAdministration.inspect(store.getId()).reservationEnabled()).isTrue();
 }
 @Test void temporaryAndFeatureEffectsRecomposeAcrossBothApplicationOrders() {
  assertTemporaryFeatureOrder("temp-first", "1234567896", true);
  assertTemporaryFeatureOrder("feature-first", "1234567897", false);
 }
 @Test void permanentClosureBindsApprovalAndRejectsGenericRelease() {
  var owner=operatorAccounts.saveAndFlush(StoreOperatorAccount.create("owner-permanent279@example.com","hash","owner"));
  Store store=stores.saveAndFlush(Store.create(owner.getId(),"1234567898","permanent","",Region.SEOUL,"서울","CAFE_BAKERY",Set.of("DATE"),true,true,true,"Asia/Seoul",LocalDateTime.now(),"v1"));
  var creator=platformAccounts.saveAndFlush(PlatformOperatorAccount.createTemporary("creator-permanent279@example.com",encoder.encode("Password1!"),"creator",Instant.now().plusSeconds(600)));
  var approver=platformAccounts.saveAndFlush(PlatformOperatorAccount.createTemporary("approver-permanent279@example.com",encoder.encode("Password1!"),"approver",Instant.now().plusSeconds(600)));
  StoreSanctionCase c=cases.saveAndFlush(StoreSanctionCase.create(store.getId(),creator.getId(),"FRAUD",Set.of("evidence://permanent"),"ADMIN-007-v9",Instant.now()));
  StoreSanction sanction=sanctions.saveAndFlush(StoreSanction.create(c.getPublicId(),store.getId(),SanctionType.PERMANENT_EXIT,Set.of(),"permanent",Instant.now(),null,creator.getId(),true,0,Instant.now()));
  StoreSanctionApproval approval=approvals.saveAndFlush(StoreSanctionApproval.approve(sanction.getId(),approver.getId(),"approved",Instant.now()));
  new TransactionTemplate(transactions).executeWithoutResult(status->{
   StoreSanction locked=sanctions.findScopedForUpdate(sanction.getId(),c.getPublicId(),store.getId()).orElseThrow();
   var current=storeAdministration.inspect(store.getId());
   var result=storeAdministration.closePermanently(new PermanentClosureCommand(
           store.getId(),current.enforcementVersion(),sanction.getId(),approval.getId(),
           PermanentClosureCause.PLATFORM_SANCTION,c.getPolicyVersion()));
   locked.approve(locked.getSanctionVersion(),result.enforcementVersion());
  });

  var closed=storeAdministration.inspect(store.getId());
  assertThat(closed.operationStatus()).isEqualTo(OperationStatus.CLOSED);
  assertThat(closed.restrictedFeatures()).containsExactlyInAnyOrder(RestrictedFeature.values());
  assertThatThrownBy(()->new TransactionTemplate(transactions).executeWithoutResult(status->
          storeAdministration.release(new ReleaseCommand(store.getId(),sanction.getId()))))
          .isInstanceOfSatisfying(ServiceException.class,error->assertThat(error.getErrorCode())
                  .isEqualTo(com.miriyum.domain.store.error.StoreErrorCode.STORE_ENFORCEMENT_VERSION_CONFLICT));
  Map<String,Object> binding=jdbc.queryForMap("select permanent_closure_sanction_id,permanent_closure_approval_id,permanent_closure_cause,permanent_closure_policy_version from store_enforcement_states where store_id=?",store.getId());
  assertThat(binding).containsEntry("permanent_closure_sanction_id",sanction.getId())
          .containsEntry("permanent_closure_approval_id",approval.getId())
          .containsEntry("permanent_closure_cause","PLATFORM_SANCTION")
          .containsEntry("permanent_closure_policy_version","ADMIN-007-v9");
 }
 private void assertTemporaryFeatureOrder(String suffix,String businessNumber,boolean temporaryFirst){
  var owner=operatorAccounts.saveAndFlush(StoreOperatorAccount.create("owner-"+suffix+"279@example.com","hash","owner"));
  Store store=stores.saveAndFlush(Store.create(owner.getId(),businessNumber,"order-"+suffix,"",Region.SEOUL,"서울","CAFE_BAKERY",Set.of("DATE"),true,true,true,"Asia/Seoul",LocalDateTime.now(),"v1"));
  var admin=platformAccounts.saveAndFlush(PlatformOperatorAccount.createTemporary("admin-"+suffix+"279@example.com",encoder.encode("Password1!"),"admin",Instant.now().plusSeconds(600)));
  StoreSanctionCase c=cases.saveAndFlush(StoreSanctionCase.create(store.getId(),admin.getId(),"FRAUD",Set.of("evidence://"+suffix),"ADMIN-007-v1",Instant.now()));
  StoreSanction temporary=sanctions.saveAndFlush(StoreSanction.create(c.getPublicId(),store.getId(),SanctionType.TEMPORARY_SUSPENSION,Set.of(),"temporary",Instant.now(),Instant.now().plusSeconds(3600),admin.getId(),false,0,Instant.now()));
  StoreSanction feature=sanctions.saveAndFlush(StoreSanction.create(c.getPublicId(),store.getId(),SanctionType.FEATURE_RESTRICTION,Set.of(RestrictedFeature.RESERVATION),"feature",Instant.now(),null,admin.getId(),false,0,Instant.now()));
  if(temporaryFirst){apply(temporary);apply(feature);releaseApplied(temporary);}
  else{apply(feature);apply(temporary);releaseApplied(feature);}
  var remaining=storeAdministration.inspect(store.getId());
  if(temporaryFirst){assertThat(remaining.operationStatus()).isEqualTo(OperationStatus.OPEN);assertThat(remaining.reservationEnabled()).isFalse();releaseApplied(feature);}
  else{assertThat(remaining.operationStatus()).isEqualTo(OperationStatus.TEMPORARILY_CLOSED);releaseApplied(temporary);}
  var restored=storeAdministration.inspect(store.getId());
  assertThat(restored.operationStatus()).isEqualTo(OperationStatus.OPEN);assertThat(restored.reservationEnabled()).isTrue();
 }
 private void assertOverlappingReleaseOrder(String suffix,boolean releaseEarlier){
  var owner=operatorAccounts.saveAndFlush(StoreOperatorAccount.create("owner-overlap-"+suffix+"@example.com","hash","owner"));
  Store store=stores.saveAndFlush(Store.create(owner.getId(),releaseEarlier?"1234567893":"1234567892","overlap-"+suffix,"",Region.SEOUL,"서울","CAFE_BAKERY",Set.of("DATE"),true,true,true,"Asia/Seoul",LocalDateTime.now(),"v1"));
  var admin=platformAccounts.saveAndFlush(PlatformOperatorAccount.createTemporary("admin-overlap-"+suffix+"@example.com",encoder.encode("Password1!"),"admin",Instant.now().plusSeconds(600)));
  StoreSanctionCase c=cases.saveAndFlush(StoreSanctionCase.create(store.getId(),admin.getId(),"FRAUD",Set.of("evidence://"+suffix),"ADMIN-007-v1",Instant.now()));
  StoreSanction reservation=sanctions.saveAndFlush(StoreSanction.create(c.getPublicId(),store.getId(),SanctionType.FEATURE_RESTRICTION,Set.of(RestrictedFeature.RESERVATION),"reservation",Instant.now(),null,admin.getId(),false,0,Instant.now()));
  StoreSanction waiting=sanctions.saveAndFlush(StoreSanction.create(c.getPublicId(),store.getId(),SanctionType.FEATURE_RESTRICTION,Set.of(RestrictedFeature.WAITING),"waiting",Instant.now(),null,admin.getId(),false,0,Instant.now()));
  apply(reservation);apply(waiting);StoreSanction target=releaseEarlier?reservation:waiting;releaseApplied(target);
  var remaining=storeAdministration.inspect(store.getId());
  if(releaseEarlier){assertThat(remaining.reservationEnabled()).isTrue();assertThat(remaining.waitingAllowed()).isFalse();}
  else{assertThat(remaining.reservationEnabled()).isFalse();assertThat(remaining.waitingAllowed()).isTrue();}
 }
 private void apply(StoreSanction seed){new TransactionTemplate(transactions).executeWithoutResult(status->{var sanction=sanctions.findScopedForUpdate(seed.getId(),seed.getCaseId(),seed.getStoreId()).orElseThrow();var current=storeAdministration.inspect(seed.getStoreId());var shape=new SanctionShape(sanction.getType(),sanction.getRestrictedFeatures(),sanction.getStartsAt(),sanction.getEndsAt());var result=storeAdministration.apply(policy.command(seed.getStoreId(),current.enforcementVersion(),seed.getId(),current,shape));sanction.enforced(result.enforcementVersion());});}
 private void releaseApplied(StoreSanction seed){new TransactionTemplate(transactions).executeWithoutResult(status->{var sanction=sanctions.findScopedForUpdate(seed.getId(),seed.getCaseId(),seed.getStoreId()).orElseThrow();var result=storeAdministration.release(new ReleaseCommand(seed.getStoreId(),seed.getId()));sanction.release(sanction.getSanctionVersion(),result.enforcementVersion(),Instant.now());});}
 private boolean release(long id,String caseId,long storeId,CountDownLatch ready,CountDownLatch start){ready.countDown();await(start);try{return new TransactionTemplate(transactions).execute(status->{var value=sanctions.findScopedForUpdate(id,caseId,storeId).orElseThrow();value.release(1,2,Instant.now());return true;});}catch(ServiceException e){assertThat(e.getErrorCode()).isEqualTo(AdminStoreErrorCode.SANCTION_STATE_CONFLICT);return false;}}
 private boolean terminal(long id,String caseId,long storeId,boolean expire,CountDownLatch ready,CountDownLatch start){ready.countDown();await(start);try{return new TransactionTemplate(transactions).execute(status->{var value=sanctions.findScopedForUpdate(id,caseId,storeId).orElseThrow();if(expire)value.expire(1,2,Instant.now());else value.release(1,2,Instant.now());return true;});}catch(ServiceException e){assertThat(e.getErrorCode()).isEqualTo(AdminStoreErrorCode.SANCTION_STATE_CONFLICT);return false;}}
 private static void await(CountDownLatch l){try{if(!l.await(10,TimeUnit.SECONDS))throw new AssertionError("timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
}
