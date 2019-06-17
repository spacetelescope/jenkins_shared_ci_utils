@Library('utils@subdirfix') _

// [skip ci] and [ci skip] have no effect here.
if (utils.scm_checkout(['skip_disable':true])) return

// Allow modification of the job configuration, affects all relevant build configs.
// Pass this object in the argument list to the`run()` function below to apply these settings to the job's execution.
jobconfig = new JobConfig()
//jobconfig.post_test_summary = true
//jobconfig.credentials = ['SECRET_VALUE']
//jobconfig.enable_env_publication = true
//jobconfig.publish_env_on_success_only = false


// Pytest wrapper
def PYTEST_BASETEMP = "test_outputs"
def PYTEST = "pytest \
              -r s \
              --basetemp=${PYTEST_BASETEMP} \
              --junit-xml=results.xml"

// Configure artifactory ingest
data_config = new DataConfig()
data_config.server_id = 'bytesalad'
data_config.root = '${PYTEST_BASETEMP}'
data_config.match_prefix = '(.*)_result' // .json is appended automatically


bc0 = new BuildConfig()
//bc0.nodetype = 'RHEL-6'
bc0.nodetype = 'linux'
bc0.name = 'First buildconfig'
bc0.env_vars = ['VAR_ONE=1',
               'VAR_TWO=2']
bc0.conda_ver = '4.6.4'
bc0.conda_packages = ['python=3.6',
                     'pytest']
bc0.build_cmds = ["date",
                  "./access_env_var.sh",
                  "which python",
                  "conda install ipython"]
bc0.test_cmds = ["${PYTEST} tests/test_75pass.py"]
bc0.test_configs = [data_config]


bc1 = utils.copy(bc0)
bc1.name = 'Second'
bc1.env_vars = ['VAR_THREE=3',
               'VAR_FOUR=4']
bc1.test_cmds[1] = "${PYTEST} tests/test_25pass.py"


bc2 = utils.copy(bc0)
bc2.name = 'Third build config'
bc2.conda_packages = ['python=3.6']
bc2.build_cmds = ["which python"]
bc2.test_cmds = ["ls -al"]
bc2.test_configs = []


utils.run([bc0, bc1, bc2, jobconfig])
