// Jenkinsfile utilities


def concurrent2(mylist) {
    for (build in mylist) {
        println("concurrent2: build.nodetype = ${build.nodetype}")
    }
}
