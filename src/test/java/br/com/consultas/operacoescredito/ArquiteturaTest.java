package br.com.consultas.operacoescredito;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/** Regras de dependência da arquitetura hexagonal (verificação estrutural). */
@AnalyzeClasses(packages = "br.com.consultas.operacoescredito", importOptions = ImportOption.DoNotIncludeTests.class)
class ArquiteturaTest {

    private static final String[] FRAMEWORKS = {
            "org.springframework..", "software.amazon..", "tools.jackson..", "com.fasterxml.jackson.."
    };

    @ArchTest
    static final ArchRule dominioNaoDependeDeFrameworks = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(FRAMEWORKS);

    @ArchTest
    static final ArchRule aplicacaoNaoDependeDeFrameworks = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(FRAMEWORKS);

    @ArchTest
    static final ArchRule adaptersDeEntradaUsamSoPortasDeEntrada = noClasses()
            .that().resideInAPackage("..adapters.in..")
            .should().dependOnClassesThat().resideInAnyPackage("..application.service..", "..adapters.out..");

    @ArchTest
    static final ArchRule adaptersDeSaidaNaoConhecemAdaptersDeEntrada = noClasses()
            .that().resideInAPackage("..adapters.out..")
            .should().dependOnClassesThat().resideInAnyPackage("..adapters.in..");

    @ArchTest
    static final ArchRule camadasHexagonais = layeredArchitecture().consideringOnlyDependenciesInLayers()
            .layer("Domain").definedBy("..domain..")
            .layer("Application").definedBy("..application..")
            .layer("Adapters").definedBy("..adapters..")
            .layer("Config").definedBy("..config..")
            .whereLayer("Adapters").mayNotBeAccessedByAnyLayer()
            .whereLayer("Application").mayOnlyBeAccessedByLayers("Adapters", "Config")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Adapters", "Config");
}
