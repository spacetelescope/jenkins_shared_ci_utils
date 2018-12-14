// Jenkinsfile support utilities
import BuildConfig.BuildConfig
import JobConfig
import groovy.io.FileType
import groovy.json.JsonOutput
import org.apache.commons.lang3.SerializationUtils
import org.apache.commons.io.FilenameUtils

@Grab(group='org.kohsuke', module='github-api', version='1.93')
import org.kohsuke.github.GitHub


@NonCPS
def postGithubIssue(reponame, username, password, subject, message) {
    def github = GitHub.connectUsingPassword("${username}", "${password}")
    def repo = github.getRepository(reponame)
    def ibuilder = repo.createIssue(subject)
    ibuilder.body(message)
    ibuilder.create()
}


// Clone the source repository and examine the most recent commit message.
// If a '[ci skip]' or '[skip ci]' directive is present, immediately
// terminate the job with a success code.
// If no skip directive is found, or skip_disable is true, stash all the
// source files for efficient retrieval by subsequent nodes.
def scm_checkout(args = ['skip_disable':false]) {
    skip_job = 0
    node("on-master") {
        stage("Setup") {

            checkout(scm)
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
        }
    }
    return skip_job
}


// Returns true if the conda exe is somewhere in the $PATH, false otherwise.
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
//  A version argument of 'null' will result in the latest available conda
//  version being installed.
def installConda(version, install_dir) {

    installer_ver = '4.5.4'
    default_conda_version = '4.5.4'
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
    def conda_install_dir = "${cwd}/${install_dir}"
    def conda_installer = "Miniconda3-${installer_ver}-${OSname}-x86_64.sh"
    dl_cmd = dl_cmd + " ${conda_base_url}/${conda_installer}"
    if (!fileExists("./${conda_installer}")) {
        sh dl_cmd
    }

    // Install miniconda
    sh "bash ./${conda_installer} -b -p ${install_dir}"
    return true
}


//
// Testing summary notifier
//
def testSummaryNotify(single_issue) {
    // Unstash all test reports produced by all possible agents.
    // Iterate over all unique files to compose the testing summary.
    def confname = ''
    def report_hdr = ''
    def short_hdr = ''
    def raw_totals = ''
    def totals = [:]
    def message = "Regression Testing (RT) Summary:\n\n"
    def subject = ''
    def send_notification = false
    def stashcount = 0
    println("Retrieving stashed test report files...")
    while(true) {
       try {
           unstash "${stashcount}.name"
           unstash "${stashcount}.report" 
       } catch(Exception) {
           println("All test report stashes retrieved.")
           break
       }
       confname = readFile "${stashcount}.name"
       println("confname: ${confname}")
       
       report_hdr = sh(script:"grep 'testsuite errors' *.xml", 
                         returnStdout: true)
       short_hdr = report_hdr.findAll(/(?<=testsuite ).*/)[0]
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
           send_notification = true
           message = "${message}Configuration: ${confname}\n\n" +
                         "| Total tests |  ${totals['tests']} |\n" +
                         "|----|----|\n" +
                         "| Errors      | ${totals['errors']} |\n" +
                         "| Failures    | ${totals['failures']} |\n" +
                         "| Skipped     | ${totals['skips']} |\n\n"
       }
       stashcount++

    } //end while(true) over stashes//

    // If there were any test errors or failures, send the summary to github.
    if (send_notification) {
        // Match digits between '/' chars at end of BUILD_URL (build number).
        def pattern = ~/\/\d+\/$/
        def report_url = env.BUILD_URL.replaceAll(pattern, '/test_results_analyzer/')
        message = "${message}Report: ${report_url}"
        subject = "[AUTO] Regression testing summary"

        def regpat = ~/https:\/\/github.com\//
        def reponame = scm.userRemoteConfigs[0].url.replaceAll(regpat, '')
        regpat = ~/\.git$/
        reponame = reponame.replaceAll(regpat, '')

        println("Test failures and/or errors occurred.\n" +
                "Posting summary to Github.\n" +
                "  ${reponame} Issue subject: ${subject}")
        if (single_issue) {
            withCredentials([usernamePassword(credentialsId:'github_st-automaton-01',
                    usernameVariable: 'USERNAME',
                    passwordVariable: 'PASSWORD')]) {
                    // Locally bound vars here to keep Jenkins happy.
                    def username = USERNAME
                    def password = PASSWORD
                    postGithubIssue(reponame, username, password, subject, message)
            }
        } else {
            println("Posting all RT summaries in separate issues is not yet implemented.")
            // TODO: Determine if the reserved issue and/or comment text already exists.
            // If so, post message as a comment on that issue.
            // If not, post a new issue with message text.
        }
    } //endif (send_notification)
}


// If a non-JUnit format .xml file exists in the
// root of the workspace, the XUnitBuilder report
// ingestion will fail.
def processTestReport(config, index) {
    def config_name = config.name
    report_exists = sh(script: "test -e *.xml", returnStatus: true)
    def thresh_summary = "failedUnstableThresh: ${config.failedUnstableThresh}\n" +
        "failedFailureThresh: ${config.failedFailureThresh}\n" +
        "skippedUnstableThresh: ${config.skippedUnstableThresh}\n" +
        "skippedFailureThresh: ${config.skippedFailureThresh}"
    println(thresh_summary)
    if (report_exists == 0) {
        step([$class: 'XUnitBuilder',
            thresholds: [
            [$class: 'SkippedThreshold', unstableThreshold: "${config.skippedUnstableThresh}"],
            [$class: 'SkippedThreshold', failureThreshold: "${config.skippedFailureThresh}"],
            [$class: 'FailedThreshold', unstableThreshold: "${config.failedUnstableThresh}"],
            [$class: 'FailedThreshold', failureThreshold: "${config.failedFailureThresh}"]],
            tools: [[$class: 'JUnitType', pattern: '*.xml']]])
    
    } else {
        println("No .xml files found in workspace. Test report ingestion skipped.")
    }
    writeFile file: "${index}.name", text: config_name, encoding: "UTF-8"
    def stashname = "${index}.name"
    // TODO: Define results file name centrally and reference here.
    if (fileExists('results.xml')) {
        stash includes: '*.name', name: stashname, useDefaultExcludes: false
        stashname = "${index}.report"
        stash includes: '*.xml', name: stashname, useDefaultExcludes: false
    }
}


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
def stagePostbuild() {
    node("on-master") {
        stage("Post-build") {
            if (ljobconfig.post_test_summary) {
                testSummaryNotify(ljobconfig.all_posts_in_same_issue)
            }
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
// @param config_idx  int - unique index of BuildConfig passed in as config.
def buildAndTest(config, config_idx) {
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

                processTestReport(config, config_idx)

            } // end test test_cmd finally clause
        } // end stage test_cmd
    } // end withEnv
}


// If conda packages were specified, create an environment containing
// them and then 'activate' it by setting key environment variables that
// influence conda's behavior. . If a specific python version is
// desired, it must be specified as a package, i.e. 'python=3.6'
// in the list config.conda_packages.
//
// @param config  BuildConfig object
//
// @return config
def processCondaPkgs(config, config_idx) {
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
        def env_name = "tmp_env${config_idx}"
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
        def conda_path = "PATH=${conda_prefix}/bin:$PATH"
        config.env_vars.add(0, conda_path)
    }
    return config
}


// Expand each environment variable in the config object's env_vars list
// using a shell invocation to perform the substitutions.
//
// @param config  BuildConfig object
//
// @return config
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
            println("Adding var to runtime ${varName}=${expansion}")
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
        println(evar)
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

    // Create JobConfig with default values.
    def ljobconfig = new JobConfig()

    // Loop over config objects passed in handling each accordingly.
    def tasks = [:]
    configs.eachWithIndex { config, index ->

        // Extract a JobConfig object if one is found.
        if (config.getClass() == JobConfig) {
            ljobconfig = config // TODO: Try clone here to make a new instance
            return  // effectively a 'continue' from within a closure.
        }

        def BuildConfig myconfig = new BuildConfig() // MUST be inside eachWith loop.
        myconfig = SerializationUtils.clone(config)
        def config_name = ""
        config_name = config.name

        abortOnGstrings(config)

        // For containerized CI builds, code defined within 'tasks' is eventually executed
        // on a separate node. Parallel builds on the RT system each get assigned a new
        // workspace directory by Jenkins. i.e. workspace, workspace@2, etc.
        // 'tasks' is a java.util.LinkedHashMap, which preserves insertion order.
        tasks["${myconfig.nodetype}/${config_name}"] = {
            node(myconfig.nodetype) {
                deleteDir()

                myconfig = processCondaPkgs(myconfig, index)

                myconfig = expandEnvVars(myconfig)

                for (var in myconfig.env_vars_raw) {
                    //runtime.add(var)
                    myconfig.runtime.add(var)
                }

                buildAndTest(myconfig, index)

            } // end node
        } //end tasks

    } //end closure configs.eachWithIndex 

    if (concurrent == true) {
        stage("Matrix") {
            parallel(tasks)
        }
    } else {
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

    stagePostBuild()
    //node("on-master") {
    //    stage("Post-build") {
    //        if (ljobconfig.post_test_summary) {
    //            testSummaryNotify(ljobconfig.all_posts_in_same_issue)
    //        }
    //        println("Post-build stage completed.")
    //    } //end stage
    //} //end node
}


// Convenience function that performs a deep copy on the supplied object.
def copy(obj) {
    return SerializationUtils.clone(obj)
}
