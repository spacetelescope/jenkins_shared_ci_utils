// src/BuildConfig.groovy
package BuildConfig;

class BuildConfig implements Serializable {
    def nodetype = ""
    def build_mode = ""
    def env_vars = []
    def build_cmds = []
    def test_cmds = []

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
}
