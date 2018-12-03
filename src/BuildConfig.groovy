// src/BuildConfig.groovy
package BuildConfig;

class BuildConfig implements Serializable {
    def nodetype = ""
    def name = ""

    def conda_packages = []
    def conda_override_channels = false
    def conda_channels = []
    def conda_ver = null

    def env_vars = []
    def env_vars_raw = []
    def build_cmds = []
    def test_cmds = []
    def test_configs = []

    def failedFailureNewThresh = ''
    def failedFailureThresh = ''
    def failedUnstableNewThresh = ''
    def failedUnstableThresh= '0'

    def skippedFailureNewThresh = ''
    def skippedFailureThresh = ''
    def skippedUnstableNewThresh = ''
    def skippedUnstableThresh= ''

    // Constructors
    BuildConfig() {
        this.nodetype = ""
    }
}
