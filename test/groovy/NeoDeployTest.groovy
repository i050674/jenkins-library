import com.sap.piper.Utils
import hudson.AbortException

import org.junit.rules.TemporaryFolder

import org.junit.BeforeClass
import org.junit.ClassRule
import util.JenkinsLockRule
import org.hamcrest.BaseMatcher
import org.hamcrest.Description
import org.jenkinsci.plugins.credentialsbinding.impl.CredentialNotFoundException
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.junit.rules.RuleChain

import util.BasePiperTest
import util.JenkinsCredentialsRule
import util.JenkinsLoggingRule
import util.JenkinsPropertiesRule
import util.JenkinsReadYamlRule
import util.JenkinsShellCallRule
import util.JenkinsShellCallRule.Type
import util.JenkinsStepRule
import util.Rules

class NeoDeployTest extends BasePiperTest {

    def toolJavaValidateCalled = false

    @ClassRule
    public static TemporaryFolder tmp = new TemporaryFolder()

    private ExpectedException thrown = new ExpectedException().none()
    private JenkinsLoggingRule loggingRule = new JenkinsLoggingRule(this)
    private JenkinsShellCallRule shellRule = new JenkinsShellCallRule(this)
    private JenkinsStepRule stepRule = new JenkinsStepRule(this)
    private JenkinsLockRule lockRule = new JenkinsLockRule(this)


    @Rule
    public RuleChain ruleChain = Rules
        .getCommonRules(this)
        .around(new JenkinsReadYamlRule(this))
        .around(new JenkinsPropertiesRule(this, propertiesFileName, configProperties))
        .around(thrown)
        .around(loggingRule)
        .around(shellRule)
        .around(new JenkinsCredentialsRule(this)
        .withCredentials('myCredentialsId', 'anonymous', '********')
        .withCredentials('CI_CREDENTIALS_ID', 'defaultUser', '********'))
        .around(stepRule)
        .around(lockRule)


    private static workspacePath
    private static warArchiveName
    private static propertiesFileName
    private static archiveName
    private static configProperties


    @BeforeClass
    static void createTestFiles() {

        workspacePath = "${tmp.getRoot()}"
        warArchiveName = 'warArchive.war'
        propertiesFileName = 'config.properties'
        archiveName = 'archive.mtar'

        configProperties = new Properties()
        configProperties.put('account', 'trialuser123')
        configProperties.put('host', 'test.deploy.host.com')
        configProperties.put('application', 'testApp')

        tmp.newFile(warArchiveName) << 'dummy war archive'
        tmp.newFile(propertiesFileName) << 'dummy properties file'
        tmp.newFile(archiveName) << 'dummy archive'
    }

    @Before
    void init() {

        helper.registerAllowedMethod('dockerExecute', [Map, Closure], null)
        helper.registerAllowedMethod('fileExists', [String], { s -> return new File(workspacePath, s).exists() })
        mockShellCommands()

        nullScript.commonPipelineEnvironment.configuration = [steps: [neoDeploy: [host: 'test.deploy.host.com', account: 'trialuser123']]]
    }


    @Test
    void straightForwardTestConfigViaConfigProperties() {

        boolean buildStatusHasBeenSet = false
        boolean notifyOldConfigFrameworkUsed = false

        nullScript.commonPipelineEnvironment.setConfigProperty('DEPLOY_HOST', 'test.deploy.host.com')
        nullScript.commonPipelineEnvironment.setConfigProperty('CI_DEPLOY_ACCOUNT', 'trialuser123')
        nullScript.commonPipelineEnvironment.configuration = [:]

        nullScript.currentBuild = [setResult: { buildStatusHasBeenSet = true }]

        def utils = new Utils() {
            void pushToSWA(Map parameters, Map config) {
                notifyOldConfigFrameworkUsed = parameters.stepParam4
            }
        }

        stepRule.step.neoDeploy(script: nullScript,
            archivePath: archiveName,
            neoCredentialsId: 'myCredentialsId',
            utils: utils
        )

        Assert.assertThat(shellRule.shell,
            new CommandLineMatcher().hasProlog("#!/bin/bash \"/opt/neo/tools/neo.sh\" deploy-mta")
                .hasSingleQuotedOption('host', 'test\\.deploy\\.host\\.com')
                .hasSingleQuotedOption('account', 'trialuser123')
                .hasOption('synchronous', '')
                .hasSingleQuotedOption('user', 'anonymous')
                .hasSingleQuotedOption('password', '\\*\\*\\*\\*\\*\\*\\*\\*')
                .hasSingleQuotedOption('source', '.*'))

        assert !buildStatusHasBeenSet
        assert notifyOldConfigFrameworkUsed
    }

    @Test
    void testConfigViaConfigPropertiesSetsBuildToUnstable() {

        def buildStatus = 'SUCCESS'

        nullScript.commonPipelineEnvironment.setConfigProperty('DEPLOY_HOST', 'test.deploy.host.com')
        nullScript.commonPipelineEnvironment.setConfigProperty('CI_DEPLOY_ACCOUNT', 'trialuser123')
        nullScript.commonPipelineEnvironment.configuration = [:]

        nullScript.currentBuild = [setResult: { r -> buildStatus = r }]

        System.setProperty('com.sap.piper.featureFlag.buildUnstableWhenOldConfigFrameworkIsUsedByNeoDeploy',
            Boolean.TRUE.toString())

        try {
            stepRule.step.neoDeploy(script: nullScript,
                archivePath: archiveName,
                neoCredentialsId: 'myCredentialsId',
                utils: utils
            )
        } finally {
            System.clearProperty('com.sap.piper.featureFlag.buildUnstableWhenOldConfigFrameworkIsUsedByNeoDeploy')
        }

        assert buildStatus == 'UNSTABLE'
    }

    @Test
    void straightForwardTestConfigViaConfiguration() {

        boolean notifyOldConfigFrameworkUsed = true

        def utils = new Utils() {
            void pushToSWA(Map parameters, Map config) {
                notifyOldConfigFrameworkUsed = parameters.stepParam4
            }
        }

        stepRule.step.neoDeploy(script: nullScript,
            archivePath: archiveName,
            neoCredentialsId: 'myCredentialsId',
            utils: utils,
        )

        Assert.assertThat(shellRule.shell,
            new CommandLineMatcher().hasProlog("#!/bin/bash \"/opt/neo/tools/neo.sh\" deploy-mta")
                .hasSingleQuotedOption('host', 'test\\.deploy\\.host\\.com')
                .hasSingleQuotedOption('account', 'trialuser123')
                .hasOption('synchronous', '')
                .hasSingleQuotedOption('user', 'anonymous')
                .hasSingleQuotedOption('password', '\\*\\*\\*\\*\\*\\*\\*\\*')
                .hasSingleQuotedOption('source', '.*'))

        assert !notifyOldConfigFrameworkUsed
    }

    @Test
    void straightForwardTestConfigViaConfigurationAndViaConfigProperties() {

        nullScript.commonPipelineEnvironment.setConfigProperty('DEPLOY_HOST', 'configProperties.deploy.host.com')
        nullScript.commonPipelineEnvironment.setConfigProperty('CI_DEPLOY_ACCOUNT', 'configPropsUser123')

        nullScript.commonPipelineEnvironment.configuration = [steps: [neoDeploy: [host   : 'configuration-frwk.deploy.host.com',
                                                                                  account: 'configurationFrwkUser123']]]

        stepRule.step.neoDeploy(script: nullScript,
            archivePath: archiveName,
            neoCredentialsId: 'myCredentialsId'
        )

        Assert.assertThat(shellRule.shell,
            new CommandLineMatcher().hasProlog("#!/bin/bash \"/opt/neo/tools/neo.sh\" deploy-mta")
                .hasSingleQuotedOption('host', 'configuration-frwk\\.deploy\\.host\\.com')
                .hasSingleQuotedOption('account', 'configurationFrwkUser123')
                .hasOption('synchronous', '')
                .hasSingleQuotedOption('user', 'anonymous')
                .hasSingleQuotedOption('password', '\\*\\*\\*\\*\\*\\*\\*\\*')
                .hasSingleQuotedOption('source', '.*'))
    }

    @Test
    void archivePathFromCPETest() {
        nullScript.commonPipelineEnvironment.setMtarFilePath('archive.mtar')
        stepRule.step.neoDeploy(script: nullScript)

        Assert.assertThat(shellRule.shell,
            new CommandLineMatcher().hasProlog("#!/bin/bash \"/opt/neo/tools/neo.sh\" deploy-mta")
                .hasSingleQuotedOption('source', 'archive.mtar'))
    }

    @Test
    void archivePathFromParamsHasHigherPrecedenceThanCPETest() {
        nullScript.commonPipelineEnvironment.setMtarFilePath('archive2.mtar')
        stepRule.step.neoDeploy(script: nullScript,
            archivePath: "archive.mtar")

        Assert.assertThat(shellRule.shell,
            new CommandLineMatcher().hasProlog("#!/bin/bash \"/opt/neo/tools/neo.sh\" deploy-mta")
                .hasSingleQuotedOption('source', 'archive.mtar'))
    }


    @Test
    void badCredentialsIdTest() {

        thrown.expect(CredentialNotFoundException)

        stepRule.step.neoDeploy(script: nullScript,
            archivePath: archiveName,
            neoCredentialsId: 'badCredentialsId'
        )
    }


    @Test
    void credentialsIdNotProvidedTest() {

        stepRule.step.neoDeploy(script: nullScript,
            archivePath: archiveName
        )

        Assert.assertThat(shellRule.shell,
            new CommandLineMatcher().hasProlog("#!/bin/bash \"/opt/neo/tools/neo.sh\" deploy-mta")
                .hasSingleQuotedOption('host', 'test\\.deploy\\.host\\.com')
                .hasSingleQuotedOption('account', 'trialuser123')
                .hasOption('synchronous', '')
                .hasSingleQuotedOption('user', 'defaultUser')
                .hasSingleQuotedOption('password', '\\*\\*\\*\\*\\*\\*\\*\\*')
                .hasSingleQuotedOption('source', '.*')
        )
    }


    @Test
    void neoHomeNotSetTest() {

        mockHomeVariablesNotSet()

        stepRule.step.neoDeploy(script: nullScript,
            archivePath: archiveName
        )

        assert shellRule.shell.find { c -> c.contains('"neo.sh" deploy-mta') }
        assert loggingRule.log.contains('SAP Cloud Platform Console Client is on PATH.')
        assert loggingRule.log.contains("Using SAP Cloud Platform Console Client 'neo.sh'.")
    }


    @Test
    void neoHomeAsParameterTest() {

        mockHomeVariablesNotSet()

        stepRule.step.neoDeploy(script: nullScript,
            archivePath: archiveName,
            neoCredentialsId: 'myCredentialsId',
            neoHome: '/param/neo'
        )

        assert shellRule.shell.find { c -> c = "\"/param/neo/tools/neo.sh\" deploy-mta" }
        assert loggingRule.log.contains("SAP Cloud Platform Console Client home '/param/neo' retrieved from configuration.")
        assert loggingRule.log.contains("Using SAP Cloud Platform Console Client '/param/neo/tools/neo.sh'.")
    }


    @Test
    void neoHomeFromEnvironmentTest() {

        stepRule.step.neoDeploy(script: nullScript,
            archivePath: archiveName
        )

        assert shellRule.shell.find { c -> c.contains("\"/opt/neo/tools/neo.sh\" deploy-mta") }
        assert loggingRule.log.contains("SAP Cloud Platform Console Client home '/opt/neo' retrieved from environment.")
        assert loggingRule.log.contains("Using SAP Cloud Platform Console Client '/opt/neo/tools/neo.sh'.")
    }


    @Test
    void neoHomeFromCustomStepConfigurationTest() {

        mockHomeVariablesNotSet()

        nullScript.commonPipelineEnvironment.configuration = [steps: [neoDeploy: [host: 'test.deploy.host.com', account: 'trialuser123', neoHome: '/config/neo']]]

        stepRule.step.neoDeploy(script: nullScript,
            archivePath: archiveName
        )

        assert shellRule.shell.find { c -> c = "\"/config/neo/tools/neo.sh\" deploy-mta" }
        assert loggingRule.log.contains("SAP Cloud Platform Console Client home '/config/neo' retrieved from configuration.")
        assert loggingRule.log.contains("Using SAP Cloud Platform Console Client '/config/neo/tools/neo.sh'.")
    }


    @Test
    void archiveNotProvidedTest() {

        thrown.expect(Exception)
        thrown.expectMessage('Error in neoDeploy: source not configured.')

        stepRule.step.neoDeploy(script: nullScript)
    }


    @Test
    void wrongArchivePathProvidedTest() {

        thrown.expect(AbortException)
        thrown.expectMessage('File wrongArchiveName cannot be found')

        stepRule.step.neoDeploy(script: nullScript,
            archivePath: 'wrongArchiveName')
    }


    @Test
    void scriptNotProvidedTest() {

        thrown.expect(Exception)
        thrown.expectMessage('Error in Neo deployment configuration. Configuration for host is missing.')

        nullScript.commonPipelineEnvironment.configuration = [:]

        stepRule.step.neoDeploy(script: nullScript, archivePath: archiveName)
    }

    @Test
    void mtaDeployModeTest() {

        stepRule.step.neoDeploy(script: nullScript, archivePath: archiveName, deployMode: 'mta')

        Assert.assertThat(shellRule.shell,
            new CommandLineMatcher().hasProlog("#!/bin/bash \"/opt/neo/tools/neo.sh\" deploy-mta")
                .hasSingleQuotedOption('host', 'test\\.deploy\\.host\\.com')
                .hasSingleQuotedOption('account', 'trialuser123')
                .hasOption('synchronous', '')
                .hasSingleQuotedOption('user', 'defaultUser')
                .hasSingleQuotedOption('password', '\\*\\*\\*\\*\\*\\*\\*\\*')
                .hasSingleQuotedOption('source', '.*'))

    }

    @Test
    void warFileParamsDeployModeTest() {

        stepRule.step.neoDeploy(script: nullScript,
            applicationName: 'testApp',
            runtime: 'neo-javaee6-wp',
            runtimeVersion: '2.125',
            deployMode: 'warParams',
            vmSize: 'lite',
            warAction: 'deploy',
            archivePath: warArchiveName)

        Assert.assertThat(shellRule.shell,
            new CommandLineMatcher().hasProlog("#!/bin/bash \"/opt/neo/tools/neo.sh\" deploy")
                .hasSingleQuotedOption('host', 'test\\.deploy\\.host\\.com')
                .hasSingleQuotedOption('account', 'trialuser123')
                .hasSingleQuotedOption('application', 'testApp')
                .hasSingleQuotedOption('runtime', 'neo-javaee6-wp')
                .hasSingleQuotedOption('runtime-version', '2\\.125')
                .hasSingleQuotedOption('size', 'lite')
                .hasSingleQuotedOption('user', 'defaultUser')
                .hasSingleQuotedOption('password', '\\*\\*\\*\\*\\*\\*\\*\\*')
                .hasSingleQuotedOption('source', '.*\\.war'))

    }

    @Test
    void warFileParamsDeployModeRollingUpdateTest() {

        shellRule.setReturnValue(JenkinsShellCallRule.Type.REGEX, '.* status .*', 'Status: STARTED')

        stepRule.step.neoDeploy(script: nullScript,
            archivePath: warArchiveName,
            deployMode: 'warParams',
            applicationName: 'testApp',
            runtime: 'neo-javaee6-wp',
            runtimeVersion: '2.125',
            warAction: 'rolling-update',
            vmSize: 'lite')

        Assert.assertThat(shellRule.shell,
            new CommandLineMatcher().hasProlog("#!/bin/bash \"/opt/neo/tools/neo.sh\" rolling-update")
                .hasSingleQuotedOption('host', 'test\\.deploy\\.host\\.com')
                .hasSingleQuotedOption('account', 'trialuser123')
                .hasSingleQuotedOption('application', 'testApp')
                .hasSingleQuotedOption('runtime', 'neo-javaee6-wp')
                .hasSingleQuotedOption('runtime-version', '2\\.125')
                .hasSingleQuotedOption('size', 'lite')
                .hasSingleQuotedOption('user', 'defaultUser')
                .hasSingleQuotedOption('password', '\\*\\*\\*\\*\\*\\*\\*\\*')
                .hasSingleQuotedOption('source', '.*\\.war'))
    }

    @Test
    void warPropertiesFileDeployModeTest() {

        stepRule.step.neoDeploy(script: nullScript,
            archivePath: warArchiveName,
            deployMode: 'warPropertiesFile',
            propertiesFile: propertiesFileName,
            applicationName: 'testApp',
            runtime: 'neo-javaee6-wp',
            runtimeVersion: '2.125',
            warAction: 'deploy',
            vmSize: 'lite')

        Assert.assertThat(shellRule.shell,
            new CommandLineMatcher().hasProlog("#!/bin/bash \"/opt/neo/tools/neo.sh\" deploy")
                .hasArgument("config.properties")
                .hasSingleQuotedOption('user', 'defaultUser')
                .hasSingleQuotedOption('password', '\\*\\*\\*\\*\\*\\*\\*\\*')
                .hasSingleQuotedOption('source', '.*\\.war'))
    }

    @Test
    void warPropertiesFileDeployModeRollingUpdateTest() {

        shellRule.setReturnValue(JenkinsShellCallRule.Type.REGEX, '.* status .*', 'Status: STARTED')

        stepRule.step.neoDeploy(script: nullScript,
            archivePath: warArchiveName,
            deployMode: 'warPropertiesFile',
            propertiesFile: propertiesFileName,
            applicationName: 'testApp',
            runtime: 'neo-javaee6-wp',
            runtimeVersion: '2.125',
            warAction: 'rolling-update',
            vmSize: 'lite')

        Assert.assertThat(shellRule.shell,
            new CommandLineMatcher().hasProlog("#!/bin/bash \"/opt/neo/tools/neo.sh\" rolling-update")
                .hasArgument('config.properties')
                .hasSingleQuotedOption('user', 'defaultUser')
                .hasSingleQuotedOption('password', '\\*\\*\\*\\*\\*\\*\\*\\*')
                .hasSingleQuotedOption('source', '.*\\.war'))
    }

    @Test
    void applicationNameNotProvidedTest() {

        thrown.expect(Exception)
        thrown.expectMessage('Error in Neo deployment configuration. Configuration for application is missing.')

        stepRule.step.neoDeploy(script: nullScript,
            archivePath: warArchiveName,
            deployMode: 'warParams',
            runtime: 'neo-javaee6-wp',
            runtimeVersion: '2.125'
        )
    }

    @Test
    void runtimeNotProvidedTest() {

        thrown.expect(Exception)
        thrown.expectMessage('Error in Neo deployment configuration. Configuration for runtime is missing.')

        stepRule.step.neoDeploy(script: nullScript,
            archivePath: warArchiveName,
            applicationName: 'testApp',
            deployMode: 'warParams',
            runtimeVersion: '2.125')
    }

    @Test
    void runtimeVersionNotProvidedTest() {

        thrown.expect(Exception)
        thrown.expectMessage('Error in Neo deployment configuration. Configuration for runtimeVersion is missing.')

        stepRule.step.neoDeploy(script: nullScript,
            archivePath: warArchiveName,
            applicationName: 'testApp',
            deployMode: 'warParams',
            runtime: 'neo-javaee6-wp')
    }

    @Test
    void illegalDeployModeTest() {

        thrown.expect(Exception)
        thrown.expectMessage("Invalid deployMode = 'illegalMode'. Valid 'deployMode' values are: [mta, warParams, warPropertiesFile].")

        stepRule.step.neoDeploy(script: nullScript,
            archivePath: warArchiveName,
            deployMode: 'illegalMode',
            applicationName: 'testApp',
            runtime: 'neo-javaee6-wp',
            runtimeVersion: '2.125',
            warAction: 'deploy',
            vmSize: 'lite')
    }

    @Test
    void illegalVMSizeTest() {

        thrown.expect(Exception)
        thrown.expectMessage("Invalid size = 'illegalVM'. Valid 'size' values are: [lite, pro, prem, prem-plus].")

        stepRule.step.neoDeploy(script: nullScript,
            archivePath: warArchiveName,
            deployMode: 'warParams',
            applicationName: 'testApp',
            runtime: 'neo-javaee6-wp',
            runtimeVersion: '2.125',
            warAction: 'deploy',
            vmSize: 'illegalVM')
    }

    @Test
    void illegalWARActionTest() {

        thrown.expect(Exception)
        thrown.expectMessage("Invalid warAction = 'illegalWARAction'. Valid 'warAction' values are: [deploy, rolling-update].")

        stepRule.step.neoDeploy(script: nullScript,
            archivePath: warArchiveName,
            deployMode: 'warParams',
            applicationName: 'testApp',
            runtime: 'neo-javaee6-wp',
            runtimeVersion: '2.125',
            warAction: 'illegalWARAction',
            vmSize: 'lite')
    }

    @Test
    void deployHostProvidedAsDeprecatedParameterTest() {

        nullScript.commonPipelineEnvironment.setConfigProperty('CI_DEPLOY_ACCOUNT', 'configPropsUser123')

        stepRule.step.neoDeploy(script: nullScript,
            archivePath: archiveName,
            deployHost: "my.deploy.host.com"
        )

        assert loggingRule.log.contains("[WARNING][neoDeploy] Deprecated parameter 'deployHost' is used. This will not work anymore in future versions. Use parameter 'host' instead.")
    }

    @Test
    void deployAccountProvidedAsDeprecatedParameterTest() {

        nullScript.commonPipelineEnvironment.setConfigProperty('CI_DEPLOY_ACCOUNT', 'configPropsUser123')

        stepRule.step.neoDeploy(script: nullScript,
            archivePath: archiveName,
            host: "my.deploy.host.com",
            deployAccount: "myAccount"
        )

        assert loggingRule.log.contains("Deprecated parameter 'deployAccount' is used. This will not work anymore in future versions. Use parameter 'account' instead.")
    }

    private mockShellCommands() {
        String javaVersion = '''openjdk version \"1.8.0_121\"
                    OpenJDK Runtime Environment (build 1.8.0_121-8u121-b13-1~bpo8+1-b13)
                    OpenJDK 64-Bit Server VM (build 25.121-b13, mixed mode)'''
        shellRule.setReturnValue(Type.REGEX, '.*java -version.*', javaVersion)

        String neoVersion = '''SAP Cloud Platform Console Client
                    SDK version    : 3.39.10
                    Runtime        : neo-java-web'''
        shellRule.setReturnValue(Type.REGEX, '.*neo.sh version.*', neoVersion)

        shellRule.setReturnValue(Type.REGEX, '.*JAVA_HOME.*', '/opt/java')
        shellRule.setReturnValue(Type.REGEX, '.*NEO_HOME.*', '/opt/neo')
        shellRule.setReturnValue(Type.REGEX, '.*which java.*', 0)
        shellRule.setReturnValue(Type.REGEX, '.*which neo.*', 0)
    }

    private mockHomeVariablesNotSet() {
        shellRule.setReturnValue(Type.REGEX, '.*JAVA_HOME.*', '')
        shellRule.setReturnValue(Type.REGEX, '.*NEO_HOME.*', '')
        shellRule.setReturnValue(Type.REGEX, '.*which java.*', 0)
        shellRule.setReturnValue(Type.REGEX, '.*which neo.*', 0)
    }

    class CommandLineMatcher extends BaseMatcher {

        String prolog
        Set<String> args = (Set) []
        Set<MapEntry> opts = (Set) []

        String hint = ''

        CommandLineMatcher hasProlog(prolog) {
            this.prolog = prolog
            return this
        }

        CommandLineMatcher hasDoubleQuotedOption(String key, String value) {
            hasOption(key, "\"${value}\"")
            return this
        }

        CommandLineMatcher hasSingleQuotedOption(String key, String value) {
            hasOption(key, "\'${value}\'")
            return this
        }

        CommandLineMatcher hasOption(String key, String value) {
            this.opts.add(new MapEntry(key, value))
            return this
        }

        CommandLineMatcher hasArgument(String arg) {
            this.args.add(arg)
            return this
        }

        @Override
        boolean matches(Object o) {

            for (String cmd : o) {

                hint = ''
                boolean matches = true

                if (!cmd.matches(/${prolog}.*/)) {
                    hint = "A command line starting with \'${prolog}\'."
                    matches = false
                }

                for (MapEntry opt : opts) {
                    if (!cmd.matches(/.*[\s]*--${opt.key}[\s]*${opt.value}.*/)) {
                        hint = "A command line containing option \'${opt.key}\' with value \'${opt.value}\'"
                        matches = false
                    }
                }

                for (String arg : args) {
                    if (!cmd.matches(/.*[\s]*${arg}[\s]*.*/)) {
                        hint = "A command line having argument '${arg}'."
                        matches = false
                    }
                }

                if (matches)
                    return true
            }

            return false
        }

        @Override
        public void describeTo(Description description) {
            description.appendText(hint)
        }
    }
}
