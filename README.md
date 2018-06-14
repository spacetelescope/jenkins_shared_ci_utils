This Jenkins shared library provides common utility classes and functions used to
support continuous integration (CI) build and test jobs for projects within the spacetelescope organization.

## A Simplified Job Definition Syntax

Functionality provided that extends the native Groovy syntax approach
1. Terminate job execution immediately (with a success status) when the string `[skip ci]` or `[ci skip]` is found in the commit message.
2. Selection of either parallel (default) or sequential execution of the specific build matrix.

This library's functionality is automatically made available to every Jenkinsfile hosted in the spacetelescope Github organization.


An example job that builds three parallel combinations and runs tests on one of them.

```groovy
// Obtain files from source control system.
if (utils.scm_checkout()) return
 
// Config data to share between builds.
CFLAGS = 'CFLAGS="-m64"'
LDFLAGS = 'LDFLAGS="-m64"'
DEFAULT_FLAGS = "${CFLAGS} ${LDFLAGS}"
// Some waf flags cause a prompt for input during configuration, hence the 'yes'.
configure_cmd = "yes '' | ./waf configure --prefix=./_install ${DEFAULT_FLAGS}"
 
 
// Define each build configuration, copying and overriding values as necessary.
bc0 = new BuildConfig()
bc0.nodetype = "linux-stable"
bc0.build_mode = "debug"
bc0.env_vars = ['PATH=./_install/bin:$PATH']
bc0.build_cmds = ["${configure_cmd} --debug",
                  "./waf build",
                  "./waf install"]
 
 
bc1 = utils.copy(bc0)
bc1.build_mode = "release"
bc1.build_cmds[0] = "${configure_cmd} --release-with-symbols"
bc1.test_cmds = ["conda install -q -y pytest requests astropy",
                 "pip install -q pytest-remotedata",
                 "pytest tests --basetemp=tests_output --junitxml results.xml --remote-data"]
bc1.failedUnstableThresh = 1
bc1.failedFailureThresh = 6
 
 
bc2 = utils.copy(bc0)
bc2.build_mode = "optimized"
bc2.build_cmds[0] = "${configure_cmd} --O3"
 
 
// Iterate over configurations that define the (distibuted) build matrix.
// Spawn a host of the given nodetype for each combination and run in parallel.
utils.run([bc0, bc1, bc2])
```

### Utils Library
The build configuration syntax shown here is provided by the `utils` library which contains two main components, the `utils` functions and the `BuildConfig` class.

#### Functions
The `utils` library provides several functions:

| Function                           | Description                                                                 |
| --- | --- |
| `if (utils.scm_checkout()) return` | <ul><li>  Performs the cloning of the git repository that contains the Jenkinsfile.</li><li>Handles delivery of checked-out files to all subsequent build nodes called upon via the `utils.run()` function described below so that only a single clone is required to feed all parallel builds.  </li><li>  When used within the full `if (utils.scm_checkout()) return` clause, it also handles aborting the build immediately after the clone if either the text `[skip ci]` of `[ci skip]` if found in the latest commit message. Note: The `if` statement is unavoidable due to how Jenkins handles script termination.  </li></ul>  Accepts: <ul><li>`skip_disable=true` (default is false)  </li></ul>  Will ignore any `[skip ci]`/`[ci skip]` directive found in the latest commit message. This is used in certain regression testing jobs that run on a schedule, the execution of which should never be skipped due to skip directives found in the commit history. |
| `utils.copy()` | <ul><li>  Copies the single object passed in as an argument into a new instance holding all the same attribute values. Useful to avoid duplication of parameters when configurations are similar to one another.  </li></ul> Accepts:  <ul><li> a single `BuildConfig` object  </ul></li>  |
| `utils.run(config_list, concurrent=true)` | <ul><li>  Responsible for running build tasks on separately provisioned hosts based on a list of configuration objects passed.  </li><li>  Parallel builds show up in the Jenkins (Blueocean) interface under the 'Matrix' heading. </li><li>  Serial builds show up in the Jenkins (Blueocean) interface under 'Serial-#' headings.  </li></ul>  Accepts:  <ul><li>  a single list of BuildConfig objects  </li><li>  (optional) A boolean named `concurrent`  Default is `true`. When 'false', each `BuildConfig` is built sequentially in the order in which they appear in the list passed to this function. NOTE: When `concurrent=False` (a sequential build), any failure encountered when executing a configuration will terminate the entire sequence.  </li></ul>

#### BuildConfig Class
The utils library also provides the definition of a class called BuildConfig that may be used to create build configuration objects used to define build tasks to be run on various hosts.

It has the following members:

| Member | Type | Required | Purpose |
| --- | --- | --- | --- |
| `nodetype` | string | yes | The Jenkins node label to which the build is assigned |
| `name`     | string | yes | A (short) arbitrary name/description of the build configuration. Builds are named `<nodetype>/<name>` in the build status GUI. I.e. "linux/stable" or "linux/debug" |
| `conda_packages` | list of strings | no | If this list is defined, the associated build job will create a temporary conda environment to host the job which contains the packages specified. Package specifications are of the form <ul><li>  `<package_name>`  </li><li>  `<package_name>=<version>`  </li></ul>  Example: `bc0.conda_packages = ["pytest", "requests", "numpy=1.14.3"]`  |
| `conda_override_channels` | boolean | no | Instructs the conda environment creation process to not implicitly prepend the anaconda defaults channel to the list of channels used.  This allows the priority of channels to be used for environment creation to be specified exactly in the order of channels provided in the `conda_channels` list, described below.  If `conda_packages` is not defined in the Jenkinsfile this property is ignored. |
| `conda_channels` | list of strings | no | The list of channels, in order of search priority, to use when retrieving packages for installation.  If `conda_override_channels` is not defined, this list will have the conda `defaults` channel implicitly prepended to it at installation time.  If `conda_packages` is not defined in the Jenkinsfile this property is ignored.  Example: `bc0.conda_channels = ["http://ssb.stsci.edu/astroconda"]`  |
 | `env_vars` | list of strings | no | Allow configuration of the shell environment in which build and test commands are run.  Noteworthy behaviors:  <ul><li>  Relative path characters such as `.` and '..' are honored with respect to the isolated build WORKSPACE directory into which the source repository is cloned and the build job takes place.  </li><li>  Shell variables appearing with `$` are dereferenced to their value by the bash shell responsible for hosting the job's activities. The variable name to dereference must exist at the time the environment is created on each parallel node. I.e. variables can appear in the definition of other variables later in the list (the list is processed in order.)  </li><li>  Strings provided in single quotes preclude the need to escape the `$` characters when referencing environment variables. Double quotes require the `$` to be escaped with a `\`.  </li></ul>  |
 | `build_cmds` | list of strings | yes | These commands are run in their order of appearance in this list with the default shell environment and any modifications to that environment provided by the `env_vars` list described above.  <ul><li>  Varables defined in the Jenkinsfile script itself may appear in these commands via `${varname}` notation and are interpolated at script execution time.  </li><li>  These command are executed BEFORE any optional `test_cmds`.  </li></ul>  |
| `test_cmds` | list of strings | no | These commands are run in their order of appearance in this list with the default shell environment plus any modifications to that environment provided by the `env_vars` list described above.  <ul><li>  If this list is not set for a build configuration, no test commands are run and no test report is generated.  </li><li>  If present, these commands are executed AFTER the build_cmds.  </li></ul> |
| `failedFailureNewThresh` |	integer |	no | (Default is no threshold set.)	The threshold for the number of newly appearing test failures that will cause the build to be flagged as "FAILED". |
| `failedFailureThresh` |	integer |	no | (Default is no threshold set.)	The threshold for the number of test failures that will cause the build to be flagged as "FAILED". |
| `failedUnstableNewThresh`	| integer	| no |  (Default is no threshold set.) The threshold for the number of newly appearing test failures that will cause the build to be flagged as "UNSTABLE". |
| `failedUnstableThresh`	| integer	| no | (Default is no threshold set.) The threshold for the number of test failures that will cause the build to be flagged as "UNSTABLE". |
| `skippedFailureNewThresh`	| integer	| no | (Default is no threshold set.) The threshold for the number of newly appearing skipped tests that will cause the build to be flagged as "FAILED". |
| `skippedFailureThresh` | integer | no | (Default is no threshold set.) The threshold for the number of skipped tests that will cause the build to be flagged as "FAILED". |
| `skippedUnstableNewThresh`	| integer	| no | (Default is no threshold set.) The threshold for the number of newly appearing skipped tests that will cause the build to be flagged as "UNSTABLE". |
| `skippedUnstableThresh`	| integer	| no | (Default is no threshold set.) The threshold for the number of skipped tests that will cause the build to be flagged as "UNSTABLE". |

