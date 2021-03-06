// src/JobConfig.groovy

class JobConfig implements Serializable {

    // Regression testing summary control
    def post_test_summary = false
    def all_posts_in_same_issue = true

    // Conda environment specification file publication control
    def enable_env_publication = false
    def publish_env_on_success_only = true
    // Filter format: "github_user_or_org_name/branch"
    def publish_env_filter = ""

    def credentials = null

    // Build retention control
    def builds_to_keep = -1

    // Development
    def debug = false

    // Constructors
    JobConfig() {}
}
