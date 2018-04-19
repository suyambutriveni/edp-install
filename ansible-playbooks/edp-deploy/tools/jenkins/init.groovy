import jenkins.model.*
import hudson.model.*
import hudson.security.csrf.DefaultCrumbIssuer
import org.csanchez.jenkins.plugins.kubernetes.*
import hudson.plugins.sonar.*
import hudson.plugins.sonar.model.*
import com.sonyericsson.hudson.plugins.gerrit.trigger.PluginImpl
import com.sonyericsson.hudson.plugins.gerrit.trigger.GerritServer
import com.sonyericsson.hudson.plugins.gerrit.trigger.config.PluginConfig
import net.sf.json.*;
import hudson.model.FreeStyleProject
import hudson.tasks.Shell
import javaposse.jobdsl.plugin.*
import hudson.triggers.*
import javaposse.jobdsl.plugin.GlobalJobDslSecurityConfiguration
import jenkins.model.GlobalConfiguration
import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.domains.Domain
import com.cloudbees.jenkins.plugins.sshcredentials.impl.*
import com.cloudbees.plugins.credentials.*
import com.cloudbees.plugins.credentials.common.*
import com.cloudbees.plugins.credentials.domains.*
import com.cloudbees.plugins.credentials.impl.*

def JENKINS_HOME = System.getenv().get('JENKINS_HOME')
def GERRIT_SSH_PORT = System.getenv().get('GERRIT_SSH_PORT')
def JENKINS_URL = System.getenv().get('JENKINS_UI_URL')
file = new File("${JENKINS_HOME}/done.txt")
if (file.exists()) {
    println("[DEBUG] Configuration of Jenkins has been already done")
    return
}

//Set Jenkins URL
urlConfig = JenkinsLocationConfiguration.get()
urlConfig.setUrl(JENKINS_URL)
urlConfig.save()
println("[DEBUG] Jenkins URL Set to ${JENKINS_URL}")

// Enabling Crumb
def instance = Jenkins.instance
instance.setCrumbIssuer(new DefaultCrumbIssuer(true))
instance.save()

// Security disabling for DSl plugin
GlobalConfiguration.all().get(GlobalJobDslSecurityConfiguration.class).useScriptSecurity = false
GlobalConfiguration.all().get(GlobalJobDslSecurityConfiguration.class).save()

// Sonar plugin configuration
SonarGlobalConfiguration sonarConf = Hudson.instance.getDescriptorByType(SonarGlobalConfiguration.class)
def sonarProperties = new SonarInstallation(
        "Sonar",
        "http://sonar:9000",
        "31fd1e3aebd44bc47a6caa9d274765a44ed82e5d",
        "5.3",
        "",
        new TriggersConfig(),
        ""
)
sonarConf.setInstallations(sonarProperties)
sonarConf.save()

// Modifying Gerrit Plugin for getting an ability to post-configure it via job
PluginImpl plugin = PluginImpl.getInstance();
PluginConfig pluginConfig = plugin.getPluginConfig();

server = new GerritServer("gerrit", false)
def config = server.getConfig()

def triggerConfig = [
    'gerritBuildStartedVerifiedValue':0,
    'gerritBuildStartedCodeReviewValue':0,
    'gerritBuildSuccessfulVerifiedValue':2,
    'gerritBuildSuccessfulCodeReviewValue':0,
    'gerritBuildFailedVerifiedValue':-2,
    'gerritBuildFailedCodeReviewValue':0,
    'gerritBuildUnstableVerifiedValue':0,
    'gerritBuildUnstableCodeReviewValue':0,
    'gerritBuildNotBuiltVerifiedValue':0,
    'gerritBuildNotBuiltCodeReviewValue':0,
  ]
config.setValues(JSONObject.fromObject(triggerConfig))
server.setConfig(config)

server.getConfig().setNumberOfSendingWorkerThreads(pluginConfig.getNumberOfSendingWorkerThreads());
server.getConfig().setGerritHostName("gerrit")
server.getConfig().setGerritFrontEndURL("http://gerrit:8080/")
server.getConfig().setGerritAuthKeyFile(new File("${JENKINS_HOME}/.ssh/id_rsa"))
server.getConfig().setGerritSshPort("${GERRIT_SSH_PORT}".toInteger())
server.getConfig().setGerritUserName("jenkins")
server.getConfig().setUseRestApi(true)
server.getConfig().setGerritHttpUserName("jenkins")
server.getConfig().setGerritHttpPassword("jenkins")
server.getConfig().setRestCodeReview(true)
server.getConfig().setRestVerified(true)

plugin.addServer(server)
server.start()
plugin.save()

// Generatin keys for Gerrit exchange
def cmd = [
        'bash',
        '-c',
        '''mkdir -p ${JENKINS_HOME}/.ssh
          |cat /dev/zero | ssh-keygen -b 4096 -t rsa -f ${JENKINS_HOME}/.ssh/id_rsa -C "jenkins@example.com" -q -N ""
          |'''.stripMargin()]
println("[DEBUG] ${cmd.execute().text}")

//Add credentials
global_domain = Domain.global()
credentials_store = Jenkins.instance.getExtensionList('com.cloudbees.plugins.credentials.SystemCredentialsProvider')[0].getStore()
credentials = new BasicSSHUserPrivateKey(CredentialsScope.GLOBAL, "jenkins", "jenkins",
        new BasicSSHUserPrivateKey.UsersPrivateKeySource(), "", "Jenkins private ssh key for Gerrit")
credentials_store.addCredentials(global_domain, credentials)

// Creating a job dsl
def jobName = "Job-provisioning"
project = Jenkins.instance.createProject(FreeStyleProject, jobName)
project.getBuildersList().clear()

// Copy app settings
def checkDir = "mkdir -p \"${JENKINS_HOME}/project-settings\"\n"
def getAppSettings = $/oc get cm project-settings --template='{{ index .data "app.settings.json" }}' > \
${JENKINS_HOME}/project-settings/app.settings.json/$
def getAutotestSettings = $/oc get cm project-settings --template='{{ index .data "auto-test.settings.json" }}' > \
${JENKINS_HOME}/project-settings/auto-test.settings.json/$
project.buildersList.add(new Shell(checkDir + getAppSettings + "\n" + getAutotestSettings + "\n"))

executeDslScripts = new ExecuteDslScripts()
executeDslScripts.setTargets("*.groovy")
executeDslScripts.setLookupStrategy(LookupStrategy.JENKINS_ROOT)

project.getBuildersList().add(executeDslScripts)
project.save()
project.scheduleBuild()

cmd = [
        'bash',
        '-c',
        """set -x
           |mkdir -p ${JENKINS_HOME}/jobs/${jobName}/workspace
           |cp ${JENKINS_HOME}/init.groovy.d/dsl/*.groovy ${JENKINS_HOME}/jobs/${jobName}/workspace/
           |cp ${JENKINS_HOME}/scriptApproval.xml.ORIG ${JENKINS_HOME}/scriptApproval.xml
           |""".stripMargin() ]
println("[DEBUG] ${cmd.execute().text}")

//================================
String filename = "${JENKINS_HOME}/done.txt"
new File(filename).createNewFile()
