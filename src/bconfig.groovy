// src/bconfig.groovy
package bconfig; 

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

class bconfig implements Serializable {
    def name = "My name"
}

