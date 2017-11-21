// Jenkinsfile utilities

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
            stash includes: '**/*', name: 'source_tree'
        }
    }
    return skip_job
}

//if (skip_job == 1) {
//    currentBuild.result = 'SUCCESS'
//    println("\nBuild skipped due to commit message directive.\n")
//    return
//}


def concurrent2(mylist) {
    for (build in mylist) {
        println("concurrent2: build.nodetype = ${build.nodetype}")
    }
}
