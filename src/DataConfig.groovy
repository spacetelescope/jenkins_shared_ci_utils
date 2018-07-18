package DataConfig;
import groovy.json.JsonOutput
import org.apache.commons.io.FileUtils

class DataConfig implements Serializable {
    String root = '.'
    String server_id = ''
    String match_prefix = '(.*)'
    Boolean keep_data = false
    int keep_builds = 20
    int keep_days = 10
    def data = [:]

    DataConfig() {}

    def insert(String name, String block) {
        /* Store JSON directly as string */
        this.data[name] = block
    }

    def insert(String name, block=[:]) {
        /* Convert a Groovy Map to JSON and store it */
        this.data[name] = JsonOutput.toJson(block)
    }
}
