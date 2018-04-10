// Jenkinsfile support utilities
import BuildConfig.BuildConfig
import org.apache.commons.lang3.SerializationUtils

// Clone the source repository and examine the most recent commit message.
// If a '[ci skip]' or '[skip ci]' directive is present, immediately
// terminate the job with a success code.
// If no skip directive is found, stash all the source files for efficient retrieval
// by subsequent nodes.
def scm_checkout() {
    skip_job = 0
    node("on-master") {
        stage("Setup") {
            checkout(scm)
            // Obtain the last commit message and examine it for skip directives.
            logoutput = sh(script:"git log -1 --pretty=%B", returnStdout: true).trim()
            if (logoutput.contains("[ci skip]") || logoutput.contains("[skip ci]")) {
                skip_job = 1
                currentBuild.result = 'SUCCESS'
                println("\nBuild skipped due to commit message directive.\n")
                return skip_job
            }
            stash includes: '**/*', name: 'source_tree', useDefaultExcludes: false
        }
    }
    return skip_job
}


// Execute build/test task(s) based on passed-in configuration(s).
// Each task is defined by a BuildConfig object.
// A list of such objects is iterated over to process all configurations.
//   Arguments:
//             configs    - list of BuildConfig objects
// (optional)  concurrent - boolean, whether or not to run all build
//                          configurations in parallel. The default is
//                          true when no value is provided.
def run(configs, concurrent = true, debug = false) {
    def tasks = [:]
    for (config in configs) {
        def myconfig = new BuildConfig() // MUST be inside for loop.
        myconfig = SerializationUtils.clone(config)

        // Code defined within 'tasks' is eventually executed on a separate node.
        // 'tasks' is a java.util.LinkedHashMap, which preserves insertion order.
        tasks["${config.nodetype}/${config.build_mode}"] = {
            node(config.nodetype) {
                def runtime = []
                // Expand environment variable specifications by using the shell
                // to dereference any var references and then render the entire
                // value as a canonical path.
                for (var in myconfig.env_vars) {
                    def varName = var.tokenize("=")[0]
                    def varValue = var.tokenize("=")[1]
                    // examine var value, if it contains var refs, expand them.
                    def expansion = varValue
                    if (varValue.contains("\$")) {
                        expansion = sh(script: "echo ${varValue}", returnStdout: true)
                    }
                    // For each segment of the var value, if the value is a path AND
                    // the first char is '.', replace with env.WORKSPACE.

                    // Convert var value to canonical based on a WORKSPACE base directory.
                    canonicalVarValue = new File(env.WORKSPACE, expansion).getCanonicalPath().trim()
                    runtime.add("${varName}=${canonicalVarValue}")
                    //if (debug) {
                        println("varName: ${varName}")
                        println("varValue: ${varValue}")
                        println("expansion: ${expansion}")
                        println("canonicalVarValue: ${canonicalVarValue}")
                    //}
                }
                for (var in myconfig.env_vars_raw) {
                    runtime.add(var)
                }
                withEnv(runtime) {
                    stage("Build (${myconfig.build_mode})") {
                        unstash "source_tree"
                        for (cmd in myconfig.build_cmds) {
                            sh(script: cmd)
                        }
                    }
                    if (myconfig.test_cmds.size() > 0) {
                        try {
                            stage("Test (${myconfig.build_mode})") {
                                for (cmd in myconfig.test_cmds) {
                                    sh(script: cmd)
                                }
                            }
                        }
                        finally {
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
                        }
                    }
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
        tasks.each{ key, value ->
            def localtask = [:]
            localtask[key] = tasks[key]
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
