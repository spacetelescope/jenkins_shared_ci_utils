// src/JobConfig.groovy
//package JobConfig;

class JobConfig implements Serializable {

    // Regression testing summary control
    def post_test_summary = false
    def all_posts_in_same_issue = true

    // Build retention control
    def builds_to_keep = -1

    // Development
    def debug = false

    // Constructors
    JobConfig() {}
}
