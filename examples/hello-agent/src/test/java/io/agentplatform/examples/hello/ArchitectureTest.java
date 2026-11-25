package io.agentplatform.examples.hello;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

class ArchitectureTest {
    @Test
    void corePackagesHaveOnlyInwardDependencies() {
        var types = new ClassFileImporter().importPackages("io.agentplatform");
        classes().that().resideInAPackage("..domain..").should()
                .onlyDependOnClassesThat().resideInAnyPackage("java..", "..domain..").check(types);
        classes().that().resideInAPackage("..spi..").should()
                .onlyDependOnClassesThat().resideInAnyPackage("java..", "..spi..", "..domain..").check(types);
        classes().that().resideInAPackage("..sdk..").should()
                .onlyDependOnClassesThat().resideInAnyPackage("java..", "..sdk..", "..spi..", "..domain..").check(types);
        classes().that().resideInAPackage("..application..").should()
                .onlyDependOnClassesThat().resideInAnyPackage("java..", "..application..", "..sdk..", "..spi..", "..domain..").check(types);
    }
}
