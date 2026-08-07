package io.github.nhwalker.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import spock.lang.Specification
import spock.lang.TempDir

class TestRollupPluginSpec extends Specification {

    @TempDir
    File projectDir

    def setup() {
        new File(projectDir, 'settings.gradle') << """
            rootProject.name = 'rollup-sample'
            include 'liba', 'libb'
        """.stripIndent()

        new File(projectDir, 'build.gradle') << """
            plugins {
                id 'io.github.nhwalker.test-rollup'
            }
            repositories {
                mavenCentral()
            }
        """.stripIndent()

        ['liba', 'libb'].each { name ->
            writeFile "$name/build.gradle", """
                plugins {
                    id 'io.github.nhwalker.test-setup'
                }
                repositories {
                    mavenCentral()
                }
            """.stripIndent()
            def cls = name.capitalize()
            writeFile "$name/src/main/java/sample/$name/${cls}.java", """
                package sample.$name;
                public class $cls {
                    public String greet(String who) { return "Hello from $name, " + who + "!"; }
                }
            """.stripIndent()
            writeFile "$name/src/test/java/sample/$name/${cls}Test.java", """
                package sample.$name;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class ${cls}Test {
                    @Test
                    void greets() {
                        assertEquals("Hello from $name, Unit!", new ${cls}().greet("Unit"));
                    }
                }
            """.stripIndent()
            writeFile "$name/src/integrationTest/java/sample/$name/${cls}IT.java", """
                package sample.$name;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class ${cls}IT {
                    @Test
                    void greets() {
                        assertEquals("Hello from $name, IT!", new ${cls}().greet("IT"));
                    }
                }
            """.stripIndent()
        }
    }

    private GradleRunner runner(String... args) {
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(args)
    }

    def 'testRollup aggregates junit, jacoco, and allure results from all test-setup subprojects'() {
        when:
        def result = runner('testRollup').build()

        then: 'all subproject suites ran'
        result.task(':liba:test').outcome == TaskOutcome.SUCCESS
        result.task(':liba:integrationTest').outcome == TaskOutcome.SUCCESS
        result.task(':libb:test').outcome == TaskOutcome.SUCCESS
        result.task(':libb:integrationTest').outcome == TaskOutcome.SUCCESS

        and: 'all aggregate reports were generated'
        result.task(':testAggregateReport').outcome == TaskOutcome.SUCCESS
        result.task(':integrationTestAggregateReport').outcome == TaskOutcome.SUCCESS
        result.task(':testCodeCoverageReport').outcome == TaskOutcome.SUCCESS
        result.task(':integrationTestCodeCoverageReport').outcome == TaskOutcome.SUCCESS
        result.task(':allureAggregateReport').outcome == TaskOutcome.SUCCESS
        result.task(':testRollup').outcome == TaskOutcome.SUCCESS

        and: 'merged junit html reports contain tests from both subprojects'
        def unitHtml = aggregateHtml('test')
        def itHtml = aggregateHtml('integrationTest')
        unitHtml.contains('LibaTest') && unitHtml.contains('LibbTest')
        itHtml.contains('LibaIT') && itHtml.contains('LibbIT')

        and: 'merged jacoco xml covers classes from both subprojects'
        def unitCoverage = coverageXml('testCodeCoverageReport')
        def itCoverage = coverageXml('integrationTestCodeCoverageReport')
        unitCoverage.contains('sample/liba/Liba') && unitCoverage.contains('sample/libb/Libb')
        itCoverage.contains('sample/liba/Liba') && itCoverage.contains('sample/libb/Libb')

        and: 'allure aggregate report exists'
        new File(projectDir, 'build/reports/allure-report/allureAggregateReport/index.html').exists()
    }

    def 'subprojects without test-setup are not added to the rollup'() {
        given:
        new File(projectDir, 'settings.gradle') << "include 'plain'\n"
        writeFile 'plain/build.gradle', "plugins { id 'java' }\n"

        when:
        def result = runner('testRollup', '--dry-run').build()

        then:
        result.output.contains(':liba:test ')
        result.output.contains(':libb:test ')
        !result.output.contains(':plain:test ')
    }

    private String aggregateHtml(String suiteName) {
        def dir = new File(projectDir, "build/reports/tests/$suiteName/aggregated-results")
        assert dir.exists(): "missing aggregate report dir: $dir"
        def text = new StringBuilder()
        dir.eachFileRecurse { f ->
            if (f.file && f.name.endsWith('.html')) {
                text.append(f.text)
            }
        }
        text.toString()
    }

    private String coverageXml(String reportName) {
        def xml = new File(projectDir, "build/reports/jacoco/$reportName/${reportName}.xml")
        assert xml.exists(): "missing coverage xml: $xml"
        xml.text
    }

    private void writeFile(String path, String content) {
        File file = new File(projectDir, path)
        file.parentFile.mkdirs()
        file << content
    }
}
