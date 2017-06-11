/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.integtests.composite

import org.gradle.integtests.fixtures.build.BuildTestFile
/**
 * Tests for resolving dependency cycles in a composite build.
 */
class CompositeBuildEventsIntegrationTest extends AbstractCompositeBuildIntegrationTest {
    BuildTestFile buildB
    BuildTestFile buildC

    def setup() {
        file('gradle-user-home/init.gradle') << """
            gradle.buildStarted {
                println 'gradle.buildStarted [' + gradle.identityPath + ']'
            }
            gradle.buildFinished {
                println 'gradle.buildFinished [' + gradle.identityPath + ']'
            }
            gradle.addBuildListener(new LoggingBuildListener())
            class LoggingBuildListener extends BuildAdapter {
                void buildStarted(Gradle gradle) {
                    println 'buildListener.buildStarted [' + gradle.identityPath + ']'
                }
                void settingsEvaluated(Settings settings) {
                    def buildName = settings.gradle.parent == null ? '' : settings.rootProject.name
                    println 'buildListener.settingsEvaluated [:' + buildName + ']'
                }
                void projectsLoaded(Gradle gradle) {
                    println 'buildListener.projectsLoaded [' + gradle.identityPath + ']'
                }
                void projectsEvaluated(Gradle gradle) {
                    println 'buildListener.projectsEvaluated [' + gradle.identityPath + ']'
                }
                void buildFinished(BuildResult result) {
                    println 'buildListener.buildFinished [' + result.gradle.identityPath + ']'
                }
            }
"""

        buildA.buildFile << """
            task resolveArtifacts(type: Copy) {
                from configurations.compile
                into 'libs'
            }
"""

        buildB = multiProjectBuild("buildB", ['b1', 'b2']) {
            buildFile << """
                allprojects {
                    apply plugin: 'java'
                }
"""
        }
        includedBuilds << buildB

        buildC = singleProjectBuild("buildC") {
            buildFile << """
                apply plugin: 'java'
"""
        }
        includedBuilds << buildC
    }

    def "fires build listener events on included builds"() {
        given:
        dependency 'org.test:buildB:1.0'
        dependency buildB, 'org.test:buildC:1.0'

        when:
        execute()

        then:
        loggedOncePerBuild('buildListener.settingsEvaluated')
        loggedOncePerBuild('buildListener.projectsLoaded')
        loggedOncePerBuild('buildListener.projectsEvaluated')
        loggedOncePerBuild('buildListener.buildFinished')
        loggedOncePerBuild('gradle.buildFinished')

        and:
        // buildStarted events should _not_ be logged, since the listeners are added too late
        // If they are logged, it's likely due to duplicate events fired.
        !result.output.contains('gradle.buildStarted')
        !result.output.contains('buildListener.buildStarted')
    }

    void loggedOncePerBuild(message) {
        loggedOnce(message + " [:]")
        loggedOnce(message + " [:buildB]")
        loggedOnce(message + " [:buildC]")
    }

    void loggedOnce(message) {
        result.assertOutputContains(message)
        assert result.output.count(message) == 1
    }

    protected void execute() {
        super.execute(buildA, ":resolveArtifacts", ["-I../gradle-user-home/init.gradle"])
    }
}