// Jenkinsfile support utilities
import BuildConfig
import groovy.io.FileType
import groovy.json.JsonOutput
import org.apache.commons.lang3.SerializationUtils
import org.apache.commons.io.FilenameUtils

import org.kohsuke.github.GitHub


@NonCPS
// Post an issue to a particular Github repository.
//
// @param reponame - str
// @param username - str  username to use when authenticating to Github
// @param password - str  password for the associated username
// @param subject  - str  Subject/title text for the issue
// @param message  - str  Body text for the issue
def postGithubIssue(reponame, username, password, subject, message) {
    def github = GitHub.connectUsingPassword("${username}", "${password}")
    def repo = github.getRepository(reponame)
    // Determine if the 'testing' label exists in the repo. If it does,
    // apply it to the new issue.
    def labels = repo.listLabels()
    def labelnames = []
    for (label in labels) {
        labelnames.add(label.getName())
    }
    def labelname = 'testing'
    def ibuilder = repo.createIssue(subject)
    ibuilder.body(message)
    if (labelname in labelnames) {
        ibuilder.label(labelname)
    }
    ibuilder.create()
}


// Clone the source repository and examine the most recent commit message.
// If a '[ci skip]' or '[skip ci]' directive is present, immediately
// terminate the job with a success code.
// If no skip directive is found, or skip_disable is true, stash all the
// source files for efficient retrieval by subsequent nodes.
//
// @param args  Map containing entries for control of Setup stage.
//
// @return skip_job  int  Status of clone step, to be tested to determine
//                        need to abort from Jenkinsfile.
def scm_checkout(args = ['skip_disable':false]) {
    skip_job = 0
    node('master') {
        stage("Setup") {
            deleteDir()
            dir('clone') {
                //scmvars = checkout(scm)
                checkoutToSubdirectory('clone')
                
                println("args['skip_disable'] = ${args['skip_disable']}")
                if (args['skip_disable'] == false) {
                    // Obtain the last commit message and examine it for skip directives.
                    logoutput = sh(script:"git log -1 --pretty=%B", returnStdout: true).trim()
                    if (logoutput.contains("[ci skip]") || logoutput.contains("[skip ci]")) {
                        skip_job = 1
                        currentBuild.result = 'SUCCESS'
                        println("\nBuild skipped due to commit message directive.\n")
                        return skip_job
                    }
                }
                stash includes: '**/*', name: 'source_tree', useDefaultExcludes: false
            } //
        }
    }
    return skip_job
}


// Returns true if the conda exe is somewhere in the $PATH, false otherwise.
// @return  boolean
def condaPresent() {
    def success = sh(script: "conda --version", returnStatus: true)
    if (success == 0) {
        return true
    } else {
        return false
    }
}


// Install a particular version of conda by downloading and running the miniconda
// installer and then installing conda at the specified version.
//
// @param version      string holding version of conda to install
//                      A version argument of 'null' will result in the latest
//                      available conda version being installed.
// @param install_dir  directory relative to the current working directory
//                     where conda should be installed.
//
// @return boolean   true if conda could be downloaded and installed, false
//                   otherwise
def installConda(version, install_dir) {

    installer_ver = '4.5.12'
    default_conda_version = '4.5.12'
    default_dir = 'miniconda'

    if (version == null) {
        version = default_conda_version
    }
    if (install_dir == null) {
        install_dir = default_dir
    }

    def conda_base_url = "https://repo.continuum.io/miniconda"

    def OSname = null
    def uname = sh(script: "uname", returnStdout: true).trim()
    if (uname == "Darwin") {
        OSname = "MacOSX"
        println("OSname=${OSname}")
    }
    if (uname == "Linux") {
        OSname = uname
        println("OSname=${OSname}")
    }
    assert uname != null

    // Check for the availability of a download tool and then use it
    // to get the conda installer.
    def dl_cmds = ["curl -OSs",
                   "wget --no-verbose --server-response --no-check-certificate"]
    def dl_cmd = null
    def stat1 = 999
    for (cmd in dl_cmds) {
        stat1 = sh(script: "which ${cmd.tokenize()[0]}", returnStatus: true)
        if( stat1 == 0 ) {
            dl_cmd = cmd
            break
        }
    }
    if (stat1 != 0) {
        println("Could not find a download tool for obtaining conda. Unable to proceed.")
        return false
    }

    def cwd = pwd()
    def conda_exe = "${install_dir}/bin/conda"
    def conda_installer = "Miniconda3-${installer_ver}-${OSname}-x86_64.sh"
    dl_cmd = dl_cmd + " ${conda_base_url}/${conda_installer}"
    if (!fileExists("./${conda_installer}")) {
        sh dl_cmd
    }

    // Install miniconda
    sh "bash ./${conda_installer} -b -p ${install_dir}"

    // Override conda version if specified and different from default.
    def curr_ver = sh(script:"${conda_exe} --version", returnStdout: true)
    curr_ver = curr_ver.tokenize()[1].trim()
    if (curr_ver != version) {
        sh "${conda_exe} install conda=${version}"
    }

    return true
}


def parseTestReports(buildconfigs) {
    // Unstash all test reports produced by all possible agents.
    // Iterate over all unique files to compose the testing summary.
    def confname = ''
    def report_hdr = ''
    def short_hdr = ''
    def raw_totals = ''
    def totals = [:]
    def tinfo = new testInfo()
    tinfo.subject = "[AUTO] Regression testing summary"
    tinfo.message = "Regression Testing (RT) Summary:\n\n"
    for (config in buildconfigs) {
       println("Unstashing test report for: ${config.name}")
       try {
           unstash "${config.name}.results"
           results_hdr = sh(script:"grep 'testsuite errors' 'results.${config.name}.xml'",
                             returnStdout: true)
           short_hdr = results_hdr.findAll(/(?<=testsuite ).*/)[0]
           short_hdr = short_hdr.split('><testcase')[0]

           raw_totals = short_hdr.split()
           totals = [:]

           for (total in raw_totals) {
               expr = total.split('=')
               expr[1] = expr[1].replace('"', '')
               totals[expr[0]] = expr[1]
               try {
                   totals[expr[0]] = expr[1].toInteger()
               } catch(Exception NumberFormatException) {
                   continue
               }
           }

           // Check for errors or failures
           if (totals['errors'] != 0 || totals['failures'] != 0) {
               tinfo.problems = true
               tinfo.message = "${tinfo.message}Configuration: ${config.name}\n\n" +
                             "| Total tests |  ${totals['tests']} |\n" +
                             "|----|----|\n" +
                             "| Errors      | ${totals['errors']} |\n" +
                             "| Failures    | ${totals['failures']} |\n" +
                             "| Skipped     | ${totals['skips']} |\n\n"
           }
       } catch(Exception ex) {
           println("No results imported.")
       }

    } // end for(config in buildconfigs)
    return tinfo
}


// Accept a file name pattern and push all files directly in the workspace
// directory matching that spec to the artifactory repository provided.
def pushToArtifactory(file_spec, repo) {

    data_config = new DataConfig()
    data_config.server_id = 'bytesalad'

    def buildInfo = Artifactory.newBuildInfo()
    buildInfo.env.capture = true
    buildInfo.env.collect()
    def server = Artifactory.server data_config.server_id

upload_spec = """
{
  "files": [
    {
      "pattern": "${env.WORKSPACE}/${file_spec}",
      "target": "${repo}"
    }
  ]
}
"""

    data_config.insert('env_file', upload_spec)
    def bi_temp = server.upload spec: data_config.data['env_file']
    buildInfo.append bi_temp
    server.publishBuildInfo buildInfo
}


// Compose a testing summary message from the junit test report files
// collected from each build configuration execution and post this message
// as an issue on the the project's Github page.
//
// @param jobconfig     JobConfig object
def testSummaryNotify(jobconfig, buildconfigs, test_info) {

    //def test_info = parseTestReports(buildconfigs)

    // If there were any test errors or failures, send the summary to github.
    if (test_info.problems) {
        // Match digits between '/' chars at end of BUILD_URL (build number).
        def pattern = ~/\/\d+\/$/
        def report_url = env.BUILD_URL.replaceAll(pattern, '/test_results_analyzer/')
        test_info.message = "${test_info.message}Report: ${report_url}"
        test_info.subject = "[AUTO] Regression testing summary"

        def regpat = ~/https:\/\/github.com\//
        def reponame = scm.userRemoteConfigs[0].url.replaceAll(regpat, '')
        regpat = ~/\.git$/
        reponame = reponame.replaceAll(regpat, '')

        println("Test failures and/or errors occurred.\n" +
                "Posting summary to Github.\n" +
                "  ${reponame} Issue subject: ${test_info.subject}")
        if (jobconfig.all_posts_in_same_issue) {
            withCredentials([usernamePassword(credentialsId:'github_st-automaton-01',
                    usernameVariable: 'USERNAME',
                    passwordVariable: 'PASSWORD')]) {
                    // Locally bound vars here to keep Jenkins happy.
                    def username = USERNAME
                    def password = PASSWORD
                    postGithubIssue(reponame, username, password, test_info.subject, test_info.message)
            }
        } else {
            println("Posting all RT summaries in separate issues is not yet implemented.")
            // TODO: Determine if the reserved issue and/or comment text already exists.
            // If so, post message as a comment on that issue.
            // If not, post a new issue with message text.
        }
    }//endif(test_info.problems)
}


def publishCondaEnv(jobconfig, test_info) {

    if (jobconfig.enable_env_publication) {
        // Extract repo from standardized location
        def testconf = readFile("setup.cfg")
        def Properties prop = new Properties()
        prop.load(new StringReader(testconf))
        println("PROP->${prop.getProperty('results_root')}")
        pub_repo = prop.getProperty('results_root')

        if (jobconfig.publish_env_on_success_only) {
            if (!test_info.problems) {
                pushToArtifactory("conda_env_dump_*", pub_repo)
            }
        } else {
            pushToArtifactory("conda_env_dump_*", pub_repo)
        }
    }
}


// If a non-JUnit format .xml file exists in the
// root of the workspace, the XUnitBuilder report
// ingestion will fail.
//
// @param config      BuildConfig object
def processTestReport(config) {
    def config_name = config.name
    report_exists = sh(script: "find *.xml", returnStatus: true)
    def threshold_summary = "failedUnstableThresh: ${config.failedUnstableThresh}\n" +
        "failedFailureThresh: ${config.failedFailureThresh}\n" +
        "skippedUnstableThresh: ${config.skippedUnstableThresh}\n" +
        "skippedFailureThresh: ${config.skippedFailureThresh}"
    println(threshold_summary)

    // Process the XML results files to include the build config name as a prefix
    // on each test name to make it more obvious from where each result originates.
    if (report_exists == 0) {
        // get all .xml files in root
        repfiles = sh(script:"find \$(pwd) -name '*.xml' -maxdepth 1", returnStdout: true).split("\n")
        for (String repfile : repfiles) {
            // loop through files
            command = "cp '${repfile}' '${repfile}.modified'"
            sh(script:command)
        }
        sh(script:"sed -i 's/ name=\"/ name=\"[${config.name}] /g' *.xml.modified")
        step([$class: 'XUnitBuilder',
            thresholds: [
            [$class: 'SkippedThreshold', unstableThreshold: "${config.skippedUnstableThresh}"],
            [$class: 'SkippedThreshold', failureThreshold: "${config.skippedFailureThresh}"],
            [$class: 'FailedThreshold', unstableThreshold: "${config.failedUnstableThresh}"],
            [$class: 'FailedThreshold', failureThreshold: "${config.failedFailureThresh}"]],
            tools: [[$class: 'JUnitType', pattern: '*.xml.modified']]])
    } else {
        println("No .xml files found in workspace. Test report ingestion skipped.")
    }
    // TODO: Define results file name centrally and reference here.
    if (fileExists('results.xml')) {
        // Copy test report to a name unique to this build configuration.
        sh("cp 'results.xml' 'results.${config.name}.xml'")
        def stashname = "${config.name}.results"
        stash includes: "results.${config.name}.xml", name: stashname, useDefaultExcludes: false
    }
}


// Define actions executed in the 'Artifactory' stage.
// Collect artifacts and push them to the artifactory server.
//
// @param config      BuildConfig object
def stageArtifactory(config) {
    stage("Artifactory (${config.name})") {
        def buildInfo = Artifactory.newBuildInfo()

        buildInfo.env.capture = true
        buildInfo.env.collect()
        def server

        for (artifact in config.test_configs) {
            server = Artifactory.server artifact.server_id

            // Construct absolute path to data
            def path = FilenameUtils.getFullPath(
                        "${env.WORKSPACE}/${artifact.root}"
            )

            // Record listing of all files starting at ${path}
            // (Native Java and Groovy approaches will not
            // work here)
            sh(script: "find ${path} -type f",
               returnStdout: true).trim().tokenize('\n').each {

                // Semi-wildcard matching of JSON input files
                // ex:
                //      it = "test_1234_result.json"
                //      artifact.match_prefix = "(.*)_result"
                //
                //      pattern becomes: (.*)_result(.*)\\.json
                if (it.matches(
                        artifact.match_prefix + '(.*)\\.json')) {
                    def basename = FilenameUtils.getBaseName(it)
                    def data = readFile(it)

                    // Store JSON in a logical map
                    // i.e. ["basename": [data]]
                    artifact.insert(basename, data)
                }
            } // end find.each

            // Submit each request to the Artifactory server
            artifact.data.each { blob ->
                def bi_temp = server.upload spec: blob.value

                // Define retention scheme
                // Defaults: see DataConfig.groovy
                bi_temp.retention \
                    maxBuilds: artifact.keep_builds, \
                    maxDays: artifact.keep_days, \
                    deleteBuildArtifacts: !artifact.keep_data

                buildInfo.append bi_temp
            }

        } // end for-loop

        server.publishBuildInfo buildInfo

    } // end stage Artifactory
}


// Like the Setup stage, this runs on the master node and allows for
// aggregation and analysis of results produced in the build configurations
// processed prior.
//
// @param jobconfig   JobConfig object holding paramters that influence the
//                    behavior of the entire Jenkins job.
def stagePostBuild(jobconfig, buildconfigs) {
    node('master') {
        stage("Post-build") {
            for (config in buildconfigs) {
                try {
                    unstash "conda_env_dump_${config.name}"
                } catch(Exception ex) {
                    println("No conda env dump stash available for ${config.name}")
                }
            }
            def test_info = parseTestReports(buildconfigs)
            if (jobconfig.post_test_summary) {
                testSummaryNotify(jobconfig, buildconfigs, test_info)
            }
            publishCondaEnv(jobconfig, test_info)
            println("Post-build stage completed.")
        } //end stage
    } //end node
}


// Unstash the source tree stashed in the pre-build stage.
// In a shell envrionment defined by the variables held in the
// config.runtime list, run the build_cmds items in sequence
// Then do the same for any test_cmds items present.
// If any test_configs were defined, run the Artifactory
// interaction steps for those.
// Then, handle test report ingestion and stashing.
//
// @param config      BuildConfig object
def buildAndTest(config) {
    println("buildAndTest")
    withEnv(config.runtime) {
        stage("Build (${config.name})") {
            unstash "source_tree"
            for (cmd in config.build_cmds) {
                sh(script: cmd)
            }
        }
        if (config.test_cmds.size() > 0) {
            try {
                stage("Test (${config.name})") {
                    for (cmd in config.test_cmds) {
                        // Ignore status code from all commands in
                        // test_cmds so Jenkins will always make it
                        // to the post-build stage.
                        // This accommodates tools like pytest returning
                        // !0 codes when a test fails which would
                        // abort the job too early.
                        sh(script: "${cmd} || true")
                    }
                }
            }
            finally {
                // Perform Artifactory upload if required
                if (config.test_configs.size() > 0) {

                    stageArtifactory(config)

                } // end test_configs check

                processTestReport(config)

            } // end test test_cmd finally clause
        } // end if(config.test_cmds...)

        // If conda is present, dump the conda environment definition to a file.
        def conda_exe = ''
        def local_conda = "${env.WORKSPACE}/miniconda/bin/conda"

        system_conda_present = sh(script:"which conda", returnStatus:true)
        if (system_conda_present == 0) {
            conda_exe = sh(script:"which conda", returnStdout:true).trim()
        } else if (fileExists(local_conda)) {
            conda_exe = local_conda
        }
        if (conda_exe != '') {
            // 'def' _required_ here to prevent use of values from one build
            // config leaking into others.
            def dump_name = "conda_env_dump_${config.name}.txt"
            println("About to dump environment: ${dump_name}")
            sh(script: "${conda_exe} list --explicit > '${dump_name}'")

            dump_name = "conda_env_dump_${config.name}.yml"
            println("About to dump environment: ${dump_name}")
            sh(script: "${conda_exe} env export > '${dump_name}'")
            def remote_out = sh(script: "git remote -v | head -1", returnStdout: true).trim()
            def remote_repo = remote_out.tokenize()[1]
            commit = sh(script: "git rev-parse HEAD", returnStdout: true).trim()
            // Remove 'prefix' line as it isn't needed and complicates the
            // addition of the 'pip' section.
            sh(script: "sed -i '/prefix/d' '${dump_name}'")
            def pip_section = sh(script: "grep 'pip:' '${dump_name}'", returnStatus: true)
            if (pip_section != 0) {
                sh "echo '  - pip:' >> '${dump_name}'"
            }
            // Add git+https line in pip section to install the commit
            // used for the target project of this job.
            def extra_yml_1 = "    - ${remote_repo}@${commit}"
            sh "echo '${extra_yml_1}' >> '${dump_name}'"

            // Stash spec file for use on master node.
            stash includes: '**/conda_env_dump*',
                  name: "conda_env_dump_${config.name}",
                  useDefaultExcludes: false
        }

    } // end withEnv
}


// If conda packages were specified, create an environment containing
// them and then 'activate' it by setting key environment variables that
// influence conda's behavior. . If a specific python version is
// desired, it must be specified as a package, i.e. 'python=3.6'
// in the list config.conda_packages.
//
// @param config  BuildConfig object
// @param index   int - unique index of BuildConfig passed in as config.
//
// @return  Modified config
def processCondaPkgs(config, index) {
    def conda_exe = null
    def conda_inst_dir = null
    println("processCondaPkgs")
    if (config.conda_packages.size() > 0) {
        // Test for presence of conda. If not available, install it in
        // a prefix unique to this build configuration.
        if (!condaPresent()) {
            println('Conda not found. Installing.')
            conda_inst_dir = "${env.WORKSPACE}/miniconda"
            println("conda_inst_dir = ${conda_inst_dir}")
            installConda(config.conda_ver, conda_inst_dir)
            conda_exe = "${conda_inst_dir}/bin/conda"
            println("conda_exe = ${conda_exe}")
        } else {
            conda_exe = sh(script: "which conda", returnStdout: true).trim()
            println("Found conda exe at ${conda_exe}.")
        }
        def conda_root = conda_exe.replace("/bin/conda", "").trim()
        def env_name = "tmp_env${index}"
        def conda_prefix = "${conda_root}/envs/${env_name}".trim()
        def packages = ""
        for (pkg in config.conda_packages) {
            packages = "${packages} '${pkg}'"
        }
        // Override removes the implicit 'defaults' channel from the channels
        // to be used, The conda_channels list is then used verbatim (in
        // priority order) by conda.
        def override = ""
        if (config.conda_override_channels.toString() == 'true') {
            override = "--override-channels"
        }
        def chans = ""
        for (chan in config.conda_channels) {
            chans = "${chans} -c ${chan}"
        }
        sh(script: "${conda_exe} create -q -y -n ${env_name} ${override} ${chans} ${packages}")
        // Configure job to use this conda environment.
        config.env_vars.add(0, "CONDA_SHLVL=1")
        config.env_vars.add(0, "CONDA_PROMPT_MODIFIER=${env_name}")
        config.env_vars.add(0, "CONDA_EXE=${conda_exe}")
        config.env_vars.add(0, "CONDA_PREFIX=${conda_prefix}")
        config.env_vars.add(0, "CONDA_PYTHON_EXE=${conda_prefix}/bin/python")
        config.env_vars.add(0, "CONDA_DEFAULT_ENV=${env_name}")
        // Prepend the PATH var adjustment to the list that gets processed below.
        def conda_path = "PATH=${conda_prefix}/bin:${conda_root}/bin:$PATH"
        config.env_vars.add(0, conda_path)
    }
    return config
}


// Expand each environment variable in the config object's env_vars list
// using a shell invocation to perform the substitutions.
//
// @param config  BuildConfig object
//
// @return  Modified config
def expandEnvVars(config) {
    // Expand environment variable specifications by using the shell
    // to dereference any var references and then render the entire
    // value as a canonical path.
    for (var in config.env_vars) {
        // Process each var in an environment defined by all the prior vars.
        withEnv(config.runtime) {
            def varName = var.tokenize("=")[0].trim()
            def varValue = var.tokenize("=")[1].trim()
            // examine var value, if it contains var refs, expand them.
            def expansion = varValue
            if (varValue.contains("\$")) {
                expansion = sh(script: "echo \"${varValue}\"", returnStdout: true)
            }

            // Change values of '.' and './' to the node's WORKSPACE.
            // Replace a leading './' with the node's WORKSPACE.
            if (expansion == '.' || expansion == './') {
                expansion = env.WORKSPACE
            } else if(expansion.size() > 2 && expansion[0..1] == './') {
                expansion = "${env.WORKSPACE}/${expansion[2..-1]}"
            }

            // Replace all ':.' combinations with the node's WORKSPACE.
            expansion = expansion.replaceAll(':\\.', ":${env.WORKSPACE}")

            // Convert var value to canonical based on a WORKSPACE base directory.
            if (expansion.contains('..')) {
                expansion = new File(expansion).getCanonicalPath()
            }
            expansion = expansion.trim()
            config.runtime.add("${varName}=${expansion}")
        } // end withEnv
    }
    return config
}


// Test for GStrings (double quoted). These perform string interpolation
// immediately and may not do what the user intends to do when defining
// environment variables to use in the build. Disallow them here.
//
// @param config  BuildConfig object
def abortOnGstrings(config) {
    config.env_vars.each { evar ->
        if (evar.getClass() == org.codehaus.groovy.runtime.GStringImpl) {
            msg = "Immediate interpolation of variables in the 'env_vars'" +
                  " list is not supported and will probably not do what" +
                  " you expect. Please change the double quotes (\") to " +
                  "single quotes (') in each value of the 'env_vars' list."
            println(msg)
            error('Abort the build.')
        }
    }
}


// Run tasks defined for the build nodes in sequential fashion.
//
// @param tasks  Map containing groovy code to execute on build nodes.
def sequentialTasks(tasks) {
    // Run tasks sequentially. Any failure halts the sequence.
    def iter = 0
    for (task in tasks) {
        def localtask = [:]
        localtask[task.key] = task.value
        stage("Serial-${iter}") {
            parallel(localtask)
        }
        iter++
    }
}


// Execute build/test task(s) based on passed-in configuration(s).
// Each task is defined by a BuildConfig object.
// A list of such objects is iterated over to process all configurations.
//
// Optionally accept a jobConfig object as part of the incoming list.
//   Test for type of list object and parse attributes accordingly.
// @param configs     list of BuildConfig (and JobConfig) objects
// @param concurrent  boolean
//                      whether or not to run all build
//                      configurations in parallel. The default is
//                      true when no value is provided.
def run(configs, concurrent = true) {

    // Map to hold code block definitions provided in the loop below for
    // passing to the build nodes.
    def tasks = [:]

    // Create JobConfig with default values.
    def jobconfig = new JobConfig()

    def buildconfigs = []

    // Separate jobconfig from buildconfig(s).
    configs.eachWithIndex { config ->

        // Extract a JobConfig object if one is found.
        if (config.getClass() == JobConfig) {
            jobconfig = config // TODO: Try clone here to make a new instance
            return  // effectively a 'continue' from within a closure.
        } else {
            buildconfigs.add(config)
        }
    }

    // Loop over config objects passed in handling each accordingly.
    buildconfigs.eachWithIndex { config, index ->

        // Make any requested credentials available to all build configs
        // in this job via environment variables.
        if (jobconfig.credentials != null) {
            jobconfig.credentials.each { cred_id ->
              withCredentials([string(credentialsId: cred_id, variable: 'cred_id_val')]) {
                  config.env_vars.add("${cred_id}=${cred_id_val}".toString())
                }
            }
        }

        def BuildConfig myconfig = new BuildConfig() // MUST be inside eachWith loop.
        myconfig = SerializationUtils.clone(config)

        // Test for problematic string interpolations requested in
        // environment variable definitions.
        abortOnGstrings(config)

        // For containerized CI builds, code defined within 'tasks' is eventually executed
        // on a separate node. Parallel builds on the RT system each get assigned a new
        // workspace directory by Jenkins. i.e. workspace, workspace@2, etc.
        // 'tasks' is a java.util.LinkedHashMap, which preserves insertion order.
        tasks["${myconfig.nodetype}/${myconfig.name}"] = {
            node(myconfig.nodetype) {
                deleteDir()
                myconfig = processCondaPkgs(myconfig, index)
                myconfig = expandEnvVars(myconfig)
                for (var in myconfig.env_vars_raw) {
                    myconfig.runtime.add(var)
                }
                buildAndTest(myconfig)
            } // end node
        }

    } //end closure configs.eachWithIndex

    if (concurrent == true) {
        stage("Matrix") {
            parallel(tasks)
        }
    } else {
        sequentialTasks(tasks)
    }

    stagePostBuild(jobconfig, buildconfigs)
}


// Condense version triplet and replace version specifier(s) with human-readable text
//
// @param    String s        string containing version specifiers
// @return   String          string with converted version specifiers
String convert_specifiers(String s) {
    String result = s
    result = result.replaceAll("\\.", "")  // No period
                   .replaceAll(",", "")    // No comma
                   .replaceAll("<", "L")   // Less than
                   .replaceAll(">", "G")   // Greater than
                   .replaceAll("~=", "C")  // Compatible (GE x.y && L x.*)
                   .replaceAll("=", "E")   // Equal to (=, E | ==, EE)
                   .replaceAll("\\!", "N") // Not equal to

    return result
}


// Convenience function that performs a deep copy on the supplied object.
//
// @param obj  Java/Groovy object to copy
//
// @return  Deep copy of obj .
def copy(obj) {
    return SerializationUtils.clone(obj)
}
