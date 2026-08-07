package io.github.nhwalker.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import spock.lang.Specification
import spock.lang.TempDir

class IntegrationTestPluginSpec extends Specification {

    @TempDir
    File projectDir

    File buildFile

    def setup() {
        new File(projectDir, 'settings.gradle') << "rootProject.name = 'sample'\n"
        buildFile = new File(projectDir, 'build.gradle')
        buildFile << """
            plugins {
                id 'java'
                id 'io.github.nhwalker.integration-test'
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

    def 'registers an integrationTest suite with its source set and task'() {
        given:
        buildFile << """
            task verifyModel {
                def suiteNames = testing.suites.names
                def sourceSetNames = sourceSets.names
                doLast {
                    assert suiteNames.contains('integrationTest')
                    assert sourceSetNames.contains('integrationTest')
                }
            }
        """.stripIndent()

        when:
        def result = runner('verifyModel', 'integrationTest').build()

        then:
        result.task(':verifyModel').outcome == TaskOutcome.SUCCESS
        result.task(':integrationTest').outcome == TaskOutcome.NO_SOURCE
    }

    def 'compiles and runs suite tests from src/integrationTest against main classes'() {
        given:
        writeSource 'src/main/java/sample/Greeter.java', '''
            package sample;
            public class Greeter {
                public String greet(String name) { return "Hello, " + name + "!"; }
            }
        '''
        writeSource 'src/integrationTest/java/sample/GreeterIT.java', '''
            package sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class GreeterIT {
                @Test
                void greets() {
                    assertEquals("Hello, World!", new Greeter().greet("World"));
                }
            }
        '''

        when:
        def result = runner('integrationTest').build()

        then:
        result.task(':compileJava').outcome == TaskOutcome.SUCCESS
        result.task(':integrationTest').outcome == TaskOutcome.SUCCESS
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

    private void writeSource(String path, String content) {
        File file = new File(projectDir, path)
        file.parentFile.mkdirs()
        file << content.stripIndent()
    }
}
