import groovy.json.JsonOutput
import org.apache.commons.io.FileUtils

class DataConfig implements Serializable {
    String root = '.'
    String server_id = ''
    String match_prefix = '(.*)'
    String direction
    Boolean managed = true
    Boolean keep_data = false
    int keep_builds = 20
    int keep_days = 10
    def data = [:]

    DataConfig(String direction = "upload") {
        this.direction = direction.toLowerCase()
        //if (!this.isUpload() && !this.isDownload()) {
        //    throw new Exception("DataConfig.direction argument must be 'upload'"
        //                        + "or 'download' (got: ${this.direction})")
        //}
    }

    def isUpload() {
        return this.direction.startsWith('u')
    }

    def isDownload() {
        return this.direction.startswith('d')
    }

    def insert(String name, String block) {
        /* Store JSON directly as string */
        this.data[name] = block
    }

    def insert(String name, block=[:]) {
        /* Convert a Groovy Map to JSON and store it */
        this.data[name] = JsonOutput.toJson(block)
    }
}
