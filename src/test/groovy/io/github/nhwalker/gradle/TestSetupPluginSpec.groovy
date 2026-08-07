package io.github.nhwalker.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import spock.lang.Specification
import spock.lang.TempDir

class TestSetupPluginSpec extends Specification {

    @TempDir
    File projectDir

    File buildFile

    def setup() {
        new File(projectDir, 'settings.gradle') << "rootProject.name = 'sample'\n"
        buildFile = new File(projectDir, 'build.gradle')
        buildFile << """
            plugins {
                id 'io.github.nhwalker.test-setup'
            }
            repositories {
                mavenCentral()
            }
        """.stripIndent()
    }

    private GradleRunner runner(String... args) {
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(args)
    }

    def 'configures suites, jacoco, and allure'() {
        given:
        buildFile << """
            task verifyModel {
                def suiteNames = testing.suites.names
                def sourceSetNames = sourceSets.names
                def hasJacoco = plugins.hasPlugin('jacoco')
                def hasAllureAdapter = plugins.hasPlugin('io.qameta.allure-adapter')
                doLast {
                    assert suiteNames.containsAll(['test', 'integrationTest'])
                    assert sourceSetNames.contains('integrationTest')
                    assert hasJacoco
                    assert hasAllureAdapter
                }
            }
        """.stripIndent()

        when:
        def result = runner('verifyModel', 'integrationTest').build()

        then:
        result.task(':verifyModel').outcome == TaskOutcome.SUCCESS
        result.task(':integrationTest').outcome == TaskOutcome.NO_SOURCE
    }

    def 'runs both suites with coverage data and allure results'() {
        given:
        writeSampleSources()

        when:
        def result = runner('check', 'jacocoTestReport', 'jacocoIntegrationTestReport').build()

        then:
        result.task(':test').outcome == TaskOutcome.SUCCESS
        result.task(':integrationTest').outcome == TaskOutcome.SUCCESS
        result.task(':jacocoTestReport').outcome == TaskOutcome.SUCCESS
        result.task(':jacocoIntegrationTestReport').outcome == TaskOutcome.SUCCESS

        and: 'jacoco execution data exists for both suites'
        new File(projectDir, 'build/jacoco/test.exec').exists()
        new File(projectDir, 'build/jacoco/integrationTest.exec').exists()

        and: 'jacoco html reports were generated'
        new File(projectDir, 'build/reports/jacoco/test/html/index.html').exists() ||
            new File(projectDir, 'build/reports/jacoco/jacocoTestReport/html/index.html').exists()
        new File(projectDir, 'build/reports/jacoco/jacocoIntegrationTestReport/html/index.html').exists()

        and: 'allure raw results were collected for both suites'
        def allureResults = findAllureResultFiles()
        !allureResults.isEmpty()
        allureResults.any { it.text.contains('GreeterTest') }
        allureResults.any { it.text.contains('GreeterIT') }
    }

    def 'check depends on integrationTest and runs it after test'() {
        when:
        def result = runner('check', '--dry-run').build()

        then:
        def lines = result.output.readLines()
        def testIndex = lines.findIndexOf { it.startsWith(':test ') }
        def integrationTestIndex = lines.findIndexOf { it.startsWith(':integrationTest ') }
        testIndex >= 0
        integrationTestIndex > testIndex
    }

    private void writeSampleSources() {
        writeSource 'src/main/java/sample/Greeter.java', '''
            package sample;
            public class Greeter {
                public String greet(String name) { return "Hello, " + name + "!"; }
            }
        '''
        writeSource 'src/test/java/sample/GreeterTest.java', '''
            package sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class GreeterTest {
                @Test
                void greets() {
                    assertEquals("Hello, Unit!", new Greeter().greet("Unit"));
                }
            }
        '''
        writeSource 'src/integrationTest/java/sample/GreeterIT.java', '''
            package sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class GreeterIT {
                @Test
                void greets() {
                    assertEquals("Hello, Integration!", new Greeter().greet("Integration"));
                }
            }
        '''
    }

    private List<File> findAllureResultFiles() {
        def results = []
        def buildDir = new File(projectDir, 'build')
        if (buildDir.exists()) {
            buildDir.eachFileRecurse { f ->
                if (f.file && f.name.endsWith('-result.json')) {
                    results << f
                }
            }
        }
        results
    }

    private void writeSource(String path, String content) {
        File file = new File(projectDir, path)
        file.parentFile.mkdirs()
        file << content.stripIndent()
    }
}
