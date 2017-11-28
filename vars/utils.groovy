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

class TClass implements Serializable {
  def var = "varvalue"
}

//@AutoClone  // annotation is not CPS-compatible?
class BuildConfig implements Serializable {
    def nodetype = ""
    def build_mode = ""
    def build_args = []
    def env_vars = []
    def configure_cmds = []
    def build_cmds = []
    def run_tests = true
    //Boolean boolValue

    // Constructors
    BuildConfig() {
        nodetype = ""
    }
    BuildConfig(nodetype) {
        this.nodetype = nodetype
    }
    // createNewInstance method requires Jenkins script approval for the
    // following signatures:
    // method groovy.lang.MetaBeanProperty getSetter
    // method groovy.lang.MetaObjectProtocol getProperties
    // method groovy.lang.MetaProperty getProperty java.lang.Object
    // method groovy.lang.MetaProperty setProperty java.lang.Object java.lang.Object
    def BuildConfig copy(){
        BuildConfig.metaClass.getProperties().findAll(){it.getSetter()!=null}.inject(new BuildConfig()){buildconfig,metaProp->
            metaProp.setProperty(buildconfig,metaProp.getProperty(this))
            buildconfig
        }
    }
}

def concurrent2(configs) {
    def tasks = [:]
    println("Size of configs = ${configs.size()}")
    for (config in configs) {
        t = new TClass()
        println("concurrent2: build.nodetype = ${config.nodetype}")
        println("concurrent2: build.build_mode= ${config.build_mode}")
        println("concurrent2: build.build_args= ${config.build_args}")
        println("concurrent2: build.env_vars= ${config.env_vars}")
        def run_tests = config.run_tests
        tasks["${config.nodetype}/${config.build_mode}"] = {
            node(config.nodetype) {
                //withEnv(config.env_vars) {
                    println("task: build.nodetype = ${config.nodetype}")
                    println("task: build.build_mode= ${config.build_mode}")
                    println("task: build.build_args= ${config.build_args}")
                    println("task: build.env_vars= ${config.env_vars}")
                    println("task: config.run_tests = ${config.run_tests}")
                    println("task: run_tests = ${run_tests}")
                    def prefix = pwd() + "/_install"
                    stage("Build (${config.build_mode})") {
                        unstash "source_tree"
                        sh(script: "ls -al")
                    } //end stage
                    stage("Test (${config.build_mode})") {
                        //if (config.run_tests) {
                        if (run_tests) {
                            println("RUNNING TESTS")
                        }
                    }
                //} //end withEnv
            } // end node
        }
    } //end for
    stage("Matrix") {
        parallel(tasks)
    }
} //end concurrent2

// Allow deep copying of a config object to another instance.
def copy(config) {
    return config.createNewInstance()
}
