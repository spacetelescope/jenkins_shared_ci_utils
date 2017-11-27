// Jenkinsfile utilities

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
                // System.exit(0) // WARNING: FATAL to Jenkins
                return skip_job
                //throw new hudson.AbortException('Guess what!')
                //throw new java.io.IOException('Guess what!')
            }
            sh(script: "ls -al")
            stash includes: '**/*', name: 'source_tree'
        }
    }
    return skip_job
}


def concurrent2(configs) {
    def tasks = [:]
    for (config in configs) {
        println("concurrent2: build.nodetype = ${config.nodetype}")
        tasks["${config.nodetype}/${config.build_mode}"] = {
            node(config.nodetype) {
                withEnv(config.env_vars) {
                    def prefix = pwd() + "/_install"
                    stage("Build (${config.build_mode})") {
                        unstash "source_tree"
                        sh "ls -al"
                    } //end stage
                } //end withEnv
            } // end node
        }
    } //end for
} //end concurrent2
