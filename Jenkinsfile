#!groovy

pipeline {
  
    agent any

    tools {
        // ... tell Jenkins what java version, maven version or other tools are required ...
        maven 'Maven 3.8.5'
        jdk 'jdk-11.0.14'
    }

    options {
        // Configure an overall timeout for the build of ten hours.
        timeout(time: 2, unit: 'HOURS')
        // When we have test-fails e.g. we don't need to run the remaining steps
        buildDiscarder(logRotator(numToKeepStr: '5', artifactNumToKeepStr: '5'))
        disableConcurrentBuilds()
    }

    parameters {
        choice(name: 'Build', choices: ['snapshot', 'release-dry-run', 'release'])
        string(name: 'ReleaseVersion', trim: true, description: 'Override release version number')
        string(name: 'NextSnapshotVersion', trim: true, description: 'Override next SNAPSHOT version number')
        booleanParam(name: 'keepWorkspace', description: 'Keep workspace', defaultValue: false)
        booleanParam(name: 'mavenDebug', description: 'Enable Maven debug mode', defaultValue: false)
    }

/**
 *    withCredentials([usernamePassword(
 *        credentialsId: 'jenkins-gitea',
 *        passwordVariable: 'PASSWORD',
 *        usernameVariable: 'USER')]) {
 *        sh "git push https://${USER}:${TOKEN}@github.com/mycompany/myrepo.git mybranch -f"
 *    }
 */
    stages {
        stage('Initialization') {
            steps {
                echo 'Building branch ' + env.BRANCH_NAME
                echo 'Using PATH ' + env.PATH
                echo 'Git URL: ' + env.GIT_URL
                echo 'Git branch: ' + env.GIT_BRANCH
                echo 'Git local branch: ' + env.GIT_LOCAL_BRANCH
            }
        }

        stage('Cleanup') {
            steps {
                echo 'Cleaning up the workspace'
                deleteDir()
                echo 'Cleaning up mvn repo io/hyte'
                sh 'rm -rf /opt/build/maven/repository/io/hyte'
            }
        }

        stage('Checkout') {
            steps {
                script {
                    echo 'Checking out branch ' + env.BRANCH_NAME
                    checkout scm

                    // Check for the temporary merge commit to test PR against target branch
                    result = sh (script: "git log -1 | grep '^Author: Jenkins'", returnStatus: true)
                    if (result == 0) {
                        // Check if next real commit was the release plugin
                        result = sh (script: "git log -2 | grep '.*\\[maven-release-plugin\\] prepare release.*'", returnStatus: true) 
                        if (result == 0) {
                            error ("'[maven-release-plugin] prepare release' spotted in git commit. Aborting.")
                            currentBuild.result = 'ABORTED'
                            error('Build aborted due to matching release commit')
                        }
                    }
                }
            }
        }

        stage('Build') {
            steps {
                echo 'Building'
                sh 'mvn -U -B -e clean install -DskipTests'
            }
        }

        stage('Tests') {
            steps {
                echo 'Running tests'
                sh 'mvn -B -e -fae test'
            }
            post {
                always {
                    junit(testResults: '**/surefire-reports/*.xml', allowEmptyResults: true)
                    junit(testResults: '**/failsafe-reports/*.xml', allowEmptyResults: true)
                }
            }
        }

        stage('Deploy') {
            when {
                expression {
                    env.BRANCH_NAME ==~ /(main|v4.2.x)/
                }
            }
            steps {
                echo 'Deploying'
                sh 'mvn -B -e deploy -Pdeploy -DskipTests'
            }
        }

        stage("Release Dry Run") {
            when {
                expression { params.Build == 'release-dry-run' }
            }
            steps {
                script {
                    if (!params.ReleaseVersion.isEmpty() && !params.NextSnapshotVersion.isEmpty()) {
                        sh "mvn -B -Prelease -Darguments=\"-DskipTests=true -Dmpir.skip=true\" -DignoreSnapshots=true -DdryRun=true -DskipTests=true -Dmpir.skip=true -DreleaseVersion=${params.ReleaseVersion} -DdevelopmentVersion=${params.NextSnapshotVersion} release:prepare release:perform"
                    } else {
                        sh "mvn -B -Prelease -Darguments=\"-DskipTests=true -Dmpir.skip=true\" -DignoreSnapshots=true -DdryRun=true -DskipTests=true -Dmpir.skip=true release:prepare release:perform"
                    }
                }
            }
        }

        stage("Release") {
            when {
                expression { params.Build == 'release' }
            }

            steps {
                script {
                    if (params.mavenDebug) {
                        if (!params.ReleaseVersion.isEmpty() && !params.NextSnapshotVersion.isEmpty()) {
                            sh "mvn -X -B -Prelease -Darguments=\"-DskipTests=true -Dmpir.skip=true\" -DignoreSnapshots=true -DskipTests=true -Dmpir.skip=true -DreleaseVersion=${params.ReleaseVersion} -DdevelopmentVersion=${params.NextSnapshotVersion} release:prepare release:perform"
                        } else {
                            sh "mvn -X -B -Prelease -Darguments=\"-DskipTests=true -Dmpir.skip=true\" -DignoreSnapshots=true -DskipTests=true -Dmpir.skip=true release:prepare release:perform"
                        }
                    }  else {
                        if (!params.ReleaseVersion.isEmpty() && !params.NextSnapshotVersion.isEmpty()) {
                            sh "mvn -B -Prelease -Darguments=\"-DskipTests=true -Dmpir.skip=true\" -DignoreSnapshots=true -DskipTests=true -Dmpir.skip=true -DreleaseVersion=${params.ReleaseVersion} -DdevelopmentVersion=${params.NextSnapshotVersion} release:prepare release:perform"
                        } else {
                            sh "mvn -B -Prelease -Darguments=\"-DskipTests=true -Dmpir.skip=true\" -DignoreSnapshots=true -DskipTests=true -Dmpir.skip=true release:prepare release:perform"
                        }
                    }
                }
            }
        }

        stage("Post-Cleanup") {
            when {
                expression { return !params.keepWorkspace }
            }
            steps {
                deleteDir()
            } 
        }
    }

    // Do any post build stuff ... such as sending emails depending on the overall build result.
    post {
        // If this build failed, send an email to the list.
        failure {
            script {
                if(env.BRANCH_NAME == "main" || env.BRANCH_NAME == "v4.2.x") {
                    emailext(
                            subject: "[BUILD-FAILURE]: Job '${env.JOB_NAME} [${env.BRANCH_NAME}] [${env.BUILD_NUMBER}]'",
                            body: """
BUILD-FAILURE: Job '${env.JOB_NAME} [${env.BRANCH_NAME}] [${env.BUILD_NUMBER}]':
Check console output at "<a href="${env.BUILD_URL}">${env.JOB_NAME} [${env.BRANCH_NAME}] [${env.BUILD_NUMBER}]</a>"
""",
                            to: "support@hyte.io",
                            recipientProviders: [[$class: 'DevelopersRecipientProvider']]
                    )
                }
            }
        }

        // If this build didn't fail, but there were failing tests, send an email to the list.
        unstable {
            script {
                if(env.BRANCH_NAME == "main" || env.BRANCH_NAME == "v4.2.x") {
                    emailext(
                            subject: "[BUILD-UNSTABLE]: Job '${env.JOB_NAME} [${env.BRANCH_NAME}] [${env.BUILD_NUMBER}]'",
                            body: """
BUILD-UNSTABLE: Job '${env.JOB_NAME} [${env.BRANCH_NAME}] [${env.BUILD_NUMBER}]':
Check console output at "<a href="${env.BUILD_URL}">${env.JOB_NAME} [${env.BRANCH_NAME}] [${env.BUILD_NUMBER}]</a>"
""",
                            to: "support@hyte.io",
                            recipientProviders: [[$class: 'DevelopersRecipientProvider']]
                    )
                }
            }
        }

        // Send an email, if the last build was not successful and this one is.
        success {
            script {
                if ((env.BRANCH_NAME == "main" || env.BRANCHE_NAME == "v4.2.x") && (currentBuild.previousBuild != null) && (currentBuild.previousBuild.result != 'SUCCESS')) {
                    emailext (
                            subject: "[BUILD-STABLE]: Job '${env.JOB_NAME} [${env.BRANCH_NAME}] [${env.BUILD_NUMBER}]'",
                            body: """
BUILD-STABLE: Job '${env.JOB_NAME} [${env.BRANCH_NAME}] [${env.BUILD_NUMBER}]':
Is back to normal.
""",
                            to: "support@hyte.io",
                            recipientProviders: [[$class: 'DevelopersRecipientProvider']]
                    )
                }
            }
        }
    }
}

