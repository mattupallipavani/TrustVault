package com.trustvault.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.GeneralCodingRules.*;

/**
 * Validates the architectural constraints of TrustVault.
 *
 * These tests enforce module boundaries and coding rules that are easy to
 * violate during development but hard to spot in code review. They run in
 * under a second and catch structural regressions early.
 */
class ArchitectureTest {

    private static JavaClasses importedClasses;

    @BeforeAll
    static void importClasses() {
        importedClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.trustvault");
    }

    // -----------------------------------------------------------------------
    // Layer enforcement
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Layered architecture: controllers -> services -> repositories")
    void testLayeredArchitecture() {
        layeredArchitecture()
                .consideringAllDependencies()
                .layer("Controller").definedBy("com.trustvault.controller..")
                .layer("Service").definedBy("com.trustvault.service..")
                .layer("Repository").definedBy("com.trustvault.repository..")
                .layer("Entity").definedBy("com.trustvault.entity..")
                .layer("DTO").definedBy("com.trustvault.dto..")
                .layer("Security").definedBy("com.trustvault.security..")
                .layer("Config").definedBy("com.trustvault.config..")
                .layer("Exception").definedBy("com.trustvault.exception..")
                .layer("Util").definedBy("com.trustvault.util..")
                .layer("Enum").definedBy("com.trustvault.enums..")

                .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
                .whereLayer("Repository").mayOnlyBeAccessedByLayers(
                        "Service", "Config", "Security")
                .check(importedClasses);
    }

    // -----------------------------------------------------------------------
    // Naming conventions
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Controllers live in the controller package and end with Controller")
    void controllerNaming() {
        classes()
                .that().areTopLevelClasses()
                .and().resideInAPackage("com.trustvault.controller..")
                .should().haveSimpleNameEndingWith("Controller")
                .check(importedClasses);
    }

    @Test
    @DisplayName("Services live in the service package and end with Service or Scanner")
    void serviceNaming() {
        classes()
                .that().areTopLevelClasses()
                .and().resideInAPackage("com.trustvault.service..")
                .and().areNotInterfaces()
                .should().haveSimpleNameEndingWith("Service")
                .orShould().haveSimpleNameEndingWith("Scanner")
                .check(importedClasses);
    }

    @Test
    @DisplayName("Repositories live in the repository package and end with Repository")
    void repositoryNaming() {
        classes()
                .that().areTopLevelClasses()
                .and().resideInAPackage("com.trustvault.repository..")
                .should().haveSimpleNameEndingWith("Repository")
                .check(importedClasses);
    }

    // -----------------------------------------------------------------------
    // Dependency hygiene
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("No classes use java.util.logging (SLF4J only)")
    void noJavaUtilLogging() {
        NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.check(importedClasses);
    }

    @Test
    @DisplayName("Controllers must not access repositories directly")
    void controllersDoNotAccessRepositories() {
        noClasses()
                .that().resideInAPackage("com.trustvault.controller..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.trustvault.repository..")
                .check(importedClasses);
    }

    @Test
    @DisplayName("DTOs must not depend on JPA entities")
    void dtosDoNotDependOnEntities() {
        noClasses()
                .that().resideInAPackage("com.trustvault.dto..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.trustvault.entity..")
                .check(importedClasses);
    }

    @Test
    @DisplayName("Entities must not depend on services")
    void entitiesDoNotDependOnServices() {
        noClasses()
                .that().resideInAPackage("com.trustvault.entity..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.trustvault.service..")
                .check(importedClasses);
    }

    @Test
    @DisplayName("Entities must not depend on controllers")
    void entitiesDoNotDependOnControllers() {
        noClasses()
                .that().resideInAPackage("com.trustvault.entity..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.trustvault.controller..")
                .check(importedClasses);
    }
}
