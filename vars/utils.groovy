// Jenkinsfile utilities
import BuildConfig.BuildConfig

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
            sh(script: "ls -al")
            stash includes: '**/*', name: 'source_tree'
        }
    }
    return skip_job
}


// Execute build/test tasks in parallel
// Each task is defined by a BuildConfig object.
// A list of such objects is iterated over to process all configurations.
def concurrent(configs) {
    def tasks = [:]
    println("Size of configs = ${configs.size()}")
    for (config in configs) {
        def myconfig = new BuildConfig() // MUST be inside for loop.
        myconfig = config.copy()

        // Code defined within 'tasks' is eventually executed on a separate node.
        tasks["${config.nodetype}/${config.build_mode}"] = {
            node(config.nodetype) {
                println("**** WORKSPACE = ${env.WORKSPACE}")
                // FIXME: Generalize env vars.
                for (var in myconfig.env_vars) {
                    if (var.contains("PATH")) {
                        cwd = pwd()
                        tvar = var.replace("PATH=.", cwd)
                        env.PATH = "${tvar}:${env.PATH}"
                    }
                }
                //env.PATH = "${env.WORKSPACE}/_install/bin:${env.PATH}"
                //withEnv(myconfig.env_vars) {
                //withEnv(vars) {
                    println("task: env.PATH = ${env.PATH}")
                    println("task: myconfig.nodetype = ${myconfig.nodetype}")
                    println("task: myconfig.build_mode = ${myconfig.build_mode}")
                    println("task: myconfig.env_vars = ${myconfig.env_vars}")
                    println("task: myconfig.build_cmds = ${myconfig.build_cmds}")
                    println("task: myconfig.test_cmds = ${myconfig.test_cmds}")
                    println("task: myconfig.run_tests = ${myconfig.run_tests}")
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
                            // TODO: Test for presence of report file.
                            //step([$class: 'XUnitBuilder',
                            //    thresholds: [
                            //    [$class: 'SkippedThreshold', failureThreshold: '0'],
                            //    [$class: 'FailedThreshold', unstableThreshold: '1'],
                            //    [$class: 'FailedThreshold', failureThreshold: '6']],
                            //    tools: [[$class: 'JUnitType', pattern: '*.xml']]])
                            step([$class: 'XUnitBuilder',
                                thresholds: [myconfig.xunit_map],
                                tools: [[$class: 'JUnitType', pattern: '*.xml']]])
                        }
                    }
                //} //end withEnv
            } // end node
        } //end tasks

    } //end for
    stage("Matrix") {
        parallel(tasks)
    }
} //end concurrent
