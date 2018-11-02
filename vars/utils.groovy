// Jenkinsfile support utilities
import BuildConfig.BuildConfig
import groovy.io.FileType
import groovy.json.JsonOutput
import org.apache.commons.lang3.SerializationUtils
import org.apache.commons.io.FilenameUtils


// Clone the source repository and examine the most recent commit message.
// If a '[ci skip]' or '[skip ci]' directive is present, immediately
// terminate the job with a success code.
// If no skip directive is found, or skip_disable is true, stash all the
// source files for efficient retrieval by subsequent nodes.
//def scm_checkout(skip_disable=false) {
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
def conda_present() {
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
def install_conda(version, install_dir) {

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

// Execute build/test task(s) based on passed-in configuration(s).
// Each task is defined by a BuildConfig object.
// A list of such objects is iterated over to process all configurations.
//   Arguments:
//             configs    - list of BuildConfig objects
// (optional)  concurrent - boolean, whether or not to run all build
//                          configurations in parallel. The default is
//                          true when no value is provided.
def run(configs, concurrent = true) {

    def tasks = [:]
    configs.eachWithIndex { config, index ->
        def BuildConfig myconfig = new BuildConfig() // MUST be inside eachWith loop.
        myconfig = SerializationUtils.clone(config)
        def config_name = ""
        config_name = config.name

        println("config_name: ${config_name}")

        // Test for GStrings (double quoted). These perform string interpolation
        // immediately and may not what the user intends to do when defining
        // environment variables to use in the build. Disallow them here.
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

        def conda_exe = null
        def conda_inst_dir = null

        // For containerized CI builds, code defined within 'tasks' is eventually executed
        // on a separate node. Parallel builds on the RT system each get assigned a new
        // workspace directory by Jenkins. i.e. workspace, workspace@2, etc.
        // 'tasks' is a java.util.LinkedHashMap, which preserves insertion order.
        tasks["${myconfig.nodetype}/${config_name}"] = {
            node(myconfig.nodetype) {
                deleteDir()
                def runtime = []
                // If conda packages were specified, create an environment containing
                // them and then 'activate' it. If a specific python version is
                // desired, it must be specified as a package, i.e. 'python=3.6'
                // in the list config.conda_packages.
                if (myconfig.conda_packages.size() > 0) {
                    // Test for presence of conda. If not available, install it in
                    // a prefix unique to this build configuration.
                    if (!conda_present()) {
                        println('Conda not found. Installing.')
                        conda_inst_dir = "${env.WORKSPACE}/miniconda-bconf${index}"
                        println("conda_inst_dir = ${conda_inst_dir}")
                        install_conda(myconfig.conda_ver, conda_inst_dir)
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
                    for (pkg in myconfig.conda_packages) {
                        packages = "${packages} '${pkg}'"
                    }
                    // Override removes the implicit 'defaults' channel from the channels
                    // to be used, The conda_channels list is then used verbatim (in
                    // priority order) by conda.
                    def override = ""
                    if (myconfig.conda_override_channels.toString() == 'true') {
                        override = "--override-channels"
                    }
                    def chans = ""
                    for (chan in myconfig.conda_channels) {
                        chans = "${chans} -c ${chan}"
                    }
                    sh(script: "${conda_exe} create -q -y -n ${env_name} ${override} ${chans} ${packages}")
                    // Configure job to use this conda environment.
                    myconfig.env_vars.add(0, "CONDA_SHLVL=1")
                    myconfig.env_vars.add(0, "CONDA_PROMPT_MODIFIER=${env_name}")
                    myconfig.env_vars.add(0, "CONDA_EXE=${conda_exe}")
                    myconfig.env_vars.add(0, "CONDA_PREFIX=${conda_prefix}")
                    myconfig.env_vars.add(0, "CONDA_PYTHON_EXE=${conda_prefix}/bin/python")
                    myconfig.env_vars.add(0, "CONDA_DEFAULT_ENV=${env_name}")
                    // Prepend the PATH var adjustment to the list that gets processed below.
                    def conda_path = "PATH=${conda_prefix}/bin:$PATH"
                    myconfig.env_vars.add(0, conda_path)
                }
                // Expand environment variable specifications by using the shell
                // to dereference any var references and then render the entire
                // value as a canonical path.
                for (var in myconfig.env_vars) {
                    // Process each var in an environment defined by all the prior vars.
                    withEnv(runtime) {
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
                        runtime.add("${varName}=${expansion}")
                    } // end withEnv
                }
                for (var in myconfig.env_vars_raw) {
                    runtime.add(var)
                }
                withEnv(runtime) {
                    stage("Build (${myconfig.name})") {
                        println('About to unstash')
                        sh "pwd"
                        sh "ls -al"
                        unstash "source_tree"
                        println('Unstash complete')
                        for (cmd in myconfig.build_cmds) {
                            sh(script: cmd)
                        }
                    }
                    if (myconfig.test_cmds.size() > 0) {
                        try {
                            stage("Test (${myconfig.name})") {
                                for (cmd in myconfig.test_cmds) {
                                    sh(script: cmd)
                                }
                            }
                        }
                        finally {
                            // Perform Artifactory upload if required
                            if (myconfig.test_configs.size() > 0) {
                                stage("Artifactory (${myconfig.name})") {
                                    def buildInfo = Artifactory.newBuildInfo()

                                    buildInfo.env.capture = true
                                    buildInfo.env.collect()
                                    def server

                                    for (artifact in myconfig.test_configs) {
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
                            } // end test_configs check

                            // If a non-JUnit format .xml file exists in the
                            // root of the workspace, the XUnitBuilder report
                            // ingestion will fail.
                            report_exists = sh(script: "test -e *.xml", returnStatus: true)
                            if (report_exists == 0) {
                                step([$class: 'XUnitBuilder',
                                    thresholds: [
                                    [$class: 'SkippedThreshold', unstableThreshold: "${myconfig.skippedUnstableThresh}"],
                                    [$class: 'SkippedThreshold', failureThreshold: "${myconfig.skippedFailureThresh}"],
                                    [$class: 'FailedThreshold', unstableThreshold: "${myconfig.failedUnstableThresh}"],
                                    [$class: 'FailedThreshold', failureThreshold: "${myconfig.failedFailureThresh}"]],
                                    tools: [[$class: 'JUnitType', pattern: '*.xml']]])
                            } else {
                                println("No .xml files found in workspace. Test report ingestion skipped.")
                            }
                        } // end test test_cmd finally clause
                    } // end stage test_cmd
                } // end withEnv
            } // end node
        } //end tasks

    } //end for

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
}


// Convenience function that performs a deep copy on the supplied object.
def copy(obj) {
    return SerializationUtils.clone(obj)
}
