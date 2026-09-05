package com.luxera.companion.contracts.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * V10 §59 / R1 ArchUnit cross-module boundary guard.
 *
 * <p>Today the codebase is one {@code platform-core} module; this test only enforces the
 * contracts module is self-contained and free of cycles. R1.5 / R1.6 will activate the
 * chat-platform ↔ digital-human-platform rules when those modules are split out.
 *
 * <p>The companion bash script {@code scripts/check-v10.sh} is the primary line of defense
 * (it greps for cross-module imports directly, which catches the same violations earlier
 * in the build, before tests even run). ArchUnit here provides a deeper structural check.
 */
class ArchitectureTest {

    private static JavaClasses importedClasses;

    @BeforeAll
    static void setup() {
        importedClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.luxera.companion");
    }

    @Test
    void contracts_have_no_module_dependencies_on_platform() {
        // contracts is a pure DTO module — must not import any platform code.
        ArchRule contracts = noClasses()
                .that().resideInAPackage("..contracts..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "..chatplatform..",
                        "..digitalhuman..",
                        "..platform..",
                        "..persistence.."
                );
        contracts.check(importedClasses);
    }

    @Test
    void contracts_have_no_cycles() {
        // Slice-level cycle detection scoped to the contracts module itself.
        ArchRule noCycles = slices()
                .matching("com.luxera.companion.contracts.(*)..")
                .should().beFreeOfCycles();
        noCycles.check(importedClasses);
    }

    @Test
    void contracts_do_not_throw_generic_exceptions() {
        NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS.check(importedClasses);
    }
}
