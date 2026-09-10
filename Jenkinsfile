pipeline {
    agent any

    options {
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        disableConcurrentBuilds()
        skipDefaultCheckout(true)
        timestamps()
    }

    triggers {
        pollSCM('H/5 * * * *')
    }

    parameters {
        choice(name: 'ROLLOUT_TARGET', choices: ['build', 'all', 'none'], description: 'Restart only Build, all Feather backends, or stage the jar for a later Gate rollout.')
    }

    stages {
        stage('Checkout') {
            steps {
                script {
                    def revision = checkout scm
                    if (!revision.GIT_BRANCH || !revision.GIT_COMMIT) {
                        error('Checkout did not provide the Git branch and commit.')
                    }
                    env.GIT_BRANCH = revision.GIT_BRANCH
                    env.GIT_COMMIT = revision.GIT_COMMIT
                    echo "Checked out ${env.GIT_BRANCH} at ${env.GIT_COMMIT}"
                }
            }
        }

        stage('Build and Test') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'nexus-deploy', usernameVariable: 'NEXUS_USER', passwordVariable: 'NEXUS_PASS')]) {
                    sh '''#!/usr/bin/env bash
                        set -euo pipefail
                        : "${NEXUS_BASE_URL:?Nexus URL must be configured on the Jenkins agent}"
                        java -version
                        mvn -B -ntp -s ci/maven-settings.xml \
                            -pl slimeworldmanager-plugin -am clean verify
                    '''
                }
            }
        }

        stage('Stage and Roll Out') {
            when {
                expression {
                    return env.BRANCH_NAME == 'develop' ||
                        env.GIT_BRANCH == 'origin/develop' ||
                        env.GIT_BRANCH == 'refs/remotes/origin/develop'
                }
            }
            steps {
                lock(resource: 'gate-rollout') {
                    sh '''#!/usr/bin/env bash
                        set -euo pipefail
                        case "${ROLLOUT_TARGET}" in
                            all) targets=(build limbo hub sg uhc practice) ;;
                            build) targets=(build) ;;
                            none) targets=() ;;
                            *) echo "Unknown rollout target: ${ROLLOUT_TARGET}" >&2; exit 1 ;;
                        esac

                        umask 0007
                        staging_dir="/srv/sghq/ci/staging/global"
                        runtime_jar="slimeworldmanager-plugin/target/feather-plugin-1.0.jar"
                        test -s "${runtime_jar}"
                        install -d -m 2770 "${staging_dir}"
                        install -m 0640 "${runtime_jar}" "${staging_dir}/Feather.jar.next"
                        mv -f "${staging_dir}/Feather.jar.next" "${staging_dir}/Feather.jar"
                        sha256sum "${staging_dir}/Feather.jar"

                        for server_type in "${targets[@]}"; do
                            gate-rollout "${server_type}"
                        done
                    '''
                }
            }
        }
    }

    post {
        always {
            junit testResults: '**/target/surefire-reports/TEST-*.xml', allowEmptyResults: true
            archiveArtifacts artifacts: 'slimeworldmanager-plugin/target/feather-plugin-1.0.jar', fingerprint: true, allowEmptyArchive: true
        }
    }
}
