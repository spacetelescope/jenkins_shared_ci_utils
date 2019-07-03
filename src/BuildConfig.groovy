// src/BuildConfig.groovy

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

    // Scheduling - default behavior is to not restrict run schedule based on
    // the day of the week.
    def run_on_days = ['sun', 'mon', 'tue', 'wed', 'thu', 'fri', 'sat']

    // Private. Not to be used directly by Jenkinsfile.
    def runtime = []

    // Constructors
    BuildConfig() {
        this.nodetype = ""
    }
}


class testInfo implements Serializable {
    def problems = false
    def subject = ""
    def message = ""
}
