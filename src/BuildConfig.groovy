// src/BuildConfig.groovy
package BuildConfig;

//@AutoClone  // annotation is not CPS-compatible?
class BuildConfig implements Serializable {
    def nodetype = ""
    def build_mode = ""
    def env_vars = []
    def build_cmds = []
    def test_cmds = []
    def run_tests = true
    def env_vars_map = [:]

    def failedFailureNewThresh = ''
    def failedFailureThresh = ''
    def failedUnstableNewThresh = ''
    def failedUnstableThresh= ''

    def skippedFailureNewThresh = ''
    def skippedFailureThresh = ''
    def skippedUnstableNewThresh = ''
    def skippedUnstableThresh= ''

    // Constructors
    BuildConfig() {
        this.nodetype = ""
    }

    //BuildConfig(nodetype) {
    //    this.nodetype = nodetype
    //}

    // copy method requires Jenkins script approval for the
    // following signatures:
    // method groovy.lang.MetaBeanProperty getSetter
    // method groovy.lang.MetaObjectProtocol getProperties
    // method groovy.lang.MetaProperty getProperty java.lang.Object
    // method groovy.lang.MetaProperty setProperty java.lang.Object java.lang.Object
    //def BuildConfig copy() {
    //    BuildConfig.metaClass.getProperties().findAll(){it.getSetter()!=null}.inject(new BuildConfig()){
    //        buildconfig,metaProp->metaProp.setProperty(buildconfig,metaProp.getProperty(this))
    //        buildconfig
    //    }
    //}
}
