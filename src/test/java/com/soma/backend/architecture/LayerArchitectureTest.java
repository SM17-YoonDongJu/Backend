package com.soma.backend.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import jakarta.persistence.EntityManager;

/**
 * CLAUDE.md의 레이어 의존 규칙을 빌드에서 강제한다. 스프링 컨텍스트 없이 바이트코드만 읽으므로 빠르다.
 *
 * <p>모든 규칙을 예외 없이 강제한다. service → service 규칙은 도입(#358) 때 기존 위반을 FreezingArchRule로
 * 기록해 두고 새 위반만 막다가, 위반을 모두 정리한 뒤(#366) freeze를 걷어냈다. 위반을 의도적으로 허용해야
 * 한다면 규칙에 예외를 사유와 함께 명시할 것.
 */
@AnalyzeClasses(packages = "com.soma.backend", importOptions = ImportOption.DoNotIncludeTests.class)
class LayerArchitectureTest {

  private static final String BASE_PACKAGE = "com.soma.backend.";

  /**
   * 서비스 레이어 클래스는 다른 {@code *Service}를 의존하지 않는다. 유스케이스끼리 얽히면 트랜잭션 경계가
   * 겹치고 컨텍스트 간 결합이 숨는다 — 다른 Aggregate의 변경은 도메인 이벤트로, 공통 로직은 엔티티나
   * 전용 협력 객체(Reader·Writer 등)로 내린다.
   *
   * <p>{@code @Service}가 아니라 이름으로 판정한다. {@code @Component}인데 Service라는 이름을 단 인프라
   * 클래스도 유스케이스처럼 읽혀 같은 혼동을 만들기 때문이다. 그래서 infra·global 어댑터는 Service 대신
   * 역할 이름을 쓴다(예: {@code S3ObjectStorage}, {@code FcmPushSender}, {@code AuthTokenManager}).
   * 같은 이유로 검사 대상(origin)도 domain service 패키지뿐 아니라 {@code infra}·{@code global}의
   * {@code *Service}까지 포함한다.
   */
  @ArchTest
  static final ArchRule serviceShouldNotDependOnOtherService = noClasses()
      .that().resideInAPackage("..domain..service..")
      .or().haveSimpleNameEndingWith("Service")
      .should(dependOnOtherServices())
      .because("service는 다른 service를 호출하지 않는다 (CLAUDE.md 레이어 의존 규칙)");

  @ArchTest
  static final ArchRule entityShouldNotDependOnOuterLayers = noClasses()
      .that().resideInAPackage("..domain..entity..")
      .should().dependOnClassesThat().resideInAnyPackage(
          "..domain..controller..", "..domain..service..", "..domain..repository..", "..domain..dto..",
          "..infra..")
      .because("entity는 아무것도 의존하지 않는다 — 의존 방향은 항상 안쪽(entity)으로");

  @ArchTest
  static final ArchRule repositoryShouldNotDependOnUpperLayers = noClasses()
      .that().resideInAPackage("..domain..repository..")
      .should().dependOnClassesThat().resideInAnyPackage(
          "..domain..controller..", "..domain..service..", "..domain..dto..", "..infra..")
      .because("repository는 entity만 의존한다");

  @ArchTest
  static final ArchRule controllerShouldNotDependOnRepository = noClasses()
      .that().resideInAPackage("..domain..controller..")
      .should().dependOnClassesThat().resideInAPackage("..domain..repository..")
      .because("controller는 service를 거쳐서만 데이터에 접근한다");

  @ArchTest
  static final ArchRule controllerShouldNotAccessPersistenceApiDirectly = noClasses()
      .that().resideInAPackage("..domain..controller..")
      .should().dependOnClassesThat().belongToAnyOf(
          EntityManager.class, JPAQueryFactory.class, JdbcTemplate.class, NamedParameterJdbcTemplate.class,
          DataSource.class)
      .because("controller는 service를 거쳐서만 데이터에 접근한다 — repository를 우회한 영속성 API 직접 사용 금지");

  /**
   * 자사 패키지의 {@code *Service}에 대한 의존을 위반으로 본다. 같은 최상위 클래스 안의 의존(중첩
   * record가 바깥 서비스를 참조하는 경우 등)과 {@code @Service} 애노테이션 같은 외부 타입은 제외한다.
   */
  private static ArchCondition<JavaClass> dependOnOtherServices() {
    return new ArchCondition<>("depend on other *Service classes") {
      @Override
      public void check(JavaClass origin, ConditionEvents events) {
        for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
          JavaClass target = dependency.getTargetClass();
          boolean otherService = target.getName().startsWith(BASE_PACKAGE)
              && target.getSimpleName().endsWith("Service")
              && !topLevelOf(target).equals(topLevelOf(origin));
          if (otherService) {
            events.add(SimpleConditionEvent.satisfied(dependency, dependency.getDescription()));
          }
        }
      }
    };
  }

  private static JavaClass topLevelOf(JavaClass javaClass) {
    JavaClass current = javaClass;
    while (current.getEnclosingClass().isPresent()) {
      current = current.getEnclosingClass().get();
    }
    return current;
  }
}
