@Library("github.com/SatelliteQE/robottelo-ci") _

pipeline {
    agent {
        label 'sat6-rhel'
    }
    parameters {
        string(name: 'AUTOMATION_BUILD_URL', description: 'The URL of the build to process')
        string(name: 'BUILD_TAGS', description: 'Space-separated set of tags to add to the launch')
        string(name: 'RP_PROJECT', defaultValue: 'satellite6', description: 'Report Portal project to feed the results into')
        string(name: 'RP_TOOLS_FORK', defaultValue: 'satelliteqe',description: 'You can override the gitlab report-portal-tools fork')
        string(name: 'RP_TOOLS_BRANCH', defaultValue: 'master', description: 'You can override the report-portal-tools repo branch if using REPORT_PORTAL_TOOLS_REPO')
        string(name: 'WORKERS', defaultValue: '8', description: 'Number of workers to parallelize automated issue claiming')
    }
    stages {
        stage('Virtualenv') {
  	        steps {
                make_venv python: defaults.python
            }
        }
        stage('Clone rp_tools repo') {
            steps {
	        configFileProvider(
		    [configFile(fileId: 'e8b0ed3c-2ca3-4a0c-a922-60264c11bbc9', variable: 'RP_TOOLS')]) {
		        sh_venv """
			    source \${RP_TOOLS}
			"""
		    }
            }
        }
        stage('Configure rp_tools') {
            steps {
                sh_venv """
	            cd rp_tools
                    export PYCURL_SSL_LIBRARY=\$(curl -V | sed -n 's/.*\\(NSS\\|OpenSSL\\).*/\\L\\1/p')
                    pip install -r requirements.txt
                    """
                configFileProvider(
                    [configFile(fileId: 'bc5f0cbc-616f-46de-bdfe-2e024e84fcbf', variable: 'CONFIG_FILES')]) {
                         sh_venv '''
                             source ${CONFIG_FILES}
                             cp config/rp_conf.yaml rp_tools/scripts/reportportal_cli/rp_conf.yaml
                             sed -i "s/^rp_project.\\+$/rp_project: ${RP_PROJECT}/" rp_tools/scripts/reportportal_cli/rp_conf.yaml
                             mkdir rp_tools/scripts/jenkins_junit/junits
                            '''
                         }

            }
        }
        stage('Collect Junit XMLs') {
            steps {
                sh_venv '''
                    cd rp_tools/scripts/jenkins_junit/
                    if echo "${AUTOMATION_BUILD_URL}" | grep -q 'automation-upgraded-[0-9.]\\+-all-tiers-rhel[0-9]\\+'; then
                        cd junits/ && rm -f *.xml
                        wget --no-verbose --no-check-certificate "${AUTOMATION_BUILD_URL}/artifact/all-tiers-upgrade-parallel-results.xml"
                        wget --no-verbose --no-check-certificate "${AUTOMATION_BUILD_URL}/artifact/all-tiers-upgrade-sequential-results.xml"
                    else
                        python3 fetch_junit.py ${AUTOMATION_BUILD_URL} -v
                    fi
                '''
            }
        }
        stage('Push results to Report Portal') {
            steps {
                sh_venv '''
                    cd rp_tools/scripts/reportportal_cli
                    if echo "${AUTOMATION_BUILD_URL}" | grep -q 'automation-upgraded-[0-9.]\\+-all-tiers-rhel[0-9]\\+'; then
                        rp_cli_extra_opts="--launch_name Upgrades"
                    fi
                    ./rp_cli.py --xunit_feed '../jenkins_junit/junits/*.xml' --strategy Sat --config rp_conf.yaml --launch_tags "${BUILD_TAGS}" ${rp_cli_extra_opts}
                '''
            }
        }
        stage('Ownership tags') {
            steps {
                sh_venv '''
                    cd rp_tools/scripts/reportportal_cli/
                    ./owners_cli.py --insecure
                '''
            }
        }
        stage('Claim known issues') {
            steps {
                sh_venv '''
                    cd rp_tools/scripts/reportportal_cli/
                    master=$( echo "$BUILD_TAGS" | sed 's/ \\+/\n/g' | grep '^6\\.[0-9]\\+$' | head -n 1 )
                    rules="kb$( echo "$master" | sed 's/\\.//' ).json"
                    if [ -e "$rules" ]; then
                        echo "Looks like we are processing '$master' launch, so will use '$rules' rules file"
                        ./claiming_cli.py --insecure --rules "$rules" -n 8
                    else
                        echo "ERROR: No rules file for launch with tags: '$BUILD_TAGS'" >&2
                    fi
                '''
            }
        }
        stage('E-Mail owners') {
            steps {
                sh_venv '''
                    cd rp_tools/scripts/reportportal_cli/
                    #sed -i 's/server\\.sendmail/print/' alert_cli.py
                    ./alert_cli.py
                '''
            }
        }
        stage('Generate initial status report') {
            steps {
                sh_venv '''
                    cd rp_tools/scripts/reportportal_cli/
                    ./todo_cli.py --insecure
                '''
            }
        }

    }
  }
