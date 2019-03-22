/* Copyright 2019 EPAM Systems.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at
http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

See the License for the specific language governing permissions and
limitations under the License. */

import groovy.json.*

def createCiPipeline(pipelineName, applicationName, applicationStages, pipelineScript, devopsRepository) {
    pipelineJob("${pipelineName}") {
        logRotator {
            numToKeep(10)
            daysToKeep(7)
        }
        triggers {
            gerrit {
                events {
                    if (pipelineName.contains("Build"))
                        changeMerged()
                    else
                        patchsetCreated()
                }
                project("plain:${applicationName}", ['ant:**'])
            }
        }
        definition {
            cpsScm {
                scm {
                    git {
                        remote { url(devopsRepository) }
                        branches("master")
                        scriptPath("${pipelineScript}")
                    }
                }
                parameters {
                    stringParam("STAGES", "${applicationStages}", "")
                    stringParam("GERRIT_PROJECT_NAME", "${applicationName}", "")
                    if (pipelineName.contains("Build"))
                        stringParam("BRANCH", "master", "")
                }
            }
        }
    }
}

def createReleasePipeline(pipelineName, applicationName, applicationStages, pipelineScript, devopsRepository) {
    pipelineJob("${pipelineName}") {
        logRotator {
            numToKeep(14)
            daysToKeep(30)
        }
        definition {
            cpsScm {
                scm {
                    git {
                        remote { url(devopsRepository) }
                        branches("master")
                        scriptPath("${pipelineScript}")
                    }
                }
                parameters {
                    stringParam("STAGES", "${applicationStages}", "")
                    if (pipelineName.contains("Create-release")) {
                        stringParam("GERRIT_PROJECT", "${applicationName}", "")
                        stringParam("RELEASE_NAME", "", "")
                    }
                }
            }
        }
    }
}

def gerritSshPort = "{{ gerrit_ssh_port }}"
def appRepositoryBase = "ssh://jenkins@gerrit:${gerritSshPort}"
def stages = [:]
stages['Code-review-application'] = "[{\"name\": \"gerrit-checkout\"},{\"name\": \"compile\"},{\"name\": \"tests\"}," +
        "{\"name\": \"sonar\"}]"
stages['Code-review-autotest'] = "[{\"name\": \"gerrit-checkout\"},{\"name\": \"tests\"},{\"name\": \"sonar\"}]"
stages['Build-maven'] = "[{\"name\": \"checkout\"},{\"name\": \"get-version\"},{\"name\": \"compile\"}," +
        "{\"name\": \"tests\"},{\"name\": \"sonar\"},{\"name\": \"build\"},{\"name\": \"build-image\"}," +
        "{\"name\": \"push\"},{\"name\": \"git-tag\"}]"
stages['Build-npm'] = stages['Build-maven']
stages['Build-gradle'] = stages['Build-maven']
stages['Build-dotnet'] = "[{\"name\": \"checkout\"},{\"name\": \"get-version\"},{\"name\": \"compile\"}," +
        "{\"name\": \"tests\"},{\"name\": \"sonar\"},{\"name\": \"build-image\"}," +
        "{\"name\": \"push\"},{\"name\": \"git-tag\"}]"
stages['Create-release'] = "[{\"name\": \"checkout\"},{\"name\": \"create-branch\"}]"

['app.settings.json', 'auto-test.settings.json'].each() { settingsFile ->
    new JsonSlurperClassic().parseText(new File("${JENKINS_HOME}/project-settings/${settingsFile}").text).each() { item ->
        def applicationName = item.name
        if (settingsFile == 'app.settings.json') {
            createCiPipeline("Code-review-${applicationName}", applicationName, stages['Code-review-application'],
                    "code-review.groovy", "${appRepositoryBase}/${item.name}")
            createCiPipeline("Build-${applicationName}", applicationName, stages["Build-${item.build_tool.toLowerCase()}"],
                    "build.groovy", "${appRepositoryBase}/${item.name}")
            createReleasePipeline("Create-release-${applicationName}", applicationName, stages["Create-release"],
                    "create-release.groovy", "${appRepositoryBase}/${item.name}")
        } else
            createCiPipeline("Code-review-${applicationName}", applicationName, stages['Code-review-autotest'],
                    "code-review.groovy", "${appRepositoryBase}/${item.name}")

    }
}

if (Boolean.valueOf("${PARAM}")) {
    def appName = "${NAME}"
    def type = "${TYPE}"
    def buildTool = "${BUILD_TOOL}"
    if (type == "app") {
        createCiPipeline("Code-review-${appName}", appName, stages['Code-review-application'], "code-review.groovy", "${appRepositoryBase}/${appName}")
        createCiPipeline("Build-${appName}", appName, stages["Build-${buildTool}"], "build.groovy", "${appRepositoryBase}/${appName}")
        createReleasePipeline("Create-release-${appName}", appName, stages["Create-release"], "create-release.groovy", "${appRepositoryBase}/${appName}")
    } else {
        createCiPipeline("Code-review-${appName}", appName, stages['Code-review-autotest'], "code-review.groovy", "${appRepositoryBase}/${appName}")
    }
}